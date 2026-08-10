package com.aioapp.mdm;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The offline kiosk-exit prompt. Reached only via {@link KioskExitReceiver} (long-press
 * Back in the nav bar, sent by SystemUI). Asks for the rotating TOTP code; on success it
 * drops the device out of lock-task locally — no server needed.
 *
 * mdm-client is whitelisted for lock-task alongside the kiosk package, so this activity
 * can surface over the locked kiosk app.
 */
public class UnlockActivity extends Activity {
    private static final String TAG = "UnlockActivity";

    private EditText codeInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Show above the kiosk app and when the screen is locked.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!KioskExit.isEnabled(this)) {
            Log.w(TAG, "offline exit not enabled/provisioned; dismissing");
            finish();
            return;
        }

        setContentView(buildView());
    }

    private View buildView() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Exit kiosk mode");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#111111"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Enter the current unlock code from your administrator.");
        sub.setTextColor(Color.parseColor("#555555"));
        sub.setPadding(0, dp(8), 0, dp(16));
        root.addView(sub);

        codeInput = new EditText(this);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setHint("6-digit code");
        codeInput.setTextSize(22);
        codeInput.setGravity(Gravity.CENTER);
        root.addView(codeInput);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#c0392b"));
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(16), 0, 0);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        btnRow.addView(cancel);

        Button exit = new Button(this);
        exit.setText("Exit kiosk");
        exit.setOnClickListener(v -> attempt());
        btnRow.addView(exit);

        root.addView(btnRow);
        return root;
    }

    private void attempt() {
        if (KioskExit.isLockedOut(this)) {
            status.setText("Too many attempts. Wait "
                    + (KioskExit.lockoutRemainingMs(this) / 1000 + 1) + "s.");
            return;
        }
        String code = codeInput.getText().toString().trim();
        if (!KioskExit.verify(this, code)) {
            codeInput.setText("");
            status.setText(KioskExit.isLockedOut(this)
                    ? "Locked out for " + (KioskExit.lockoutRemainingMs(this) / 1000 + 1) + "s."
                    : "Incorrect code.");
            return;
        }
        // Correct code: leave lock-task locally and record the event for the server.
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, MdmAdminReceiver.class);
            KioskManager.suspendLocally(this, dpm, admin);
        } catch (Exception e) {
            Log.e(TAG, "suspendLocally error: " + e.getMessage());
        }
        Toast.makeText(this, "Kiosk mode exited", Toast.LENGTH_LONG).show();
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
