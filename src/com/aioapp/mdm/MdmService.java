package com.aioapp.mdm;

import android.app.*;
import android.content.*;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MdmService extends Service {
    private static final String TAG = "MdmService";
    private static final String LOCATION_TAG = "MdmLocation";
    private static final String CHANNEL_ID = "MDM_SERVICE";
    private static final int NOTIFICATION_ID = 1001;

    private static final String POLL_ACTION = "com.aioapp.mdm.POLL";

    private AlarmManager alarmManager;
    private PendingIntent pollIntent;
    private BroadcastReceiver pollReceiver;
    private MdmApiService apiService;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private volatile boolean networkAvailable = false;
    private volatile boolean polling = false;
    private volatile boolean remoteConfigLoaded = false;
    private OtaUpdateManager otaUpdateManager;
    private volatile String otaCommandId;  // set while an OTA is in progress
    private volatile boolean isCapturing = false;
    private MdmWebSocketClient wsClient;
    private JSONArray cachedInstalledApps = null;
    private BroadcastReceiver packageChangeReceiver;
    private String lastNotificationText = "";
    private ExecutorService executor;
    private ScheduledExecutorService wifiScanExecutor;

    // Battery: registered once in onCreate, updated via sticky broadcast
    private volatile Intent cachedBatteryIntent = null;
    private BroadcastReceiver batteryReceiver;
    // Edge-detect charging state so we only push telemetry on plug/unplug
    // transitions (ACTION_BATTERY_CHANGED fires on every level change too).
    private volatile int lastChargingState = -1; // -1 unknown, 0 not charging, 1 charging

    // Per-field system query caches
    private double cachedStorageFreeGb = 0;
    private long storageLastMs = 0;
    private static final long STORAGE_CACHE_MS = 120_000;

    private JSONObject cachedWifiExtra = null;
    private long wifiLastMs = 0;
    private static final long WIFI_CACHE_MS = 300_000;

    private JSONArray cachedWifiScan = null;
    private static final long WIFI_SCAN_INTERVAL_SEC = 60;

    private JSONObject cachedRam = null;
    private long ramLastMs = 0;
    private static final long RAM_CACHE_MS = 30_000;

    private int cachedWlcStatus = -1;
    private long wlcLastMs = 0;
    private static final long WLC_CACHE_MS = 60_000;

    // App list delta
    private String lastAppsHash = null;
    private volatile boolean sendFullAppList = false;

    // Connection health
    private static final long HTTP_SAFETY_NET_MS = 5 * 60_000L;
    private static final long STALE_WS_THRESHOLD_SECS = 120;
    private volatile long lastHttpCheckinAt = 0;

    private static final Set<String> ALLOWED_SHELL_COMMANDS = new HashSet<>(Arrays.asList(
            "ls", "cat", "echo", "ps", "df", "uptime", "date", "id",
            "ip", "netstat", "ifconfig", "ping", "nslookup",
            "getprop", "setprop", "am", "pm", "wm", "settings",
            "dumpsys", "logcat", "screencap", "input", "service",
            "cmd", "stat", "find", "grep", "awk", "sed",
            "top", "free", "mount", "lsof", "du"
    ));

    private static boolean isShellCommandAllowed(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return false;
        String firstWord = cmd.trim().split("\\s+")[0];
        int slash = firstWord.lastIndexOf('/');
        if (slash >= 0) firstWord = firstWord.substring(slash + 1);
        return ALLOWED_SHELL_COMMANDS.contains(firstWord);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.DiscardOldestPolicy());
        wifiScanExecutor = Executors.newSingleThreadScheduledExecutor();
        wifiScanExecutor.scheduleWithFixedDelay(this::runWifiScan, 0, WIFI_SCAN_INTERVAL_SEC, TimeUnit.SECONDS);
        apiService = new MdmApiService();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MdmAdminReceiver.class);
        ensureDeviceOwner();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("MDM service running"));
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                cachedBatteryIntent = intent;
                int charging = extractCharging(intent) ? 1 : 0;
                if (lastChargingState != -1 && charging != lastChargingState) {
                    Log.i(TAG, "Charging state changed: " + lastChargingState + " -> " + charging
                            + " — pushing immediate telemetry");
                    sendTelemetryOverWs();
                }
                lastChargingState = charging;
            }
        };
        cachedBatteryIntent = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        alarmManager = getSystemService(AlarmManager.class);
        pollReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (networkAvailable && !polling) performCheckin();
                scheduleNextPoll();
            }
        };
        registerReceiver(pollReceiver, new IntentFilter(POLL_ACTION), Context.RECEIVER_NOT_EXPORTED);
        pollIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(POLL_ACTION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);

        registerNetworkCallback();
        registerPackageChangeReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Cancel any existing alarm before rescheduling — prevents duplicates when
        // LOCKED_BOOT_COMPLETED + BOOT_COMPLETED both fire on a fresh boot.
        alarmManager.cancel(pollIntent);
        if (networkAvailable && !polling) performCheckin();
        scheduleNextPoll();
        return START_STICKY;
    }

    private void scheduleNextPoll() {
        long intervalMs = getAdaptivePollInterval() * apiService.getBackoffMultiplier();
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs, pollIntent);
    }

    private void ensureDeviceOwner() {
        if (dpm.isDeviceOwnerApp(getPackageName())) {
            Log.i(TAG, "Already device owner");
            return;
        }
        // Mirror what `adb shell dpm set-device-owner` does internally:
        // setActiveAdmin must be called first, then setDeviceOwner.
        try {
            dpm.setActiveAdmin(adminComponent, true);
            Log.i(TAG, "setActiveAdmin OK");
        } catch (Exception e) {
            Log.e(TAG, "setActiveAdmin failed: " + e.getMessage());
        }
        try {
            boolean result = dpm.setDeviceOwner(adminComponent, android.os.UserHandle.USER_SYSTEM);
            Log.i(TAG, "setDeviceOwner: " + result);
        } catch (Exception e) {
            Log.e(TAG, "setDeviceOwner failed: " + e.getMessage());
        }
    }

    private void registerNetworkCallback() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                networkAvailable = true;
                Log.i(TAG, "Network available");
                if (!remoteConfigLoaded) {
                    executor.submit(() -> {
                        apiService.loadRemoteConfig();
                        remoteConfigLoaded = true;
                        startWebSocket();
                    });
                }
            }

            @Override
            public void onLost(Network network) {
                networkAvailable = false;
                Log.i(TAG, "Network lost");
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);

        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
        networkAvailable = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void performCheckin() {
        long now = System.currentTimeMillis();
        if (wsClient != null && wsClient.isConnected()) {
            // Detect stale WS: appears connected but nothing sent in >2 minutes
            long staleSecs = wsClient.getSecsSinceLastSend();
            if (staleSecs > STALE_WS_THRESHOLD_SECS) {
                Log.w(TAG, "WS stale for " + staleSecs + "s, forcing reconnect");
                wsClient.forceReconnect();
            } else {
                sendTelemetryOverWs();
            }
        }
        // HTTP safety net: always checkin via HTTP every 5 minutes regardless of WS state
        if ((now - lastHttpCheckinAt) >= HTTP_SAFETY_NET_MS) {
            lastHttpCheckinAt = now;
            executor.submit(() -> {
                polling = true;
                try {
                    JSONObject payload = buildCheckinPayload();
                    JSONObject response = apiService.checkin(payload);
                    if (response != null) {
                        if (response.optBoolean("send_apps", false)) sendFullAppList = true;
                        JSONObject config = response.optJSONObject("config");
                        if (config != null) applyConfig(config);
                    } else {
                        Log.w(TAG, "Checkin failed, reloading remote config");
                        apiService.loadRemoteConfig();
                        remoteConfigLoaded = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Checkin error: " + e.getMessage());
                } finally {
                    polling = false;
                }
            });
        }
    }

    private void ackCommand(String cmdId, String serial, String status, String output) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "command_ack");
                m.put("command_id", cmdId);
                m.put("serial_number", serial);
                m.put("status", status);
                if (!output.isEmpty()) m.put("output", output);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS ack failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.ackCommand(cmdId, serial, status, output));
    }

    private void reportLogcat(String requestId, String content) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "logcat_result");
                m.put("request_id", requestId);
                m.put("content", content);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS logcat failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.postLogcat(getDeviceSerial(), requestId, content));
    }

    private void reportOtaStatus(String cmdId, String status, String errorCode) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "ota_status");
                m.put("command_id", cmdId);
                m.put("status", status);
                if (errorCode != null && !errorCode.isEmpty()) m.put("error_code", errorCode);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS ota_status failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.postOtaStatus(getDeviceSerial(), cmdId, status, errorCode));
    }

    private void sendTelemetryOverWs() {
        if (wsClient == null || !wsClient.isConnected()) return;
        executor.submit(() -> {
            try {
                JSONObject payload = buildCheckinPayload();
                payload.put("type", "telemetry");
                wsClient.send(payload.toString());
            } catch (Exception e) {
                Log.e(TAG, "WS telemetry failed, falling back to HTTP: " + e.getMessage());
                try {
                    JSONObject payload = buildCheckinPayload();
                    JSONObject response = apiService.checkin(payload);
                    if (response != null) {
                        JSONObject config = response.optJSONObject("config");
                        if (config != null) applyConfig(config);
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "HTTP telemetry fallback error: " + e2.getMessage());
                }
            }
        });
    }

    private void applyConfig(JSONObject config) throws Exception {
        KioskManager.applyAndSave(MdmService.this, dpm, adminComponent, config);
        long secs = config.optLong("checkin_interval_seconds", 0);
        if (secs >= 10) apiService.setPollInterval(secs * 1000L);
    }

    private synchronized void startWebSocket() {
        if (wsClient != null) return;
        String serial = getDeviceSerial();
        wsClient = new MdmWebSocketClient(apiService.getApiBaseUrl(), serial, apiService.getApiKey());
        wsClient.setListener(this::handleWsMessage);
        wsClient.setConnectedCallback(this::sendTelemetryOverWs);
        wsClient.start();
        Log.i(TAG, "WebSocket client started");
    }

    private void handleWsMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "command":
                executor.submit(() -> {
                    try { processWsCommand(msg); } catch (Exception e) {
                        Log.e(TAG, "WS command error: " + e.getMessage());
                    }
                });
                break;
            case "logcat_request":
                executor.submit(() -> {
                    try { processWsLogcatRequest(msg); } catch (Exception e) {
                        Log.e(TAG, "WS logcat error: " + e.getMessage());
                    }
                });
                break;
            case "telemetry_request":
                sendTelemetryOverWs();
                break;
            case "ping_request":
                executor.submit(() -> {
                    try {
                        JSONObject pong = new JSONObject();
                        pong.put("type", "pong_response");
                        pong.put("nonce", msg.optString("nonce", ""));
                        wsClient.send(pong.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "ping_request handler error: " + e.getMessage());
                    }
                });
                break;
            case "config":
                executor.submit(() -> {
                    try { applyConfig(msg); } catch (Exception e) {
                        Log.e(TAG, "WS config error: " + e.getMessage());
                    }
                });
                break;
            case "input_event":
                executor.submit(() -> {
                    try { handleInputEvent(msg); } catch (Exception e) {
                        Log.e(TAG, "input_event error: " + e.getMessage());
                    }
                });
                break;
            case "start_capture":
                executor.submit(() -> {
                    int quality = msg.optInt("quality", 60);
                    double scale = msg.optDouble("scale", 0.5);
                    int maxFps = msg.optInt("max_fps", 10);
                    if (!isCapturing) {
                        isCapturing = true;
                        runCaptureLoop(quality, scale, maxFps);
                    }
                });
                break;
            case "stop_capture":
                isCapturing = false;
                break;
            default:
                Log.w(TAG, "Unknown WS message type: " + type);
        }
    }

    private void processWsCommand(JSONObject cmd) throws Exception {
        String cmdId = cmd.getString("id");
        String cmdType = cmd.optString("command_type", "install_apk");
        JSONObject payload = cmd.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        String serialNumber = getDeviceSerial();

        Log.i(TAG, "Processing WS command " + cmdId + " type=" + cmdType);
        switch (cmdType) {
            case "install_apk": {
                boolean ok = installApk(cmd.getString("apk_url"));
                ackCommand(cmdId, serialNumber, ok ? "installed" : "failed", "");
                break;
            }
            case "shell": {
                String shellCmd = payload.optString("cmd", "");
                if (shellCmd.isEmpty()) {
                    ackCommand(cmdId, serialNumber, "failed", "empty cmd");
                    break;
                }
                if (!isShellCommandAllowed(shellCmd)) {
                    Log.w(TAG, "Rejected shell command not on allowlist: " + shellCmd);
                    ackCommand(cmdId, serialNumber, "failed", "command not permitted");
                    break;
                }
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
                // Drain stderr in a side thread; cap at 10 MB to prevent OOM
                ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
                Thread stderrThread = new Thread(() -> {
                    try (InputStream es = p.getErrorStream()) {
                        byte[] buf = new byte[8192];
                        int n, total = 0;
                        while ((n = es.read(buf)) != -1) {
                            if (total < 10 * 1024 * 1024) { stderrBuf.write(buf, 0, n); total += n; }
                            // keep draining even past cap so process doesn't stall on full pipe
                        }
                    } catch (Exception ignored) {}
                });
                stderrThread.start();
                // Stream stdout chunks via WebSocket so the browser sees them immediately
                StringBuilder collected = new StringBuilder();
                try (InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        collected.append(chunk);
                        JSONObject outFrame = new JSONObject();
                        outFrame.put("type", "command_output");
                        outFrame.put("command_id", cmdId);
                        outFrame.put("chunk", chunk);
                        wsClient.send(outFrame.toString());
                    }
                }
                boolean finished = p.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                }
                stderrThread.join(5_000);
                int exitCode = finished ? p.exitValue() : -1;
                // Signal stream end so the browser SSE closes
                JSONObject doneFrame = new JSONObject();
                doneFrame.put("type", "command_done");
                doneFrame.put("command_id", cmdId);
                doneFrame.put("exit_code", exitCode);
                wsClient.send(doneFrame.toString());
                // HTTP-ack to persist status in DB
                String output = collected.length() > 0 ? collected.toString()
                        : stderrBuf.toString(StandardCharsets.UTF_8);
                ackCommand(cmdId, serialNumber, exitCode == 0 ? "completed" : "failed", output);
                break;
            }
            case "screenshot": {
                File tmp = new File(getCacheDir(), "mdm_screen_" + System.currentTimeMillis() + ".png");
                try {
                    java.lang.Process p = Runtime.getRuntime().exec(
                            new String[]{"screencap", "-p", tmp.getAbsolutePath()});
                    p.waitFor();
                    // Stream-encode to base64 in 8 KB chunks — raw PNG bytes never fully in heap
                    java.io.ByteArrayOutputStream b64Buf = new java.io.ByteArrayOutputStream();
                    try (FileInputStream fis = new FileInputStream(tmp);
                         OutputStream enc = java.util.Base64.getEncoder().wrap(b64Buf)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) != -1) enc.write(buf, 0, n);
                    }
                    ackCommand(cmdId, serialNumber, "completed",
                            b64Buf.toString(StandardCharsets.UTF_8.name()));
                } finally {
                    tmp.delete();
                }
                break;
            }
            case "get_app_inventory": {
                ackCommand(cmdId, serialNumber, "completed", getInstalledApps().toString());
                break;
            }
            case "reboot": {
                // Server marks reboot commands completed at delivery — no ack needed
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                pm.reboot(null);
                break;
            }
            case "ota": {
                String updateUrl = payload.optString("update_url", "");
                final String otaCmdId = cmdId;
                final String otaSerial = serialNumber;
                // Cancel any previous OTA before starting a new one
                if (otaUpdateManager != null) {
                    otaUpdateManager.cancel();
                }
                otaCommandId = otaCmdId;
                if (otaUpdateManager == null) {
                    otaUpdateManager = new OtaUpdateManager(this, executor);
                }
                otaUpdateManager.setListener(new OtaUpdateManager.Listener() {
                    @Override public void onDownloadProgress(String phase, int percent) {
                        // Send real-time progress via WebSocket
                        if (wsClient != null) {
                            try {
                                JSONObject frame = new JSONObject();
                                frame.put("type", "ota_progress");
                                frame.put("command_id", otaCmdId);
                                frame.put("phase", phase);
                                frame.put("percent", percent);
                                wsClient.send(frame.toString());
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to send OTA progress frame: " + e.getMessage());
                            }
                        }
                    }
                    @Override public void onDownloadComplete() {
                        reportOtaStatus(otaCmdId, "downloaded", null);
                    }
                    @Override public void onInstallComplete() {
                        otaCommandId = null;
                        reportOtaStatus(otaCmdId, "installed", null);
                    }
                    @Override public void onError(String errorCode) {
                        otaCommandId = null;
                        reportOtaStatus(otaCmdId, "error", errorCode);
                    }
                });
                otaUpdateManager.startUpdate(updateUrl);
                break;
            }
            case "start_capture": {
                int quality = payload.optInt("quality", 60);
                double scale = payload.optDouble("scale", 0.5);
                int maxFps = payload.optInt("max_fps", 10);
                if (!isCapturing) {
                    isCapturing = true;
                    executor.submit(() -> runCaptureLoop(quality, scale, maxFps));
                }
                ackCommand(cmdId, serialNumber, "completed", "");
                break;
            }
            case "stop_capture": {
                isCapturing = false;
                ackCommand(cmdId, serialNumber, "completed", "");
                break;
            }
            default:
                Log.w(TAG, "Unknown command type: " + cmdType);
                ackCommand(cmdId, serialNumber, "failed", "unknown type: " + cmdType);
        }
    }


    private void runCaptureLoop(int quality, double scale, int maxFps) {
        android.view.Display display = getDisplay();
        if (display == null) {
            Log.e(TAG, "Cannot get display for capture");
            isCapturing = false;
            return;
        }
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        int scaledW = (int) (size.x * scale);
        int scaledH = (int) (size.y * scale);
        int rotation = display.getRotation();

        long targetMs = 1000 / maxFps;
        Log.i(TAG, "Capture loop started — scaled=" + scaledW + "x" + scaledH
                + " quality=" + quality + " fps=" + maxFps);

        while (isCapturing && wsClient != null && wsClient.isConnected()) {
            long start = System.currentTimeMillis();
            try {
                android.graphics.Bitmap screen = captureScreen(scaledW, scaledH, rotation);
                if (screen == null) {
                    Thread.sleep(targetMs);
                    continue;
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(32768);
                screen.compress(android.graphics.Bitmap.CompressFormat.WEBP, quality, bos);
                screen.recycle();

                byte[] frame = bos.toByteArray();
                wsClient.sendBinary(frame);

                long elapsed = System.currentTimeMillis() - start;
                if (elapsed < targetMs) {
                    Thread.sleep(targetMs - elapsed);
                }
            } catch (Exception e) {
                Log.e(TAG, "Capture loop error: " + e.getMessage());
                if (wsClient == null || !wsClient.isConnected()) break;
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
        isCapturing = false;
        Log.i(TAG, "Capture loop stopped");
    }

    private android.graphics.Bitmap captureScreen(int w, int h, int rotation) {
        try {
            java.lang.reflect.Method method = android.view.SurfaceControl.class.getMethod(
                    "screenshot", android.graphics.Rect.class, int.class, int.class, int.class);
            return (android.graphics.Bitmap) method.invoke(null,
                    new android.graphics.Rect(), w, h, rotation);
        } catch (Exception e) {
            Log.w(TAG, "SurfaceControl.screenshot() failed, falling back to screencap: " + e.getMessage());
            return captureScreenFallback(w, h);
        }
    }

    private android.graphics.Bitmap captureScreenFallback(int maxW, int maxH) {
        File tmp = new File(getCacheDir(), "mdm_remote_" + System.currentTimeMillis() + ".png");
        try {
            java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"screencap", "-p", tmp.getAbsolutePath()});
            p.waitFor();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 2; // half resolution as fallback
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(
                    tmp.getAbsolutePath(), opts);
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "screencap fallback failed: " + e.getMessage());
            return null;
        } finally {
            tmp.delete();
        }
    }

    private void handleInputEvent(JSONObject msg) throws Exception {
        float normX = (float) msg.optDouble("x", 0.5);
        float normY = (float) msg.optDouble("y", 0.5);
        String action = msg.optString("action", "touch");
        String event = msg.optString("event", "down");

        android.hardware.input.InputManager im =
                (android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE);

        if ("touch".equals(action)) {
            android.view.Display display = null;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                display = getDisplay();
            }
            int displayW = 1080, displayH = 1920;
            if (display != null) {
                android.graphics.Point size = new android.graphics.Point();
                display.getRealSize(size);
                displayW = size.x;
                displayH = size.y;
            }
            float x = normX * displayW;
            float y = normY * displayH;

            int actionCode;
            switch (event) {
                case "down":  actionCode = android.view.MotionEvent.ACTION_DOWN; break;
                case "move":  actionCode = android.view.MotionEvent.ACTION_MOVE; break;
                case "up":    actionCode = android.view.MotionEvent.ACTION_UP; break;
                default:      actionCode = android.view.MotionEvent.ACTION_DOWN; break;
            }

            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent me = android.view.MotionEvent.obtain(
                    now, now, actionCode, x, y, 0);
            im.injectInputEvent(me, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            me.recycle();

        } else if ("key".equals(action)) {
            int keycode = msg.optInt("keycode", 0);
            int keyAction = "down".equals(event)
                    ? android.view.KeyEvent.ACTION_DOWN
                    : android.view.KeyEvent.ACTION_UP;

            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent ke = new android.view.KeyEvent(now, now, keyAction, keycode, 0);
            im.injectInputEvent(ke, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            ke.recycle();
        }
    }

    private void processWsLogcatRequest(JSONObject req) throws Exception {
        String requestId = req.getString("id");
        String level = req.optString("level", "V");
        int lines = req.optInt("lines", 500);
        String tag = req.optString("tag", "");

        String[] cmd;
        if (tag.isEmpty()) {
            cmd = new String[]{"logcat", "-t", String.valueOf(lines), "-v", "threadtime", "*:" + level};
        } else {
            cmd = new String[]{"logcat", "-t", String.valueOf(lines), "-v", "threadtime", tag + ":" + level, "*:S"};
        }

        Log.d(TAG, "Logcat cmd: " + java.util.Arrays.toString(cmd));

        final int MAX_LOGCAT_BYTES = 5 * 1024 * 1024;
        java.lang.Process process = Runtime.getRuntime().exec(cmd);
        // Drain stderr in background — prevents the process blocking on a full stderr pipe
        Thread stderrDrain = new Thread(() -> {
            try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        });
        stderrDrain.start();

        StringBuilder sb = new StringBuilder();
        try (InputStream is = process.getInputStream()) {
            byte[] buf = new byte[65536];
            int n, total = 0;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                total += n;
                if (total >= MAX_LOGCAT_BYTES) {
                    sb.append("\n[truncated — exceeded 5 MB limit]");
                    break;
                }
            }
        }
        process.destroy();
        process.waitFor();
        stderrDrain.join();

        String content = sb.toString();
        Log.d(TAG, "Logcat result: " + content.length() + " bytes");
        reportLogcat(requestId, content);
    }

    private boolean installApk(String apkUrl) {
        File apkFile = new File(getCacheDir(), "mdm_install_" + System.currentTimeMillis() + ".apk");
        try {
            // Download
            URL url = new URL(apkUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "APK download failed: HTTP " + conn.getResponseCode());
                return false;
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(apkFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Log.i(TAG, "APK downloaded to " + apkFile.getAbsolutePath());

            // Extract package name from APK for fallback verification
            String apkPackageName = null;
            android.content.pm.PackageInfo apkInfo = getPackageManager().getPackageArchiveInfo(
                    apkFile.getAbsolutePath(), 0);
            if (apkInfo != null) {
                apkPackageName = apkInfo.packageName;
                Log.i(TAG, "APK package: " + apkPackageName);
            }

            // Install via PackageInstaller API
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);

            BroadcastReceiver resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        Log.i(TAG, "APK installed successfully");
                        success.set(true);
                    } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Log.e(TAG, "PackageInstaller requires user action — check USER_ACTION_NOT_REQUIRED / INSTALL_PACKAGES permission");
                    } else {
                        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        int legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", -999);
                        Log.e(TAG, "PackageInstaller failed status=" + status + " legacy=" + legacyStatus + " msg=" + msg);
                    }
                    latch.countDown();
                }
            };

            String action = "com.aioapp.mdm.INSTALL_RESULT_" + System.currentTimeMillis();
            registerReceiver(resultReceiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            boolean completed;
            try {
                int sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream in = new java.io.FileInputStream(apkFile);
                         OutputStream out = session.openWrite("base.apk", 0, apkFile.length())) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(this, sessionId,
                            new Intent(action).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
                    session.commit(pi.getIntentSender());
                }
                completed = latch.await(180, TimeUnit.SECONDS);
            } finally {
                try { unregisterReceiver(resultReceiver); } catch (Exception ignored) {}
            }

            if (success.get()) return true;

            // Fallback: if timed out or callback reported failure, check if the package is actually installed
            if (apkPackageName != null) {
                try {
                    getPackageManager().getPackageInfo(apkPackageName, 0);
                    Log.i(TAG, "APK package " + apkPackageName + " is installed (verified via PackageManager"
                            + (completed ? " after callback reported failure)" : " after timeout)"));
                    return true;
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "APK package " + apkPackageName + " not found after install"
                            + (completed ? " (callback reported failure)" : " (timed out after 180s)"));
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "installApk error: " + e.getMessage());
            return false;
        } finally {
            apkFile.delete();
        }
    }

    private JSONArray getInstalledApps() {
        JSONArray apps = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> activities = pm.queryIntentActivities(launcherIntent, 0);
            for (android.content.pm.ResolveInfo ri : activities) {
                JSONObject app = new JSONObject();
                app.put("package", ri.activityInfo.packageName);
                app.put("name", ri.loadLabel(pm).toString());
                apps.put(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "getInstalledApps error: " + e.getMessage());
        }
        return apps;
    }

    private JSONObject buildCheckinPayload() throws JSONException {
        JSONObject payload = new JSONObject();

        payload.put("serial_number", getDeviceSerial());
        payload.put("build_id", SystemPropertiesProxy.get("ro.build.id", Build.UNKNOWN));

        // Read battery intent once; extract both pct and temp from the same object
        Intent batteryIntent = getBatteryIntent();
        payload.put("battery_pct", extractBatteryPct(batteryIntent));

        JSONObject extra = new JSONObject();
        populateWifiInfo(extra);
        populateWifiScanResults(extra);
        extra.put("storage_free_gb", getStorageFreeGb());
        extra.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000);
        extra.put("wlc_status", getWlcStatus());
        extra.put("ram_usage_mb", getRamUsageMb());
        extra.put("timezone", java.util.TimeZone.getDefault().getID());
        extra.put("battery_temp_c", extractBatteryTemperature(batteryIntent));
        extra.put("charging", extractCharging(batteryIntent));

        // Include OTA progress if an update is in progress
        if (otaUpdateManager != null && otaUpdateManager.isActive() && otaCommandId != null) {
            JSONObject otaProgress = new JSONObject();
            otaProgress.put("command_id", otaCommandId);
            otaProgress.put("phase", otaUpdateManager.getCurrentPhase());
            otaProgress.put("percent", otaUpdateManager.getCurrentPercent());
            extra.put("ota_progress", otaProgress);
        }

        payload.put("extra", extra);

        // Send full app list only when packages changed or server explicitly requests it
        JSONArray apps = getCachedInstalledApps();
        String currentHash = computeAppsHash(apps);
        if (sendFullAppList || !currentHash.equals(lastAppsHash)) {
            payload.put("installed_apps", apps);
            lastAppsHash = currentHash;
            sendFullAppList = false;
        }
        payload.put("apps_hash", currentHash);

        return payload;
    }

    private String getDeviceSerial() {
        try {
            return Build.getSerial();
        } catch (SecurityException e) {
            return Build.UNKNOWN;
        }
    }

    private Intent getBatteryIntent() {
        return cachedBatteryIntent;
    }

    private int extractBatteryPct(Intent batteryStatus) {
        if (batteryStatus == null) return -1;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return (int) ((level / (float) scale) * 100);
    }

    private float extractBatteryTemperature(Intent batteryStatus) {
        if (batteryStatus == null) return -999;
        int tenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        return tenths / 10.0f;
    }

    private boolean extractCharging(Intent batteryStatus) {
        if (batteryStatus == null) return false;
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0) return true;
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void populateWifiInfo(JSONObject extra) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (cachedWifiExtra != null && now - wifiLastMs < WIFI_CACHE_MS) {
            extra.put("ip_address", cachedWifiExtra.opt("ip_address"));
            extra.put("wifi", cachedWifiExtra.opt("wifi"));
            return;
        }
        cachedWifiExtra = new JSONObject();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            cachedWifiExtra.put("ip_address", JSONObject.NULL);
            cachedWifiExtra.put("wifi", JSONObject.NULL);
        } else {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                cachedWifiExtra.put("ip_address", JSONObject.NULL);
                cachedWifiExtra.put("wifi", JSONObject.NULL);
            } else {
                int ip4 = info.getIpAddress();
                cachedWifiExtra.put("ip_address", String.format("%d.%d.%d.%d",
                        ip4 & 0xff, (ip4 >> 8) & 0xff, (ip4 >> 16) & 0xff, (ip4 >> 24) & 0xff));
                cachedWifiExtra.put("wifi", info.getSSID());
            }
        }
        wifiLastMs = now;
        extra.put("ip_address", cachedWifiExtra.opt("ip_address"));
        extra.put("wifi", cachedWifiExtra.opt("wifi"));
    }

    /** Non-blocking: writes the latest cached scan into the checkin extra. */
    private void populateWifiScanResults(JSONObject extra) throws JSONException {
        if (cachedWifiScan != null) {
            extra.put("wifi_scan", cachedWifiScan);
        } else {
            extra.put("wifi_scan", new JSONArray());
        }
    }

    /** Runs on wifiScanExecutor every WIFI_SCAN_INTERVAL_SEC; blocks up to 5s per scan. */
    private void runWifiScan() {
        Log.i(LOCATION_TAG, "Starting WiFi scan...");
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                Log.w(LOCATION_TAG, "WiFi scan skipped: WifiManager is null");
                return;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final List<ScanResult>[] holder = new List[1];

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(LOCATION_TAG, "Scan results available broadcast received");
                    holder[0] = wm.getScanResults();
                    latch.countDown();
                }
            };

            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            getApplicationContext().registerReceiver(receiver, filter);

            try {
                boolean scanStarted = wm.startScan();
                Log.i(LOCATION_TAG, "startScan() returned: " + scanStarted);

                if (scanStarted) {
                    boolean gotResults = latch.await(5, TimeUnit.SECONDS);
                    if (!gotResults) {
                        Log.w(LOCATION_TAG, "WiFi scan timed out after 5s, falling back to cached results");
                        holder[0] = wm.getScanResults();
                    }
                } else {
                    Log.w(LOCATION_TAG, "startScan() returned false, using cached results");
                    holder[0] = wm.getScanResults();
                }
            } finally {
                try {
                    getApplicationContext().unregisterReceiver(receiver);
                } catch (IllegalArgumentException ignored) {
                }
            }

            List<ScanResult> results = holder[0];
            if (results != null) {
                cachedWifiScan = new JSONArray();
                Log.i(LOCATION_TAG, "WiFi scan returned " + results.size() + " APs");
                for (ScanResult sr : results) {
                    JSONObject ap = new JSONObject();
                    ap.put("bssid", sr.BSSID);
                    ap.put("ssid", sr.SSID);
                    ap.put("rssi", sr.level);
                    cachedWifiScan.put(ap);
                }
            } else {
                Log.w(LOCATION_TAG, "WiFi scan returned null");
            }
        } catch (SecurityException e) {
            Log.w(LOCATION_TAG, "WiFi scan permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(LOCATION_TAG, "WiFi scan error: " + e.getMessage());
        }
        Log.i(LOCATION_TAG, "WiFi scan complete: " + (cachedWifiScan != null ? cachedWifiScan.length() : 0) + " APs in cache");
    }

    private JSONObject getRamUsageMb() throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (cachedRam != null && now - ramLastMs < RAM_CACHE_MS) return cachedRam;
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        cachedRam = new JSONObject();
        cachedRam.put("total", mi.totalMem / (1024 * 1024));
        cachedRam.put("available", mi.availMem / (1024 * 1024));
        cachedRam.put("used", (mi.totalMem - mi.availMem) / (1024 * 1024));
        ramLastMs = now;
        return cachedRam;
    }

    private int getWlcStatus() {
        long now = SystemClock.elapsedRealtime();
        if (wlcLastMs > 0 && now - wlcLastMs < WLC_CACHE_MS) return cachedWlcStatus;
        final String gpioPath = "/sys/devices/platform/soc/soc:customer_gpio/gpio27";
        final long windowMs = 500;
        final long stepMs = 10;
        boolean sawZero = false;
        boolean sawOne = false;
        boolean anyRead = false;
        long deadline = now + windowMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(gpioPath))) {
                String line = reader.readLine();
                if (line != null) {
                    String v = line.replace("\0", "").trim();
                    if (v.equals("0")) { sawZero = true; anyRead = true; }
                    else if (v.equals("1")) { sawOne = true; anyRead = true; }
                }
            } catch (Exception e) {
                Log.e(TAG, "getWlcStatus error: " + e.getMessage());
                cachedWlcStatus = -1;
                wlcLastMs = now;
                return cachedWlcStatus;
            }
            try { Thread.sleep(stepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        if (!anyRead) cachedWlcStatus = -1;
        else if (sawZero && sawOne) cachedWlcStatus = 2; // pad disconnected (toggling)
        else if (sawOne) cachedWlcStatus = 1;            // device placed
        else cachedWlcStatus = 0;                        // no device
        wlcLastMs = now;
        return cachedWlcStatus;
    }

    private double getStorageFreeGb() {
        long now = SystemClock.elapsedRealtime();
        if (storageLastMs > 0 && now - storageLastMs < STORAGE_CACHE_MS) return cachedStorageFreeGb;
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long bytesAvailable = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        cachedStorageFreeGb = Math.round(bytesAvailable / (1024.0 * 1024.0 * 1024.0) * 10.0) / 10.0;
        storageLastMs = now;
        return cachedStorageFreeGb;
    }

    private void registerPackageChangeReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        packageChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Package changed, invalidating app cache");
                cachedInstalledApps = null;
            }
        };
        registerReceiver(packageChangeReceiver, filter);
    }

    private JSONArray getCachedInstalledApps() {
        if (cachedInstalledApps == null) {
            cachedInstalledApps = getInstalledApps();
        }
        return cachedInstalledApps;
    }

    private String computeAppsHash(JSONArray apps) {
        List<String> packages = new ArrayList<>(apps.length());
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null) packages.add(app.optString("package", ""));
        }
        Collections.sort(packages);
        CRC32 crc = new CRC32();
        for (String pkg : packages) {
            crc.update(pkg.getBytes(StandardCharsets.UTF_8));
        }
        return Long.toHexString(crc.getValue());
    }

    private long getAdaptivePollInterval() {
        long base = apiService.getPollInterval();
        Intent battery = getBatteryIntent();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();

        if (battery == null) return interactive ? base : base * 2;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0) return base; // charging — use normal interval

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (scale > 0 && level >= 0) ? (int) ((level / (float) scale) * 100) : 100;
        if (pct <= 15) return base * 4;  // critical battery: poll 4× less often
        if (pct <= 30) return base * 2;  // low battery: poll 2× less often
        if (!interactive) return base * 2; // screen off, not charging, not low battery
        return base;
    }

    private void updateNotificationIfNeeded(String text) {
        if (text.equals(lastNotificationText)) return;
        lastNotificationText = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "MDM Service", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MDM Client")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        isCapturing = false;
        alarmManager.cancel(pollIntent);
        if (pollReceiver != null) {
            try { unregisterReceiver(pollReceiver); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        wifiScanExecutor.shutdownNow();
        if (wsClient != null) wsClient.stop();
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        if (packageChangeReceiver != null) {
            try { unregisterReceiver(packageChangeReceiver); } catch (Exception ignored) {}
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
        if (packageChangeReceiver != null) {
            try { unregisterReceiver(packageChangeReceiver); } catch (Exception ignored) {}
            packageChangeReceiver = null;
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
