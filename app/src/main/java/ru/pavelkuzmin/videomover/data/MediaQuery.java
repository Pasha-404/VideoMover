package ru.pavelkuzmin.videomover.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

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

    /** Возвращает первые N видео из «камерных» папок, исключая мессенджеры. */
    public static List<VideoItem> findCameraVideos(Context ctx, int limit) {
        ContentResolver cr = ctx.getContentResolver();
        List<VideoItem> out = new ArrayList<>();

        String[] projection = new String[] {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };

        // Камерные пути (включение)
        String camLike = "DCIM/%Camera%";
        String moviesCamLike = "Movies/%Camera%";

        // Исключения (мессенджеры и пр.)
        String ex1 = "%WhatsApp%";
        String ex2 = "%Telegram%";
        String ex3 = "%Download%";
        String ex4 = "%/Android/media/%";

        String selection =
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? OR " +
                        MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? OR " +
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " = ?";

        // включения
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
                // фильтруем мессенджеры по пути
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

    private static String safe(String s) { return s == null ? "" : s; }
}
