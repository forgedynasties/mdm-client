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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
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

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (networkAvailable && !polling) {
                performCheckin();
            }
            mainHandler.postDelayed(this, apiService.getPollInterval());
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
                    long pollMs = response.optLong("poll_interval_ms", 0);
                    if (pollMs > 0) apiService.setPollInterval(pollMs);
                    JSONObject config = response.optJSONObject("config");
                    if (config != null) {
                        KioskManager.applyAndSave(MdmService.this, dpm, adminComponent, config);
                    }
                    String serial = getDeviceSerial();
                    processLogcatRequests(response, serial);
                    processCommands(response, serial);
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

    private void processCommands(JSONObject response, String serialNumber) {
        JSONArray commands = response.optJSONArray("commands");
        if (commands == null || commands.length() == 0) return;

        for (int i = 0; i < commands.length(); i++) {
            try {
                JSONObject cmd = commands.getJSONObject(i);
                String cmdId = cmd.getString("id");
                String cmdType = cmd.optString("type", "install_apk");
                JSONObject payload = cmd.optJSONObject("payload");
                if (payload == null) payload = new JSONObject();

                Log.i(TAG, "Processing command " + cmdId + " type=" + cmdType);
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
                        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                        p.waitFor();
                        apiService.ackCommand(cmdId, serialNumber, "completed", stdout.isEmpty() ? stderr : stdout);
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
                        apiService.ackCommand(cmdId, serialNumber, "completed", "");
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        pm.reboot(null);
                        break;
                    }
                    default:
                        Log.w(TAG, "Unknown command type: " + cmdType);
                        apiService.ackCommand(cmdId, serialNumber, "failed", "unknown type: " + cmdType);
                }
            } catch (Exception e) {
                Log.e(TAG, "Command processing error: " + e.getMessage());
            }
        }
    }

    private void processLogcatRequests(JSONObject response, String serialNumber) {
        JSONArray requests = response.optJSONArray("logcat_requests");
        if (requests == null || requests.length() == 0) return;

        for (int i = 0; i < requests.length(); i++) {
            try {
                JSONObject req = requests.getJSONObject(i);
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

                apiService.postLogcat(serialNumber, requestId, content);
            } catch (Exception e) {
                Log.e(TAG, "Logcat request error: " + e.getMessage());
            }
        }
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

        // Required fields
        payload.put("serial_number", getDeviceSerial());
        payload.put("build_id", SystemPropertiesProxy.get("ro.build.id", Build.UNKNOWN));
        payload.put("battery_pct", getBatteryPct());

        // Extra fields
        JSONObject extra = new JSONObject();
        extra.put("ip_address", getWifiIpAddress());
        extra.put("wifi", getWifiSsid());
        extra.put("storage_free_gb", getStorageFreeGb());
        extra.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000);
        extra.put("wlc_status", getWlcStatus());
        extra.put("ram_usage_mb", getRamUsageMb());
        extra.put("timezone", java.util.TimeZone.getDefault().getID());
        payload.put("extra", extra);

        payload.put("installed_apps", getInstalledApps());

        return payload;
    }

    private String getDeviceSerial() {
        try {
            return Build.getSerial();
        } catch (SecurityException e) {
            return Build.UNKNOWN;
        }
    }

    private int getBatteryPct() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus == null) return -1;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return (int) ((level / (float) scale) * 100);
    }

    private String getWifiIpAddress() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) return null;
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return null;
        int ip4 = info.getIpAddress();
        return String.format("%d.%d.%d.%d",
                ip4 & 0xff, (ip4 >> 8) & 0xff, (ip4 >> 16) & 0xff, (ip4 >> 24) & 0xff);
    }

    private String getWifiSsid() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) return null;
        WifiInfo info = wm.getConnectionInfo();
        return info != null ? info.getSSID() : null;
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
        try {
            for (int i = 0; i < 5; i++) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.FileReader(gpioPath));
                String val = reader.readLine();
                reader.close();
                if (val != null && val.contains("0")) return 0;
                Thread.sleep(100);
            }
            return 1;
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
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
