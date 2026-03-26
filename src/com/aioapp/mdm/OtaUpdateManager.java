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

    public interface Listener {
        void onDownloadComplete();
        void onInstallComplete();
        void onError(String errorCode);
    }

    private final UpdateEngine updateEngine;
    private Listener listener;
    private volatile boolean downloadedReported = false;

    public OtaUpdateManager() {
        this.updateEngine = new UpdateEngine();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Passes the payload URL and metadata directly to UpdateEngine.
     * url must be a valid http(s):// or file:// URI — passed through as-is.
     */
    public void startUpdate(String url, long offset, long size, String[] headers) {
        Log.i(TAG, "startUpdate url=" + url + " offset=" + offset + " size=" + size);
        downloadedReported = false;

        UpdateEngineCallback callback = new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.d(TAG, "status=" + status + " progress=" + Math.round(percent * 100) + "%");
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
            if (listener != null) listener.onError("UPDATE_ENGINE_BIND_ERROR");
        }
    }
}
