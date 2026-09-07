package com.aioapp.mdm;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mic capture gain (the codec's {@code TX_DEC0..7 Volume} mixer controls) as an admin
 * telemetry field.
 *
 * <p>Background: the trinket mic fix raised the capture DEC gain from the codec default
 * (84) to 102 by adding top-level defaults for {@code TX_DEC0..7 Volume} to
 * {@code /vendor/etc/mixer_paths_idp.xml}. Ops want to see on the device page which
 * units carry the fix ({@code 102}) and which don't ({@code 84}).
 *
 * <p>The app cannot read the live mixer itself: AOSP core policy has
 * {@code neverallow appdomain audio_device:chr_file { read write }} and this client is
 * {@code system_app} (an appdomain), so neither a JNI tinyalsa read nor exec'ing
 * {@code /system/bin/tinymix} can open {@code /dev/snd/controlC0} on any build variant.
 * Two sources are used instead, in preference order:
 * <ol>
 *   <li><b>live</b>: the {@code vendor.mdm.mic_gain} system property, written by the
 *       vendor {@code micgain_probe} daemon (Phase 2, firmware side) from the real mixer.
 *       Present only on firmware that ships the daemon and grants {@code system_app}
 *       read on the property.</li>
 *   <li><b>config</b>: the top-level {@code <ctl name="TX_DECn Volume">} defaults in the
 *       mixer-paths XML. Readable by every domain ({@code vendor_configs_file}), so it
 *       works on user builds and on old fleet firmware. The audio HAL applies these at
 *       init and audio_route restores them after every path teardown, so at idle the
 *       codec holds exactly these values. A channel with no top-level default is
 *       reported as the codec default {@link #CODEC_DEFAULT}.</li>
 * </ol>
 */
public final class MicGain {
    private static final String TAG = "MicGain";

    /** Number of TX decimator channels on the trinket codec (TX_DEC0..TX_DEC7). */
    public static final int CHANNELS = 8;
    /** Codec power-on default for TX_DECn Volume (0 dB) when the HAL sets nothing. */
    public static final int CODEC_DEFAULT = 84;

    /** Live value property, "v0,v1,...,v7" (or "error:<reason>"); set by micgain_probe. */
    public static final String PROP_LIVE = "vendor.mdm.mic_gain";
    /** Epoch seconds of the last live probe; set by micgain_probe. */
    public static final String PROP_LIVE_TS = "vendor.mdm.mic_gain_ts";
    /** init service name of the vendor probe daemon (restart = re-probe now). */
    public static final String PROBE_SERVICE = "vendor.micgain_probe";

    /** Mixer-paths files the primary HAL may load, most likely first. */
    private static final String[] MIXER_XMLS = {
            "/vendor/etc/mixer_paths_idp.xml",
            "/vendor/etc/mixer_paths.xml",
            "/vendor/etc/mixer_paths_qrd.xml",
    };

    private static final Pattern CTL_RE = Pattern.compile(
            "<ctl\\s+name=\"TX_DEC(\\d) Volume\"\\s+value=\"(-?\\d+)\"");
    private static final Pattern PATH_OPEN_RE = Pattern.compile("<path\\b");
    private static final Pattern PATH_CLOSE_RE = Pattern.compile("</path>");

    private MicGain() {}

    /** Parsed configured defaults; null entries = no top-level default for that channel. */
    static final class Config {
        final String file;
        final Integer[] configured = new Integer[CHANNELS];
        Config(String file) { this.file = file; }
        boolean anyConfigured() {
            for (Integer v : configured) if (v != null) return true;
            return false;
        }
    }

    // Vendor config is read-only and only changes with an OTA (which reboots), so one
    // parse per process is enough.
    private static volatile Config cachedConfig;
    private static volatile boolean configResolved;

    /**
     * Builds the telemetry object, or returns null when the device exposes neither source
     * (no mixer XML at all — not a trinket audio platform), so the field is omitted rather
     * than reported as a bogus default.
     *
     * <pre>
     * {"tx_dec":[102,102,102,102,102,102,102,102],
     *  "source":"live"|"config",
     *  "configured":true,          // config: at least one top-level default present
     *  "file":"mixer_paths_idp.xml",
     *  "ts":1757000000}            // live only: probe epoch seconds
     * </pre>
     */
    public static JSONObject snapshot() {
        try {
            JSONObject live = readLive();
            if (live != null) return live;
            Config cfg = config();
            if (cfg == null) return null;
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < CHANNELS; i++) {
                Integer v = cfg.configured[i];
                arr.put(v != null ? v.intValue() : CODEC_DEFAULT);
            }
            o.put("tx_dec", arr);
            o.put("source", "config");
            o.put("configured", cfg.anyConfigured());
            o.put("file", new File(cfg.file).getName());
            return o;
        } catch (JSONException e) {
            Log.w(TAG, "snapshot failed: " + e.getMessage());
            return null;
        }
    }

    /** True when the firmware ships the probe daemon (its value prop is populated). */
    public static boolean hasLiveSource() {
        return !SystemPropertiesProxy.get(PROP_LIVE, "").trim().isEmpty();
    }

    /**
     * Asks the vendor probe daemon for a fresh mixer read by restarting its service via
     * {@code ctl.restart}. {@code ctl.} props map to {@code ctl_default_prop}, which core
     * policy lets {@code system_app} set (unlike any vendor.* prop, which a coredomain
     * neverallow forbids). Blocks up to {@code waitMs} for the timestamp prop to advance.
     * No-op (returns false) on firmware without the daemon.
     */
    public static boolean refreshLive(long waitMs) {
        if (!hasLiveSource()) return false;
        String before = SystemPropertiesProxy.get(PROP_LIVE_TS, "");
        if (!SystemPropertiesProxy.set("ctl.restart", PROBE_SERVICE)) return false;
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            String now = SystemPropertiesProxy.get(PROP_LIVE_TS, "");
            if (!now.isEmpty() && !now.equals(before)) return true;
        }
        Log.w(TAG, "live refresh: " + PROP_LIVE_TS + " did not advance within " + waitMs + "ms");
        return false;
    }

    /** Live values from the vendor probe daemon, or null when absent/unreadable/errored. */
    private static JSONObject readLive() throws JSONException {
        String raw = SystemPropertiesProxy.get(PROP_LIVE, "").trim();
        if (raw.isEmpty()) return null;
        if (raw.startsWith("error:")) {
            Log.w(TAG, "live probe reported " + raw + "; falling back to config");
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != CHANNELS) {
            Log.w(TAG, "unexpected live value '" + raw + "'; falling back to config");
            return null;
        }
        JSONArray arr = new JSONArray();
        for (String p : parts) {
            try {
                arr.put(Integer.parseInt(p.trim()));
            } catch (NumberFormatException e) {
                Log.w(TAG, "unparseable live value '" + raw + "'; falling back to config");
                return null;
            }
        }
        JSONObject o = new JSONObject();
        o.put("tx_dec", arr);
        o.put("source", "live");
        String ts = SystemPropertiesProxy.get(PROP_LIVE_TS, "").trim();
        if (!ts.isEmpty()) {
            try { o.put("ts", Long.parseLong(ts)); } catch (NumberFormatException ignored) {}
        }
        return o;
    }

    /** Cached parse of the first mixer-paths XML that exists; null if none does. */
    static Config config() {
        if (configResolved) return cachedConfig;
        synchronized (MicGain.class) {
            if (!configResolved) {
                Config c = null;
                for (String path : MIXER_XMLS) {
                    File f = new File(path);
                    if (!f.isFile()) continue;
                    c = parse(f);
                    if (c != null) break;
                }
                if (c == null) Log.i(TAG, "no readable mixer_paths xml; mic_gain omitted");
                else Log.i(TAG, "mic gain config from " + c.file + ": " + describe(c));
                cachedConfig = c;
                configResolved = true;
            }
        }
        return cachedConfig;
    }

    /** Drops the cached parse so the next snapshot() re-reads the XML (e.g. tests). */
    static void invalidate() {
        synchronized (MicGain.class) {
            configResolved = false;
            cachedConfig = null;
        }
    }

    /**
     * Collects {@code TX_DECn Volume} ctl values that sit OUTSIDE any {@code <path>} block.
     * Path-scoped entries (e.g. the capture path's 102s or the old headset-mic 84) are
     * transient — applied only while that path is active — so they don't describe the
     * idle codec state. Line-based scan: the XML is one element per line.
     */
    static Config parse(File f) {
        Config c = new Config(f.getPath());
        int depth = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher open = PATH_OPEN_RE.matcher(line);
                while (open.find()) depth++;
                if (depth == 0) {
                    Matcher m = CTL_RE.matcher(line);
                    if (m.find()) {
                        int ch = Integer.parseInt(m.group(1));
                        if (ch < CHANNELS) c.configured[ch] = Integer.valueOf(m.group(2));
                    }
                }
                Matcher close = PATH_CLOSE_RE.matcher(line);
                while (close.find()) depth = Math.max(0, depth - 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + f + ": " + e.getMessage());
            return null;
        }
        return c;
    }

    private static String describe(Config c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHANNELS; i++) {
            if (i > 0) sb.append(',');
            sb.append(c.configured[i] != null ? c.configured[i].toString() : "-");
        }
        return sb.toString();
    }
}
