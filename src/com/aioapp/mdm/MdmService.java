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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MdmService extends Service {
    private static final String TAG = "MdmService";
    private static final String LOCATION_TAG = "MdmLocation";
    private static final String CHANNEL_ID = "MDM_SERVICE";
    // Separate, gently-alerting channel for update / app-install completions, so the
    // persistent status channel can stay silent.
    private static final String UPDATES_CHANNEL_ID = "AIO_MDM_UPDATES";
    private static final int NOTIFICATION_ID = 1001;

    // Kiosk-exit gesture: SystemUI's long-press-Back arms an exit (via KioskExitReceiver
    // -> this action); the technician confirms by pressing Power (screen-off) within the
    // window below. Retires the TOTP prompt without removing the code from the tree.
    public static final String ACTION_KIOSK_EXIT_ARM = "com.aioapp.mdm.action.KIOSK_EXIT_ARM";
    private static final long KIOSK_EXIT_ARM_WINDOW_MS = 4000;
    private volatile long kioskExitArmedUntilMs = 0;
    private BroadcastReceiver screenOffReceiver;
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
    // Command ids the server told us to cancel (operator deleted the action). An
    // in-flight APK download polls this and aborts, so we stop pulling bytes for a
    // command that no longer exists. Populated by the WS "cancel_command" frame.
    private final java.util.Set<String> cancelledCommands = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── Command idempotency + reliable terminal acks ─────────────────────────────
    // Ids currently executing, so a re-delivered command is not run a second time.
    private final java.util.Set<String> inFlightCommands = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Recently-completed command ids (bounded, persisted) so a re-delivery AFTER completion
    // is not re-run. Guarded by its own monitor.
    private final java.util.LinkedHashSet<String> recentDoneCommands = new java.util.LinkedHashSet<>();
    // Terminal acks not yet CONFIRMED by the server, persisted so a 'completed'/'failed'
    // survives a half-open socket or a process restart. cmdId → ack JSON. Guarded by itself.
    private final java.util.LinkedHashMap<String, JSONObject> pendingAcks = new java.util.LinkedHashMap<>();
    private static final String PREFS_CMD = "mdm_cmd";
    private static final String KEY_DONE_CMDS = "recent_done_commands";
    private static final String KEY_PENDING_ACKS = "pending_acks";
    private static final int MAX_DONE_CMDS = 200;
    private volatile boolean cmdStateLoaded = false;

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
    private ScheduledExecutorService wifiScanExecutor;

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

    private JSONArray cachedWifiScan = null;
    private static final long WIFI_SCAN_INTERVAL_SEC = 60;

    private JSONObject cachedRam = null;
    private long ramLastMs = 0;
    private static final long RAM_CACHE_MS = 30_000;

    private int cachedWlcStatus = -1;
    private long wlcLastMs = 0;
    private static final long WLC_CACHE_MS = 120_000;
    private final Object wlcLock = new Object();          // guards cachedWlcStatus / wlcLastMs
    // The Qi charging pad exposes no system broadcast (unlike charging, which rides
    // ACTION_BATTERY_CHANGED), so a guest device being placed on / removed from the pad is
    // invisible until the next telemetry read. A short GPIO poll edge-detects that change and
    // pushes immediately, making wlc_status as responsive as the charging state.
    private ScheduledExecutorService wlcWatcher;
    private static final long WLC_WATCH_MS = 1_000;
    private volatile int lastWatchedWlc = Integer.MIN_VALUE; // last value the watcher observed

    // gpio27 only carries guest state while charging is ON (gpio127=1): STABLE 1 = guest
    // on the pad, STABLE 0 = vacant. With charging OFF the line free-runs (toggles rapidly)
    // and carries NO placement info — so to read placement while charging is disabled we
    // briefly pulse gpio127=1, take a settled read, then restore. Characterized on-device
    // with tools/wlc-gpio-matrix.sh and validated with tools/wlc-pulse-test.sh. The old
    // "toggling == no pad" heuristic was wrong (toggling just means charging is off), and
    // "no pad connected" is not detectable from this line at all (a disconnected pad reads
    // identically to a placed guest when charging is on), so that status was dropped.
    private static final int  WLC_SETTLE_READS    = 8;      // max reads to confirm a stable value
    private static final int  WLC_SETTLE_AGREE    = 3;      // consecutive equal reads = settled
    private static final long WLC_SETTLE_STEP_MS  = 20L;    // gap between settle reads

    // Hardware product category (T7 vs Kiosk 18/22/27) + its capabilities, resolved once
    // in onCreate. Gates which telemetry we sample: a wall-powered kiosk has no battery,
    // charger, or Qi pad, so those fields are omitted rather than reported as bogus values.
    private final MdmProduct product = MdmProduct.detect();

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
        wifiScanExecutor = Executors.newSingleThreadScheduledExecutor();
        wifiScanExecutor.scheduleWithFixedDelay(this::runWifiScan, 0, WIFI_SCAN_INTERVAL_SEC, TimeUnit.SECONDS);
        apiService = new MdmApiService();
        // Restore command dedup + any terminal acks that weren't confirmed before a restart,
        // and try to flush the acks now (also retried on every reconnect).
        loadCommandState();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MdmAdminReceiver.class);
        // startForeground MUST be called within ~5s of startForegroundService or the OS crashes
        // the process (ForegroundServiceDidNotStartInTimeAllowedException). ensureDeviceOwner()
        // makes synchronous DPM binder calls that can stall past 5s on a cold fleet boot, so
        // promote to foreground FIRST, then do provisioning.
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Your device is set up and protected"));
        ensureDeviceOwner();

        // Product (resolved once at field init) gates the receiver registrations below and
        // the telemetry collectors via its capabilities.
        Log.i(TAG, "Device product=" + product.key()
                + " battery=" + product.hasBattery()
                + " charging=" + product.hasCharging()
                + " wlc=" + product.hasWlc());

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

        // Kiosk-exit confirm: a Power press turns the screen off. When an exit was armed
        // (long-press Back) within the last KIOSK_EXIT_ARM_WINDOW_MS, that Power press is
        // the confirmation — leave kiosk. Outside the armed window this is a no-op.
        screenOffReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                if (SystemClock.elapsedRealtime() > kioskExitArmedUntilMs) return;
                kioskExitArmedUntilMs = 0;
                performGestureKioskExit();
            }
        };
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

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
        // Only run the WLC watcher on products that actually have a wireless-charging
        // pad. Kiosks have none, so there is nothing to sample — skip it entirely.
        if (product.hasWlc()) {
            startWlcWatcher();
            applySavedWlcCharging(); // sysfs resets to on across reboot — restore the saved choice
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Kiosk-exit arm request from the long-press-Back gesture (KioskExitReceiver).
        if (intent != null && ACTION_KIOSK_EXIT_ARM.equals(intent.getAction())) {
            armKioskExit();
            return START_STICKY;
        }
        // Cancel any existing alarm before rescheduling — prevents duplicates when
        // LOCKED_BOOT_COMPLETED + BOOT_COMPLETED both fire on a fresh boot.
        alarmManager.cancel(pollIntent);
        if (networkAvailable && !polling) performCheckin();
        enforceKioskLock(); // self-heal: re-assert lock-task if it has dropped
        scheduleNextPoll();
        return START_STICKY;
    }

    /**
     * Kiosk watchdog. Kiosk policy is otherwise applied only when the server pushes a
     * CHANGED config, so any drop of lock-task -- a warm apply that never entered it, the
     * kiosk app crashing/relaunching, an errant stopLockTask, a reboot race -- would leave
     * the device unlocked until someone toggles the config. This runs every poll and
     * re-asserts the saved kiosk policy whenever the device should be locked but isn't
     * (not LOCKED, or the wrong app is foreground). Honours the offline-exit suspension so
     * a technician's local exit is not fought.
     */
    private void enforceKioskLock() {
        try {
            JSONObject cfg = KioskManager.loadConfig(this);
            if (cfg == null || !cfg.optBoolean("kiosk_enabled", false)) return;
            String pkg = cfg.optString("kiosk_package", "");
            if (pkg.isEmpty()) return;
            if (KioskExit.isSuspended(this)) return; // respect a verified offline exit
            // Use isProperlyLocked (LOCKED only): PINNED leaves the nav bar visible, so it
            // must be treated as unlocked and re-asserted into a real device-owner lock.
            boolean locked = KioskManager.isProperlyLocked(this);
            String fg = KioskManager.foregroundPackage(this);
            if (!locked || !pkg.equals(fg)) {
                Log.w(TAG, "kiosk watchdog: reasserting lock (state=" + KioskManager.lockState(this) + " fg=" + fg + " want=" + pkg + ")");
                KioskManager.apply(this, dpm, adminComponent, cfg);
            }
        } catch (Exception e) {
            Log.e(TAG, "kiosk watchdog error: " + e.getMessage());
        }
    }

    /** True when the device is currently in lock-task (kiosk) mode. */
    private boolean isInKioskLock() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    /** Arm a kiosk exit (long-press Back). Only while actually in kiosk; the technician
     *  then presses Power within KIOSK_EXIT_ARM_WINDOW_MS to confirm. */
    private void armKioskExit() {
        if (!isInKioskLock()) {
            Log.d(TAG, "kiosk-exit arm ignored: not in kiosk lock");
            return;
        }
        kioskExitArmedUntilMs = SystemClock.elapsedRealtime() + KIOSK_EXIT_ARM_WINDOW_MS;
        Log.i(TAG, "kiosk-exit armed for " + KIOSK_EXIT_ARM_WINDOW_MS + "ms — press Power to exit");
    }

    /** Confirmed exit (armed Back + Power). Leaves kiosk locally and reports it on the
     *  next check-in; a check-in is kicked off immediately so the server reflects it fast. */
    private void performGestureKioskExit() {
        try {
            if (!isInKioskLock()) return;
            KioskManager.suspendLocally(this, dpm, adminComponent);
            Log.i(TAG, "kiosk exited via Back+Power gesture — pushing immediate telemetry");
            // Report the exit AT ONCE so the dashboard flips immediately (like the charging
            // chip), not on the next scheduled poll. Force a keyframe so the frame carrying
            // offline_exit_at isn't dropped by the delta-skip; push over WS if connected,
            // and fall back to an HTTP check-in otherwise.
            forceKeyframe = true;
            sendTelemetryOverWs();
            if (networkAvailable && !polling) performCheckin();
        } catch (Exception e) {
            Log.e(TAG, "gesture kiosk exit failed: " + e.getMessage());
        }
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
                        // Battery-less products (kiosks) omit battery_pct from the payload — use
                        // optInt(-1) to match extractBatteryPct's no-battery value and avoid a
                        // JSONException ("No value for battery_pct") after an otherwise-OK check-in.
                        rememberSent(payload.getJSONObject("extra"), payload.optInt("battery_pct", -1));
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

    // ── Command idempotency + reliable terminal acks ─────────────────────────────

    /** Load persisted dedup + pending-ack state once, on startup. */
    private synchronized void loadCommandState() {
        if (cmdStateLoaded) return;
        cmdStateLoaded = true;
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_CMD, MODE_PRIVATE);
        String done = sp.getString(KEY_DONE_CMDS, "");
        if (!done.isEmpty()) {
            synchronized (recentDoneCommands) {
                for (String id : done.split(",")) if (!id.isEmpty()) recentDoneCommands.add(id);
            }
        }
        String acks = sp.getString(KEY_PENDING_ACKS, "");
        if (!acks.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(acks);
                synchronized (pendingAcks) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.getJSONObject(i);
                        pendingAcks.put(a.getString("command_id"), a);
                    }
                }
            } catch (Exception e) { Log.e(TAG, "loadCommandState acks: " + e.getMessage()); }
        }
        drainPendingAcks();
    }

    /** Entry point for an incoming WS command: confirm receipt, dedup, then run. */
    private void handleIncomingCommand(JSONObject msg) {
        final String cmdId = msg.optString("id", "");
        if (cmdId.isEmpty()) { Log.w(TAG, "WS command with no id"); return; }
        // 1. Always confirm receipt so the server stops re-delivering — even a duplicate.
        sendReceived(cmdId);
        // 2. Idempotency: never execute a command already finished or still running.
        boolean alreadyDone;
        synchronized (recentDoneCommands) { alreadyDone = recentDoneCommands.contains(cmdId); }
        if (alreadyDone || !inFlightCommands.add(cmdId)) {
            Log.i(TAG, "Duplicate command " + cmdId + " ignored (already handled/in-flight)");
            return;
        }
        heavyExecutor.submit(() -> {
            try {
                processWsCommand(msg);
            } catch (Exception e) {
                Log.e(TAG, "WS command error: " + e.getMessage());
            } finally {
                markCommandDone(cmdId);
            }
        });
    }

    /** Confirm receipt of a command (before/independent of executing it). */
    private void sendReceived(String cmdId) {
        ackCommand(cmdId, getDeviceSerial(), "received", "");
    }

    private void markCommandDone(String cmdId) {
        inFlightCommands.remove(cmdId);
        synchronized (recentDoneCommands) {
            recentDoneCommands.remove(cmdId);
            recentDoneCommands.add(cmdId);
            while (recentDoneCommands.size() > MAX_DONE_CMDS) {
                java.util.Iterator<String> it = recentDoneCommands.iterator();
                it.next(); it.remove();
            }
            getSharedPreferences(PREFS_CMD, MODE_PRIVATE).edit()
                    .putString(KEY_DONE_CMDS, String.join(",", recentDoneCommands)).apply();
        }
    }

    /** Terminal command result. Persisted and retried over HTTP until the server confirms,
     *  so a 'completed'/'failed' is never lost to a half-open socket or a process restart.
     *  Replaces the old fire-and-forget WS/HTTP terminal acks. */
    private void reportTerminal(String cmdId, String serial, String status, String output) {
        reportTerminal(cmdId, serial, status, output, null);
    }
    private void reportTerminal(String cmdId, String serial, String status, String output, String pkg) {
        try {
            JSONObject a = new JSONObject();
            a.put("command_id", cmdId);
            a.put("serial_number", serial);
            a.put("status", status);
            if (output != null && !output.isEmpty()) a.put("output", output);
            if (pkg != null && !pkg.isEmpty()) a.put("package", pkg);
            synchronized (pendingAcks) { pendingAcks.put(cmdId, a); persistPendingAcks(); }
        } catch (Exception e) { Log.e(TAG, "reportTerminal: " + e.getMessage()); }
        drainPendingAcks();
    }

    /** persist pendingAcks; caller holds the monitor. */
    private void persistPendingAcks() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject a : pendingAcks.values()) arr.put(a);
        getSharedPreferences(PREFS_CMD, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_ACKS, arr.toString()).apply();
    }

    /** Deliver every not-yet-confirmed terminal ack over HTTP; drop each on a 2xx. Safe to
     *  call repeatedly (on reconnect, on report, on startup) — server acks are idempotent. */
    private void drainPendingAcks() {
        executor.submit(() -> {
            java.util.List<JSONObject> snapshot;
            synchronized (pendingAcks) {
                if (pendingAcks.isEmpty()) return;
                snapshot = new java.util.ArrayList<>(pendingAcks.values());
            }
            for (JSONObject a : snapshot) {
                boolean ok = apiService.ackCommandOk(
                        a.optString("command_id"), a.optString("serial_number"),
                        a.optString("status"), a.optString("output", ""),
                        a.has("package") ? a.optString("package") : null);
                if (ok) {
                    synchronized (pendingAcks) { pendingAcks.remove(a.optString("command_id")); persistPendingAcks(); }
                }
            }
        });
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
                updateNotificationIfNeeded("Updating your device… " + percent + "%");
                sendOtaProgressFrame(otaCommandId, phase, percent);
            }
            @Override public void onDownloadComplete() {
                updateNotificationIfNeeded("Almost done — installing the update…");
                reportOtaStatus(otaCommandId, "downloaded", null);
            }
            @Override public void onInstallComplete() {
                updateNotificationIfNeeded("Update ready — your device will restart to finish");
                reportOtaStatus(otaCommandId, "installed", null);
                otaCommandId = null;
            }
            @Override public void onError(String errorCode) {
                // Keep the user-facing text reassuring; the raw code goes to the log + server.
                updateNotificationIfNeeded("Couldn't finish the update — it'll try again automatically");
                Log.w(TAG, "OTA error: " + errorCode);
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
                        // Battery-less products (kiosks) omit battery_pct from the payload — use
                        // optInt(-1) to match extractBatteryPct's no-battery value and avoid a
                        // JSONException ("No value for battery_pct") after an otherwise-OK check-in.
                        rememberSent(payload.getJSONObject("extra"), payload.optInt("battery_pct", -1));
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
        // Offline kiosk-exit policy (TOTP seed + settings) is provisioned via config;
        // store it before applying kiosk policy so a suspended device is respected.
        try {
            KioskExit.savePolicy(MdmService.this, config.optJSONObject("offline_exit"));
            // Server acknowledged our reported exit → it has flipped kiosk off on its side.
            // Stop resending and drop the local "exited" guard; the config in this same
            // response now says kiosk-off, so the device settles cleanly.
            long ack = config.optLong("offline_exit_ack", 0);
            if (ack > 0 && ack == KioskExit.pendingEventAt(MdmService.this)) {
                KioskExit.clearPendingEvent(MdmService.this);
                KioskExit.setSuspended(MdmService.this, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "offline-exit savePolicy error: " + e.getMessage());
        }
        // WLC charging enable/disable. Persist so it survives a reboot (the sysfs line
        // resets to on), and only touch the hardware on products that have a pad. Config
        // arrives on every check-in and telemetry frame, and several frames can interleave
        // around a toggle — writing the gpio every time made it visibly flap. Write only
        // when the target actually changes from what we last applied.
        if (config.has("wlc_charging_enabled")) {
            boolean wlc = config.optBoolean("wlc_charging_enabled", true);
            getSharedPreferences(PREFS_WLC, MODE_PRIVATE).edit()
                    .putBoolean(KEY_WLC_CHARGING, wlc).apply();
            if (product.hasWlc() && (wlcLastApplied == null || wlcLastApplied.booleanValue() != wlc)) {
                if (setWlcCharging(wlc)) wlcLastApplied = Boolean.valueOf(wlc);
            }
        }
        // Apply kiosk policy only when it actually changed. Config rides every check-in
        // and telemetry frame (many per second when the charger flaps), and re-applying an
        // unchanged policy churned lock-task/DPM state and forced HOME each time. The kiosk
        // watchdog (enforceKioskLock) still re-asserts a dropped lock independently.
        try {
            String kioskSig = config.optBoolean("kiosk_enabled", false) + "|" + config.optString("kiosk_package", "");
            if (!kioskSig.equals(kioskLastSig)) {
                KioskManager.applyAndSave(MdmService.this, dpm, adminComponent, config);
                kioskLastSig = kioskSig;
            }
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
        wsClient.setConnectedCallback(() -> { forceKeyframe = true; sendTelemetryOverWs(); drainPendingAcks(); });
        wsClient.start();
        Log.i(TAG, "WebSocket client started");
    }

    private void handleWsMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "command":
                handleIncomingCommand(msg);
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
                    String codec = "h264".equals(msg.optString("codec", "jpeg")) ? "h264" : "jpeg";
                    int quality = Math.max(1, Math.min(100, msg.optInt("quality", 60)));
                    double scale = Math.max(0.1, Math.min(1.0, msg.optDouble("scale", 0.5)));
                    int maxFps = Math.max(1, Math.min(30, msg.optInt("max_fps", 10)));
                    int bitrate = Math.max(250000, Math.min(20000000, msg.optInt("bitrate", 4000000)));
                    if (!isCapturing) {
                        isCapturing = true;
                        acquireRemoteWakeLock();
                        runCaptureLoop(codec, quality, scale, maxFps, bitrate);
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
            case "cancel_command": {
                // Operator deleted/cancelled the action. Flag the id so an in-flight
                // download aborts (checked every read chunk in installApkInner).
                String cid = msg.optString("id", "");
                if (!cid.isEmpty()) {
                    cancelledCommands.add(cid);
                    Log.i(TAG, "Cancel requested for command " + cid);
                    // Bound the set so broadcast cancels for commands we never ran
                    // can't accumulate forever.
                    if (cancelledCommands.size() > 128) cancelledCommands.clear();
                }
                break;
            }
            case "checkin_now":
                // Server nudge (e.g. an OTA was just assigned): do a full HTTP check-in
                // immediately so the update resolves now instead of on the next periodic
                // poll. performCheckin() self-guards against a concurrent poll.
                executor.submit(() -> {
                    try { performCheckin(); } catch (Exception e) {
                        Log.e(TAG, "checkin_now error: " + e.getMessage());
                    }
                });
                break;
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
                try {
                    String err = installApk(cmd.getString("apk_url"), cmdId, serialNumber, pkgHolder,
                            apkSize, apkEtag);
                    if (cancelledCommands.contains(cmdId)) {
                        reportTerminal(cmdId, serialNumber, "cancelled", "cancelled by operator", pkgHolder[0]);
                    } else {
                        // HTTP (not WS): a long install can leave the WS half-open, silently
                        // dropping a WS ack and making the install show 'failed' though it
                        // succeeded.
                        reportTerminal(cmdId, serialNumber, err.isEmpty() ? "installed" : "failed", err, pkgHolder[0]);
                    }
                } finally {
                    cancelledCommands.remove(cmdId);
                }
                break;
            }
            case "uninstall": {
                String pkg = payload.optString("package", "");
                if (pkg.isEmpty()) {
                    reportTerminal(cmdId, serialNumber, "failed", "missing package");
                    break;
                }
                String err = uninstallPackage(pkg);
                reportTerminal(cmdId, serialNumber, err == null ? "completed" : "failed",
                        err == null ? ("uninstalled " + pkg) : err);
                break;
            }
            case "shell": {
                String shellCmd = payload.optString("cmd", "");
                if (shellCmd.isEmpty()) {
                    reportTerminal(cmdId, serialNumber, "failed", "empty cmd");
                    break;
                }
                if (!isShellCommandAllowed(shellCmd)) {
                    Log.w(TAG, "Rejected shell command not on allowlist: " + shellCmd);
                    reportTerminal(cmdId, serialNumber, "failed", "command not permitted");
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
                reportTerminal(cmdId, serialNumber, exitCode == 0 ? "completed" : "failed", output);
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
                    reportTerminal(cmdId, serialNumber, "completed",
                            b64Buf.toString(StandardCharsets.UTF_8.name()));
                } finally {
                    tmp.delete();
                }
                break;
            }
            case "get_app_inventory": {
                reportTerminal(cmdId, serialNumber, "completed", getInstalledApps().toString());
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
                    reportTerminal(cmdId, serialNumber, "failed", "missing url");
                    break;
                }
                long partitionSize = payload.optLong("partition_size", 0);
                String status = downloadAndUpdateSplash(splashUrl, partitionSize);
                boolean ok = "ok".equals(status);
                reportTerminal(cmdId, serialNumber, ok ? "completed" : "failed", status);
                break;
            }
            case "start_capture": {
                // Clamp server-supplied capture params (see the WS start_capture handler).
                String codec = "h264".equals(payload.optString("codec", "jpeg")) ? "h264" : "jpeg";
                int quality = Math.max(1, Math.min(100, payload.optInt("quality", 60)));
                double scale = Math.max(0.1, Math.min(1.0, payload.optDouble("scale", 0.5)));
                int maxFps = Math.max(1, Math.min(30, payload.optInt("max_fps", 10)));
                int bitrate = Math.max(250000, Math.min(20000000, payload.optInt("bitrate", 4000000)));
                if (!isCapturing) {
                    isCapturing = true;
                    acquireRemoteWakeLock();
                    heavyExecutor.submit(() -> runCaptureLoop(codec, quality, scale, maxFps, bitrate));
                }
                reportTerminal(cmdId, serialNumber, "completed", "");
                break;
            }
            case "stop_capture": {
                isCapturing = false;
                releaseRemoteWakeLock();
                reportTerminal(cmdId, serialNumber, "completed", "");
                break;
            }
            default:
                Log.w(TAG, "Unknown command type: " + cmdType);
                reportTerminal(cmdId, serialNumber, "failed", "unknown type: " + cmdType);
        }
    }


    private void runCaptureLoop(String codec, int quality, double scale, int maxFps, int bitrate) {
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

        // Tier 3: hardware H.264 when the operator opted in (?codec=h264). Runs until the
        // session stops; falls back to the JPEG still path if the encoder or auto-mirror
        // display can't be set up on this device.
        if ("h264".equals(codec)) {
            boolean ran = runH264Loop(scaledW, scaledH, maxFps, bitrate);
            if (ran) {
                isCapturing = false;
                releaseRemoteWakeLock();
                Log.i(TAG, "Capture loop stopped");
                return;
            }
            Log.w(TAG, "H.264 unavailable — falling back to JPEG stills");
        }

        long targetMs = 1000 / maxFps;
        Log.i(TAG, "Capture loop started — scaled=" + scaledW + "x" + scaledH
                + " quality=" + quality + " fps=" + maxFps + " codec=jpeg");

        // Decouple the blocking WS send from capture+encode: a dedicated sender drains this
        // queue so the next frame is captured while the previous one uploads. Bounded and
        // drop-oldest so a slow link sheds frames instead of piling up latency.
        final java.util.concurrent.ArrayBlockingQueue<byte[]> sendQ =
                new java.util.concurrent.ArrayBlockingQueue<>(2);
        Thread sender = new Thread(() -> {
            try {
                while (isCapturing || !sendQ.isEmpty()) {
                    byte[] f = sendQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (f != null && wsClient != null && wsClient.isConnected()) {
                        try {
                            wsClient.sendBinary(f);
                        } catch (java.io.IOException io) {
                            Log.w(TAG, "Frame send failed: " + io.getMessage());
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "mdm-frame-sender");
        sender.start();

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
                // JPEG software-encodes several times faster than WEBP at similar size/quality —
                // the main lever moving the still path from ~3 fps toward the target.
                screen.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos);
                screen.recycle();

                byte[] frame = bos.toByteArray();
                if (!sendQ.offer(frame)) { sendQ.poll(); sendQ.offer(frame); } // drop oldest under back-pressure
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
        sender.interrupt();
        releaseRemoteWakeLock();
        Log.i(TAG, "Capture loop stopped");
    }

    /**
     * Tier 3 hardware path: mirror the screen into a MediaCodec H.264 encoder via an
     * auto-mirror VirtualDisplay and stream Annex-B NAL units. Each WS message is
     * [1-byte type][payload]: type 1 = key frame (SPS/PPS prepended), 0 = delta — the
     * browser decodes this with WebCodecs. Returns true if it ran (setup succeeded),
     * false if the encoder/display couldn't be set up so the caller can fall back to
     * JPEG stills. Blocks until the session stops. Needs CAPTURE_VIDEO_OUTPUT (held as
     * a privileged platform app).
     */
    private boolean runH264Loop(int w, int h, int fps, int bitrate) {
        w = w & ~1; h = h & ~1; // H.264 wants even dimensions
        android.media.MediaCodec mc = null;
        android.hardware.display.VirtualDisplay vd = null;
        android.view.Surface surface = null;
        Thread sender = null;
        final java.util.concurrent.ArrayBlockingQueue<byte[]> sendQ =
                new java.util.concurrent.ArrayBlockingQueue<>(3);
        try {
            android.media.MediaFormat fmt = android.media.MediaFormat.createVideoFormat("video/avc", w, h);
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1s key frames
            // Baseline profile matches the browser's avc1.42E01E decoder config.
            fmt.setInteger(android.media.MediaFormat.KEY_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            // Low-latency tuning — minimise tap-to-screen delay for remote control:
            //  - realtime priority so the encoder is scheduled ahead of background work;
            //  - CBR so a static screen doesn't build a VBR buffer that delays motion;
            //  - no B-frames (baseline has none, but be explicit) so no reorder delay;
            //  - encode latency of 1 frame so the encoder never holds a frame back;
            //  - cap the input rate to the target fps (fixed fps) so we don't burn CPU
            //    encoding redundant frames on a busy screen;
            //  - repeat the last frame after one frame-interval so the stream keeps a
            //    steady cadence and the decoder always holds the freshest content even
            //    when the screen is idle. Unsupported keys are ignored by the encoder.
            fmt.setInteger(android.media.MediaFormat.KEY_BITRATE_MODE,
                    android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            fmt.setInteger(android.media.MediaFormat.KEY_PRIORITY, 0); // 0 = realtime
            fmt.setInteger(android.media.MediaFormat.KEY_MAX_B_FRAMES, 0);
            fmt.setInteger(android.media.MediaFormat.KEY_LATENCY, 1);
            fmt.setFloat(android.media.MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float) fps);
            fmt.setLong(android.media.MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000L / fps);
            mc = android.media.MediaCodec.createEncoderByType("video/avc");
            mc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = mc.createInputSurface();
            mc.start();

            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
            vd = dm.createVirtualDisplay("mdm-remote", w, h, dpi, surface, flags);
            if (vd == null) throw new IllegalStateException("createVirtualDisplay returned null");

            final android.media.MediaCodec fmc = mc;
            sender = new Thread(() -> {
                try {
                    while (isCapturing || !sendQ.isEmpty()) {
                        byte[] f = sendQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (f != null && wsClient != null && wsClient.isConnected()) {
                            try {
                                wsClient.sendBinary(f);
                            } catch (java.io.IOException io) {
                                Log.w(TAG, "Frame send failed: " + io.getMessage());
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }, "mdm-frame-sender-h264");
            sender.start();

            Log.i(TAG, "H.264 capture started — " + w + "x" + h + " fps=" + fps + " bitrate=" + bitrate);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            byte[] csd = null; // SPS/PPS, prepended to each key frame
            long sent = 0, lastLog = System.currentTimeMillis();
            while (isCapturing && wsClient != null && wsClient.isConnected()) {
                int idx = mc.dequeueOutputBuffer(info, 100000); // 100ms
                if (idx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = mc.getOutputFormat();
                    java.nio.ByteBuffer sps = of.getByteBuffer("csd-0");
                    java.nio.ByteBuffer pps = of.getByteBuffer("csd-1");
                    if (sps != null && pps != null) {
                        byte[] s = new byte[sps.remaining()]; sps.get(s);
                        byte[] p = new byte[pps.remaining()]; pps.get(p);
                        csd = new byte[s.length + p.length];
                        System.arraycopy(s, 0, csd, 0, s.length);
                        System.arraycopy(p, 0, csd, s.length, p.length);
                    }
                    continue;
                }
                if (idx < 0) continue; // INFO_TRY_AGAIN_LATER etc.
                java.nio.ByteBuffer buf = mc.getOutputBuffer(idx);
                if (buf == null) { mc.releaseOutputBuffer(idx, false); continue; }
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                buf.get(data);
                mc.releaseOutputBuffer(idx, false);

                boolean isConfig = (info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (isConfig) { csd = data; continue; } // SPS/PPS as a config buffer
                byte[] payload = data;
                if (isKey && csd != null) {
                    payload = new byte[csd.length + data.length];
                    System.arraycopy(csd, 0, payload, 0, csd.length);
                    System.arraycopy(data, 0, payload, csd.length, data.length);
                }
                byte[] framed = new byte[payload.length + 1];
                framed[0] = (byte) (isKey ? 1 : 0);
                System.arraycopy(payload, 0, framed, 1, payload.length);
                if (!sendQ.offer(framed)) { sendQ.poll(); sendQ.offer(framed); } // drop oldest
                sent++;
                if (System.currentTimeMillis() - lastLog >= 5000) {
                    Log.i(TAG, "H.264: " + sent + " frames in last 5s (" + framed.length + "B last)");
                    sent = 0; lastLog = System.currentTimeMillis();
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "H.264 capture unavailable: " + t);
            return false;
        } finally {
            try { if (vd != null) vd.release(); } catch (Exception ignored) {}
            try { if (mc != null) { mc.stop(); mc.release(); } } catch (Exception ignored) {}
            try { if (surface != null) surface.release(); } catch (Exception ignored) {}
            if (sender != null) sender.interrupt();
        }
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
        String[] title = { "AIO MDM" };
        // Distinct notification per install so concurrent installs don't overwrite each
        // other's progress (they all shared INSTALL_NOTIFICATION_ID, so the notification
        // "kept switching" between apps). Derived from cmdId → stable for this install.
        int notifId = installNotifId(cmdId);
        String err = installApkInner(apkUrl, cmdId, serial, outPkg, expectedSize, etag, title, notifId);
        // Final result notification (dismissible; not ongoing) — reuses this install's id so
        // it replaces the progress notification in place. Keep it human; the raw error goes
        // to the log + server, not the user.
        if (!err.isEmpty()) Log.w(TAG, "APK install failed: " + err);
        showInstallNotification(notifId, title[0], err.isEmpty() ? "Installed and ready" : "Couldn't install — we'll retry",
                err.isEmpty() ? 100 : 0, false);
        return err;
    }

    /** A per-install notification id, distinct from the status (1001) and legacy install
     *  (1002) ids, so several installs running at once each keep their own notification. */
    private int installNotifId(String cmdId) {
        return 2000 + Math.floorMod(cmdId == null ? 0 : cmdId.hashCode(), 100000);
    }

    private String installApkInner(String apkUrl, String cmdId, String serial, String[] outPkg,
                                   long expectedSize, String etag, String[] title, int notifId) {
        File apkFile = new File(getCacheDir(), "mdm_install_" + System.currentTimeMillis() + ".apk");
        try {
            // Download with Range resume + retry. A stale keep-alive socket ("unexpected
            // end of stream") or a transient stall on a large APK ("SocketTimeoutException")
            // no longer fails the whole install — the next attempt resumes from disk and the
            // final size is verified before we hand the file to PackageInstaller.
            // The 15-min retry budget lets a real network disturbance recover and resume
            // instead of hard-failing after a few attempts, matched to the server-side
            // stalled-install sweep so a device that never recovers gives up in step. FW-2026-000020.
            reportInstallProgress(cmdId, serial, "downloading", expectedSize > 0 ? 0 : -1);
            showInstallNotification(notifId, title[0], "Downloading…", expectedSize > 0 ? 0 : -1, true);
            final int[] lastPct = { -1 };       // notification: fine-grained, every 1%
            final int[] lastReported = { -1 };  // server report: throttled to 5% to avoid spam
            HttpDownloader.downloadWithResume(apkUrl, apkFile, expectedSize, etag,
                    30_000, 60_000, 5, 15 * 60 * 1000L,
                    (downloaded, total) -> {
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            // Update the on-device notification on every 1% step, showing
                            // downloaded / total MB alongside the progress bar.
                            if (pct != lastPct[0] && pct < 100) {
                                lastPct[0] = pct;
                                double dMb = downloaded / 1048576.0, tMb = total / 1048576.0;
                                showInstallNotification(notifId, title[0],
                                        String.format(java.util.Locale.US, "Downloading… %.1f / %.1f MB", dMb, tMb),
                                        pct, true);
                                // Report to the server less often (every 5%) so a fast link
                                // doesn't flood check-ins.
                                if (pct >= lastReported[0] + 5) {
                                    lastReported[0] = pct;
                                    reportInstallProgress(cmdId, serial, "downloading", pct);
                                }
                            }
                        }
                    },
                    // Abort mid-download if the operator deleted the action (server
                    // pushes a cancel_command frame → cancelledCommands).
                    () -> cancelledCommands.contains(cmdId));
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
                // The downloaded file doesn't parse as an APK — almost always a corrupt or
                // truncated download (a flaky host with no known size to verify against).
                // Fail here with a clear, actionable reason instead of handing a bad file to
                // PackageInstaller, which only reports a generic "status=1". The retry budget
                // will re-download and resume.
                Log.e(TAG, "APK could not be parsed (corrupt/truncated download): " + apkUrl
                        + " (" + written + " bytes)");
                apkFile.delete();
                return "download appears corrupt (unparseable APK, " + written + " bytes) — will retry";
            }
            showInstallNotification(notifId, title[0], "Installing…", -1, true);

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
                // Version name for the dashboard's app-info popup. Best-effort.
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    if (pi.versionName != null) app.put("version_name", pi.versionName);
                } catch (Exception ignore) {}
                // Launcher icon as a base64 PNG so the dashboard can render a real
                // app-drawer icon. Best-effort — a failure just omits it (server falls
                // back to a monogram). Sent only with the full app list (on change), so
                // this doesn't ride every check-in.
                try {
                    String icon = drawableToBase64Png(ri.loadIcon(pm), 96);
                    if (icon != null) app.put("icon", icon);
                } catch (Exception ignore) {}
                apps.put(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "getInstalledApps error: " + e.getMessage());
        }
        return apps;
    }

    /** Renders a Drawable to a size×size base64-encoded PNG (NO_WRAP), or null on error. */
    private String drawableToBase64Png(android.graphics.drawable.Drawable d, int size) {
        try {
            if (d == null || size <= 0) return null;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Builds the current full telemetry "extra" — a complete snapshot of every field. */
    private JSONObject buildExtra() throws JSONException {
        Intent batteryIntent = getBatteryIntent();
        JSONObject extra = new JSONObject();
        populateWifiInfo(extra);
        populateWifiScanResults(extra);
        extra.put("storage_free_gb", getStorageFreeGb());
        extra.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000);
        // Wireless-charging pad reading — only on products that have a pad. Omitted on
        // kiosks so the server sees "no such hardware", not a stuck -1.
        if (product.hasWlc()) {
            extra.put("wlc_status", getWlcStatus());
            extra.put("wlc_charging", readWlcCharging()); // reported so the dashboard shows real state
        }
        extra.put("ram_usage_mb", getRamUsageMb());
        extra.put("timezone", java.util.TimeZone.getDefault().getID());
        // Offline kiosk-exit: ack that the TOTP seed is provisioned, and surface a pending
        // "exited kiosk offline" event (epoch secs) so the server can audit/alert. The event
        // keeps riding check-ins until the server echoes offline_exit_ack (see applyConfig).
        extra.put("offline_exit_seed_set", KioskExit.seedSet(MdmService.this));
        // Actual live kiosk state: true once a technician has exited offline (the device is
        // out of lock-task even though the server's desired config still says kiosk on).
        extra.put("kiosk_suspended", KioskExit.isSuspended(MdmService.this));
        long offlineExitAt = KioskExit.pendingEventAt(MdmService.this);
        if (offlineExitAt > 0) {
            extra.put("offline_exit_at", offlineExitAt);
        }
        // Temperature rides the battery broadcast (EXTRA_TEMPERATURE) but the sensor is a
        // board thermal channel present on every product, kiosks included (same wiring as
        // the T7). So report it whenever we have a reading, regardless of hasBattery().
        if (batteryIntent != null) {
            extra.put("battery_temp_c", extractBatteryTemperature(batteryIntent));
        }
        // Charging state + charger detail (voltage mV, plug type) — only on products with a
        // charger input. A wall-powered kiosk has none, so these are omitted rather than
        // reported as a permanent "not charging".
        if (product.hasCharging()) {
            extra.put("charging", extractCharging(batteryIntent));
            extra.put("charger_voltage_mv", extractChargerVoltage(batteryIntent));
            extra.put("charger_type", extractChargerType(batteryIntent));
        }
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
        payload.put("product", product.key());
        // Battery percent only for products with a battery; a no-battery kiosk omits it
        // (server keeps its prior value / default rather than storing a bogus reading, and
        // its check-in validation rejects the -1 "unknown" sentinel anyway).
        if (product.hasBattery()) {
            payload.put("battery_pct", extractBatteryPct(getBatteryIntent()));
        }
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
        payload.put("product", product.key());     // identity — cheap, lets delta-only devices report it
        JSONObject extra = new JSONObject();
        for (String k : VOLATILE_EXTRA_KEYS) {
            if (curExtra.has(k)) extra.put(k, curExtra.get(k));
        }
        synchronized (baselineLock) {
            for (String k : GATED_EXTRA_KEYS) {
                if (!sameAsBaseline(k, curExtra)) extra.put(k, curExtra.get(k));
            }
            // Battery only for products that have one (matches the keyframe path).
            if (product.hasBattery() && lastSentBattery != curBattery) payload.put("battery_pct", curBattery);
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
    // Bounds on the crash_events field. crash_events is only ONE part of `extra` (wifi,
    // storage, ram, boot ids, etc. stack on top), and the server rejects an oversized
    // `extra` outright (413 / dropped WS frame). A crash-heavy device (e.g. full GMS) used
    // to blow the whole check-in past the limit. Keep the trace budget well under any sane
    // server cap so the total `extra` stays small, and bound the entry count so a device
    // with hundreds of crashes can't balloon the array with summary-only objects.
    private static final int  MAX_TRACE_BYTES = 48 * 1024;   // full trace body per crash
    private static final int  MAX_TOTAL_TRACE_BYTES = 128 * 1024; // trace budget across one check-in
    private static final int  MAX_CRASH_ENTRIES = 40;        // hard cap on entries per check-in

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
                if (arr.length() >= MAX_CRASH_ENTRIES) break;
                long cursor = since;
                for (int i = 0; i < 50; i++) { // bound work per tag
                    if (arr.length() >= MAX_CRASH_ENTRIES) break;
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
        synchronized (wlcLock) {
            if (wlcLastMs > 0 && now - wlcLastMs < WLC_CACHE_MS) return cachedWlcStatus;
        }
        // Cold path (the watcher hasn't populated the cache yet). Non-pulsing best effort so
        // we never write gpio127 off the watcher thread: charging on -> settled gpio27;
        // charging off -> unknown (the watcher will populate a real reading on its cadence).
        int v = readWlcCharging() ? readSettledWlc() : -1;
        synchronized (wlcLock) {
            cachedWlcStatus = v;
            wlcLastMs = now;
        }
        return v;
    }

    /** Instantaneous, uncached read of the wireless-charging pad's guest-detection GPIO
     *  (gpio27): 1 = line high, 0 = line low, -1 = unreadable. This is the BARE line; it
     *  only means guest-present(1)/vacant(0) while charging is ON (gpio127=1). With charging
     *  off the line free-runs and a single sample is meaningless — classifyWlc() reports
     *  unknown in that case rather than sampling it. */
    private int readWlcStatusUncached() {
        final String gpioPath = "/sys/devices/platform/soc/soc:customer_gpio/gpio27";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(gpioPath))) {
            String line = reader.readLine();
            String v = line == null ? "" : line.replace("\0", "").trim();
            return v.equals("1") ? 1 : (v.equals("0") ? 0 : -1);
        } catch (Exception e) {
            Log.e(TAG, "getWlcStatus error: " + e.getMessage());
            return -1;
        }
    }

    // ── WLC charging enable/disable via customer_gpio gpio127 (1 = charging on, 0 = off) ──
    private static final String WLC_CHARGING_GPIO = "/sys/devices/platform/soc/soc:customer_gpio/gpio127";
    private static final String PREFS_WLC = "mdm_wlc";
    private static final String KEY_WLC_CHARGING = "wlc_charging_enabled";
    // Last value actually written to the gpio, so repeated/interleaved config frames don't
    // re-write (and flap) the line. null = not applied yet this process.
    private Boolean wlcLastApplied = null;
    private String kioskLastSig = null; // last applied "kiosk_enabled|kiosk_package"; gates re-apply

    /** Write the wireless-charging enable line. Requires sepolicy allowing system_app to
     *  write the customer_gpio sysfs node (added in debug-GMS). Returns true on success. */
    private boolean setWlcCharging(boolean enable) {
        try (java.io.FileWriter w = new java.io.FileWriter(WLC_CHARGING_GPIO)) {
            w.write(enable ? "1" : "0");
            w.flush();
            Log.i(TAG, "WLC charging set " + (enable ? "on" : "off"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "setWlcCharging error: " + e.getMessage());
            return false;
        }
    }

    /** Current WLC charging line: true = on (1), false = off (0); true if unreadable. */
    private boolean readWlcCharging() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(WLC_CHARGING_GPIO))) {
            String line = r.readLine();
            String v = line == null ? "" : line.replace("\0", "").trim();
            return !v.equals("0");
        } catch (Exception e) {
            return true;
        }
    }

    /** Re-apply the saved WLC charging setting — the sysfs line resets to on across a
     *  reboot, so a device left "charging off" must be re-disabled on boot. */
    private void applySavedWlcCharging() {
        if (!product.hasWlc()) return;
        boolean enabled = getSharedPreferences(PREFS_WLC, MODE_PRIVATE)
                .getBoolean(KEY_WLC_CHARGING, true);
        if (setWlcCharging(enabled)) wlcLastApplied = Boolean.valueOf(enabled);
    }

    // Reported when charging is ON but gpio27 never settles to WLC_SETTLE_AGREE consecutive
    // reads — the line is genuinely oscillating (observed in the field on hardware that should
    // hold steady), not just a single glitchy sample. Distinct from -1 (charging off / unreadable).
    private static final int WLC_STATUS_FLAPPING = 2;

    /** Read gpio27 until WLC_SETTLE_AGREE consecutive reads agree, so a single glitchy
     *  sample (or a mid-placement transition) doesn't flip the reported state. Returns
     *  1 (guest on pad) / 0 (vacant) on real consensus, WLC_STATUS_FLAPPING if valid reads
     *  came back but kept disagreeing, or -1 if every read was unreadable. Only meaningful
     *  while charging is ON — the caller ensures that. */
    private int readSettledWlc() {
        int stable = -1, agree = 0, reads = 0;
        boolean sawDisagreement = false;
        for (int i = 0; i < WLC_SETTLE_READS; i++) {
            reads++;
            int v = readWlcStatusUncached();
            if (v >= 0) {
                if (v == stable) {
                    if (++agree >= WLC_SETTLE_AGREE) {
                        Log.d(TAG, "readSettledWlc: settled=" + stable + " after " + reads
                                + " reads (agree=" + agree + ")");
                        return stable;
                    }
                } else {
                    if (stable >= 0) sawDisagreement = true; // a real flip, not just the first sample
                    stable = v;
                    agree = 1;
                }
            }
            try {
                Thread.sleep(WLC_SETTLE_STEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (sawDisagreement) {
            Log.w(TAG, "readSettledWlc: FLAPPING after " + reads + " reads (last=" + stable + ")");
            return WLC_STATUS_FLAPPING; // never reached consensus — line is unstable
        }
        Log.d(TAG, "readSettledWlc: best-effort=" + stable + " after " + reads
                + " reads (no consensus, no disagreement)");
        return stable; // best effort (may be -1 if every read was unreadable)
    }

    /** Charging-aware WLC classification (watcher thread only). Returns 1 = guest on pad,
     *  0 = vacant, WLC_STATUS_FLAPPING = line won't settle, -1 = unknown/not-available.
     *  gpio27 is a valid guest-detection line ONLY while charging is ON (gpio127=1); with
     *  charging OFF it free-runs and is meaningless. We deliberately do NOT pulse charging
     *  on to read it — that would deliver charge the operator disabled — so charging-off
     *  reports unknown (-1) and the dashboard shows "Not available". */
    private int classifyWlc() {
        if (readWlcCharging()) {           // gpio127 == 1: normally stable, but verify
            return readSettledWlc();
        }
        return -1;                         // charging OFF: line not readable, report unknown
    }

    /** Poll the Qi pad's guest-detection GPIO on a short cadence and push telemetry the
     *  instant it changes, so placing/removing a device on the pad is reflected within
     *  ~WLC_WATCH_MS instead of waiting out the telemetry cache + next check-in. Each tick
     *  also refreshes the wlc cache so the HTTP safety-net path sees a fresh value too. */
    private void startWlcWatcher() {
        wlcWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mdm-wlc-watch");
            t.setDaemon(true);
            return t;
        });
        wlcWatcher.scheduleWithFixedDelay(() -> {
            try {
                int w = classifyWlc();
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
                Log.e(TAG, "wlc watcher error: " + e.getMessage());
            }
        }, WLC_WATCH_MS, WLC_WATCH_MS, TimeUnit.MILLISECONDS);
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
        NotificationManager nm = getSystemService(NotificationManager.class);
        // Persistent status: silent, low-importance, no badge.
        NotificationChannel status = new NotificationChannel(
                CHANNEL_ID, "Device status", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Shows that this device is set up and protected.");
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        // Updates & app installs: gently alerting so a finished update can be noticed,
        // without the persistent status ever making a sound.
        NotificationChannel updates = new NotificationChannel(
                UPDATES_CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT);
        updates.setDescription("Progress and results for system updates and app installs.");
        nm.createNotificationChannel(updates);
    }

    private Notification buildNotification(String text) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AIO MDM")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notify_shield)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        // Keep the persistent status non-dismissible — setOngoing alone lets the user
        // swipe a foreground-service notification away on modern Android.
        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return n;
    }

    /**
     * Posts/updates the app download+install progress notification (id 1002, separate from
     * the persistent service notification). While ongoing it shows a progress bar —
     * determinate when {@code percent >= 0}, indeterminate otherwise; the final result is
     * posted with {@code ongoing=false} so the user can dismiss it. Thread-safe.
     */
    private void showInstallNotification(int notifId, String title, String text, int percent, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this, UPDATES_CHANNEL_ID)
                .setContentTitle(title != null && !title.isEmpty() ? title : "AIO MDM")
                .setContentText(text)
                .setSmallIcon(ongoing ? R.drawable.ic_notify_download
                                      : R.drawable.ic_notify_done)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) {
            if (percent >= 0) b.setProgress(100, percent, false);
            else b.setProgress(0, 0, true); // indeterminate (installing / unknown size)
        }
        getSystemService(NotificationManager.class).notify(notifId, b.build());
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
        if (wlcWatcher != null) wlcWatcher.shutdownNow();
        if (wifiScanExecutor != null) wifiScanExecutor.shutdownNow();
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
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) {}
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
