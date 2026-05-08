package ru.pavelkuzmin.videomover.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MediaQuery {
    private static final String DCIM_PREFIX = "DCIM/";
    private static final int DEFAULT_CANDIDATE_SCAN_LIMIT = 400;
    private static final int DEFAULT_CANDIDATE_RESULT_LIMIT = 25;

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

    public static List<VideoItem> findCameraVideosList(Context ctx, String relPrefix) {
        String normalized = normalizeRelPath(relPrefix);
        if (isEmpty(normalized)) {
            return new ArrayList<>();
        }
        return queryVideos(ctx, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{normalized + "%"}, false);
    }

    public static List<VideoItem> findDcimVideosList(Context ctx) {
        return queryVideos(ctx, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{DCIM_PREFIX + "%"}, true);
    }

    public static String detectLikelyCameraRelPath(Context ctx) {
        List<String> candidates = collectCameraFolderCandidates(ctx,
                DEFAULT_CANDIDATE_SCAN_LIMIT,
                DEFAULT_CANDIDATE_RESULT_LIMIT);
        return pickBestCameraRelPath(candidates);
    }

    public static List<String> collectCameraFolderCandidates(Context ctx, int maxScan, int maxResults) {
        Set<String> set = new LinkedHashSet<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DATE_TAKEN
        };
        String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, order)) {
            if (c == null) return new ArrayList<>();
            int iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
            int scanned = 0;
            while (c.moveToNext() && scanned < maxScan) {
                scanned++;
                String rel = iRel >= 0 ? normalizeRelPath(c.getString(iRel)) : null;
                if (isEmpty(rel) || isExcludedFolder(rel)) continue;
                if (looksLikeCameraPath(rel)) {
                    set.add(rel);
                    if (set.size() >= maxResults) break;
                }
            }
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
        return new ArrayList<>(set);
    }

    public static String pickBestCameraRelPath(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        for (String rel : candidates) {
            if ("DCIM/Camera/".equals(rel) || "DCIM/Camera".equals(rel)) {
                return "DCIM/Camera/";
            }
        }

        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String rel : candidates) {
            freq.put(rel, freq.getOrDefault(rel, 0) + 1);
        }

        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : freq.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    public static boolean looksLikeCameraPath(String rel) {
        if (isEmpty(rel)) return false;
        if (rel.startsWith(DCIM_PREFIX)) return true;
        String low = rel.toLowerCase(Locale.ROOT);
        return low.contains("camera")
                || low.contains("100media")
                || low.contains("100andro")
                || low.contains("opencamera");
    }

    public static String normalizeRelPath(String rel) {
        if (rel == null) return null;
        rel = rel.trim();
        if (rel.isEmpty()) return rel;
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (!rel.endsWith("/")) {
            rel = rel + "/";
        }
        return rel;
    }

    private static List<VideoItem> queryVideos(Context ctx, String selection,
                                               String[] selectionArgs, boolean filterNonCameraFolders) {
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };
        String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

        List<VideoItem> out = new ArrayList<>();
        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                selection, selectionArgs, order)) {
            if (c == null) return out;
            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iRel = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            while (c.moveToNext()) {
                String rel = safe(c.getString(iRel));
                if (filterNonCameraFolders && isExcludedFolder(rel)) continue;

                long id = c.getLong(iId);
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                String name = c.getString(iName);
                long size = c.getLong(iSize);
                out.add(new VideoItem(uri, name, size, rel));
            }
        }
        return out;
    }

    private static boolean isExcludedFolder(String rel) {
        String low = safe(rel).toLowerCase(Locale.ROOT);
        return low.contains("whatsapp")
                || low.contains("telegram")
                || low.contains("instagram");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
