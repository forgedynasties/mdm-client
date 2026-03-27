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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MdmService extends Service {
    private static final String TAG = "MdmService";
    private static final String CHANNEL_ID = "MDM_SERVICE";
    private static final int NOTIFICATION_ID = 1001;

    private Handler mainHandler;
    private MdmApiService apiService;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private volatile boolean networkAvailable = false;
    private volatile boolean polling = false;
    private volatile boolean remoteConfigLoaded = false;
    private OtaUpdateManager otaUpdateManager;
    private MdmWebSocketClient wsClient;
    private final ConcurrentHashMap<String, OutputStream> shellSessions = new ConcurrentHashMap<>();
    private JSONArray cachedInstalledApps = null;
    private BroadcastReceiver packageChangeReceiver;
    private String lastNotificationText = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (networkAvailable && !polling) {
                performCheckin();
            }
            mainHandler.postDelayed(this, getAdaptivePollInterval() * apiService.getBackoffMultiplier());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        apiService = new MdmApiService();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MdmAdminReceiver.class);
        ensureDeviceOwner();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("MDM service running"));
        registerNetworkCallback();
        registerPackageChangeReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mainHandler.post(pollRunnable);
        return START_STICKY;
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
                    new Thread(() -> {
                        apiService.loadRemoteConfig();
                        remoteConfigLoaded = true;
                        startWebSocket();
                    }).start();
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
        polling = true;
        new Thread(() -> {
            try {
                JSONObject payload = buildCheckinPayload();
                JSONObject response = apiService.checkin(payload);
                if (response != null) {
                    JSONObject config = response.optJSONObject("config");
                    if (config != null) {
                        KioskManager.applyAndSave(MdmService.this, dpm, adminComponent, config);
                    }
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
        }).start();
    }

    private synchronized void startWebSocket() {
        if (wsClient != null) return;
        String serial = getDeviceSerial();
        wsClient = new MdmWebSocketClient(apiService.getApiBaseUrl(), serial, MdmApiService.API_KEY);
        wsClient.setListener(this::handleWsMessage);
        wsClient.start();
        Log.i(TAG, "WebSocket client started");
    }

    private void handleWsMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "command":
                new Thread(() -> {
                    try { processWsCommand(msg); } catch (Exception e) {
                        Log.e(TAG, "WS command error: " + e.getMessage());
                    }
                }).start();
                break;
            case "logcat_request":
                new Thread(() -> {
                    try { processWsLogcatRequest(msg); } catch (Exception e) {
                        Log.e(TAG, "WS logcat error: " + e.getMessage());
                    }
                }).start();
                break;
            case "shell_start":
                new Thread(() -> handleShellStart(msg.optString("session_id"))).start();
                break;
            case "shell_stdin":
                handleShellStdin(msg.optString("session_id"), msg.optString("data"));
                break;
            case "shell_resize":
                break; // pty resize not supported without pty4j
            case "shell_close":
                handleShellClose(msg.optString("session_id"));
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
                apiService.ackCommand(cmdId, serialNumber, ok ? "installed" : "failed", "");
                break;
            }
            case "shell": {
                String shellCmd = payload.optString("cmd", "");
                if (shellCmd.isEmpty()) {
                    apiService.ackCommand(cmdId, serialNumber, "failed", "empty cmd");
                    break;
                }
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
                // Drain stderr in a side thread; collect it in case stdout is empty
                ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
                Thread stderrThread = new Thread(() -> {
                    try { p.getErrorStream().transferTo(stderrBuf); } catch (Exception ignored) {}
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
                int exitCode = p.waitFor();
                stderrThread.join();
                // Signal stream end so the browser SSE closes
                JSONObject doneFrame = new JSONObject();
                doneFrame.put("type", "command_done");
                doneFrame.put("command_id", cmdId);
                doneFrame.put("exit_code", exitCode);
                wsClient.send(doneFrame.toString());
                // HTTP-ack to persist status in DB
                String output = collected.length() > 0 ? collected.toString()
                        : stderrBuf.toString(StandardCharsets.UTF_8);
                apiService.ackCommand(cmdId, serialNumber, exitCode == 0 ? "completed" : "failed", output);
                break;
            }
            case "screenshot": {
                File tmp = new File(getCacheDir(), "mdm_screen_" + System.currentTimeMillis() + ".png");
                try {
                    java.lang.Process p = Runtime.getRuntime().exec(
                            new String[]{"screencap", "-p", tmp.getAbsolutePath()});
                    p.waitFor();
                    byte[] bytes;
                    try (FileInputStream fis = new FileInputStream(tmp)) {
                        bytes = fis.readAllBytes();
                    }
                    String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    apiService.ackCommand(cmdId, serialNumber, "completed", b64);
                } finally {
                    tmp.delete();
                }
                break;
            }
            case "get_app_inventory": {
                apiService.ackCommand(cmdId, serialNumber, "completed", getInstalledApps().toString());
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
                long payloadOffset = payload.optLong("payload_offset", 0);
                long payloadSize = payload.optLong("payload_size", 0);
                JSONArray headersArr = payload.optJSONArray("payload_headers");
                String[] headers = new String[headersArr != null ? headersArr.length() : 0];
                if (headersArr != null) {
                    for (int j = 0; j < headersArr.length(); j++) headers[j] = headersArr.getString(j);
                }
                final String otaCmdId = cmdId;
                final String otaSerial = serialNumber;
                otaUpdateManager = new OtaUpdateManager();
                otaUpdateManager.setListener(new OtaUpdateManager.Listener() {
                    @Override public void onDownloadComplete() {
                        new Thread(() -> apiService.postOtaStatus(otaSerial, otaCmdId, "downloaded", null)).start();
                    }
                    @Override public void onInstallComplete() {
                        new Thread(() -> apiService.postOtaStatus(otaSerial, otaCmdId, "installed", null)).start();
                    }
                    @Override public void onError(String errorCode) {
                        new Thread(() -> apiService.postOtaStatus(otaSerial, otaCmdId, "error", errorCode)).start();
                    }
                });
                otaUpdateManager.startUpdate(updateUrl, payloadOffset, payloadSize, headers);
                break;
            }
            default:
                Log.w(TAG, "Unknown command type: " + cmdType);
                apiService.ackCommand(cmdId, serialNumber, "failed", "unknown type: " + cmdType);
        }
    }

    private void handleShellStart(String sessionId) {
        Log.i(TAG, "handleShellStart session=" + sessionId);
        try {
            java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"/system/bin/sh"});
            shellSessions.put(sessionId, proc.getOutputStream());
            Log.i(TAG, "Shell process started session=" + sessionId);

            // Drain stderr so the process never blocks on a full stderr pipe
            new Thread(() -> {
                try { proc.getErrorStream().transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
            }, "mdm-shell-err-" + sessionId).start();

            // Relay stdout back to server in chunks
            new Thread(() -> {
                byte[] buf = new byte[4096];
                try (InputStream is = proc.getInputStream()) {
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        Log.d(TAG, "shell_output chunk=" + n + "b session=" + sessionId);
                        try {
                            JSONObject out = new JSONObject();
                            out.put("type", "shell_output");
                            out.put("session_id", sessionId);
                            out.put("data", chunk);
                            wsClient.send(out.toString());
                        } catch (Exception e) {
                            Log.w(TAG, "shell_output send error session=" + sessionId + ": " + e.getMessage());
                        }
                    }
                    Log.i(TAG, "Shell stdout EOF session=" + sessionId);
                } catch (Exception e) {
                    Log.w(TAG, "Shell stdout relay exception session=" + sessionId + ": " + e.getMessage());
                } finally {
                    shellSessions.remove(sessionId);
                    try {
                        int exitCode = proc.waitFor();
                        Log.i(TAG, "Shell process exited code=" + exitCode + " session=" + sessionId);
                        JSONObject exit = new JSONObject();
                        exit.put("type", "shell_exit");
                        exit.put("session_id", sessionId);
                        exit.put("exit_code", exitCode);
                        wsClient.send(exit.toString());
                    } catch (Exception e) {
                        Log.w(TAG, "shell_exit send failed session=" + sessionId + ": " + e.getMessage());
                    }
                }
            }, "mdm-shell-out-" + sessionId).start();
        } catch (Exception e) {
            Log.e(TAG, "handleShellStart error session=" + sessionId + ": " + e.getMessage());
        }
    }

    private void handleShellStdin(String sessionId, String data) {
        OutputStream stdin = shellSessions.get(sessionId);
        if (stdin == null) {
            Log.w(TAG, "shell_stdin: no active session=" + sessionId);
            return;
        }
        Log.d(TAG, "shell_stdin " + data.length() + "b session=" + sessionId);
        try {
            // xterm.js sends \r for Enter; without a PTY the shell only recognises \n
            String normalized = data.replace("\r", "\n");
            stdin.write(normalized.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception e) {
            Log.w(TAG, "shell_stdin write error session=" + sessionId + ": " + e.getMessage());
            shellSessions.remove(sessionId);
        }
    }

    private void handleShellClose(String sessionId) {
        Log.i(TAG, "handleShellClose session=" + sessionId);
        OutputStream stdin = shellSessions.remove(sessionId);
        if (stdin != null) {
            try { stdin.close(); } catch (Exception ignored) {}
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

        java.lang.Process process = Runtime.getRuntime().exec(cmd);
        String content;
        try (InputStream is = process.getInputStream();
             InputStream es = process.getErrorStream()) {
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            if (!stderr.isEmpty()) Log.w(TAG, "Logcat stderr: " + stderr);
        }
        process.waitFor();
        Log.d(TAG, "Logcat result: " + content.length() + " bytes");

        apiService.postLogcat(getDeviceSerial(), requestId, content);
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

            latch.await(60, TimeUnit.SECONDS);
            unregisterReceiver(resultReceiver);
            return success.get();
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
        extra.put("storage_free_gb", getStorageFreeGb());
        extra.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000);
        extra.put("wlc_status", getWlcStatus());
        extra.put("ram_usage_mb", getRamUsageMb());
        extra.put("timezone", java.util.TimeZone.getDefault().getID());
        extra.put("battery_temp_c", extractBatteryTemperature(batteryIntent));
        payload.put("extra", extra);

        payload.put("installed_apps", getCachedInstalledApps());

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
        return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
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

    private void populateWifiInfo(JSONObject extra) throws JSONException {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            extra.put("ip_address", JSONObject.NULL);
            extra.put("wifi", JSONObject.NULL);
            return;
        }
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) {
            extra.put("ip_address", JSONObject.NULL);
            extra.put("wifi", JSONObject.NULL);
            return;
        }
        int ip4 = info.getIpAddress();
        extra.put("ip_address", String.format("%d.%d.%d.%d",
                ip4 & 0xff, (ip4 >> 8) & 0xff, (ip4 >> 16) & 0xff, (ip4 >> 24) & 0xff));
        extra.put("wifi", info.getSSID());
    }

    private JSONObject getRamUsageMb() throws JSONException {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        JSONObject ram = new JSONObject();
        ram.put("total", mi.totalMem / (1024 * 1024));
        ram.put("available", mi.availMem / (1024 * 1024));
        ram.put("used", (mi.totalMem - mi.availMem) / (1024 * 1024));
        return ram;
    }

    private int getWlcStatus() {
        final String gpioPath = "/sys/devices/platform/soc/soc:customer_gpio/gpio27";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(gpioPath))) {
            String val = reader.readLine();
            if (val == null) return -1;
            return val.trim().equals("0") ? 0 : 1;
        } catch (Exception e) {
            Log.e(TAG, "getWlcStatus error: " + e.getMessage());
            return -1;
        }
    }

    private double getStorageFreeGb() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long bytesAvailable = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        double gb = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
        return Math.round(gb * 10.0) / 10.0;
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

    private long getAdaptivePollInterval() {
        long base = apiService.getPollInterval();
        Intent battery = getBatteryIntent();
        if (battery == null) return base;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0) return base; // charging — use normal interval
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (scale > 0 && level >= 0) ? (int) ((level / (float) scale) * 100) : 100;
        if (pct <= 15) return base * 4;  // critical battery: poll 4× less often
        if (pct <= 30) return base * 2;  // low battery: poll 2× less often
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
        mainHandler.removeCallbacks(pollRunnable);
        if (wsClient != null) wsClient.stop();
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        if (packageChangeReceiver != null) {
            try { unregisterReceiver(packageChangeReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
