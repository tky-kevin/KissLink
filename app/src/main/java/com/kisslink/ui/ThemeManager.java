package com.kisslink.ui;

import android.app.Activity;
import android.content.Context;

import com.kisslink.R;

public class ThemeManager {

    private static final String PREFS = "theme_prefs";
    private static final String KEY   = "theme_index";

    public static final int THEME_INK      = 0;
    public static final int THEME_CORAL    = 1;
    public static final int THEME_MINT     = 2;
    public static final int THEME_SUNSET   = 3;
    public static final int THEME_LAVENDER = 4;
    public static final int THEME_ROSE     = 5;

    public static final String[] NAMES = {"墨水藍", "珊瑚", "薄荷", "暖橙", "薰衣草", "玫瑰"};

    public static final int[] COLORS = {
        0xFF2EAADC, 0xFFFF6B6B, 0xFF0F9D58,
        0xFFF4A261, 0xFF9B5DE5, 0xFFE91E8C
    };

    private ThemeManager() {}

    public static int getTheme(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, THEME_INK);
    }

    public static void setTheme(Context ctx, int index) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, index).apply();
    }

    public static void apply(Activity activity) {
        int idx = getTheme(activity);
        int[] styles = {
            R.style.Theme_KissLink_Ink,
            R.style.Theme_KissLink_Coral,
            R.style.Theme_KissLink_Mint,
            R.style.Theme_KissLink_Sunset,
            R.style.Theme_KissLink_Lavender,
            R.style.Theme_KissLink_Rose,
        };
        activity.setTheme(idx < styles.length ? styles[idx] : styles[0]);
    }
}
