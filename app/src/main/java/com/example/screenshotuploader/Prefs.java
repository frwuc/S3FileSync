package com.example.screenshotuploader;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class Prefs {
    private static final String NAME = "screenshot_uploader_prefs";

    public static final String KEY_ACCESS_KEY = "access_key";
    public static final String KEY_SECRET_KEY = "secret_key";
    public static final String KEY_BUCKET = "bucket";
    public static final String KEY_DOMAIN = "domain";
    public static final String KEY_TIMES = "upload_times";

    private static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void putString(Context context, String key, String value) {
        get(context).edit().putString(key, value).apply();
    }

    public static String getString(Context context, String key, String def) {
        return get(context).getString(key, def);
    }

    public static void putStringSet(Context context, String key, Set<String> values) {
        get(context).edit().putStringSet(key, values).apply();
    }

    public static Set<String> getStringSet(Context context, String key, Set<String> def) {
        return get(context).getStringSet(key, def);
    }
}
