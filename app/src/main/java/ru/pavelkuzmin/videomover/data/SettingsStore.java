package ru.pavelkuzmin.videomover.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class SettingsStore {
    private static final String PREFS = "videomover_prefs";

    private static final String KEY_DEST_URI = "dest_tree_uri";
    private static final String KEY_SOURCE_REL_PATH = "source_rel_path";
    private static final String KEY_DELETE_AFTER = "delete_after"; // default true

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
        return sp(ctx).getBoolean(KEY_DELETE_AFTER, true);
    }
    public static void setDeleteAfter(Context ctx, boolean value) {
        sp(ctx).edit().putBoolean(KEY_DELETE_AFTER, value).apply();
    }
}
