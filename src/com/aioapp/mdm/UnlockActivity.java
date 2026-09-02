package com.aioapp.mdm;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The offline kiosk-exit prompt: a technician enters the current rotating code to leave
 * kiosk lock mode with no server connection. Unused — the nav-bar gesture (long-press Back
 * + Power) exits directly via {@link MdmService#armKioskExit}, no code entry. Deliberately
 * unbranded.
 */
public class UnlockActivity extends Activity {
    private static final String TAG = "UnlockActivity";

    // Palette (self-contained; no theme dependency).
    private static final int C_BG      = Color.parseColor("#FFFFFF");
    private static final int C_TITLE   = Color.parseColor("#0F172A");
    private static final int C_SUB     = Color.parseColor("#64748B");
    private static final int C_FIELD   = Color.parseColor("#F1F5F9");
    private static final int C_FIELDTX = Color.parseColor("#0F172A");
    private static final int C_ACCENT  = Color.parseColor("#2563EB");
    private static final int C_ERR     = Color.parseColor("#DC2626");

    private EditText codeInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(""); // suppress the app label in the window title
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        if (!KioskExit.isEnabled(this)) {
            Log.w(TAG, "offline exit not enabled/provisioned; dismissing");
            finish();
            return;
        }
        setContentView(buildView());
    }

    private View buildView() {
        // Outer transparent wrapper centres the card.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(20), dp(20), dp(20), dp(20));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(26);
        card.setPadding(p, p, p, dp(20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_BG);
        cardBg.setCornerRadius(dp(22));
        card.setBackground(cardBg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(320),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        // Lock glyph in an accent-tinted circle.
        TextView glyph = new TextView(this);
        glyph.setText("🔒");
        glyph.setTextSize(22);
        glyph.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#E8EEFF"));
        glyph.setBackground(circle);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        glyph.setLayoutParams(glyphLp);
        card.addView(glyph);

        TextView title = new TextView(this);
        title.setText("Exit kiosk mode");
        title.setTextColor(C_TITLE);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(14), 0, 0);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Enter the current unlock code to leave kiosk.");
        sub.setTextColor(C_SUB);
        sub.setTextSize(13.5f);
        sub.setPadding(0, dp(4), 0, dp(18));
        card.addView(sub);

        codeInput = new EditText(this);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        codeInput.setHint("• • • • • •");
        codeInput.setHintTextColor(Color.parseColor("#94A3B8"));
        codeInput.setTextColor(C_FIELDTX);
        codeInput.setTextSize(26);
        codeInput.setLetterSpacing(0.35f);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(C_FIELD);
        fieldBg.setCornerRadius(dp(12));
        codeInput.setBackground(fieldBg);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { attempt(); return true; }
            return false;
        });
        card.addView(codeInput);

        status = new TextView(this);
        status.setTextColor(C_ERR);
        status.setTextSize(12.5f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, 0);
        card.addView(status);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(18), 0, 0);

        TextView cancel = pillButton("Cancel", Color.TRANSPARENT, C_SUB);
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        cLp.rightMargin = dp(8);
        cancel.setLayoutParams(cLp);
        btnRow.addView(cancel);

        TextView exit = pillButton("Exit kiosk", C_ACCENT, Color.WHITE);
        exit.setOnClickListener(v -> attempt());
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(0, dp(48), 1.4f);
        exit.setLayoutParams(eLp);
        btnRow.addView(exit);

        card.addView(btnRow);
        outer.addView(card);
        return outer;
    }

    private TextView pillButton(String text, int bg, int fg) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(12));
        if (bg == Color.TRANSPARENT) {
            d.setStroke(dp(1), Color.parseColor("#E2E8F0"));
        }
        b.setBackground(d);
        return b;
    }

    private void attempt() {
        if (KioskExit.isLockedOut(this)) {
            status.setText("Too many attempts — wait "
                    + (KioskExit.lockoutRemainingMs(this) / 1000 + 1) + "s.");
            return;
        }
        String code = codeInput.getText().toString().trim();
        if (!KioskExit.verify(this, code)) {
            codeInput.setText("");
            status.setText(KioskExit.isLockedOut(this)
                    ? "Locked for " + (KioskExit.lockoutRemainingMs(this) / 1000 + 1) + "s."
                    : "Incorrect code. Try again.");
            return;
        }
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
