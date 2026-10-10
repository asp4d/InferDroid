package dev.inferdroid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.Window;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public final class ThemeHelper {
    public static final String PREFS_NAME = "ui_settings";
    public static final String KEY_THEME = "app_theme";
    public static final String KEY_LANGUAGE = "app_language";

    public static final String THEME_LIGHT = "light";
    public static final String THEME_OLED_DARK = "oled_dark";
    public static final String THEME_AUTO = "auto";
    public static final String THEME_GRUVBOX_LIGHT = "gruvbox_light";
    public static final String THEME_GRUVBOX_DARK = "gruvbox_dark";
    public static final String THEME_AUTO_GRUVBOX = "auto_gruvbox";

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_EN = "en";
    public static final String LANG_IT = "it";

    private ThemeHelper() {}

    public static String getSelectedTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_THEME, THEME_LIGHT);
    }

    public static void setSelectedTheme(Context context, String theme) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_THEME, theme).apply();
    }

    public static String getSelectedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, LANG_SYSTEM);
    }

    public static void setSelectedLanguage(Context context, String language) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, language).apply();
    }

    public static boolean isSystemNightMode(Context context) {
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int getThemeResId(Context context) {
        String theme = getSelectedTheme(context);
        boolean isSystemNight = isSystemNightMode(context);

        switch (theme) {
            case THEME_OLED_DARK:
                return R.style.Theme_InferDroid_OledDark;
            case THEME_AUTO:
                return isSystemNight ? R.style.Theme_InferDroid_OledDark : R.style.Theme_InferDroid_Light;
            case THEME_GRUVBOX_LIGHT:
                return R.style.Theme_InferDroid_GruvboxLight;
            case THEME_GRUVBOX_DARK:
                return R.style.Theme_InferDroid_GruvboxDark;
            case THEME_AUTO_GRUVBOX:
                return isSystemNight ? R.style.Theme_InferDroid_GruvboxDark : R.style.Theme_InferDroid_GruvboxLight;
            case THEME_LIGHT:
            default:
                return R.style.Theme_InferDroid_Light;
        }
    }

    public static void applyTheme(Activity activity) {
        String theme = getSelectedTheme(activity);

        int targetNightMode;
        if (THEME_OLED_DARK.equals(theme) || THEME_GRUVBOX_DARK.equals(theme)) {
            targetNightMode = AppCompatDelegate.MODE_NIGHT_YES;
        } else if (THEME_LIGHT.equals(theme) || THEME_GRUVBOX_LIGHT.equals(theme)) {
            targetNightMode = AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            targetNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }

        if (AppCompatDelegate.getDefaultNightMode() != targetNightMode) {
            AppCompatDelegate.setDefaultNightMode(targetNightMode);
        }

        int themeResId = getThemeResId(activity);
        activity.setTheme(themeResId);

        Window window = activity.getWindow();
        boolean isDarkTheme = (themeResId == R.style.Theme_InferDroid_OledDark || themeResId == R.style.Theme_InferDroid_GruvboxDark);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkTheme);
            controller.setAppearanceLightNavigationBars(!isDarkTheme);
        }
    }

    public static void applyLanguage(Context context) {
        String lang = getSelectedLanguage(context);
        if (LANG_SYSTEM.equals(lang)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang));
        }
    }
}
