package com.aioapp.otautil;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

public class MdmService extends Service {
    private static final String TAG = "MdmService";
    private static final String CHANNEL_ID = "MDM_SERVICE";
    private static final int NOTIFICATION_ID = 1001;

    private Handler mainHandler;
    private MdmApiService apiService;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean networkAvailable = false;
    private volatile boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (networkAvailable && !polling) {
                performPoll();
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
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("MDM service running"));
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!polling) {
            new Thread(() -> {
                apiService.loadRemoteConfig();
                mainHandler.post(pollRunnable);
            }).start();
        }
        return START_STICKY;
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

    private void performPoll() {
        polling = true;
        new Thread(() -> {
            try {
                JSONObject deviceData = collectDeviceData();
                apiService.reportDeviceData(deviceData);
            } catch (Exception e) {
                Log.e(TAG, "Poll failed: " + e.getMessage());
            } finally {
                polling = false;
            }
        }).start();
    }

    private JSONObject collectDeviceData() throws JSONException {
        JSONObject data = new JSONObject();

        // Device identity
        data.put("serial", getDeviceSerial());
        data.put("build_id", SystemPropertiesProxy.get("ro.build.id", Build.ID));
        data.put("android_version", Build.VERSION.RELEASE);
        data.put("sdk_int", Build.VERSION.SDK_INT);
        data.put("manufacturer", Build.MANUFACTURER);
        data.put("model", Build.MODEL);

        // Battery
        data.put("battery", getBatteryData());

        // WiFi
        data.put("wifi", getWifiData());

        // System
        data.put("uptime_ms", SystemClock.elapsedRealtime());
        data.put("timestamp", System.currentTimeMillis());

        return data;
    }

    private String getDeviceSerial() {
        try {
            return Build.getSerial();
        } catch (SecurityException e) {
            return Build.UNKNOWN;
        }
    }

    private JSONObject getBatteryData() throws JSONException {
        JSONObject battery = new JSONObject();
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            battery.put("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            battery.put("charging", bm.isCharging());
            battery.put("status", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS));
            battery.put("current_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        }
        return battery;
    }

    private JSONObject getWifiData() throws JSONException {
        JSONObject wifi = new JSONObject();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wm.isWifiEnabled()) {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                wifi.put("ssid", info.getSSID());
                wifi.put("bssid", info.getBSSID());
                wifi.put("rssi", info.getRssi());
                wifi.put("link_speed_mbps", info.getLinkSpeed());
                int ip4 = info.getIpAddress();
                wifi.put("ip", String.format("%d.%d.%d.%d",
                        ip4 & 0xff, (ip4 >> 8) & 0xff, (ip4 >> 16) & 0xff, (ip4 >> 24) & 0xff));
            }
        }
        return wifi;
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
