package com.aioapp.otautil;

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
    private static final String DISCOVERY_URL = "";

    // Fallback used when DISCOVERY_URL is empty or unreachable
    private static final String DEFAULT_API_BASE_URL = "http://10.32.0.61:8080";

    // Shared API key — must match DEVICE_API_KEY in server .env
    static final String API_KEY = "your-secret-key-here";

    private static final long DEFAULT_POLL_INTERVAL_MS = 60_000;

    private String apiBaseUrl = DEFAULT_API_BASE_URL;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    public long getPollInterval() { return pollIntervalMs; }

    /**
     * Fetches config from DISCOVERY_URL.
     * Expected response: { "api_base_url": "http://...", "poll_interval_ms": 60000 }
     * Falls back to defaults if DISCOVERY_URL is empty or the request fails.
     */
    public void loadRemoteConfig() {
        if (DISCOVERY_URL.isEmpty()) {
            Log.i(TAG, "No DISCOVERY_URL set, using defaults: apiBaseUrl=" + apiBaseUrl + ", pollIntervalMs=" + pollIntervalMs);
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
                if (cfg.has("poll_interval_ms")) pollIntervalMs = cfg.getLong("poll_interval_ms");
            }
            Log.i(TAG, "Remote config loaded: apiBaseUrl=" + apiBaseUrl + ", pollIntervalMs=" + pollIntervalMs);
        } catch (Exception e) {
            Log.w(TAG, "Remote config fetch failed, using defaults: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/checkin
     * Retries once on HTTP 5xx. Skips on 401 (bad key — needs code fix, not retry).
     */
    public boolean checkin(JSONObject payload) {
        try {
            int code = doPost("/api/v1/checkin", payload.toString());
            if (code == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Checkin OK");
                return true;
            } else if (code >= 500) {
                Log.w(TAG, "Server error " + code + " — retrying in 10s");
                Thread.sleep(10_000);
                code = doPost("/api/v1/checkin", payload.toString());
                if (code == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Checkin OK (retry)");
                    return true;
                }
                Log.w(TAG, "Checkin retry failed: " + code + " — skipping cycle");
            } else if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.e(TAG, "Checkin 401: invalid API key — check API_KEY constant");
            } else {
                Log.w(TAG, "Checkin unexpected response: " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Checkin failed: " + e.getMessage());
        }
        return false;
    }

    private int doPost(String endpoint, String jsonBody) throws Exception {
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
        return conn.getResponseCode();
    }
}
