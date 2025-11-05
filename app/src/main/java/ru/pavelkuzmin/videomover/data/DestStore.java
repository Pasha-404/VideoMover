package ru.pavelkuzmin.videomover.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class DestStore {
    private static final String PREFS = "videomover_prefs";
    private static final String KEY_URI = "dest_tree_uri";

    public static void save(Context ctx, Uri treeUri) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_URI, treeUri.toString()).apply();
    }

    public static Uri get(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_URI, null);
        return v == null ? null : Uri.parse(v);
    }

    public static void clear(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_URI).apply();
    }
}
