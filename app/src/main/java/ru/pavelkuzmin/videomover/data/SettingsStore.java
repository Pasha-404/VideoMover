package ru.pavelkuzmin.videomover.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class SettingsStore {
    private static final String PREFS = "videomover_prefs";

    private static final String KEY_DEST_URI = "dest_tree_uri";
    private static final String KEY_SOURCE_REL_PATH = "source_rel_path";
    private static final String KEY_DELETE_AFTER = "delete_after";
    private static final String KEY_USE_DCIM_ALL = "pref_use_dcim_all";

    public static final boolean DEFAULT_DELETE_AFTER = true;
    public static final boolean DEFAULT_USE_DCIM_ALL = true;

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // DEST (SAF tree URI)
    public static void setDestTreeUri(Context ctx, Uri uri) {
        sp(ctx).edit().putString(KEY_DEST_URI, uri == null ? null : uri.toString()).apply();
    }
    public static Uri getDestTreeUri(Context ctx) {
        String v = sp(ctx).getString(KEY_DEST_URI, null);
        return v == null ? null : Uri.parse(v);
    }

    // SOURCE (MediaStore RELATIVE_PATH prefix)
    public static void setSourceRelPath(Context ctx, String relPath) {
        sp(ctx).edit().putString(KEY_SOURCE_REL_PATH, relPath).apply();
    }
    public static String getSourceRelPath(Context ctx) {
        return sp(ctx).getString(KEY_SOURCE_REL_PATH, null);
    }

    // Delete after copy
    public static boolean isDeleteAfter(Context ctx) {
        return sp(ctx).getBoolean(KEY_DELETE_AFTER, DEFAULT_DELETE_AFTER);
    }
    public static void setDeleteAfter(Context ctx, boolean value) {
        sp(ctx).edit().putBoolean(KEY_DELETE_AFTER, value).apply();
    }

    public static boolean isUseDcimAll(Context ctx) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(KEY_USE_DCIM_ALL, DEFAULT_USE_DCIM_ALL);
    }
    public static void setUseDcimAll(Context ctx, boolean v) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(KEY_USE_DCIM_ALL, v).apply();
    }
}
