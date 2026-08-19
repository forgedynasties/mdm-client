package com.aioapp.mdm;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONObject;

import java.util.List;

public class KioskManager {
    private static final String TAG = "KioskManager";
    private static final String PREFS_NAME = "mdm_kiosk";
    private static final String KEY_CONFIG = "kiosk_config";

    /** Apply kiosk config and persist it so it survives reboots. */
    public static void applyAndSave(Context ctx, DevicePolicyManager dpm,
                                    ComponentName admin, JSONObject config) {
        saveConfig(ctx, config);
        apply(ctx, dpm, admin, config);
    }

    /** Apply kiosk config without persisting (e.g. on boot from saved prefs). */
    public static void apply(Context ctx, DevicePolicyManager dpm,
                             ComponentName admin, JSONObject config) {
        try {
            boolean enabled = config.optBoolean("kiosk_enabled", false);
            String pkg = config.optString("kiosk_package", "");

            if (enabled && !pkg.isEmpty()) {
                // A technician exited kiosk offline (TOTP): honour that local suspension and do
                // NOT re-lock, even though the server config still says kiosk_enabled. The flag is
                // cleared on reboot (BootReceiver) — reboot re-locks — or when the server pushes
                // kiosk_enabled=false (the else branch below), so an admin can re-lock via toggle.
                if (KioskExit.isSuspended(ctx)) {
                    Log.i(TAG, "Kiosk suspended by offline exit — not re-locking");
                    return;
                }

                // Verify the package is installed and launchable BEFORE imposing lock-task.
                // Previously the policy was set (and home/status bar hidden) first, then the
                // launch intent checked — so a missing/typo'd/uninstalled kiosk package trapped
                // the device on a package it couldn't launch, with no local way out (only a
                // server kiosk_enabled=false could recover it, and this config is re-applied on
                // every boot → persistent brick). Bail out and clear lock-task if not launchable.
                Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) {
                    Log.w(TAG, "Kiosk package not installed/launchable, refusing lock-task: " + pkg);
                    stopSystemLockTask();
                    dpm.setLockTaskPackages(admin, new String[]{});
                    return;
                }

                // 1. Whitelist the kiosk package AND mdm-client itself, so the offline-exit
                //    UnlockActivity can surface over the locked kiosk app.
                dpm.setLockTaskPackages(admin, new String[]{pkg, ctx.getPackageName()});

                // 2. LOCK_TASK_FEATURE_NONE hides home, overview, notifications,
                //    status bar, and global actions. The back button ALWAYS remains
                //    visible in lock task mode -- it cannot be hidden.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);

                Settings.Global.putString(ctx.getContentResolver(),
                        Settings.Global.POLICY_CONTROL, "");

                // 3. Enter REAL device-owner lock-task (LOCK_TASK_MODE_LOCKED — the mode
                //    that hides the nav bar). The only reliable way in is
                //    ActivityOptions.setLockTaskEnabled(true) on a FRESHLY CREATED activity.
                //    startSystemLockTaskMode(taskId) on an already-running task was tried and
                //    it enters screen-pinning (LOCK_TASK_MODE_PINNED) even for an allowlisted
                //    device owner — the nav bar stays visible (reproduced on the T7). So:
                //      - already properly LOCKED on the kiosk app -> nothing to do
                //      - not running                             -> launch fresh -> LOCKED
                //      - running (unlocked) or stuck PINNED       -> relaunch with CLEAR_TASK
                //        so the activity is recreated and actually enters LOCKED
                if (isProperlyLocked(ctx) && pkg.equals(foregroundPackage(ctx))) {
                    Log.i(TAG, "Kiosk already LOCKED on " + pkg);
                } else {
                    if (lockState(ctx) == ActivityManager.LOCK_TASK_MODE_PINNED) {
                        stopSystemLockTask(); // leave screen-pinning before relaunching into LOCKED
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    boolean running = findRunningTaskId(ctx, pkg) >= 0;
                    if (running) {
                        // A live task won't re-create its activity on a plain relaunch, so
                        // setLockTaskEnabled would be a no-op — clear it to force a fresh start.
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    }
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLockTaskEnabled(true);
                    ctx.startActivity(intent, options.toBundle());
                    Log.i(TAG, "Kiosk: launched " + pkg + " into lock task (clearTask=" + running + ")");
                }
                Log.i(TAG, "Kiosk enabled: pkg=" + pkg);
            } else {
                // Remove the package from the allowlist FIRST (that is what authorises the
                // system to drop the task), then stop lock-task.
                dpm.setLockTaskPackages(admin, new String[]{});
                stopSystemLockTask();
                Settings.Global.putString(ctx.getContentResolver(),
                        Settings.Global.POLICY_CONTROL, "");
                // Server disabled kiosk — clear any local offline-exit suspension so a later
                // re-enable (or a reboot) locks cleanly from a known state.
                KioskExit.setSuspended(ctx, false);
                // Only touch the foreground if the device is STILL stuck in a LOCKED task
                // (on this ROM, clearing the allowlist + stopSystemLockTaskMode doesn't always
                // drop an active device-owner lock). When it's already unlocked, kiosk-off must
                // NOT force HOME: the user is free to launch any app, and a blind goHome here
                // bounced them out of whatever they opened every time config re-applied.
                if (isLocked(ctx)) {
                    goHome(ctx);
                    if (isLocked(ctx)) {
                        stopSystemLockTask();
                        dpm.setLockTaskPackages(admin, new String[]{});
                        goHome(ctx);
                    }
                }
                Log.i(TAG, "Kiosk disabled (stillLocked=" + isLocked(ctx) + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "apply error: " + e.getMessage());
        }
    }

    /**
     * Leave lock-task locally after a verified offline exit. The saved kiosk config is left
     * intact (still enabled); a local suspension flag prevents re-lock until reboot or a
     * server kiosk toggle. Sends the user to the launcher so the device isn't stuck on the
     * now-unlocked kiosk app.
     */
    public static void suspendLocally(Context ctx, DevicePolicyManager dpm, ComponentName admin) {
        stopSystemLockTask();
        try {
            dpm.setLockTaskPackages(admin, new String[]{});
        } catch (Exception e) {
            Log.e(TAG, "suspendLocally clear packages error: " + e.getMessage());
        }
        KioskExit.markExited(ctx);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(home);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "Kiosk suspended locally (offline exit)");
    }

    /** Raw lock-task state: LOCK_TASK_MODE_NONE / _PINNED / _LOCKED. */
    public static int lockState(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) return am.getLockTaskModeState();
        } catch (Exception e) {
            Log.e(TAG, "lockState error: " + e.getMessage());
        }
        return ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /** True when in ANY lock-task mode (LOCKED or PINNED). */
    public static boolean isLocked(Context ctx) {
        return lockState(ctx) != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /** True ONLY for real device-owner lock (LOCK_TASK_MODE_LOCKED), which hides the nav
     *  bar. PINNED (screen pinning) is deliberately NOT counted — it leaves the nav bar
     *  visible, so the watchdog must treat it as "not locked" and re-assert. */
    public static boolean isProperlyLocked(Context ctx) {
        return lockState(ctx) == ActivityManager.LOCK_TASK_MODE_LOCKED;
    }

    /** Package of the current foreground/top task, or "" if it can't be read.
     *  Used to decide the warm-vs-cold entry path and by the MdmService watchdog. */
    public static String foregroundPackage(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "";
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo t = tasks.get(0);
                if (t.topActivity != null) return t.topActivity.getPackageName();
                if (t.baseActivity != null) return t.baseActivity.getPackageName();
            }
        } catch (Exception e) {
            Log.e(TAG, "foregroundPackage error: " + e.getMessage());
        }
        return "";
    }

    /** Task id of a running task rooted at pkg, or -1 if none. Privileged/system app
     *  (device owner) so getRunningTasks returns the real task list. */
    private static int findRunningTaskId(Context ctx, String pkg) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            for (ActivityManager.RunningTaskInfo t : am.getRunningTasks(20)) {
                String p = t.topActivity != null ? t.topActivity.getPackageName()
                        : (t.baseActivity != null ? t.baseActivity.getPackageName() : null);
                if (pkg.equals(p)) return t.taskId;
            }
        } catch (Exception e) {
            Log.e(TAG, "findRunningTaskId error: " + e.getMessage());
        }
        return -1;
    }

    /** Send the device to the launcher (used when leaving kiosk, so it isn't stranded on
     *  the now-unlocked kiosk app). */
    private static void goHome(Context ctx) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(home);
        } catch (Exception e) {
            Log.e(TAG, "goHome error: " + e.getMessage());
        }
    }

    private static void stopSystemLockTask() {
        try {
            ActivityTaskManager.getService().stopSystemLockTaskMode();
        } catch (Exception e) {
            Log.e(TAG, "stopSystemLockTask error: " + e.getMessage());
        }
    }

    /**
     * Kiosk config must be readable during Direct Boot (before the user unlocks) so lock-task
     * is re-applied on the LOCKED_BOOT_COMPLETED path — reading credential-encrypted prefs there
     * throws and left headless devices out of kiosk after every reboot. Use device-protected
     * storage and migrate any prefs written by the old (credential-encrypted) build once.
     */
    private static Context prefsContext(Context ctx) {
        Context de = ctx.createDeviceProtectedStorageContext();
        de.moveSharedPreferencesFrom(ctx, PREFS_NAME); // one-time CE->DE migration; no-op afterward
        return de;
    }

    public static void saveConfig(Context ctx, JSONObject config) {
        prefsContext(ctx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, config.toString())
                .apply();
    }

    /** Returns the last saved config, or null if none has been received yet. */
    public static JSONObject loadConfig(Context ctx) {
        String json = prefsContext(ctx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CONFIG, null);
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "loadConfig parse error: " + e.getMessage());
            return null;
        }
    }
}
