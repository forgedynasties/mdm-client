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
    private static final String API_KEY = "ca1b6b645bb96b0a738ecc1bbd7c03078b29d5257d56755f";

    private static final boolean USE_LOCAL_SERVER = true;
    private static final String LOCAL_API_BASE_URL = "http://10.32.1.113:8082";
    private static final String DEFAULT_API_BASE_URL = "https://mdm.dev.aioapp.com";
    // Persistent system property that re-points the fleet at a different server
    // without a system update: setprop persist.sys.mdm.url https://host[:port]
    private static final String URL_OVERRIDE_PROP = "persist.sys.mdm.url";

    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000;
    private static final Random JITTER = new Random();

    private volatile String apiBaseUrl = resolveBaseUrl();
    // Written on the executor thread (applyConfig / checkin), read on the alarm thread.
    private volatile long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private volatile int consecutiveFailures = 0;
    // One-shot delay requested by the server via a 429/503 Retry-After; consumed by scheduleNextPoll.
    private volatile long retryAfterMs = 0;

    public MdmApiService() {}

    private static String resolveBaseUrl() {
        // Forced hardcoded server: the persist.sys.mdm.url override is intentionally
        // ignored so a stale/leftover prop can never re-point this fleet.
        Log.i(TAG, "Server URL hardcoded (override ignored): " + LOCAL_API_BASE_URL);
        return LOCAL_API_BASE_URL;
    }

    public String getApiKey() { return API_KEY; }

    public String getApiBaseUrl() { return apiBaseUrl; }

    public long getPollInterval() { return pollIntervalMs; }

    /** One-shot delay requested by the server via a 429/503 Retry-After, in ms (0 if none). Consumed. */
    public long consumeRetryAfterMs() {
        long v = retryAfterMs;
        retryAfterMs = 0;
        return v;
    }

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
                if (result.code == 429 || result.code == HttpURLConnection.HTTP_UNAVAILABLE) {
                    // Server is shedding load. Burning the fast retries here only deepens the
                    // overload — stop, honor Retry-After for the next poll, and count it as a
                    // failure so the alarm backoff engages.
                    if (result.retryAfterSecs > 0) {
                        retryAfterMs = Math.max(retryAfterMs, result.retryAfterSecs * 1000L);
                    }
                    consecutiveFailures++;
                    Log.w(TAG, "Checkin rate-limited (HTTP " + result.code + "), Retry-After="
                            + result.retryAfterSecs + "s — backing off");
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
        int retryAfterSecs = -1; // parsed from Retry-After on 429/503, else -1
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
                // Cap the read: a malfunctioning/hostile server (or a MITM) returning a giant body
                // must not OOM the service. 256 KB is far more than any check-in/ack response.
                while ((line = br.readLine()) != null && sb.length() <= 256 * 1024) sb.append(line);
            } catch (Exception ignored) {}
        }
        PostResult result = new PostResult(code, sb.toString());
        if (code == 429 || code == HttpURLConnection.HTTP_UNAVAILABLE) {
            try { result.retryAfterSecs = Integer.parseInt(conn.getHeaderField("Retry-After").trim()); }
            catch (Exception ignored) { /* absent or HTTP-date form — leave -1 */ }
        }
        return result;
    }
}
