package com.aioapp.otautil;

import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MdmApiService {
    private static final String TAG = "MdmApiService";

    // Set this to your config discovery endpoint.
    // The URL should return a JSON body with "api_base_url" and "poll_interval_ms".
    private static final String DISCOVERY_URL = "";

    private static final String DEFAULT_API_URL = "http://10.32.0.61:8000";
    private static final long DEFAULT_POLL_INTERVAL_MS = 30000;

    private String apiBaseUrl = DEFAULT_API_URL;
    private long pollInterval = DEFAULT_POLL_INTERVAL_MS;

    public long getPollInterval() { return pollInterval; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String url) { this.apiBaseUrl = url; }
    public void setPollInterval(long ms) { this.pollInterval = ms; }

    /**
     * Fetches config from DISCOVERY_URL and updates apiBaseUrl + pollInterval.
     * Falls back to defaults if DISCOVERY_URL is empty or the request fails.
     */
    public void loadRemoteConfig() {
        if (DISCOVERY_URL.isEmpty()) {
            Log.i(TAG, "No DISCOVERY_URL set, using defaults: apiUrl=" + apiBaseUrl + ", pollInterval=" + pollInterval + "ms");
            return;
        }
        try {
            URL url = new URL(DISCOVERY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject cfg = new JSONObject(sb.toString());
                if (cfg.has("api_base_url")) apiBaseUrl = cfg.getString("api_base_url");
                if (cfg.has("poll_interval_ms")) pollInterval = cfg.getLong("poll_interval_ms");
            }
            Log.i(TAG, "Remote config loaded: apiUrl=" + apiBaseUrl + ", pollInterval=" + pollInterval + "ms");
        } catch (Exception e) {
            Log.w(TAG, "Remote config fetch failed, using defaults: " + e.getMessage());
        }
    }

    /**
     * Report device telemetry to MDM server.
     * TODO: wire up endpoint path once MDM API is provided.
     */
    public boolean reportDeviceData(JSONObject deviceData) {
        Log.d(TAG, "Device telemetry: " + deviceData.toString());
        // TODO: uncomment and set the correct endpoint once MDM API is defined, e.g.:
        // return makePostRequest("/device/report", deviceData.toString());
        return true;
    }

    String makePostRequest(String endpoint, String jsonBody) throws Exception {
        URL url = new URL(this.apiBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line.trim());
                return sb.toString();
            }
        } else {
            throw new Exception("HTTP " + code);
        }
    }
}
