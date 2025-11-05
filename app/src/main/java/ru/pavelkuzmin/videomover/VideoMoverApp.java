package ru.pavelkuzmin.videomover;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

/**
 * Надёжное применение языка:
 *  - В attachBaseContext() — максимально рано (до Activity).
 *  - В onCreate() — повторно на всякий случай.
 *  - Слушаем изменения pref_language и применяем мгновенно.
 *
 * НИЧЕГО другого тут не трогаем.
 */
public class VideoMoverApp extends Application {

    private SharedPreferences.OnSharedPreferenceChangeListener langListener;

    @Override
    protected void attachBaseContext(Context base) {
        // Применяем язык максимально рано, до старта любой Activity
        applyAppLanguage(readSavedLanguage(base));
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // На всякий случай повторяем в onCreate (идемпотентно)
        applyAppLanguage(readSavedLanguage(this));

        // Слушаем изменения pref_language и применяем сразу
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        langListener = (prefs, key) -> {
            if (!"pref_language".equals(key)) return;
            applyAppLanguage(prefs.getString("pref_language", ""));
        };
        sp.registerOnSharedPreferenceChangeListener(langListener);
    }

    private String readSavedLanguage(Context ctx) {
        // Явно читаем тот же файл, что использует PreferenceManager: <package>_preferences
        String prefsName = ctx.getPackageName() + "_preferences";
        SharedPreferences sp = ctx.getSharedPreferences(prefsName, MODE_PRIVATE);
        return sp.getString("pref_language", ""); // "" = системный, "en", "ru"
    }

    private void applyAppLanguage(String tag) {
        if (tag == null) tag = "";
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }
}
