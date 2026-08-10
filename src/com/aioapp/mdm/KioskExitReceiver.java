package com.aioapp.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Bridges the SystemUI nav-bar gesture (long-press Back) to the offline-exit prompt.
 * SystemUI broadcasts {@link #ACTION} — guarded by a signature permission so only
 * platform-signed components (SystemUI is one) can trigger it — and we launch the
 * {@link UnlockActivity}. No-op unless offline exit is provisioned, so the gesture is
 * inert on devices that don't have the feature.
 */
public class KioskExitReceiver extends BroadcastReceiver {
    private static final String TAG = "KioskExitReceiver";
    public static final String ACTION = "com.aioapp.mdm.action.KIOSK_EXIT_PROMPT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (!KioskExit.isEnabled(context)) {
            Log.d(TAG, "offline exit not provisioned; ignoring gesture");
            return;
        }
        try {
            Intent i = new Intent(context, UnlockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "failed to launch UnlockActivity: " + e.getMessage());
        }
    }
}
