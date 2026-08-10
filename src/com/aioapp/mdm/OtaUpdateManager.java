package com.aioapp.mdm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
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

    private static final int PROGRESS_REPORT_STEP = 1;
    private static final String OTA_PACKAGE_DIR = "/data/ota_package";
    private static final String OTA_PACKAGE_PATH = OTA_PACKAGE_DIR + "/update.zip";
    private static final String TEMP_FILE_NAME = "update_temp.zip";
    // 2 hours covers a full-image download + install on slow Wi-Fi (legacy
    // ota-app parity); the safety timer is a backstop slightly past the timeout.
    private static final long WAKE_LOCK_TIMEOUT_MS = 2 * 3_600_000L;
    private static final long WAKE_LOCK_SAFETY_MS  = WAKE_LOCK_TIMEOUT_MS + 10 * 60_000L;

    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS    = 60_000;

    public interface Listener {
        void onDownloadProgress(String phase, int percent);
        void onDownloadComplete();
        void onInstallComplete();
        void onError(String errorCode);
    }

    private final Context context;
    private final Executor executor;
    private final UpdateEngine updateEngine;
    private Listener listener;

    private volatile int lastReportedPercent = -1;
    private volatile int lastReportedStatus = -1;
    private volatile int generation = 0;
    private volatile String currentPhase = "idle";
    private volatile int currentPercent = 0;
    private volatile boolean active = false;
    private volatile String currentUrl = null;

    private PowerManager.WakeLock wakeLock;
    private Handler wakeLockTimer;

    public OtaUpdateManager(Context context, Executor executor) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.updateEngine = new UpdateEngine();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive()         { return active; }
    public String getCurrentPhase()   { return currentPhase; }
    public int    getCurrentPercent()  { return currentPercent; }
    /** URL of the in-flight (or most recent) update, for de-duplicating repeat commands. */
    public String getCurrentUrl()     { return currentUrl; }

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
        currentUrl = url;

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

        executor.execute(() -> {
            File tempFile = new File(context.getCacheDir(), TEMP_FILE_NAME);
            boolean handedOff = false;

            try {
                // 1. Download with resume support
                downloadFile(url, tempFile, myGen);
                if (myGen != generation) return;

                // 2. Parse the app-owned cache copy FIRST. ZipFile mmaps the file to read
                // its central directory; the app can mmap files in its own cacheDir, but NOT
                // a file under /data/ota_package (labeled ota_package_file — update_engine's
                // domain), so parsing after the move fails with EACCES ("Permission denied")
                // on mmap. The payload offset/size/props are identical regardless of where
                // the zip lives, so parse here and reuse them for the moved file.
                currentPhase = "parsing";
                UpdateParser.ParsedUpdate parsed = UpdateParser.parse(tempFile);
                if (parsed == null || !parsed.isValid()) {
                    throw new IOException("Failed to parse OTA ZIP: " + parsed);
                }
                Log.i(TAG, "Parsed OTA: " + parsed);
                if (myGen != generation) return;

                // 3. Move to /data/ota_package + set permissions (where update_engine reads it).
                finalizeOtaFile(tempFile);
                File finalFile = new File(OTA_PACKAGE_PATH);
                if (!finalFile.exists()) throw new IOException("OTA file missing after move");
                if (myGen != generation) return;

                // 4. Notify download complete
                if (listener != null) listener.onDownloadComplete();

                handedOff = true;

                // 5. Hand off to UpdateEngine using the /data/ota_package path (NOT the parsed
                // temp path) with the offset/size/props from the parse above.
                applyViaUpdateEngine(
                        "file://" + OTA_PACKAGE_PATH, parsed.mOffset, parsed.mSize, parsed.mProps, myGen);

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
        });
    }

    // ----------------------------------------------------------------
    // HTTP download with resume (Range header)
    // ----------------------------------------------------------------

    private void downloadFile(String fileUrl, File targetFile, int myGen) throws Exception {
        // Range resume + retry (5 attempts, exponential backoff). A dropped or stale
        // connection mid-download no longer restarts the whole (potentially multi-GB)
        // image — the next attempt continues from disk — and the final size is verified
        // before hand-off to UpdateEngine. expectedSize is unknown here, so the helper
        // falls back to the response Content-Length.
        HttpDownloader.downloadWithResume(fileUrl, targetFile, -1, null,
                DOWNLOAD_CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS, 5,
                (downloaded, totalSize) -> {
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
                },
                () -> myGen != generation);
    }

    // ----------------------------------------------------------------
    // File move & permissions (matches ota-app exactly)
    // ----------------------------------------------------------------

    private void finalizeOtaFile(File downloadedFile) throws Exception {
        java.nio.file.Path otaDir = Paths.get(OTA_PACKAGE_DIR);
        if (!Files.exists(otaDir)) {
            Files.createDirectories(otaDir);
        }
        Files.move(downloadedFile.toPath(), Paths.get(OTA_PACKAGE_PATH),
                StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(Paths.get(OTA_PACKAGE_PATH),
                PosixFilePermissions.fromString("rw-r--r--"));
        // chown requires fixed system UIDs — no user-supplied path interpolation
        Runtime.getRuntime().exec(new String[]{"chown", "system:cache", OTA_PACKAGE_PATH}).waitFor();
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
        if (wakeLockTimer == null) wakeLockTimer = new Handler(Looper.getMainLooper());
        wakeLockTimer.removeCallbacksAndMessages(null);
        wakeLockTimer.postDelayed(() -> {
            Log.w(TAG, "Wake lock safety timer fired — force releasing after " + WAKE_LOCK_SAFETY_MS + "ms");
            releaseWakeLock();
        }, WAKE_LOCK_SAFETY_MS);
    }

    private void releaseWakeLock() {
        if (wakeLockTimer != null) wakeLockTimer.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "Wake lock released");
        }
    }
}
