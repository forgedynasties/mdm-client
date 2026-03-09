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
}