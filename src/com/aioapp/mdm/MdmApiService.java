package com.aioapp.mdm;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MdmApiService {
    private static final String TAG = "MdmApiService";

    // Permanent config URL — this never changes.
    // It returns a JSON body with "api_base_url" so the actual server address can be updated remotely.
    private static final String DISCOVERY_URL = "https://ota-update-packages.s3.us-west-2.amazonaws.com/mdm-config.json";

    // Fallback used when DISCOVERY_URL is empty or unreachable
    private static final String DEFAULT_API_BASE_URL = "http://10.32.0.246:8080";

    // When true, always use DEFAULT_API_BASE_URL and skip the remote config fetch.
    private static final boolean USE_HARDCODED_API_URL = false;

    // Shared API key — must match DEVICE_API_KEY in server .env
    static final String API_KEY = "your-secret-key-here";

    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000; // 30 seconds

    private String apiBaseUrl = DEFAULT_API_BASE_URL;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private int consecutiveFailures = 0;

    public long getPollInterval() { return pollIntervalMs; }

    public void setPollInterval(long ms) { pollIntervalMs = ms; }

    /** Returns a backoff multiplier (1, 2, 4, … up to 16×) based on consecutive failures. */
    public long getBackoffMultiplier() {
        if (consecutiveFailures == 0) return 1;
        return Math.min((long) Math.pow(2, consecutiveFailures), 16);
    }

    /**
     * Fetches config from DISCOVERY_URL.
     * Expected response: { "api_base_url": "http://...", "poll_interval_ms": 60000 }
     * Falls back to defaults if DISCOVERY_URL is empty or the request fails.
     */
    public void loadRemoteConfig() {
        if (USE_HARDCODED_API_URL) {
            Log.i(TAG, "USE_HARDCODED_API_URL=true, skipping discovery: apiBaseUrl=" + apiBaseUrl);
            return;
        }
        if (DISCOVERY_URL.isEmpty()) {
            Log.i(TAG, "No DISCOVERY_URL set, using defaults: apiBaseUrl=" + apiBaseUrl);
            return;
        }
        try {
            URL url = new URL(DISCOVERY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject cfg = new JSONObject(sb.toString());
                if (cfg.has("api_base_url")) apiBaseUrl = cfg.getString("api_base_url");
            }
            Log.i(TAG, "Remote config loaded: apiBaseUrl=" + apiBaseUrl);
        } catch (Exception e) {
            Log.w(TAG, "Remote config fetch failed, using defaults: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/checkin
     * Returns the response body as a JSONObject on success, or null on failure.
     * Tracks consecutive failures for exponential backoff — no blocking retries.
     */
    public JSONObject checkin(JSONObject payload) {
        try {
            PostResult result = doPost("/api/v1/checkin", payload.toString());
            if (result.code == HttpURLConnection.HTTP_OK) {
                consecutiveFailures = 0;
                Log.d(TAG, "Checkin OK");
                return new JSONObject(result.body);
            } else if (result.code >= 500) {
                consecutiveFailures++;
                Log.w(TAG, "Server error " + result.code + " (failure #" + consecutiveFailures + ") — backing off");
            } else if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.e(TAG, "Checkin 401: invalid API key — check API_KEY constant");
            } else {
                consecutiveFailures++;
                Log.w(TAG, "Checkin unexpected response: " + result.code);
            }
        } catch (Exception e) {
            consecutiveFailures++;
            Log.e(TAG, "Checkin failed: " + e.getMessage());
        }
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
     * Reports OTA progress. status = "downloaded" | "installed" | "error"
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
        try {
            JSONObject body = new JSONObject();
            body.put("serial_number", serialNumber);
            body.put("status", status);
            if (!output.isEmpty()) body.put("output", output);
            PostResult result = doPost("/api/v1/commands/" + commandId + "/ack", body.toString());
            Log.d(TAG, "Ack command " + commandId + " status=" + status + " response=" + result.code);
        } catch (Exception e) {
            Log.e(TAG, "Ack command failed: " + e.getMessage());
        }
    }

    private static class PostResult {
        final int code;
        final String body;
        PostResult(int code, String body) { this.code = code; this.body = body; }
    }

    private PostResult doPost(String endpoint, String jsonBody) throws Exception {
        URL url = new URL(apiBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("X-API-Key", API_KEY);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } catch (Exception ignored) {}
        return new PostResult(code, sb.toString());
    }
}
