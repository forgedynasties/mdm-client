package com.aioapp.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Bridges the SystemUI nav-bar gesture (long-press Back) to the kiosk-exit flow.
 * SystemUI broadcasts {@link #ACTION} — guarded by a signature permission so only
 * platform-signed components (SystemUI is one) can trigger it.
 *
 * Exit strategy: the long-press-Back gesture ARMS an exit; the technician then
 * confirms by pressing the Power button (screen-off) within a short window. The
 * arming + Power watch + exit all live in {@link MdmService} (a long-lived
 * foreground service that can hold the timer and receive the screen-off broadcast),
 * so this receiver just forwards the arm request.
 *
 * The TOTP unlock code ({@link Totp} / {@link UnlockActivity} / {@link KioskExit})
 * is retained in the tree but no longer used to gate the exit.
 */
public class KioskExitReceiver extends BroadcastReceiver {
    private static final String TAG = "KioskExitReceiver";
    public static final String ACTION = "com.aioapp.mdm.action.KIOSK_EXIT_PROMPT";
    public static final String PERMISSION = "com.aioapp.mdm.permission.KIOSK_EXIT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        try {
            Intent i = new Intent(context, MdmService.class);
            i.setAction(MdmService.ACTION_KIOSK_EXIT_ARM);
            context.startForegroundService(i);
        } catch (Exception e) {
            Log.e(TAG, "failed to arm kiosk exit: " + e.getMessage());
        }
    }
}
