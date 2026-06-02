package com.aioapp.mdm;

import java.lang.reflect.Method;
import android.util.Log;

public class SystemPropertiesProxy {
    private static final String TAG = "SystemPropertiesProxy";

    public static String get(String key, String def) {
        try {
            Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Method getMethod = systemPropertiesClass.getMethod("get", String.class, String.class);
            return (String) getMethod.invoke(null, key, def);
        } catch (Exception e) {
            Log.e(TAG, "Failed to invoke SystemProperties.get: " + e.getMessage());
            return def;
        }
    }

    /** Sets a system property. Requires the caller to be permitted by the
     *  property's SELinux context (the splash props are set-able by system_app). */
    public static boolean set(String key, String value) {
        try {
            Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Method setMethod = systemPropertiesClass.getMethod("set", String.class, String.class);
            setMethod.invoke(null, key, value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to invoke SystemProperties.set(" + key + "): " + e.getMessage());
            return false;
        }
    }
}