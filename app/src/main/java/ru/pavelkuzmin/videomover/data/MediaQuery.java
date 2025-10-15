package ru.pavelkuzmin.videomover.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MediaQuery {

    public static class VideoItem {
        public final long id;
        public final String displayName;
        public final long size;
        public final String relativePath;

        public VideoItem(long id, String displayName, long size, String relativePath) {
            this.id = id;
            this.displayName = displayName;
            this.size = size;
            this.relativePath = relativePath;
        }

        public Uri uri() {
            return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
        }
    }

    /** Базовая версия: ищем видео в типичных камерных путях и отбрасываем мессенджеры. */
    public static List<VideoItem> findCameraVideos(Context ctx, int limit) {
        ContentResolver cr = ctx.getContentResolver();
        List<VideoItem> out = new ArrayList<>();

        String[] projection = new String[] {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };

        // Типичные камеры
        String camLike = "DCIM/%Camera%";
        String moviesCamLike = "Movies/%Camera%";

        String selection =
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? OR " +
                        MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? OR " +
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " = ?";

        String[] args = new String[] { camLike, moviesCamLike, "Camera" };
        String sort = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort)) {
            if (c == null) return out;
            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iPath = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);

            while (c.moveToNext() && out.size() < limit) {
                String rel = safe(c.getString(iPath));
                if (rel.contains("WhatsApp") || rel.contains("Telegram") ||
                        rel.contains("Download") || rel.contains("/Android/media/")) {
                    continue;
                }
                out.add(new VideoItem(
                        c.getLong(iId),
                        safe(c.getString(iName)),
                        c.getLong(iSize),
                        rel
                ));
            }
        }
        return out;
    }

    /** Версия с фильтром по RELATIVE_PATH prefix, если в настройках задано. */
    public static List<VideoItem> findCameraVideos(Context ctx, int limit, @Nullable String relPrefix) {
        if (relPrefix == null || relPrefix.isEmpty()) return findCameraVideos(ctx, limit);

        ContentResolver cr = ctx.getContentResolver();
        List<VideoItem> out = new ArrayList<>();

        String[] projection = new String[] {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };
        String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = new String[] { relPrefix + "%" };
        String sort = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort)) {
            if (c == null) return out;
            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iPath = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);

            while (c.moveToNext() && out.size() < limit) {
                String rel = safe(c.getString(iPath));
                if (rel.contains("WhatsApp") || rel.contains("Telegram") ||
                        rel.contains("Download") || rel.contains("/Android/media/")) {
                    continue;
                }
                out.add(new VideoItem(
                        c.getLong(iId),
                        safe(c.getString(iName)),
                        c.getLong(iSize),
                        rel
                ));
            }
        }
        return out;
    }

    /** Находит самый вероятный RELATIVE_PATH камерной папки по последним 50 видео (с приоритетом DCIM). */
    public static @Nullable String detectLikelyCameraRelPath(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DATE_TAKEN
        };
        String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, order)) {
            if (c == null) return null;
            Map<String, Integer> freq = new HashMap<>();
            int iPath = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            int count = 0;
            while (c.moveToNext() && count < 50) {
                String rel = safe(c.getString(iPath));
                if (rel.contains("DCIM")) {
                    freq.put(rel, freq.getOrDefault(rel, 0) + 1);
                }
                count++;
            }
            String best = null; int bestN = 0;
            for (var e : freq.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            return best; // может быть null
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
