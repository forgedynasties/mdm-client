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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MdmService extends Service {
    private static final String TAG = "MdmService";
    private static final String CHANNEL_ID = "MDM_SERVICE";
    private static final int NOTIFICATION_ID = 1001;
    // Separate notification for app download/install progress, so it doesn't disturb the
    // persistent foreground-service notification (1001).
    private static final int INSTALL_NOTIFICATION_ID = 1002;

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
    // Live logcat streams keyed by request_id; the Process is killed to stop a stream.
    private final java.util.Map<String, java.lang.Process> logcatStreams = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_LOGCAT_STREAMS = 3;
    private android.os.PowerManager.WakeLock remoteWakeLock;  // keeps the screen on during a remote session
    private static final long REMOTE_WAKE_TIMEOUT_MS = 30 * 60 * 1000L;  // safety cap so a dropped session can't pin the screen
    private volatile MdmWebSocketClient wsClient;      // published from startWebSocket, read on many threads
    private volatile JSONArray cachedInstalledApps = null;  // invalidated from a package-change receiver thread
    private BroadcastReceiver packageChangeReceiver;
    private String lastNotificationText = "";
    // Control-plane pool: short tasks (check-ins, telemetry, acks, config, input, pings).
    private ExecutorService executor;
    // Heavy pool: long-running ops (commands, logcat, OTA download, screen capture) so they
    // never occupy the control-plane pool and starve/drop a telemetry or ack task.
    private ExecutorService heavyExecutor;
    private String deviceSerial;  // cached; Build.getSerial() is a binder call and never changes
    // Cached reflected SurfaceControl.screenshot — looked up once, not per capture frame.
    private volatile java.lang.reflect.Method screenshotMethod;
    private volatile boolean screenshotMethodResolved;

    // Battery: registered once in onCreate, updated via sticky broadcast
    private volatile Intent cachedBatteryIntent = null;
    private BroadcastReceiver batteryReceiver;
    // Edge-detect charging state so we only push telemetry on plug/unplug
    // transitions (ACTION_BATTERY_CHANGED fires on every level change too).
    private volatile int lastChargingState = -1; // -1 unknown, 0 not charging, 1 charging

    // Wi-Fi disconnect tracking: edge-detect connected→disconnected and keep the last
    // hour of event timestamps (elapsedRealtime millis) so getWifiDisconnects1h() is O(n).
    private BroadcastReceiver wifiReceiver;
    private volatile boolean wifiWasConnected = false;
    private final java.util.ArrayDeque<Long> wifiDisconnects = new java.util.ArrayDeque<>();

    // Per-field system query caches
    private double cachedStorageFreeGb = 0;
    private long storageLastMs = 0;
    private static final long STORAGE_CACHE_MS = 120_000;

    private JSONObject cachedWifiExtra = null;
    private long wifiLastMs = 0;
    private static final long WIFI_CACHE_MS = 300_000;

    private JSONObject cachedRam = null;
    private long ramLastMs = 0;
    private static final long RAM_CACHE_MS = 30_000;

    private int cachedWlcStatus = -1;
    private long wlcLastMs = 0;
    private static final long WLC_CACHE_MS = 120_000;
    private final Object wlcLock = new Object();          // guards cachedWlcStatus / wlcLastMs
    // The Qi pad's guest-presence state is now published by the OS as a sticky, protected
    // broadcast (WlcService in system_server), exactly like charging rides ACTION_BATTERY_CHANGED.
    // We subscribe instead of polling the GPIO on a timer: registerReceiver() hands back the
    // current state synchronously, and every subsequent transition pushes telemetry immediately.
    private BroadcastReceiver wlcReceiver;
    private static final String ACTION_WLC_GUEST_STATE_CHANGED =
            "com.aioapp.action.WLC_GUEST_STATE_CHANGED";
    private static final String EXTRA_WLC_STATE = "state";
    private volatile int lastWatchedWlc = Integer.MIN_VALUE;  // last value the receiver observed

    // App list delta
    private String lastAppsHash = null;
    private volatile boolean sendFullAppList = false;

    // Telemetry delta baseline: what the server was last told. A WS frame carries only the
    // gated fields that changed (others are merged server-side from this baseline); a send is
    // skipped entirely when nothing changed. forceKeyframe makes the next send a full snapshot
    // (on start, WS (re)connect, and after a failed send) to (re)establish the baseline.
    private final Object baselineLock = new Object();
    private JSONObject lastSentExtra = null;          // guarded by baselineLock
    private int lastSentBattery = Integer.MIN_VALUE;  // guarded by baselineLock
    private volatile boolean forceKeyframe = true;

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
        // CallerRunsPolicy applies backpressure instead of silently discarding a queued
        // ack/telemetry task if the pool ever saturates. Long ops live on heavyExecutor.
        executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32), new ThreadPoolExecutor.CallerRunsPolicy());
        // Bounded (was an unbounded cached pool). A flood of long commands — installs each
        // block up to 180s, shells up to 30s — could otherwise spawn threads without limit and
        // OOM / exhaust fds on a system-UID process. 16 threads + a 32-deep queue is far more
        // than any real command load; genuine overflow is logged and the task dropped (the
        // server re-sends unacked commands).
        heavyExecutor = new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "mdm-heavy");
                    t.setDaemon(true);
                    return t;
                },
                (r, ex) -> Log.w(TAG, "heavy task rejected — pool saturated (possible command flood)"));
        apiService = new MdmApiService();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MdmAdminReceiver.class);
        // startForeground MUST be called within ~5s of startForegroundService or the OS crashes
        // the process (ForegroundServiceDidNotStartInTimeAllowedException). ensureDeviceOwner()
        // makes synchronous DPM binder calls that can stall past 5s on a cold fleet boot, so
        // promote to foreground FIRST, then do provisioning.
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("MDM service running"));
        ensureDeviceOwner();
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                cachedBatteryIntent = intent;
                int charging = extractCharging(intent) ? 1 : 0;
                if (lastChargingState != -1 && charging != lastChargingState) {
                    Log.i(TAG, "Charging state changed: " + lastChargingState + " -> " + charging
                            + " — pushing immediate telemetry");
                    // A power transition can change what the pad reports; drop the wlc cache
                    // so the imminent push carries a fresh reading rather than a stale one.
                    synchronized (wlcLock) { wlcLastMs = 0; }
                    sendTelemetryOverWs();
                }
                lastChargingState = charging;
            }
        };
        cachedBatteryIntent = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        // Wi-Fi stability: count connected→disconnected transitions so the server can flag
        // "≥N disconnects in an hour". SUPPLICANT/NETWORK state changes fire often, so we
        // only record an edge from a previously-connected state to a disconnected one.
        wifiReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                NetworkInfo ni = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                boolean connected = ni != null && ni.isConnected();
                if (wifiWasConnected && !connected) {
                    recordWifiDisconnect();
                }
                wifiWasConnected = connected;
            }
        };
        registerReceiver(wifiReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

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

        // Subscribe to the Qi pad's guest-presence broadcast. It's sticky, so registerReceiver()
        // returns the current state as of now — adopt that seed WITHOUT pushing telemetry, so the
        // first real transition (not service start) is what triggers a push.
        wlcReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                handleWlcIntent(intent);
            }
        };
        Intent stickyWlc = registerReceiver(wlcReceiver,
                new IntentFilter(ACTION_WLC_GUEST_STATE_CHANGED));
        if (stickyWlc != null) {
            int w = stickyWlc.getIntExtra(EXTRA_WLC_STATE, -1);
            synchronized (wlcLock) {
                cachedWlcStatus = w;
                wlcLastMs = SystemClock.elapsedRealtime();
            }
            lastWatchedWlc = w;
        }
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

    /** True when the device is on external power (wired or wireless pad). */
    private boolean isOnExternalPower() {
        Intent b = getBatteryIntent();
        return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private void scheduleNextPoll() {
        long intervalMs = getAdaptivePollInterval() * apiService.getBackoffMultiplier();

        // If the server asked us to slow down (429/503 Retry-After), never poll sooner than that.
        intervalMs = Math.max(intervalMs, apiService.consumeRetryAfterMs());

        boolean powered = isOnExternalPower();
        boolean wsHealthy = wsClient != null && wsClient.isConnected()
                && wsClient.getSecsSinceLastData() <= STALE_WS_THRESHOLD_SECS;

        // On battery with a healthy WebSocket, the WS keepalive thread (which uses no alarm
        // of its own) already carries presence + pushed commands and self-reconnects, so the
        // wakeup alarm only needs to fire at the HTTP safety-net cadence — this drops the
        // ~9/10 Doze wakeups that previously only re-sent a WS telemetry frame.
        if (!powered && wsHealthy) {
            intervalMs = Math.max(intervalMs, HTTP_SAFETY_NET_MS);
        }

        // Add up to +20% jitter so ~900 devices that boot / restore power / reconnect together
        // don't fire their poll alarm in lockstep and stampede the server every cycle. Only
        // lengthens the interval (never below the intended cadence).
        long jitter = ThreadLocalRandom.current().nextLong((intervalMs / 5) + 1);
        long triggerAt = SystemClock.elapsedRealtime() + intervalMs + jitter;
        if (powered) {
            // Powered (kiosk on a charger): keep exact, responsive wakeups.
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pollIntent);
        } else {
            // On battery: inexact so the OS can batch this wakeup with other Doze maintenance.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pollIntent);
        }
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
            // Liveness is gauged by data *received* (server keepalive pings every ~45s), not by
            // how often we send — with change-gated telemetry a healthy link can be quiet.
            long staleSecs = wsClient.getSecsSinceLastData();
            if (staleSecs > STALE_WS_THRESHOLD_SECS) {
                Log.w(TAG, "WS stale (no data " + staleSecs + "s), forcing reconnect");
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
                        // Keyframe landed — reset the delta baseline to this full snapshot.
                        rememberSent(payload.getJSONObject("extra"), payload.getInt("battery_pct"));
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
        ackCommand(cmdId, serial, status, output, null);
    }

    /** pkg (nullable) is the package an install produced — reported on 'installed' so
     *  the server can reconcile a stuck install against the device's package list. */
    private void ackCommand(String cmdId, String serial, String status, String output, String pkg) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "command_ack");
                m.put("command_id", cmdId);
                m.put("serial_number", serial);
                m.put("status", status);
                if (!output.isEmpty()) m.put("output", output);
                if (pkg != null && !pkg.isEmpty()) m.put("package", pkg);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS ack failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.ackCommand(cmdId, serial, status, output, pkg));
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

    /**
     * Runs a continuous `logcat` for a live-tail session and streams batched chunks
     * over the WebSocket as {type:logcat_stream, request_id, chunk} frames, ending
     * with a {type:logcat_stream_end} frame. The process is killed when the matching
     * stop_logcat_stream arrives (it removes the entry from logcatStreams) or the WS
     * drops. Filters: buffer (main/system/crash/radio/events/all), level (V..E),
     * tag (exact), grep (logcat -e regex), tail (initial backlog lines).
     */
    private void runLogcatStream(String reqId, JSONObject opts) throws Exception {
        if (reqId == null || reqId.isEmpty()) return;
        if (logcatStreams.size() >= MAX_LOGCAT_STREAMS) {
            sendLogcatStreamEnd(reqId, "error", "too many active logcat streams");
            return;
        }

        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("logcat");
        args.add("-v"); args.add("threadtime");
        String buffer = opts.optString("buffer", "").trim();
        if (!buffer.isEmpty() && !buffer.equals("default") && !buffer.equals("main")) {
            args.add("-b"); args.add(buffer);  // "all" and named buffers; "main" is the default
        }
        int tail = opts.optInt("tail", 200);
        if (tail < 0) tail = 0;
        if (tail > 5000) tail = 5000;
        args.add("-T"); args.add(String.valueOf(tail));
        String grep = opts.optString("grep", "").trim();
        if (!grep.isEmpty()) { args.add("-e"); args.add(grep); }

        String level = opts.optString("level", "V").trim();
        if (level.isEmpty()) level = "V";
        String tag = opts.optString("tag", "").trim();
        if (!tag.isEmpty()) { args.add(tag + ":" + level); args.add("*:S"); }
        else { args.add("*:" + level); }

        java.lang.Process p;
        try {
            p = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (Exception e) {
            sendLogcatStreamEnd(reqId, "error", "failed to start logcat: " + e.getMessage());
            return;
        }
        logcatStreams.put(reqId, p);
        Log.i(TAG, "logcat stream " + reqId + " started: " + args);

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 16384)) {
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            long lastFlush = System.currentTimeMillis();
            int n;
            // Batch output and flush every ~250ms (or when the buffer grows) so a chatty
            // log doesn't flood the WebSocket with a frame per line.
            while (logcatStreams.containsKey(reqId)
                    && wsClient != null && wsClient.isConnected()
                    && (n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
                long now = System.currentTimeMillis();
                if (sb.length() >= 12000 || now - lastFlush >= 250) {
                    sendLogcatChunk(reqId, sb.toString());
                    sb.setLength(0);
                    lastFlush = now;
                }
            }
            if (sb.length() > 0) sendLogcatChunk(reqId, sb.toString());
        } finally {
            logcatStreams.remove(reqId);
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            sendLogcatStreamEnd(reqId, "stopped", "");
            Log.i(TAG, "logcat stream " + reqId + " ended");
        }
    }

    private void sendLogcatChunk(String reqId, String chunk) {
        if (wsClient == null || !wsClient.isConnected() || chunk == null || chunk.isEmpty()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "logcat_stream");
            m.put("request_id", reqId);
            m.put("chunk", chunk);
            wsClient.send(m.toString());
        } catch (Exception e) {
            Log.e(TAG, "logcat chunk send failed: " + e.getMessage());
        }
    }

    private void sendLogcatStreamEnd(String reqId, String reason, String error) {
        if (wsClient == null || !wsClient.isConnected()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "logcat_stream_end");
            m.put("request_id", reqId);
            m.put("reason", reason);
            if (error != null && !error.isEmpty()) m.put("error", error);
            wsClient.send(m.toString());
        } catch (Exception ignored) {}
    }

    /** Kills every active logcat stream — called when the device WS drops or the service stops. */
    private void stopAllLogcatStreams() {
        for (java.util.Map.Entry<String, java.lang.Process> e : logcatStreams.entrySet()) {
            try { e.getValue().destroyForcibly(); } catch (Exception ignored) {}
        }
        logcatStreams.clear();
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

    /** Relay interim install progress ('downloading' with a percent, or 'installing')
     *  to the server so the dashboard shows it live. WS-first, HTTP fallback; percent
     *  < 0 omits the number. Non-terminal — the terminal ack still follows. */
    private void reportInstallProgress(String cmdId, String serial, String status, int percent) {
        if (cmdId == null || cmdId.isEmpty()) return;
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "command_ack");
                m.put("command_id", cmdId);
                m.put("status", status);
                if (percent >= 0) m.put("progress", percent);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS install progress failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.reportCommandProgress(cmdId, serial, status, percent));
    }

    /** Sends a real-time OTA progress frame over the WebSocket (best-effort). */
    private void sendOtaProgressFrame(String cmdId, String phase, int percent) {
        if (cmdId == null || wsClient == null) return;
        try {
            JSONObject frame = new JSONObject();
            frame.put("type", "ota_progress");
            frame.put("command_id", cmdId);
            frame.put("phase", phase);
            frame.put("percent", percent);
            wsClient.send(frame.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to send OTA progress frame: " + e.getMessage());
        }
    }

    /** OTA listener that reports against the current otaCommandId, so a duplicate command
     *  can re-point status to the new id without restarting the in-flight update. */
    private OtaUpdateManager.Listener buildOtaListener() {
        return new OtaUpdateManager.Listener() {
            @Override public void onDownloadProgress(String phase, int percent) {
                updateNotificationIfNeeded("System update: " + phase + " " + percent + "%");
                sendOtaProgressFrame(otaCommandId, phase, percent);
            }
            @Override public void onDownloadComplete() {
                updateNotificationIfNeeded("System update: download complete, installing…");
                reportOtaStatus(otaCommandId, "downloaded", null);
            }
            @Override public void onInstallComplete() {
                updateNotificationIfNeeded("System update installed — awaiting reboot");
                reportOtaStatus(otaCommandId, "installed", null);
                otaCommandId = null;
            }
            @Override public void onError(String errorCode) {
                updateNotificationIfNeeded("System update failed (" + errorCode + ")");
                reportOtaStatus(otaCommandId, "error", errorCode);
                otaCommandId = null;
            }
        };
    }

    private void sendTelemetryOverWs() {
        if (wsClient == null || !wsClient.isConnected()) return;
        executor.submit(() -> {
            try {
                JSONObject curExtra = buildExtra();
                int curBattery = extractBatteryPct(getBatteryIntent());
                boolean keyframe = forceKeyframe;
                if (!keyframe) {
                    synchronized (baselineLock) {
                        if (lastSentBattery == curBattery && !gatedChanged(curExtra) && !tempTrigger(curExtra)) {
                            return; // nothing changed — skip the send entirely
                        }
                    }
                }
                JSONObject payload = keyframe
                        ? buildCheckinPayload()
                        : buildDeltaPayload(curExtra, curBattery);
                payload.put("type", "telemetry");
                wsClient.send(payload.toString());
                rememberSent(curExtra, curBattery);
            } catch (Exception e) {
                forceKeyframe = true; // next send re-establishes the full baseline
                Log.e(TAG, "WS telemetry failed, falling back to HTTP: " + e.getMessage());
                try {
                    JSONObject payload = buildCheckinPayload();
                    JSONObject response = apiService.checkin(payload);
                    if (response != null) {
                        rememberSent(payload.getJSONObject("extra"), payload.getInt("battery_pct"));
                        JSONObject config = response.optJSONObject("config");
                        if (config != null) applyConfig(config);
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "HTTP telemetry fallback error: " + e2.getMessage());
                }
            }
        });
    }

    private void applyConfig(JSONObject config) {
        // Apply the check-in interval first and independently: a failure applying
        // kiosk policy (DPM/lock-task can throw) must never stop the device from
        // adopting the configured poll interval, or it stays on the 30s default.
        long secs = config.optLong("checkin_interval_seconds", 0);
        if (secs >= 10) {
            long ms = secs * 1000L;
            if (apiService.getPollInterval() != ms) {
                apiService.setPollInterval(ms);
                Log.i(TAG, "Applied checkin interval: " + secs + "s");
                scheduleNextPoll(); // re-arm the alarm immediately at the new cadence
            }
        }
        try {
            KioskManager.applyAndSave(MdmService.this, dpm, adminComponent, config);
        } catch (Exception e) {
            Log.e(TAG, "kiosk applyAndSave error: " + e.getMessage());
        }
    }

    private synchronized void startWebSocket() {
        if (wsClient != null) return;
        String serial = getDeviceSerial();
        wsClient = new MdmWebSocketClient(apiService.getApiBaseUrl(), serial, apiService.getApiKey());
        wsClient.setListener(this::handleWsMessage);
        // On every (re)connect the server has no baseline for us — send a full keyframe.
        wsClient.setConnectedCallback(() -> { forceKeyframe = true; sendTelemetryOverWs(); });
        wsClient.start();
        Log.i(TAG, "WebSocket client started");
    }

    private void handleWsMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "command":
                heavyExecutor.submit(() -> {
                    try { processWsCommand(msg); } catch (Exception e) {
                        Log.e(TAG, "WS command error: " + e.getMessage());
                    }
                });
                break;
            case "logcat_request":
                heavyExecutor.submit(() -> {
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
                heavyExecutor.submit(() -> {
                    // Clamp server-supplied capture params so an out-of-range value can't peg CPU
                    // or flood the WS (a bad/hostile server shouldn't be able to melt the device).
                    int quality = Math.max(1, Math.min(100, msg.optInt("quality", 60)));
                    double scale = Math.max(0.1, Math.min(1.0, msg.optDouble("scale", 0.5)));
                    int maxFps = Math.max(1, Math.min(30, msg.optInt("max_fps", 10)));
                    if (!isCapturing) {
                        isCapturing = true;
                        acquireRemoteWakeLock();
                        runCaptureLoop(quality, scale, maxFps);
                    }
                });
                break;
            case "stop_capture":
                isCapturing = false;
                releaseRemoteWakeLock();
                break;
            case "start_logcat_stream": {
                final JSONObject lcOpts = msg;
                final String lcReq = msg.optString("request_id", "");
                heavyExecutor.submit(() -> {
                    try {
                        runLogcatStream(lcReq, lcOpts);
                    } catch (Exception e) {
                        Log.e(TAG, "logcat stream error: " + e.getMessage());
                        sendLogcatStreamEnd(lcReq, "error", e.getMessage());
                    }
                });
                break;
            }
            case "stop_logcat_stream": {
                java.lang.Process lp = logcatStreams.remove(msg.optString("request_id", ""));
                if (lp != null) lp.destroyForcibly();
                break;
            }
            case "wake_screen":
                wakeScreen(null);
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
                String[] pkgHolder = new String[1];
                // apk_size / apk_etag are populated server-side (best-effort HEAD of the
                // APK URL) so the download can verify completeness and resume safely.
                long apkSize = payload.optLong("apk_size", -1);
                String apkEtag = payload.optString("apk_etag", null);
                String err = installApk(cmd.getString("apk_url"), cmdId, serialNumber, pkgHolder,
                        apkSize, apkEtag);
                ackCommand(cmdId, serialNumber, err.isEmpty() ? "installed" : "failed", err, pkgHolder[0]);
                break;
            }
            case "uninstall": {
                String pkg = payload.optString("package", "");
                if (pkg.isEmpty()) {
                    ackCommand(cmdId, serialNumber, "failed", "missing package");
                    break;
                }
                String err = uninstallPackage(pkg);
                ackCommand(cmdId, serialNumber, err == null ? "completed" : "failed",
                        err == null ? ("uninstalled " + pkg) : err);
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
                // Stream stdout chunks via WebSocket so the browser sees them immediately.
                // `collected` only backs the HTTP ack, so cap it (the WS stream is unbounded).
                final int MAX_COLLECTED = 1024 * 1024;
                StringBuilder collected = new StringBuilder();
                try (InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        if (collected.length() < MAX_COLLECTED) collected.append(chunk);
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
                final String updateUrl = payload.optString("update_url", "");
                final String otaCmdId = cmdId;
                // Route subsequent OTA status/progress to the latest command id.
                otaCommandId = otaCmdId;
                if (otaUpdateManager == null) {
                    otaUpdateManager = new OtaUpdateManager(this, heavyExecutor);
                    otaUpdateManager.setListener(buildOtaListener());
                }
                // Idempotent delivery: a duplicate "ota" command (e.g. a redeploy) must NOT
                // restart an update that's already applied or in flight — restarting aborts
                // update_engine and re-downloads from 0%. Only start fresh for a new package.
                if (otaUpdateManager.isUpdatePendingReboot()) {
                    // Already applied; the device just needs to reboot. Leave update_engine alone.
                    Log.i(TAG, "OTA already applied, pending reboot — acking cmd " + otaCmdId);
                    reportOtaStatus(otaCmdId, "installed", null);
                    break;
                }
                if (otaUpdateManager.isActive() && updateUrl.equals(otaUpdateManager.getCurrentUrl())) {
                    // Same package still downloading/installing: keep going, just surface the
                    // current progress against the new command id.
                    Log.i(TAG, "OTA already in progress for same URL — not restarting (cmd " + otaCmdId + ")");
                    sendOtaProgressFrame(otaCmdId, otaUpdateManager.getCurrentPhase(),
                            otaUpdateManager.getCurrentPercent());
                    break;
                }
                // New or different package: cancel anything stale and start fresh.
                otaUpdateManager.cancel();
                otaUpdateManager.startUpdate(updateUrl);
                break;
            }
            case "update_splash": {
                // Replace the boot logo. The image is staged + flashed by an
                // init broker (the app can't write the block device); we only
                // transport the file and trigger. Runs on the worker thread.
                String splashUrl = payload.optString("url", "");
                if (splashUrl.isEmpty()) {
                    ackCommand(cmdId, serialNumber, "failed", "missing url");
                    break;
                }
                long partitionSize = payload.optLong("partition_size", 0);
                String status = downloadAndUpdateSplash(splashUrl, partitionSize);
                boolean ok = "ok".equals(status);
                ackCommand(cmdId, serialNumber, ok ? "completed" : "failed", status);
                break;
            }
            case "start_capture": {
                // Clamp server-supplied capture params (see the WS start_capture handler).
                int quality = Math.max(1, Math.min(100, payload.optInt("quality", 60)));
                double scale = Math.max(0.1, Math.min(1.0, payload.optDouble("scale", 0.5)));
                int maxFps = Math.max(1, Math.min(30, payload.optInt("max_fps", 10)));
                if (!isCapturing) {
                    isCapturing = true;
                    acquireRemoteWakeLock();
                    heavyExecutor.submit(() -> runCaptureLoop(quality, scale, maxFps));
                }
                ackCommand(cmdId, serialNumber, "completed", "");
                break;
            }
            case "stop_capture": {
                isCapturing = false;
                releaseRemoteWakeLock();
                ackCommand(cmdId, serialNumber, "completed", "");
                break;
            }
            default:
                Log.w(TAG, "Unknown command type: " + cmdType);
                ackCommand(cmdId, serialNumber, "failed", "unknown type: " + cmdType);
        }
    }


    private void runCaptureLoop(int quality, double scale, int maxFps) {
        // getDisplay() throws/returns null on a Service context on some Android
        // versions; fall back to the DisplayManager's default display.
        android.view.Display display = null;
        try {
            display = getDisplay();
        } catch (Throwable t) {
            Log.w(TAG, "getDisplay() failed on service context: " + t);
        }
        if (display == null) {
            try {
                android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            } catch (Throwable t) {
                Log.w(TAG, "DisplayManager fallback failed: " + t);
            }
        }
        if (display == null) {
            Log.e(TAG, "Cannot get display for capture — remote control will show no frames");
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

        long sent = 0, nullFrames = 0, lastLog = System.currentTimeMillis();
        while (isCapturing && wsClient != null && wsClient.isConnected()) {
            long start = System.currentTimeMillis();
            try {
                android.graphics.Bitmap screen = captureScreen(scaledW, scaledH, rotation);
                if (screen == null) {
                    nullFrames++;
                    if (start - lastLog >= 5000) {
                        Log.w(TAG, "Capture: 0 frames sent, " + nullFrames + " null captures in last 5s — screen capture is failing");
                        nullFrames = 0; lastLog = start;
                    }
                    Thread.sleep(targetMs);
                    continue;
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(32768);
                screen.compress(android.graphics.Bitmap.CompressFormat.WEBP, quality, bos);
                screen.recycle();

                byte[] frame = bos.toByteArray();
                wsClient.sendBinary(frame);
                sent++;
                if (start - lastLog >= 5000) {
                    Log.i(TAG, "Capture: " + sent + " frames sent in last 5s (" + frame.length + "B last)");
                    sent = 0; nullFrames = 0; lastLog = start;
                }

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
        releaseRemoteWakeLock();
        Log.i(TAG, "Capture loop stopped");
    }

    /**
     * Turns the display on and holds it bright for the duration of a remote-control
     * session. Called at session start so the operator never connects to a dark or
     * sleeping screen. Safe to call repeatedly — re-arms the safety timeout.
     */
    private void acquireRemoteWakeLock() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeScreen(pm);
            if (remoteWakeLock == null) {
                remoteWakeLock = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "MDM:RemoteControl");
                remoteWakeLock.setReferenceCounted(false);
            }
            remoteWakeLock.acquire(REMOTE_WAKE_TIMEOUT_MS);
            Log.i(TAG, "Remote wake lock acquired (" + REMOTE_WAKE_TIMEOUT_MS + "ms)");
        } catch (Exception e) {
            Log.e(TAG, "acquireRemoteWakeLock failed: " + e.getMessage());
        }
    }

    private void releaseRemoteWakeLock() {
        try {
            if (remoteWakeLock != null && remoteWakeLock.isHeld()) {
                remoteWakeLock.release();
                Log.i(TAG, "Remote wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "releaseRemoteWakeLock failed: " + e.getMessage());
        }
    }

    /** Forces the display on (used at session start and on operator right-click). */
    private void wakeScreen(android.os.PowerManager pm) {
        try {
            if (pm == null) {
                pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            }
            java.lang.reflect.Method wakeUp = android.os.PowerManager.class.getMethod(
                    "wakeUp", long.class);
            wakeUp.invoke(pm, android.os.SystemClock.uptimeMillis());
        } catch (Exception e) {
            // wakeUp() is a hidden API; the ACQUIRE_CAUSES_WAKEUP wake-lock flag is the fallback.
            Log.w(TAG, "PowerManager.wakeUp() unavailable: " + e.getMessage());
        }
    }

    private android.graphics.Bitmap captureScreen(int w, int h, int rotation) {
        java.lang.reflect.Method method = screenshotMethod;
        if (method == null && !screenshotMethodResolved) {
            synchronized (this) {
                if (!screenshotMethodResolved) {
                    try {
                        screenshotMethod = android.view.SurfaceControl.class.getMethod(
                                "screenshot", android.graphics.Rect.class, int.class, int.class, int.class);
                    } catch (Exception e) {
                        Log.w(TAG, "SurfaceControl.screenshot() unavailable: " + e.getMessage());
                    }
                    screenshotMethodResolved = true;
                }
            }
            method = screenshotMethod;
        }
        if (method == null) return captureScreenFallback(w, h);
        try {
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

    /**
     * Downloads a splash image and hands it to the broker via {@link SplashUpdater}.
     * Streams straight from the network into staging — a wrong file fails before
     * the whole file is pulled. Returns the broker's final status string (or an
     * {@code error_*} reason on transport failure).
     */
    private String downloadAndUpdateSplash(String imageUrl, long partitionSize) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Splash download failed: HTTP " + conn.getResponseCode());
                return "error_download";
            }
            try (InputStream in = conn.getInputStream()) {
                return SplashUpdater.updateSplash(in, partitionSize);
            }
        } catch (Exception e) {
            Log.e(TAG, "Splash update error: " + e.getMessage());
            return "error_download";
        }
    }

    /**
     * Downloads and installs an APK. Returns "" on success, otherwise a short error
     * reason that is sent back to the server in the command ack so install failures
     * are diagnosable from the dashboard (previously this returned a bare boolean and
     * acked with an empty output, so a failed install gave no clue why).
     */
    private String installApk(String apkUrl, String cmdId, String serial, String[] outPkg,
                              long expectedSize, String etag) {
        // title[0] starts generic and is upgraded to the app's display label once the APK
        // is parsed, so the on-device notification (and its final result) names the app.
        String[] title = { "App install" };
        String err = installApkInner(apkUrl, cmdId, serial, outPkg, expectedSize, etag, title);
        // Final result notification (dismissible; not ongoing) — reuses the same id so it
        // replaces the progress notification in place.
        showInstallNotification(title[0], err.isEmpty() ? "Installed" : "Install failed: " + err,
                err.isEmpty() ? 100 : 0, false);
        return err;
    }

    private String installApkInner(String apkUrl, String cmdId, String serial, String[] outPkg,
                                   long expectedSize, String etag, String[] title) {
        File apkFile = new File(getCacheDir(), "mdm_install_" + System.currentTimeMillis() + ".apk");
        try {
            // Download with Range resume + retry. A stale keep-alive socket ("unexpected
            // end of stream") or a transient stall on a large APK ("SocketTimeoutException")
            // no longer fails the whole install — the next attempt resumes from disk and the
            // final size is verified before we hand the file to PackageInstaller.
            reportInstallProgress(cmdId, serial, "downloading", expectedSize > 0 ? 0 : -1);
            showInstallNotification(title[0], "Downloading…", expectedSize > 0 ? 0 : -1, true);
            final int[] lastPct = { -1 };
            HttpDownloader.downloadWithResume(apkUrl, apkFile, expectedSize, etag,
                    30_000, 60_000, 5,
                    (downloaded, total) -> {
                        // Relay percent while we know the size, throttled to whole-5% steps
                        // so we don't spam the server on a fast link.
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            if (pct >= lastPct[0] + 5 && pct < 100) {
                                lastPct[0] = pct;
                                reportInstallProgress(cmdId, serial, "downloading", pct);
                                showInstallNotification(title[0], "Downloading " + pct + "%", pct, true);
                            }
                        }
                    },
                    null);
            long written = apkFile.length();
            Log.i(TAG, "APK downloaded to " + apkFile.getAbsolutePath() + " (" + written + " bytes)");
            if (written == 0) return "download failed: empty file";
            // Download done → now installing.
            reportInstallProgress(cmdId, serial, "installing", -1);

            // Extract package name (+ display label) from the APK for fallback verification,
            // a clearer error, and the user-facing notification title.
            String apkPackageName = null;
            android.content.pm.PackageInfo apkInfo = getPackageManager().getPackageArchiveInfo(
                    apkFile.getAbsolutePath(), 0);
            if (apkInfo != null) {
                apkPackageName = apkInfo.packageName;
                if (outPkg != null) outPkg[0] = apkPackageName; // report to server on ack
                // Load the human-readable app label (needs sourceDir set for an uninstalled APK).
                if (apkInfo.applicationInfo != null) {
                    apkInfo.applicationInfo.sourceDir = apkFile.getAbsolutePath();
                    apkInfo.applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
                    CharSequence lbl = apkInfo.applicationInfo.loadLabel(getPackageManager());
                    if (lbl != null && lbl.length() > 0) title[0] = lbl.toString();
                }
                Log.i(TAG, "APK package: " + apkPackageName);
            } else {
                Log.e(TAG, "APK could not be parsed (corrupt or not an APK): " + apkUrl);
            }
            showInstallNotification(title[0], "Installing…", -1, true);

            // Install via PackageInstaller API
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicReference<String> failReason = new AtomicReference<>("");

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
                        failReason.set("needs user action — INSTALL_PACKAGES not granted / not a privileged app");
                    } else {
                        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        int legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", -999);
                        Log.e(TAG, "PackageInstaller failed status=" + status + " legacy=" + legacyStatus + " msg=" + msg);
                        failReason.set("install failed: status=" + status + " legacy=" + legacyStatus
                                + (msg != null ? " " + msg : ""));
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

            if (success.get()) return "";

            // Fallback: the package may be present even if the callback reported failure
            // or never fired (USER_ACTION_NOT_REQUIRED installs sometimes don't broadcast).
            if (apkPackageName != null) {
                try {
                    getPackageManager().getPackageInfo(apkPackageName, 0);
                    Log.i(TAG, "APK package " + apkPackageName + " is installed (verified via PackageManager"
                            + (completed ? " after callback reported failure)" : " after timeout)"));
                    return "";
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "APK package " + apkPackageName + " not found after install"
                            + (completed ? " (callback reported failure)" : " (timed out after 180s)"));
                }
            }
            String reason = failReason.get();
            if (!reason.isEmpty()) return reason;
            if (!completed) return "install timed out after 180s (no result; package not present)";
            if (apkPackageName == null) return "could not parse APK (corrupt download?)";
            return "install failed: package not present after commit";
        } catch (Exception e) {
            Log.e(TAG, "installApk error: " + e.getMessage());
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            apkFile.delete();
        }
    }

    /**
     * Silently uninstalls a package via the PackageInstaller API. Running this from the app
     * (calling package = com.aioapp.mdm) avoids the AppOps NullPointerException that "pm
     * uninstall" hits when invoked from the system UID with no calling package. Requires
     * the DELETE_PACKAGES permission (granted to this platform-signed privileged app).
     * Returns null on success, or an error message on failure.
     */
    private String uninstallPackage(String pkg) {
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            final StringBuilder errMsg = new StringBuilder();

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        success.set(true);
                    } else {
                        String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        Log.e(TAG, "Uninstall failed status=" + status + " msg=" + m);
                        errMsg.append(m != null ? m : ("status " + status));
                    }
                    latch.countDown();
                }
            };

            String action = "com.aioapp.mdm.UNINSTALL_RESULT_" + System.currentTimeMillis();
            registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            try {
                PendingIntent pi = PendingIntent.getBroadcast(this, action.hashCode(),
                        new Intent(action).setPackage(getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                installer.uninstall(pkg, pi.getIntentSender());
                latch.await(60, TimeUnit.SECONDS);
            } finally {
                try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            }

            if (success.get()) return null;

            // Fallback: the callback can be flaky — treat "package is gone" as success.
            try {
                getPackageManager().getPackageInfo(pkg, 0);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return null;
            }
            return errMsg.length() > 0 ? errMsg.toString() : "uninstall failed";
        } catch (Exception e) {
            Log.e(TAG, "uninstallPackage error: " + e.getMessage());
            return e.getMessage() != null ? e.getMessage() : "uninstall error";
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
                String pkg = ri.activityInfo.packageName;
                app.put("package", pkg);
                app.put("name", ri.loadLabel(pm).toString());
                // Flag system apps so the dashboard only offers uninstall for user apps.
                boolean isSystem = false;
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    isSystem = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            || (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                } catch (Exception ignore) {}
                app.put("is_system", isSystem);
                apps.put(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "getInstalledApps error: " + e.getMessage());
        }
        return apps;
    }

    /** Builds the current full telemetry "extra" — a complete snapshot of every field. */
    private JSONObject buildExtra() throws JSONException {
        Intent batteryIntent = getBatteryIntent();
        JSONObject extra = new JSONObject();
        populateWifiInfo(extra);
        extra.put("storage_free_gb", getStorageFreeGb());
        extra.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000);
        extra.put("wlc_status", getWlcStatus());
        extra.put("ram_usage_mb", getRamUsageMb());
        extra.put("timezone", java.util.TimeZone.getDefault().getID());
        extra.put("battery_temp_c", extractBatteryTemperature(batteryIntent));
        extra.put("charging", extractCharging(batteryIntent));
        // Charger detail for the 5V-charger / slow-charge rules: voltage (mV) + plug type.
        extra.put("charger_voltage_mv", extractChargerVoltage(batteryIntent));
        extra.put("charger_type", extractChargerType(batteryIntent));
        // Wi-Fi stability: disconnects observed in the last hour (WifiStateTracker).
        extra.put("wifi_disconnects_1h", getWifiDisconnects1h());
        // System health: reboot reason + per-boot id (changes every reboot → the server
        // detects unexpected reboots) + recent DropBox crashes/ANRs (server dedupes).
        extra.put("boot_reason", SystemPropertiesProxy.get("sys.boot.reason",
                SystemPropertiesProxy.get("ro.boot.bootreason", "")));
        extra.put("boot_id", getBootId());
        extra.put("crash_events", getRecentCrashEvents());
        // Include OTA progress if an update is in progress. Snapshot otaCommandId once: the OTA
        // listener nulls it on a binder callback thread, so re-reading the field after the
        // null-check could NPE at the put() below.
        String otaId = otaCommandId;
        if (otaUpdateManager != null && otaUpdateManager.isActive() && otaId != null) {
            JSONObject otaProgress = new JSONObject();
            otaProgress.put("command_id", otaId);
            otaProgress.put("phase", otaUpdateManager.getCurrentPhase());
            otaProgress.put("percent", otaUpdateManager.getCurrentPercent());
            extra.put("ota_progress", otaProgress);
        }
        return extra;
    }

    private String currentBuildId() {
        return SystemPropertiesProxy.get("ro.build.id", Build.UNKNOWN);
    }

    /** Full keyframe payload (HTTP check-in + forced WS resync): complete snapshot + app
     *  inventory delta. The server replaces latest_extra from this, clearing any stale keys. */
    private JSONObject buildCheckinPayload() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("serial_number", getDeviceSerial());
        payload.put("build_id", currentBuildId());
        payload.put("battery_pct", extractBatteryPct(getBatteryIntent()));
        payload.put("extra", buildExtra());

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

    // ── Delta telemetry ──────────────────────────────────────────────────────────
    // Gated keys: a change in any of these (or battery_pct, or a temperature trigger) is what
    // makes us transmit a WS frame; unchanged gated keys are omitted (server keeps the prior
    // value via merge). Volatile keys are always included in any frame we do send.
    private static final String[] GATED_EXTRA_KEYS = {
            "charging", "storage_free_gb", "wlc_status", "wifi", "ip_address",
            "timezone", "boot_reason", "charger_type", "boot_id"
    };
    private static final String[] VOLATILE_EXTRA_KEYS = {
            "battery_temp_c", "ram_usage_mb", "uptime_seconds", "wifi_rssi", "ota_progress",
            // Always carried in any frame we send: charger voltage moves continuously, and
            // crash/disconnect signals must never be dropped waiting for a gated change.
            "charger_voltage_mv", "wifi_disconnects_1h", "crash_events"
    };

    /** Delta payload: the volatile set + any changed gated fields; battery_pct only if changed. */
    private JSONObject buildDeltaPayload(JSONObject curExtra, int curBattery) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("serial_number", getDeviceSerial());
        payload.put("build_id", currentBuildId()); // identity — always present
        JSONObject extra = new JSONObject();
        for (String k : VOLATILE_EXTRA_KEYS) {
            if (curExtra.has(k)) extra.put(k, curExtra.get(k));
        }
        synchronized (baselineLock) {
            for (String k : GATED_EXTRA_KEYS) {
                if (!sameAsBaseline(k, curExtra)) extra.put(k, curExtra.get(k));
            }
            if (lastSentBattery != curBattery) payload.put("battery_pct", curBattery);
        }
        payload.put("extra", extra);
        return payload;
    }

    /** Caller holds baselineLock. True when key's current value equals the last sent value. */
    private boolean sameAsBaseline(String key, JSONObject curExtra) {
        if (lastSentExtra == null) return false;
        return String.valueOf(lastSentExtra.opt(key)).equals(String.valueOf(curExtra.opt(key)));
    }

    /** Caller holds baselineLock. Any gated field changed since the last successful send? */
    private boolean gatedChanged(JSONObject curExtra) {
        if (lastSentExtra == null) return true;
        for (String k : GATED_EXTRA_KEYS) {
            if (!sameAsBaseline(k, curExtra)) return true;
        }
        return false;
    }

    /** Temperature moved enough to warrant an out-of-band push (keeps thermal alerts fresh). */
    private boolean tempTrigger(JSONObject curExtra) {
        if (lastSentExtra == null) return true;
        double cur = curExtra.optDouble("battery_temp_c", -999);
        double prev = lastSentExtra.optDouble("battery_temp_c", -999);
        if (prev <= -999) return true;
        if (Math.abs(cur - prev) >= 2.0) return true;
        return tempBand(cur) != tempBand(prev);
    }

    private int tempBand(double c) { return c >= 45 ? 2 : (c >= 38 ? 1 : 0); }

    /** Record what the server now knows, after a confirmed send. */
    private void rememberSent(JSONObject extra, int battery) {
        synchronized (baselineLock) {
            try { lastSentExtra = new JSONObject(extra.toString()); }
            catch (JSONException e) { lastSentExtra = null; }
            lastSentBattery = battery;
        }
        forceKeyframe = false;
    }

    private String getDeviceSerial() {
        if (deviceSerial == null) {
            try {
                deviceSerial = Build.getSerial();
            } catch (SecurityException e) {
                deviceSerial = Build.UNKNOWN;
            }
        }
        return deviceSerial;
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

    /** Charger voltage in millivolts (BatteryManager.EXTRA_VOLTAGE); -1 if unknown. A plain
     *  5V (~5000 mV) supply distinguishes a basic charger from a higher-voltage fast charger. */
    private int extractChargerVoltage(Intent batteryStatus) {
        if (batteryStatus == null) return -1;
        return batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
    }

    /** Plug type: none | ac | usb | wireless | unknown (from BatteryManager.EXTRA_PLUGGED). */
    private String extractChargerType(Intent batteryStatus) {
        if (batteryStatus == null) return "unknown";
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged == 0) return "none";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "ac";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "usb";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "unknown";
    }

    /** Record a Wi-Fi disconnect and prune events older than an hour. */
    private void recordWifiDisconnect() {
        long now = SystemClock.elapsedRealtime();
        synchronized (wifiDisconnects) {
            wifiDisconnects.addLast(now);
            while (!wifiDisconnects.isEmpty() && now - wifiDisconnects.peekFirst() > CRASH_LOOKBACK_MS) {
                wifiDisconnects.removeFirst();
            }
        }
        Log.i(TAG, "Wi-Fi disconnect recorded (" + wifiDisconnects.size() + " in last hour)");
    }

    /** Count of Wi-Fi disconnects in the last hour (prunes old events first). */
    private int getWifiDisconnects1h() {
        long now = SystemClock.elapsedRealtime();
        synchronized (wifiDisconnects) {
            while (!wifiDisconnects.isEmpty() && now - wifiDisconnects.peekFirst() > CRASH_LOOKBACK_MS) {
                wifiDisconnects.removeFirst();
            }
            return wifiDisconnects.size();
        }
    }

    /** Per-boot UUID from /proc (changes on every reboot). Cached — it is constant per boot.
     *  The server compares it across check-ins to detect an (unexpected) reboot. */
    private String cachedBootId;
    private String getBootId() {
        if (cachedBootId != null) return cachedBootId;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"));
            String line = r.readLine();
            cachedBootId = (line != null) ? line.trim() : "";
        } catch (Exception e) {
            cachedBootId = "";
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
        return cachedBootId;
    }

    // DropBox tags that represent an app/system crash, ANR, or native tombstone.
    private static final String[] CRASH_TAGS = {
            "data_app_crash", "data_app_anr", "data_app_native_crash",
            "system_app_crash", "system_app_anr", "system_app_native_crash",
            "system_server_crash", "system_server_anr", "system_server_native_crash",
            "SYSTEM_TOMBSTONE"
    };
    private static final long CRASH_LOOKBACK_MS = 60 * 60 * 1000L; // last hour
    private static final int  MAX_TRACE_BYTES = 64 * 1024;   // full trace body per crash
    private static final int  MAX_TOTAL_TRACE_BYTES = 256 * 1024; // budget across one check-in

    /** Recent crash/ANR/tombstone entries from DropBoxManager (readable to this system-UID
     *  app), as [{kind,time_ms,summary,trace}]. The DropBox entry holds the real diagnostic —
     *  the Java stack trace, the ANR (blocked-thread) trace, or the native tombstone — so we
     *  send it as `trace` (capped per-entry and across the check-in), keeping `summary` as the
     *  headline first line. Reports the last hour on every check-in; the server dedupes by
     *  (device,kind,time_ms), so a repeated report is harmless and a lost send self-recovers. */
    private JSONArray getRecentCrashEvents() {
        JSONArray arr = new JSONArray();
        try {
            android.os.DropBoxManager dbm =
                    (android.os.DropBoxManager) getSystemService(Context.DROPBOX_SERVICE);
            if (dbm == null) return arr;
            long since = System.currentTimeMillis() - CRASH_LOOKBACK_MS;
            int traceBudget = MAX_TOTAL_TRACE_BYTES;
            for (String tag : CRASH_TAGS) {
                long cursor = since;
                for (int i = 0; i < 50; i++) { // bound work per tag
                    android.os.DropBoxManager.Entry e = dbm.getNextEntry(tag, cursor);
                    if (e == null) break;
                    try {
                        long t = e.getTimeMillis();
                        JSONObject o = new JSONObject();
                        o.put("kind", tag);
                        o.put("time_ms", t);
                        // Read the full entry (up to the remaining budget). getText's arg is a
                        // byte cap, so the old getText(200) truncated to the first line only.
                        int cap = Math.min(MAX_TRACE_BYTES, Math.max(0, traceBudget));
                        String txt = readEntry(e, cap > 0 ? cap : 200);
                        if (txt != null && !txt.isEmpty()) {
                            o.put("summary", crashHeadline(txt));
                            if (cap > 0) {
                                o.put("trace", txt);
                                traceBudget -= txt.length();
                            }
                        }
                        arr.put(o);
                        if (t <= cursor) t = cursor + 1; // guard against equal timestamps
                        cursor = t;
                    } finally {
                        e.close();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "crash events read failed: " + t.getMessage());
        }
        return arr;
    }

    /** A useful one-line headline from a DropBox crash/ANR/tombstone body. The entry opens with
     *  "Key: value" header lines (SystemUptimeMs, Process, PID, …), then a blank line, then the
     *  real exception/abort message. The bare first line is "SystemUptimeMs: …", which is
     *  useless as an alert headline — so prefer "&lt;process&gt; — &lt;first body line&gt;",
     *  falling back to the first non-blank line (e.g. native tombstones with no header). */
    private static String crashHeadline(String txt) {
        String[] lines = txt.split("\\n");
        String proc = null;
        int body = 0; // index of the first line after the header's blank separator
        for (int i = 0; i < lines.length; i++) {
            if (proc == null && lines[i].startsWith("Process:")) proc = lines[i].substring(8).trim();
            if (lines[i].trim().isEmpty()) { body = i + 1; break; }
        }
        String exc = "";
        for (int i = body; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) { exc = lines[i].trim(); break; }
        }
        if (exc.isEmpty()) exc = lines.length > 0 ? lines[0].trim() : "";
        if (proc != null && !proc.isEmpty() && !exc.isEmpty()) return proc + " — " + exc;
        if (!exc.isEmpty()) return exc;
        return proc != null ? proc : "";
    }

    /** Read up to {@code cap} bytes of a DropBox entry's text. Prefers getText(), but falls
     *  back to the raw (gzip-decoded) input stream when getText() returns null — which it does
     *  for any entry not flagged IS_TEXT (notably native tombstones). Without this fallback the
     *  crash event is reported with only its kind/time and no trace at all. */
    private static String readEntry(android.os.DropBoxManager.Entry e, int cap) {
        String txt = e.getText(cap);
        if (txt != null) return txt;
        java.io.InputStream is = null;
        try {
            is = e.getInputStream(); // already gunzipped by the framework when IS_GZIPPED
            if (is == null) return null;
            byte[] buf = new byte[cap];
            int off = 0, n;
            while (off < cap && (n = is.read(buf, off, cap - off)) > 0) off += n;
            return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (java.io.IOException ignore) {}
        }
    }

    private void populateWifiInfo(JSONObject extra) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (cachedWifiExtra != null && now - wifiLastMs < WIFI_CACHE_MS) {
            extra.put("ip_address", cachedWifiExtra.opt("ip_address"));
            extra.put("wifi", cachedWifiExtra.opt("wifi"));
            extra.put("wifi_rssi", cachedWifiExtra.opt("wifi_rssi"));
            return;
        }
        cachedWifiExtra = new JSONObject();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            cachedWifiExtra.put("ip_address", JSONObject.NULL);
            cachedWifiExtra.put("wifi", JSONObject.NULL);
            cachedWifiExtra.put("wifi_rssi", JSONObject.NULL);
        } else {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                cachedWifiExtra.put("ip_address", JSONObject.NULL);
                cachedWifiExtra.put("wifi", JSONObject.NULL);
                cachedWifiExtra.put("wifi_rssi", JSONObject.NULL);
            } else {
                int ip4 = info.getIpAddress();
                cachedWifiExtra.put("ip_address", String.format("%d.%d.%d.%d",
                        ip4 & 0xff, (ip4 >> 8) & 0xff, (ip4 >> 16) & 0xff, (ip4 >> 24) & 0xff));
                cachedWifiExtra.put("wifi", info.getSSID());
                cachedWifiExtra.put("wifi_rssi", info.getRssi());
            }
        }
        wifiLastMs = now;
        extra.put("ip_address", cachedWifiExtra.opt("ip_address"));
        extra.put("wifi", cachedWifiExtra.opt("wifi"));
        extra.put("wifi_rssi", cachedWifiExtra.opt("wifi_rssi"));
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
        synchronized (wlcLock) {
            if (wlcLastMs > 0 && now - wlcLastMs < WLC_CACHE_MS) return cachedWlcStatus;
        }
        int v = readWlcStatusUncached();
        synchronized (wlcLock) {
            cachedWlcStatus = v;
            wlcLastMs = now;
        }
        return v;
    }

    /** Instantaneous, uncached read of the wireless-charging pad's guest-detection GPIO:
     *  1 = guest device on the pad, 0 = none, -1 = unreadable. The GPIO is the authoritative
     *  presence signal, so we read it regardless of the host's reported charging state — the
     *  pad detects a guest even when the host itself shows no external power. (The former
     *  "skip the read on battery" optimization assumed the pad only works while the host is on
     *  external power, which is untrue for this hardware and reported a false 0.)
     *  We no longer sample over a 500 ms window to detect the toggling "disconnected"
     *  state (formerly status 2): no alert consumes it anymore, and the server's daily
     *  wlc_guest_frac only counts wlc_status == 1 (pad_readable just needs >= 0). */
    private int readWlcStatusUncached() {
        final String gpioPath = "/sys/devices/platform/soc/soc:customer_gpio/gpio27";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(gpioPath))) {
            String line = reader.readLine();
            String v = line == null ? "" : line.replace("\0", "").trim();
            return v.equals("1") ? 1 : (v.equals("0") ? 0 : -1);
        } catch (Exception e) {
            Log.e(TAG, "WLC gpio27 read error: " + e.getMessage());
            return -1;
        }
    }

    /** Handle a WLC guest-presence broadcast: refresh the wlc cache (so the HTTP safety-net path
     *  sees a fresh value too) and push telemetry the instant the pad state changes, so placing /
     *  removing a device on the pad is reflected immediately instead of waiting out the telemetry
     *  cache + next check-in. The seed value adopted in onCreate() is guarded against here via
     *  lastWatchedWlc == Integer.MIN_VALUE, so a process restart never emits a spurious push.
     *  Confirmable via: adb logcat -s MdmService:I | grep "WLC pad state changed" */
    private void handleWlcIntent(Intent intent) {
        try {
            int w = intent.getIntExtra(EXTRA_WLC_STATE, -1);
            synchronized (wlcLock) {
                cachedWlcStatus = w;
                wlcLastMs = SystemClock.elapsedRealtime();
            }
            if (w != lastWatchedWlc) {
                boolean first = lastWatchedWlc == Integer.MIN_VALUE;
                lastWatchedWlc = w;
                if (!first) {
                    Log.i(TAG, "WLC pad state changed: " + w + " — pushing immediate telemetry");
                    sendTelemetryOverWs();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "wlc receiver error: " + e.getMessage());
        }
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
                Log.i(TAG, "Package changed, invalidating app cache + pushing refreshed list");
                cachedInstalledApps = null;
                // Push the refreshed app list right away as a keyframe so the dashboard
                // reflects an install/uninstall in seconds instead of waiting for the
                // ~5-minute HTTP safety-net check-in (WS deltas don't carry the app list).
                forceKeyframe = true;
                if (networkAvailable && !polling) performCheckin();
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
        if (pct <= 15) return base * 6;  // critical battery: poll far less often
        if (pct <= 30) return base * 3;  // low battery: poll less often
        if (!interactive) return base * 4; // screen off, not charging: nobody's watching
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

    /**
     * Posts/updates the app download+install progress notification (id 1002, separate from
     * the persistent service notification). While ongoing it shows a progress bar —
     * determinate when {@code percent >= 0}, indeterminate otherwise; the final result is
     * posted with {@code ongoing=false} so the user can dismiss it. Thread-safe.
     */
    private void showInstallNotification(String title, String text, int percent, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title != null && !title.isEmpty() ? title : "App install")
                .setContentText(text)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                                      : android.R.drawable.stat_sys_download_done)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) {
            if (percent >= 0) b.setProgress(100, percent, false);
            else b.setProgress(0, 0, true); // indeterminate (installing / unknown size)
        }
        getSystemService(NotificationManager.class).notify(INSTALL_NOTIFICATION_ID, b.build());
    }

    @Override
    public void onDestroy() {
        isCapturing = false;
        stopAllLogcatStreams();
        releaseRemoteWakeLock();
        alarmManager.cancel(pollIntent);
        if (pollReceiver != null) {
            try { unregisterReceiver(pollReceiver); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        if (heavyExecutor != null) heavyExecutor.shutdownNow();
        if (wlcReceiver != null) {
            try { unregisterReceiver(wlcReceiver); } catch (Exception ignored) {}
        }
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
        if (wifiReceiver != null) {
            try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
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
