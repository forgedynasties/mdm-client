package com.aioapp.mdm;

import android.app.ActivityManager;
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
            int features = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;

            if (enabled && !pkg.isEmpty()) {
                dpm.setLockTaskPackages(admin, new String[]{pkg});
                dpm.setLockTaskFeatures(admin, features);

                // Clear any immersive policy so the navigation bar is visible.
                // Lock task with LOCK_TASK_FEATURE_NONE already hides home & recents,
                // leaving only the back button shown.
                Settings.Global.putString(ctx.getContentResolver(),
                        Settings.Global.POLICY_CONTROL, "");

                Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    pinTaskAfterLaunch(ctx, pkg);
                } else {
                    Log.w(TAG, "No launch intent for kiosk package: " + pkg);
                }
                Log.i(TAG, "Kiosk enabled: pkg=" + pkg + " features=" + features);
            } else {
                stopSystemLockTask();
                dpm.setLockTaskPackages(admin, new String[]{});
                Settings.Global.putString(ctx.getContentResolver(),
                        Settings.Global.POLICY_CONTROL, "");
                Log.i(TAG, "Kiosk disabled");
            }
        } catch (Exception e) {
            Log.e(TAG, "apply error: " + e.getMessage());
        }
    }

    /**
     * Polls the running task list until the kiosk package appears, then pins it
     * via the system-side lock task API. Retries for up to 5 seconds to handle
     * slow app launches.
     */
    private static void pinTaskAfterLaunch(Context ctx, String pkg) {
        new Thread(() -> {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(500);
                    List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(20);
                    for (ActivityManager.RunningTaskInfo task : tasks) {
                        if (task.topActivity != null
                                && pkg.equals(task.topActivity.getPackageName())) {
                            ActivityTaskManager.getService().startSystemLockTaskMode(task.taskId);
                            Log.i(TAG, "Lock task started: taskId=" + task.taskId);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "pinTaskAfterLaunch error: " + e.getMessage());
                    return;
                }
            }
            Log.w(TAG, "Task not found after retries for pkg=" + pkg);
        }).start();
    }

    private static void stopSystemLockTask() {
        try {
            ActivityTaskManager.getService().stopSystemLockTaskMode();
        } catch (Exception e) {
            Log.e(TAG, "stopSystemLockTask error: " + e.getMessage());
        }
    }

    public static void saveConfig(Context ctx, JSONObject config) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, config.toString())
                .apply();
    }

    /** Returns the last saved config, or null if none has been received yet. */
    public static JSONObject loadConfig(Context ctx) {
        String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
