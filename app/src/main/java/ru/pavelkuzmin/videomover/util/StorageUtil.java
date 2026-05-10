package ru.pavelkuzmin.videomover.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

import ru.pavelkuzmin.videomover.R;

public class StorageUtil {
    public static long getAvailableBytes(Context ctx, Uri treeUri) {
        File root = resolveVolumeRoot(ctx, treeUri);
        if (root == null) return -1L;
        try {
            return new StatFs(root.getAbsolutePath()).getAvailableBytes();
        } catch (Exception ignore) {
            return -1L;
        }
    }

    public static String buildDestSummary(Context ctx, Uri treeUri) {
        String label = getRemovableVolumeLabel(ctx, treeUri);
        if (isEmpty(label)) {
            label = getTreeRootDisplayName(ctx, treeUri);
        }

        String rel = getRelativeFolderInTree(treeUri);
        String displayPath = rel.startsWith("/") ? rel : "/" + rel;
        if (!isEmpty(label)) {
            return ctx.getString(R.string.dest_summary_label, label, displayPath);
        }

        String shortUri = treeUri.toString();
        if (shortUri.length() > 48) {
            shortUri = shortUri.substring(0, 48) + "...";
        }
        return ctx.getString(R.string.dest_summary_no_label, displayPath, shortUri);
    }

    private static @Nullable File resolveVolumeRoot(Context ctx, Uri treeUri) {
        String volumeId = getTreeVolumeId(treeUri);
        if (isEmpty(volumeId)) return null;
        if ("primary".equalsIgnoreCase(volumeId)) {
            return Environment.getExternalStorageDirectory();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }

        try {
            StorageManager storageManager = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (storageManager == null) return null;
            List<StorageVolume> volumes = storageManager.getStorageVolumes();
            for (StorageVolume volume : volumes) {
                String uuid = volume.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(volumeId)) {
                    return volume.getDirectory();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static @Nullable String getTreeRootDisplayName(Context ctx, Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
            try (Cursor cursor = ctx.getContentResolver().query(
                    doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null,
                    null,
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getString(0);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static @Nullable String getRemovableVolumeLabel(Context ctx, Uri treeUri) {
        try {
            String volId = getTreeVolumeId(treeUri);
            if (isEmpty(volId) || "primary".equalsIgnoreCase(volId)) {
                return null;
            }

            StorageManager storageManager = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (storageManager == null) return null;
            List<StorageVolume> volumes = storageManager.getStorageVolumes();
            for (StorageVolume volume : volumes) {
                String uuid = volume.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(volId)) {
                    return volume.getDescription(ctx);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static @Nullable String getTreeVolumeId(Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            String[] parts = treeId.split(":", 2);
            return parts.length > 0 ? parts[0] : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String getRelativeFolderInTree(Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            String[] parts = treeId.split(":", 2);
            String rel = parts.length == 2 ? parts[1] : "";
            if (isEmpty(rel)) return "/";
            return rel.replace("%2F", "/");
        } catch (Exception ignore) {
            return "/";
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
