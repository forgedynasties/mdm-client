package com.aioapp.mdm;

import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s step) used to authorise an OFFLINE kiosk
 * exit. The seed is provisioned once by the server and stored on-device; the code is
 * verified locally with no server round-trip, so a technician can leave kiosk mode
 * with a rotating code even when the device has no connectivity.
 *
 * Verification allows ±1 step to tolerate clock skew between the device RTC and the
 * server that displays the current code.
 */
public final class Totp {
    private Totp() {}

    /** Returns true if {@code code} matches the TOTP for {@code seed} at the given time (±1 step). */
    public static boolean verify(String base32Seed, String code, long unixSeconds,
                                 int digits, int periodSeconds) {
        if (base32Seed == null || code == null) return false;
        code = code.trim();
        if (code.isEmpty()) return false;
        byte[] key;
        try {
            key = base32Decode(base32Seed);
        } catch (Exception e) {
            return false;
        }
        if (key.length == 0) return false;
        long counter = unixSeconds / periodSeconds;
        for (long off = -1; off <= 1; off++) {
            String expected = generate(key, counter + off, digits);
            if (constantTimeEquals(expected, code)) return true;
        }
        return false;
    }

    /** Compute the TOTP code for a counter — also used server-side conceptually. */
    public static String generate(byte[] key, long counter, int digits) {
        try {
            byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int mod = (int) Math.pow(10, digits);
            int otp = binary % mod;
            StringBuilder sb = new StringBuilder(Integer.toString(otp));
            while (sb.length() < digits) sb.insert(0, '0');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** RFC 4648 base32 decode (uppercase, no padding required). */
    public static byte[] base32Decode(String s) {
        s = s.trim().replace("=", "").replace(" ", "").toUpperCase();
        final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0, bits = 0, idx = 0;
        byte[] out = new byte[s.length() * 5 / 8];
        for (int i = 0; i < s.length(); i++) {
            int val = ALPHA.indexOf(s.charAt(i));
            if (val < 0) throw new IllegalArgumentException("bad base32 char");
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }

    /** Constant-time string compare to avoid timing side-channels on the code. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
