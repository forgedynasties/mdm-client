package com.aioapp.mdm;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Replaces the boot logo (splash) partition at runtime.
 *
 * mdm-client runs as the {@code system_app} SELinux domain (sharedUserId
 * android.uid.system + platform cert), which can never write a raw block
 * device — {@code neverallow appdomain dev_type:blk_file}. So this class only
 * acts as transport + trigger: it stages an image and pokes a property; an
 * init-launched broker in its own SELinux domain does the partition write.
 *
 * Contract (see splash-update-mechanism.md):
 *   1. Stage  → write image to {@link #SPLASH_PATH}
 *   2. Trigger→ setprop {@link #PROP_FLASH} = 1
 *   3. Poll   → read {@link #PROP_STATUS}: running→wait, ok→done, error_*→fail
 *
 * This class does NOT build the Qualcomm splash container and does NOT touch
 * the block device. Run it off the main/binder thread — it does I/O and polls.
 */
public final class SplashUpdater {
    private static final String TAG = "SplashUpdater";

    /** Staging path. Dir is created at boot 0770 system system, label
     *  vendor_splash_data_file — uid system writes it directly, no restorecon. */
    static final String SPLASH_PATH = "/data/vendor/splash/splash.img";

    /**
     * Legacy Qualcomm splash layout (produced by splash_logo_gen.py): {@link #BMP_OFFSET}
     * bytes of zero filler followed by a raw BMP. There is no "SPLASH!!" container header
     * in this format — the bootloader skips the filler and parses the BMP directly — so we
     * validate the BMP signature at {@link #BMP_OFFSET} as the fail-fast sanity check.
     */
    private static final int BMP_OFFSET = 0x4000;

    /** BMP signature ("BM") expected at {@link #BMP_OFFSET}. */
    private static final byte[] BMP_MAGIC = {0x42, 0x4D};

    static final String PROP_FLASH = "vendor.splash.flash";
    static final String PROP_STATUS = "vendor.splash.status";

    private static final int POLL_INTERVAL_MS = 100;
    private static final int POLL_MAX = 50; // ~5 s — broker is a fast oneshot

    private SplashUpdater() {}

    /**
     * Stages {@code newImage}, triggers the broker and polls for the result.
     *
     * @param newImage      the raw splash.img bytes (caller owns/closes the stream)
     * @param maxSizeBytes  optional partition-size guard from the server; {@code <= 0}
     *                      skips the in-app size check. The app domain cannot open the
     *                      block device to read its real size (SELinux), so the broker
     *                      is the authoritative size check (returns error_size).
     * @return the final {@link #PROP_STATUS} value: {@code ok}, {@code error_magic},
     *         {@code error_size}, {@code error_nofile}, …, or one of this class's own
     *         outcomes: {@code error_magic}/{@code error_size} (pre-check),
     *         {@code busy} (flash already running), {@code timeout}.
     */
    public static String updateSplash(InputStream newImage, long maxSizeBytes) throws Exception {
        File dst = new File(SPLASH_PATH);

        // Don't re-trigger while a previous flash is still running (one-shot).
        if ("running".equals(SystemPropertiesProxy.get(PROP_STATUS, ""))) {
            Log.w(TAG, "Splash flash already running — refusing to re-trigger");
            return "busy";
        }

        // 1. Stage — validate the BMP signature at 0x4000 before committing the
        //    rest, so a wrong file fails fast without a full download/write.
        String staged = stage(newImage, dst, maxSizeBytes);
        if (!"ok".equals(staged)) {
            return staged; // stage() logged and deleted any partial file
        }

        // 2. Trigger the broker. Clear any stale status first so we don't read a
        //    leftover "ok" from a previous run.
        SystemPropertiesProxy.set(PROP_STATUS, "");
        if (!SystemPropertiesProxy.set(PROP_FLASH, "1")) {
            return "error_trigger";
        }

        // 3. Poll for completion.
        for (int i = 0; i < POLL_MAX; i++) {
            String st = SystemPropertiesProxy.get(PROP_STATUS, "");
            if (!st.isEmpty() && !"running".equals(st)) {
                Log.i(TAG, "Splash flash finished: " + st);
                return st;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        Log.w(TAG, "Splash flash timed out after ~" + (POLL_MAX * POLL_INTERVAL_MS) + "ms");
        return "timeout";
    }

    /** Writes the validated image to {@code dst}. Returns {@code "ok"} or an
     *  {@code error_*} reason; deletes any partial file on failure. */
    private static String stage(InputStream in, File dst, long maxSizeBytes) {
        // Read the leading filler plus the BMP signature so we can fail fast on a
        // wrong file (e.g. an HTML error page) before pulling/writing the whole image.
        byte[] header = new byte[BMP_OFFSET + BMP_MAGIC.length];
        int read = 0;
        try {
            int n;
            while (read < header.length && (n = in.read(header, read, header.length - read)) >= 0) {
                read += n;
            }
        } catch (Exception e) {
            Log.e(TAG, "Splash read error: " + e.getMessage());
            return "error_read";
        }
        if (read < header.length
                || header[BMP_OFFSET] != BMP_MAGIC[0]
                || header[BMP_OFFSET + 1] != BMP_MAGIC[1]) {
            Log.e(TAG, "Splash image not in expected format (no BMP at 0x" + Integer.toHexString(BMP_OFFSET) + ")");
            return "error_magic";
        }

        String result = "error_stage";
        try (FileOutputStream out = new FileOutputStream(dst)) {
            out.write(header, 0, read);
            long total = read;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (maxSizeBytes > 0 && total > maxSizeBytes) {
                    Log.e(TAG, "Splash image exceeds partition size " + maxSizeBytes);
                    result = "error_size";
                    return result; // finally{} deletes the partial file
                }
                out.write(buf, 0, n);
            }
            out.getFD().sync();
            result = "ok";
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Splash stage error: " + e.getMessage());
            return result;
        } finally {
            if (!"ok".equals(result)) {
                // Leave no partial image for the broker to flash.
                if (dst.exists() && !dst.delete()) {
                    Log.w(TAG, "Failed to delete partial staged splash image");
                }
            }
        }
    }
}
