package com.aioapp.mdm;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.json.JSONObject;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Log.d(TAG, "onReceive: " + intent.getAction());

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed - starting MDM service");
            try {
                Intent serviceIntent = new Intent(context, MdmService.class);
                context.startForegroundService(serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MdmService: " + e.getMessage(), e);
            }

            // loadConfig + apply are both inside the try: on LOCKED_BOOT_COMPLETED (Direct Boot)
            // loadConfig now reads device-protected storage, but keep the whole block guarded so
            // any failure here can never abort the boot handler (which also starts the service).
            try {
                JSONObject savedConfig = KioskManager.loadConfig(context);
                if (savedConfig != null) {
                    DevicePolicyManager dpm = (DevicePolicyManager)
                            context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    ComponentName admin = new ComponentName(context, MdmAdminReceiver.class);
                    KioskManager.apply(context, dpm, admin, savedConfig);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply kiosk config on boot: " + e.getMessage(), e);
            }
        }
    }
}
