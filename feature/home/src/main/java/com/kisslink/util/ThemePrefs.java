package com.kisslink.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主題偏好持久化——三模式：跟隨系統（預設）、強制亮色、強制深色。
 *
 * <p>使用方式：
 *
 * <pre>
 *   // 在 Application.onCreate() 或 Activity 最早時套用：
 *   ThemePrefs.apply(context);
 *
 *   // 切換模式（立即生效）：
 *   ThemePrefs.setMode(context, ThemePrefs.MODE_DARK);
 * </pre>
 */
public final class ThemePrefs {

    public static final int MODE_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    public static final int MODE_LIGHT = AppCompatDelegate.MODE_NIGHT_NO;
    public static final int MODE_DARK = AppCompatDelegate.MODE_NIGHT_YES;

    private static final String PREFS_NAME = "kiss_theme";
    private static final String KEY_MODE = "night_mode";

    private ThemePrefs() {}

    /** 讀取偏好並套用至 AppCompatDelegate（應在 Activity.onCreate 前呼叫）。 */
    public static void apply(Context ctx) {
        AppCompatDelegate.setDefaultNightMode(getMode(ctx));
    }

    /** 取得目前儲存的模式（預設 MODE_SYSTEM）。 */
    public static int getMode(Context ctx) {
        return prefs(ctx).getInt(KEY_MODE, MODE_SYSTEM);
    }

    /**
     * 設定模式並立即套用（AppCompatDelegate 會自動重建所有 Activity）。
     *
     * @param mode {@link #MODE_SYSTEM}、{@link #MODE_LIGHT} 或 {@link #MODE_DARK}
     */
    public static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
