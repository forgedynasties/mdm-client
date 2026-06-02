package com.aioapp.mdm;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;

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

    /** Qualcomm splash container magic: "SPLASH!!" (53 50 4C 41 53 48 21 21). */
    private static final byte[] MAGIC = {0x53, 0x50, 0x4C, 0x41, 0x53, 0x48, 0x21, 0x21};

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

        // 1. Stage — validate the magic on the first 8 bytes before committing the
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
        // Read and validate the magic header first.
        byte[] header = new byte[MAGIC.length];
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
        if (read < header.length || !Arrays.equals(header, MAGIC)) {
            Log.e(TAG, "Splash image missing SPLASH!! magic");
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
