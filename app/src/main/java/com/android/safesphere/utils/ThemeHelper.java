package com.android.safesphere.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public class ThemeHelper {
    private static final String PREF_KEY = "theme_preference";
    private static final String THEME_KEY = "app_theme_mode";

    // Call this in your Application class or before setContentView() in your main activity
    public static void applyTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_KEY, Context.MODE_PRIVATE);
        int themeMode = prefs.getInt(THEME_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(themeMode);
    }

    public static void setTheme(Context context, int themeMode) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_KEY, Context.MODE_PRIVATE).edit();
        editor.putInt(THEME_KEY, themeMode);
        editor.apply();
        AppCompatDelegate.setDefaultNightMode(themeMode);
    }
}