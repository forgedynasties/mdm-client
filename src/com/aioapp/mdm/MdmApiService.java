package com.aioapp.mdm;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class MdmApiService {
    private static final String TAG = "MdmApiService";
    // Keep in sync with DEVICE_API_KEY in server .env
    private static final String API_KEY = "your-secret-key-here";

    private static final boolean USE_LOCAL_SERVER = false;
    private static final String LOCAL_API_BASE_URL = "http://10.32.1.170:8080";
    private static final String DEFAULT_API_BASE_URL = "https://mdm.dev.aioapp.com";
    // Persistent system property that re-points the fleet at a different server
    // without a system update: setprop persist.sys.mdm.url https://host[:port]
    private static final String URL_OVERRIDE_PROP = "persist.sys.mdm.url";

    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000;
    private static final Random JITTER = new Random();

    private String apiBaseUrl = resolveBaseUrl();
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private int consecutiveFailures = 0;

    public MdmApiService() {}

    private static String resolveBaseUrl() {
        String override = SystemPropertiesProxy.get(URL_OVERRIDE_PROP, "");
        if (override != null && !override.trim().isEmpty()) {
            String url = override.trim();
            Log.i(TAG, "Server URL overridden via " + URL_OVERRIDE_PROP + ": " + url);
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return USE_LOCAL_SERVER ? LOCAL_API_BASE_URL : DEFAULT_API_BASE_URL;
    }

    public String getApiKey() { return API_KEY; }

    public String getApiBaseUrl() { return apiBaseUrl; }

    public long getPollInterval() { return pollIntervalMs; }

    public void setPollInterval(long ms) { pollIntervalMs = ms; }

    /** Returns a backoff multiplier (1, 2, 4, … up to 16×) based on consecutive failures. */
    public long getBackoffMultiplier() {
        if (consecutiveFailures == 0) return 1;
        return Math.min((long) Math.pow(2, consecutiveFailures), 16);
    }

    public void loadRemoteConfig() {
        // Re-read the persistent property so a setprop takes effect at the next
        // sync without restarting the service.
        apiBaseUrl = resolveBaseUrl();
    }

    /**
     * POST /api/v1/checkin
     * Up to 3 attempts with 500ms + random jitter between tries before counting as a failure.
     * 401 aborts immediately (no retry). consecutiveFailures only increments after all tries fail.
     */
    public JSONObject checkin(JSONObject payload) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(500 + JITTER.nextInt(500)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
            try {
                PostResult result = doPost("/api/v1/checkin", payload.toString());
                if (result.code == HttpURLConnection.HTTP_OK) {
                    consecutiveFailures = 0;
                    Log.d(TAG, "Checkin OK");
                    return new JSONObject(result.body);
                }
                if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    Log.e(TAG, "Checkin 401: invalid API key");
                    return null;
                }
                Log.w(TAG, "Checkin attempt " + (attempt + 1) + " failed: HTTP " + result.code);
            } catch (Exception e) {
                Log.w(TAG, "Checkin attempt " + (attempt + 1) + " failed: " + e.getMessage());
            }
        }
        consecutiveFailures++;
        Log.w(TAG, "Checkin failed after retries (failure #" + consecutiveFailures + ") — backing off");
        return null;
    }

    /**
     * POST /api/v1/logcat
     * Submits collected logcat output for a given request id.
     */
    public void postLogcat(String serialNumber, String requestId, String content) {
        try {
            JSONObject body = new JSONObject();
            body.put("serial_number", serialNumber);
            body.put("request_id", requestId);
            body.put("content", content);
            PostResult result = doPost("/api/v1/logcat", body.toString());
            Log.d(TAG, "Logcat POST requestId=" + requestId + " response=" + result.code);
        } catch (Exception e) {
            Log.e(TAG, "postLogcat failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/ota/status
     * Reports OTA milestone transitions: "downloaded" | "installed" | "error".
     * Incremental download progress is sent via WebSocket (ota_progress frames)
     * and included in the check-in extra payload.
     * errorCode is only included for status="error".
     */
    public void postOtaStatus(String serialNumber, String commandId, String status, String errorCode) {
        try {
            JSONObject body = new JSONObject();
            body.put("serial_number", serialNumber);
            body.put("command_id", commandId);
            body.put("status", status);
            if (errorCode != null && !errorCode.isEmpty()) body.put("error_code", errorCode);
            PostResult result = doPost("/api/v1/ota/status", body.toString());
            Log.d(TAG, "OTA status commandId=" + commandId + " status=" + status + " response=" + result.code);
        } catch (Exception e) {
            Log.e(TAG, "postOtaStatus failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/commands/{id}/ack
     * Reports result to the server. status = "installed" | "completed" | "failed"
     * output may be empty, stdout text, or base64-encoded binary.
     */
    public void ackCommand(String commandId, String serialNumber, String status, String output) {
        ackCommand(commandId, serialNumber, status, output, null);
    }

    /** pkg (nullable) = the package an install produced; sent on 'installed' so the
     *  server learns the APK→package mapping and can reconcile stuck installs. */
    public void ackCommand(String commandId, String serialNumber, String status, String output, String pkg) {
        try {
            JSONObject body = new JSONObject();
            body.put("serial_number", serialNumber);
            body.put("status", status);
            if (!output.isEmpty()) body.put("output", output);
            if (pkg != null && !pkg.isEmpty()) body.put("package", pkg);
            PostResult result = doPost("/api/v1/commands/" + commandId + "/ack", body.toString());
            Log.d(TAG, "Ack command " + commandId + " status=" + status + " response=" + result.code);
        } catch (Exception e) {
            Log.e(TAG, "Ack command failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/commands/{id}/ack — interim install progress.
     * status = "downloading" | "installing"; percent 0-100 (meaningful while
     * downloading, pass -1 to omit). Non-terminal: updates the live status only.
     */
    public void reportCommandProgress(String commandId, String serialNumber, String status, int percent) {
        try {
            JSONObject body = new JSONObject();
            body.put("serial_number", serialNumber);
            body.put("status", status);
            if (percent >= 0) body.put("progress", percent);
            PostResult result = doPost("/api/v1/commands/" + commandId + "/ack", body.toString());
            Log.d(TAG, "Progress command " + commandId + " status=" + status + " pct=" + percent + " response=" + result.code);
        } catch (Exception e) {
            Log.e(TAG, "reportCommandProgress failed: " + e.getMessage());
        }
    }

    private static class PostResult {
        final int code;
        final String body;
        PostResult(int code, String body) { this.code = code; this.body = body; }
    }

    private static byte[] gzip(byte[] data) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(data.length / 2);
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private PostResult doPost(String endpoint, String jsonBody) throws Exception {
        URL url = new URL(apiBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("X-API-Key", API_KEY);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        // Gzip only payloads big enough to win (check-ins); tiny bodies (acks) would
        // grow. The server inflates Content-Encoding: gzip transparently.
        if (payload.length > 512) {
            payload = gzip(payload);
            conn.setRequestProperty("Content-Encoding", "gzip");
        }
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        // On an error code getInputStream() throws; read getErrorStream() instead so the
        // body is drained and HttpURLConnection can keep the socket alive for reuse (a
        // dropped error stream forces a fresh TCP+TLS handshake on the next request).
        java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            } catch (Exception ignored) {}
        }
        return new PostResult(code, sb.toString());
    }
}
