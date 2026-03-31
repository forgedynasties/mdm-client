package com.aioapp.mdm;

import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

/**
 * Wraps Android UpdateEngine for A/B (seamless) OTA updates.
 * Driven by the MDM checkin "ota" command — no separate update check needed.
 */
public class OtaUpdateManager {
    private static final String TAG = "OtaUpdateManager";

    /** Minimum percent change before firing onDownloadProgress again. */
    private static final int PROGRESS_REPORT_STEP = 5;

    public interface Listener {
        void onDownloadProgress(String phase, int percent);
        void onDownloadComplete();
        void onInstallComplete();
        void onError(String errorCode);
    }

    private final UpdateEngine updateEngine;
    private Listener listener;
    private volatile boolean downloadedReported = false;
    private volatile int lastReportedPercent = -1;
    private volatile int lastReportedStatus = -1;

    // Readable state for check-in payload
    private volatile String currentPhase = "idle";
    private volatile int currentPercent = 0;
    private volatile boolean active = false;

    public OtaUpdateManager() {
        this.updateEngine = new UpdateEngine();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() { return active; }
    public String getCurrentPhase() { return currentPhase; }
    public int getCurrentPercent() { return currentPercent; }

    /**
     * Passes the payload URL and metadata directly to UpdateEngine.
     * url must be a valid http(s):// or file:// URI — passed through as-is.
     */
    public void startUpdate(String url, long offset, long size, String[] headers) {
        Log.i(TAG, "startUpdate url=" + url + " offset=" + offset + " size=" + size);
        downloadedReported = false;
        lastReportedPercent = -1;
        lastReportedStatus = -1;
        active = true;
        currentPhase = "downloading";
        currentPercent = 0;

        UpdateEngineCallback callback = new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                int pct = Math.round(percent * 100);
                Log.d(TAG, "status=" + status + " progress=" + pct + "%");

                // Map UpdateEngine status to a human-readable phase
                String phase;
                switch (status) {
                    case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                        phase = "downloading";
                        break;
                    case UpdateEngine.UpdateStatusConstants.VERIFYING:
                        phase = "verifying";
                        break;
                    case UpdateEngine.UpdateStatusConstants.FINALIZING:
                        phase = "finalizing";
                        break;
                    default:
                        phase = "downloading";
                        break;
                }

                currentPhase = phase;
                currentPercent = pct;

                // Fire progress callback when phase changes or percent crosses a step boundary
                boolean phaseChanged = status != lastReportedStatus;
                boolean percentCrossedStep = (pct / PROGRESS_REPORT_STEP) != (lastReportedPercent / PROGRESS_REPORT_STEP);
                if (phaseChanged || percentCrossedStep || pct == 100) {
                    lastReportedStatus = status;
                    lastReportedPercent = pct;
                    if (listener != null) listener.onDownloadProgress(phase, pct);
                }

                // VERIFYING (4) means the download phase has finished.
                // Use >= so we don't miss it if status jumps straight to FINALIZING.
                if (!downloadedReported
                        && status >= UpdateEngine.UpdateStatusConstants.VERIFYING) {
                    downloadedReported = true;
                    if (listener != null) listener.onDownloadComplete();
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                Log.i(TAG, "onPayloadApplicationComplete errorCode=" + errorCode);
                active = false;
                currentPhase = "idle";
                currentPercent = 0;
                if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
                    if (listener != null) listener.onInstallComplete();
                } else {
                    if (listener != null) listener.onError("UPDATE_ERROR_" + errorCode);
                }
            }
        };

        try {
            updateEngine.bind(callback);
            updateEngine.applyPayload(url, offset, size, headers != null ? headers : new String[]{});
        } catch (Exception e) {
            Log.e(TAG, "UpdateEngine error: " + e.getMessage(), e);
            active = false;
            currentPhase = "idle";
            if (listener != null) listener.onError("UPDATE_ENGINE_BIND_ERROR");
        }
    }
}
