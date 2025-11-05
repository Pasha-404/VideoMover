package ru.pavelkuzmin.videomover;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public final class LocaleUtils {
    private LocaleUtils() {}
    public static void applyLanguage(String langTagOrEmpty) {
        LocaleListCompat locales = LocaleListCompat.forLanguageTags(langTagOrEmpty == null ? "" : langTagOrEmpty);
        AppCompatDelegate.setApplicationLocales(locales);
    }
}
