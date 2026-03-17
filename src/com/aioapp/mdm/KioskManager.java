package com.aioapp.mdm;

import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONObject;

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
                // 1. Whitelist the package for lock task mode
                dpm.setLockTaskPackages(admin, new String[]{pkg});

                // 2. LOCK_TASK_FEATURE_NONE hides home, overview, notifications,
                //    status bar, and global actions. The back button ALWAYS remains
                //    visible in lock task mode -- it cannot be hidden.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);

                Settings.Global.putString(ctx.getContentResolver(),
                        Settings.Global.POLICY_CONTROL, "");

                // 3. Use ActivityOptions.setLockTaskEnabled(true) to launch the
                //    third-party app directly into REAL lock task mode (API 28+).
                //    This is the correct approach for device owners. Do NOT use
                //    ActivityTaskManager.startSystemLockTaskMode() -- that triggers
                //    screen pinning (weaker mode with "unpin" toast).
                Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLockTaskEnabled(true);
                    ctx.startActivity(intent, options.toBundle());
                    Log.i(TAG, "Launched " + pkg + " in lock task mode");
                } else {
                    Log.w(TAG, "No launch intent for kiosk package: " + pkg);
                }
                Log.i(TAG, "Kiosk enabled: pkg=" + pkg);
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
