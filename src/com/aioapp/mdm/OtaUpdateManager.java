package com.aioapp.mdm;

import android.content.Context;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Wraps Android UpdateEngine for A/B (seamless) OTA updates.
 *
 * Mirrors the proven ota-app flow:
 *   1. Download ZIP to cache dir (with HTTP Range resume support)
 *   2. Move to /data/ota_package/update.zip
 *   3. chmod 644 + chown system:cache
 *   4. Hand file:// URI to UpdateEngine
 *
 * Holds a partial wake lock for the duration of download + install.
 * Reboot is NOT triggered here — the MDM server manages that.
 */
public class OtaUpdateManager {
    private static final String TAG = "OtaUpdateManager";

    private static final int PROGRESS_REPORT_STEP = 5;
    private static final String OTA_PACKAGE_DIR = "/data/ota_package";
    private static final String OTA_PACKAGE_PATH = OTA_PACKAGE_DIR + "/update.zip";
    private static final String TEMP_FILE_NAME = "update_temp.zip";
    private static final long WAKE_LOCK_TIMEOUT_MS = 3_600_000L; // 1 hour

    public interface Listener {
        void onDownloadProgress(String phase, int percent);
        void onDownloadComplete();
        void onInstallComplete();
        void onError(String errorCode);
    }

    private final Context context;
    private final UpdateEngine updateEngine;
    private Listener listener;

    private volatile int lastReportedPercent = -1;
    private volatile int lastReportedStatus = -1;
    private volatile int generation = 0;
    private volatile String currentPhase = "idle";
    private volatile int currentPercent = 0;
    private volatile boolean active = false;

    private PowerManager.WakeLock wakeLock;

    public OtaUpdateManager(Context context) {
        this.context = context.getApplicationContext();
        this.updateEngine = new UpdateEngine();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive()         { return active; }
    public String getCurrentPhase()   { return currentPhase; }
    public int    getCurrentPercent()  { return currentPercent; }

    // ----------------------------------------------------------------
    // Duplicate guard — check if UpdateEngine already applied an update
    // ----------------------------------------------------------------

    /** Returns true when status == 6 (UPDATED_NEED_REBOOT). */
    public boolean isUpdatePendingReboot() {
        final int[] status = {0};
        final CountDownLatch latch = new CountDownLatch(1);

        UpdateEngineCallback cb = new UpdateEngineCallback() {
            @Override public void onStatusUpdate(int s, float p) {
                status[0] = s;
                latch.countDown();
            }
            @Override public void onPayloadApplicationComplete(int e) {}
        };

        try {
            updateEngine.bind(cb);
            latch.await(100, TimeUnit.MILLISECONDS);
            updateEngine.unbind();
        } catch (Exception e) {
            Log.w(TAG, "isUpdatePendingReboot check failed: " + e.getMessage());
        }
        return status[0] == 6;
    }

    // ----------------------------------------------------------------
    // Cancel
    // ----------------------------------------------------------------

    public void cancel() {
        generation++;
        active = false;
        currentPhase = "idle";
        currentPercent = 0;
        try { updateEngine.cancel(); } catch (Exception e) { Log.w(TAG, "cancel: " + e.getMessage()); }
        try { updateEngine.unbind(); } catch (Exception e) { Log.w(TAG, "unbind: " + e.getMessage()); }
        releaseWakeLock();
    }

    // ----------------------------------------------------------------
    // Start update — full ota-app flow
    // ----------------------------------------------------------------

    public void startUpdate(String url) {
        Log.i(TAG, "startUpdate url=" + url);

        // Duplicate guard
        if (isUpdatePendingReboot()) {
            Log.i(TAG, "Update already applied, pending reboot — skipping download.");
            if (listener != null) listener.onInstallComplete();
            return;
        }

        cancel();

        final int myGen = ++generation;
        lastReportedPercent = -1;
        lastReportedStatus = -1;
        active = true;
        currentPhase = "downloading";
        currentPercent = 0;

        acquireWakeLock();

        new Thread(() -> {
            File tempFile = new File(context.getCacheDir(), TEMP_FILE_NAME);
            boolean handedOff = false;

            try {
                // 1. Download with resume support
                downloadFile(url, tempFile, myGen);
                if (myGen != generation) return;

                // 2. Move to /data/ota_package + set permissions
                finalizeOtaFile(tempFile);
                File finalFile = new File(OTA_PACKAGE_PATH);
                if (!finalFile.exists()) throw new IOException("OTA file missing after move");
                if (myGen != generation) return;

                // 3. Parse the ZIP to extract payload offset, size, and properties
                currentPhase = "parsing";
                UpdateParser.ParsedUpdate parsed = UpdateParser.parse(finalFile);
                if (parsed == null || !parsed.isValid()) {
                    throw new IOException("Failed to parse OTA ZIP: " + parsed);
                }
                Log.i(TAG, "Parsed OTA: " + parsed);
                if (myGen != generation) return;

                // 4. Notify download complete
                if (listener != null) listener.onDownloadComplete();

                handedOff = true;

                // 5. Hand off to UpdateEngine with parsed values
                applyViaUpdateEngine(
                        parsed.mUrl, parsed.mOffset, parsed.mSize, parsed.mProps, myGen);

            } catch (Exception e) {
                if (myGen == generation) {
                    Log.e(TAG, "OTA error: " + e.getMessage(), e);
                    active = false;
                    currentPhase = "idle";
                    currentPercent = 0;
                    if (listener != null) listener.onError("DOWNLOAD_ERROR");
                }
            } finally {
                if (!handedOff) releaseWakeLock();
            }
        }).start();
    }

    // ----------------------------------------------------------------
    // HTTP download with resume (Range header)
    // ----------------------------------------------------------------

    private void downloadFile(String fileUrl, File targetFile, int myGen) throws Exception {
        long existingSize = targetFile.exists() ? targetFile.length() : 0;
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        if (existingSize > 0) {
            conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
        }

        int responseCode = conn.getResponseCode();
        boolean resuming = (responseCode == HttpURLConnection.HTTP_PARTIAL);
        long contentLength = conn.getContentLengthLong();
        long totalSize = contentLength + (resuming ? existingSize : 0);

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(targetFile, resuming)) {
            byte[] buffer = new byte[8192];
            int read;
            long downloaded = resuming ? existingSize : 0;

            while ((read = in.read(buffer)) != -1) {
                if (myGen != generation) return; // cancelled
                out.write(buffer, 0, read);
                downloaded += read;

                if (totalSize > 0) {
                    int pct = (int) (downloaded * 100 / totalSize);
                    currentPercent = pct;
                    int step = pct / PROGRESS_REPORT_STEP;
                    int lastStep = lastReportedPercent < 0 ? -1 : lastReportedPercent / PROGRESS_REPORT_STEP;
                    if (step != lastStep || pct == 100) {
                        lastReportedPercent = pct;
                        if (listener != null) listener.onDownloadProgress("downloading", pct);
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // File move & permissions (matches ota-app exactly)
    // ----------------------------------------------------------------

    private void finalizeOtaFile(File downloadedFile) throws Exception {
        exec("mkdir -p " + OTA_PACKAGE_DIR);
        exec("mv " + downloadedFile.getAbsolutePath() + " " + OTA_PACKAGE_PATH);
        exec("chmod 644 " + OTA_PACKAGE_PATH);
        exec("chown system:cache " + OTA_PACKAGE_PATH);
    }

    private void exec(String cmd) throws Exception {
        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}).waitFor();
    }

    // ----------------------------------------------------------------
    // UpdateEngine hand-off
    // ----------------------------------------------------------------

    private void applyViaUpdateEngine(String path, long offset, long size,
                                       String[] headers, int myGen) {
        currentPhase = "installing";
        // Reset progress tracking for the install phase
        lastReportedPercent = -1;
        lastReportedStatus = -1;

        UpdateEngineCallback callback = new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                if (myGen != generation) return;
                int pct = Math.round(percent * 100);
                Log.d(TAG, "engine status=" + status + " progress=" + pct + "%");

                String phase;
                switch (status) {
                    case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                        phase = "installing";
                        break;
                    case UpdateEngine.UpdateStatusConstants.VERIFYING:
                        phase = "verifying";
                        break;
                    case UpdateEngine.UpdateStatusConstants.FINALIZING:
                        phase = "finalizing";
                        break;
                    default:
                        phase = "installing";
                        break;
                }

                currentPhase = phase;
                currentPercent = pct;

                boolean phaseChanged = status != lastReportedStatus;
                boolean stepCrossed = (pct / PROGRESS_REPORT_STEP)
                        != (lastReportedPercent < 0 ? -1 : lastReportedPercent / PROGRESS_REPORT_STEP);
                if (phaseChanged || stepCrossed || pct == 100) {
                    lastReportedStatus = status;
                    lastReportedPercent = pct;
                    if (listener != null) listener.onDownloadProgress(phase, pct);
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                if (myGen != generation) return;
                Log.i(TAG, "onPayloadApplicationComplete errorCode=" + errorCode);
                active = false;
                currentPhase = "idle";
                currentPercent = 0;
                releaseWakeLock();
                if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
                    if (listener != null) listener.onInstallComplete();
                } else {
                    if (listener != null) listener.onError("UPDATE_ERROR_" + errorCode);
                }
            }
        };

        try {
            updateEngine.bind(callback);
            updateEngine.applyPayload(path, offset, size,
                    headers != null ? headers : new String[]{});
        } catch (Exception e) {
            Log.e(TAG, "UpdateEngine error: " + e.getMessage(), e);
            active = false;
            currentPhase = "idle";
            releaseWakeLock();
            if (listener != null) listener.onError("UPDATE_ENGINE_BIND_ERROR");
        }
    }

    // ----------------------------------------------------------------
    // Wake lock management
    // ----------------------------------------------------------------

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MDM:OtaWorkLock");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            Log.i(TAG, "Wake lock acquired (" + WAKE_LOCK_TIMEOUT_MS + "ms)");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "Wake lock released");
        }
    }
}
