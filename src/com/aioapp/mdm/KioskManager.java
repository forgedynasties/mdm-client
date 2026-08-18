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

                // 3. Enter REAL lock-task. Two paths, because ActivityOptions
                //    .setLockTaskEnabled(true) ONLY takes effect when the activity is
                //    freshly (re)created: if the kiosk app is already the resumed
                //    foreground task, startActivity is a bring-to-front no-op and
                //    lock-task is NEVER entered (the DPM package is whitelisted but
                //    nothing pins the task, so home/recents stay live). Reproduced on
                //    a T7: cold enable -> LOCKED, warm enable (app already foreground)
                //    -> stuck NONE indefinitely.
                //
                //    Warm path: whenever a task for the kiosk app already exists (whether
                //    foreground OR backgrounded to the launcher), force it into lock-task
                //    in place with startSystemLockTaskMode(taskId) -- it also brings the
                //    task to front. For a device owner whose package is in the lock-task
                //    allowlist (set above) this enters full LOCKED mode; the "screen
                //    pinning / unpin toast" caveat applies only to NON-allowlisted
                //    packages, which is not our case.
                int taskId = findRunningTaskId(ctx, pkg);
                boolean entered = false;
                if (taskId >= 0) {
                    entered = forceSystemLockTask(taskId);
                    if (entered) {
                        Log.i(TAG, "Kiosk: forced running task " + taskId + " into lock task: " + pkg);
                    }
                }

                //    Cold path (app not running/foreground, or the force call failed):
                //    launch it fresh with the lock-task option so the new activity
                //    enters lock-task on creation.
                if (!entered) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLockTaskEnabled(true);
                    ctx.startActivity(intent, options.toBundle());
                    Log.i(TAG, "Kiosk: cold-launched " + pkg + " in lock task mode");
                }

                // 4. Verify + escalate (only meaningful for the synchronous warm path;
                //    a cold startActivity is async, so the watchdog re-checks it next
                //    cycle). If we forced a running task but it still is not LOCKED,
                //    relaunch clearing the task so a clean activity enters lock-task.
                if (entered && !isLocked(ctx)) {
                    Log.w(TAG, "Kiosk: force-lock did not stick, relaunching " + pkg + " (clear task)");
                    Intent hard = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (hard != null) {
                        hard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        ActivityOptions o2 = ActivityOptions.makeBasic();
                        o2.setLockTaskEnabled(true);
                        ctx.startActivity(hard, o2.toBundle());
                    }
                }
                Log.i(TAG, "Kiosk enabled: pkg=" + pkg);
            } else {
                stopSystemLockTask();
                dpm.setLockTaskPackages(admin, new String[]{});
                Settings.Global.putString(ctx.getContentResolver(),
                        Settings.Global.POLICY_CONTROL, "");
                // Server disabled kiosk — clear any local offline-exit suspension so a later
                // re-enable (or a reboot) locks cleanly from a known state.
                KioskExit.setSuspended(ctx, false);
                Log.i(TAG, "Kiosk disabled");
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

    /** True when the device is currently in lock-task (kiosk) mode. */
    public static boolean isLocked(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
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

    /** Force an already-running task into REAL lock-task mode in place (no relaunch,
     *  no app-state loss). Partner of stopSystemLockTaskMode() used below. Returns
     *  false if the framework call throws. */
    private static boolean forceSystemLockTask(int taskId) {
        try {
            ActivityTaskManager.getService().startSystemLockTaskMode(taskId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "forceSystemLockTask error: " + e.getMessage());
            return false;
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
