package com.appslayer.official;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

// Applies the language picked in the main screen (AppSlayerPrefs, key
// "language"). Empty means: follow the system language. Indonesian is
// the default resource set, "en" loads values-en.
public class LocaleHelper {

    public static final String PREFS_NAME = PrefsKeys.APP_PREFS;
    public static final String KEY_LANGUAGE = PrefsKeys.LANGUAGE;

    public static Context wrap(Context base) {
        String lang = getSavedLanguage(base);
        if (lang.isEmpty()) {
            return base;
        }
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }

    public static String getSavedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lang = prefs.getString(KEY_LANGUAGE, "");
        return lang == null ? "" : lang;
    }

    // The language the UI is effectively showing right now.
    public static String getEffectiveLanguage(Context context) {
        String saved = getSavedLanguage(context);
        if (!saved.isEmpty()) {
            return saved;
        }
        return Locale.getDefault().getLanguage();
    }

    public static void saveLanguage(Context context, String lang) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, lang).apply();
    }
}
