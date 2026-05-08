package ru.pavelkuzmin.videomover;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

public class VideoMoverApp extends Application {
    private SharedPreferences.OnSharedPreferenceChangeListener languageListener;

    @Override
    protected void attachBaseContext(Context base) {
        applyAppLanguage(readSavedLanguage(base));
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        applyAppLanguage(readSavedLanguage(this));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        languageListener = (sharedPreferences, key) -> {
            if ("pref_language".equals(key)) {
                applyAppLanguage(sharedPreferences.getString("pref_language", ""));
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(languageListener);
    }

    private String readSavedLanguage(Context context) {
        String prefsName = context.getPackageName() + "_preferences";
        SharedPreferences prefs = context.getSharedPreferences(prefsName, MODE_PRIVATE);
        return prefs.getString("pref_language", "");
    }

    private void applyAppLanguage(String tag) {
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(tag == null ? "" : tag));
    }
}
