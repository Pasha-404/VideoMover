package ru.pavelkuzmin.videomover.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Утилиты для получения списка видеороликов с устройства.
 * Работает с MediaStore и поддерживает два режима:
 *  1) Поиск видео в конкретной подпапке камеры (RELATIVE_PATH)
 *  2) Поиск всех видео в DCIM/*
 */
public class MediaQuery {

    /** Обёртка данных о видео */
    public static class VideoItem {
        public final Uri uri;
        public final String displayName;
        public final long size;
        public final String relPath;

        public VideoItem(Uri uri, String displayName, long size, String relPath) {
            this.uri = uri;
            this.displayName = displayName;
            this.size = size;
            this.relPath = relPath;
        }
    }

    /** Безопасно возвращает строку (не null) */
    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Находит видео, снятые камерой телефона (по пути RELATIVE_PATH).
     * Обычно это DCIM/Camera или DCIM/100MEDIA и т.п.
     */
    public static List<VideoItem> findCameraVideosList(Context ctx, String relPrefix) {
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };

        String sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] selArgs = new String[]{ relPrefix + "%" };
        String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        List<VideoItem> out = new ArrayList<>();
        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, sel, selArgs, order)) {
            if (c == null) return out;
            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iRel  = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            while (c.moveToNext()) {
                String rel = safe(c.getString(iRel));
                long id = c.getLong(iId);
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                String name = c.getString(iName);
                long size = c.getLong(iSize);
                out.add(new VideoItem(uri, name, size, rel));
            }
        }
        return out;
    }

    /**
     * Новый режим: берёт ВСЕ видео в DCIM и его подпапках
     * (включая DCIM/Camera, DCIM/DJI Album и т.д.)
     */
    public static List<VideoItem> findDcimVideosList(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };

        String sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] selArgs = new String[]{"DCIM/%"};
        String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        List<VideoItem> out = new ArrayList<>();
        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, sel, selArgs, order)) {
            if (c == null) return out;
            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iRel  = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            while (c.moveToNext()) {
                String rel = safe(c.getString(iRel));
                // На всякий случай отфильтруем явные мессенджеры
                if (rel.contains("WhatsApp") || rel.contains("Telegram") || rel.contains("Instagram"))
                    continue;

                long id = c.getLong(iId);
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                String name = c.getString(iName);
                long size = c.getLong(iSize);
                out.add(new VideoItem(uri, name, size, rel));
            }
        }
        return out;
    }

    /**
     * Пробует автоматически определить путь к папке камеры.
     * Возвращает строку вроде "DCIM/Camera/" или "DCIM/100MEDIA/"
     */
    public static String detectLikelyCameraRelPath(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = { MediaStore.Video.Media.RELATIVE_PATH };
        String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, order + " LIMIT 20")) {
            if (c == null) return null;
            int iRel = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            while (c.moveToNext()) {
                String rel = safe(c.getString(iRel));
                if (rel.startsWith("DCIM/Camera")
                        || rel.startsWith("DCIM/100MEDIA")
                        || rel.startsWith("DCIM/OpenCamera")
                        || rel.startsWith("DCIM/Photos")) {
                    return rel;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
}
