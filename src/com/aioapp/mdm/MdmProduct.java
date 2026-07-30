package com.aioapp.mdm;

import android.util.Log;

/**
 * MdmProduct is the client-side catalog of hardware product categories and their
 * capabilities. It mirrors the server's internal/product package so both ends agree
 * on what a product key means.
 *
 * <p>Historically this client only ran on the T7 tablet (battery + charger + wireless
 * charging guest pad) and unconditionally sampled all of that. The Kiosk 18/22/27
 * variants are wall-powered panels with no battery, no charger, and no Qi pad, so
 * reading those signals is meaningless (a bogus 0% battery, a wlc GPIO that doesn't
 * exist). Resolving the product once at startup lets the collection code sample only
 * the hardware a given device actually has, and lets it report its category to the
 * server for grouping/filtering and capability-aware UI.
 *
 * <p>Resolution order (first hit wins):
 * <ol>
 *   <li>{@code persist.sys.mdm.product} — explicit override, settable without an OTA
 *       via {@code setprop persist.sys.mdm.product kiosk27}. Authoritative.</li>
 *   <li>Heuristic over {@code ro.product.*} / {@code Build.MODEL} — "kiosk"/"18"/"22"/
 *       "27" markers map to the matching kiosk variant.</li>
 *   <li>Default {@link #KEY_T7} — the pre-existing fleet reports no product, so the
 *       safe fallback keeps today's behaviour (all capabilities on).</li>
 * </ol>
 */
public final class MdmProduct {
    private static final String TAG = "MdmProduct";

    public static final String KEY_T7      = "t7";
    public static final String KEY_KIOSK18 = "kiosk18";
    public static final String KEY_KIOSK22 = "kiosk22";
    public static final String KEY_KIOSK27 = "kiosk27";
    public static final String DEFAULT_KEY = KEY_T7;

    private final String key;
    private final boolean hasBattery;
    private final boolean hasCharging;
    private final boolean hasWlc;

    private MdmProduct(String key, boolean hasBattery, boolean hasCharging, boolean hasWlc) {
        this.key = key;
        this.hasBattery = hasBattery;
        this.hasCharging = hasCharging;
        this.hasWlc = hasWlc;
    }

    /** The canonical product key sent to the server (e.g. "t7", "kiosk27"). */
    public String key() { return key; }

    /** True when the device runs on an internal battery (battery %, temperature meaningful). */
    public boolean hasBattery() { return hasBattery; }

    /** True when the device has a charger input (charging state, charger voltage/type meaningful). */
    public boolean hasCharging() { return hasCharging; }

    /** True when the device has a wireless-charging guest pad (wlc_status meaningful). */
    public boolean hasWlc() { return hasWlc; }

    /** Returns the declared capability set for a product key, defaulting to T7. */
    private static MdmProduct forKey(String key) {
        if (key == null) key = "";
        switch (key.toLowerCase().trim()) {
            case KEY_KIOSK18: return new MdmProduct(KEY_KIOSK18, false, false, false);
            case KEY_KIOSK22: return new MdmProduct(KEY_KIOSK22, false, false, false);
            case KEY_KIOSK27: return new MdmProduct(KEY_KIOSK27, false, false, false);
            case KEY_T7:      return new MdmProduct(KEY_T7, true, true, true);
            default:          return new MdmProduct(DEFAULT_KEY, true, true, true);
        }
    }

    /**
     * Resolves this device's product once, following the resolution order above. Cheap
     * enough to call at startup; callers should cache the result for the process.
     */
    public static MdmProduct detect() {
        String override = SystemPropertiesProxy.get("persist.sys.mdm.product", "").trim();
        if (!override.isEmpty()) {
            Log.i(TAG, "product from persist.sys.mdm.product=" + override);
            return forKey(override);
        }
        String heuristic = heuristicKey();
        if (heuristic != null) {
            Log.i(TAG, "product inferred as " + heuristic);
            return forKey(heuristic);
        }
        Log.i(TAG, "product defaulting to " + DEFAULT_KEY);
        return forKey(DEFAULT_KEY);
    }

    /**
     * Best-effort inference from build identifiers. Returns null when nothing matches so
     * detect() falls through to the default rather than guessing wrong.
     */
    private static String heuristicKey() {
        String hay = (SystemPropertiesProxy.get("ro.product.device", "") + " "
                + SystemPropertiesProxy.get("ro.product.name", "") + " "
                + SystemPropertiesProxy.get("ro.product.model", "") + " "
                + android.os.Build.MODEL).toLowerCase();
        if (hay.contains("kiosk")) {
            if (hay.contains("27")) return KEY_KIOSK27;
            if (hay.contains("22")) return KEY_KIOSK22;
            if (hay.contains("18")) return KEY_KIOSK18;
            return KEY_KIOSK27; // a kiosk of unknown size — still capability-correct (no wlc/charging)
        }
        if (hay.contains("t7")) return KEY_T7;
        return null;
    }
}
