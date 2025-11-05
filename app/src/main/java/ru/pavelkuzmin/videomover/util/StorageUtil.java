package ru.pavelkuzmin.videomover.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import ru.pavelkuzmin.videomover.R;

import java.util.List;

public class StorageUtil {

    /** Возвращает displayName корня дерева (как его отдаёт SAF-провайдер). */
    private static @Nullable String getTreeRootDisplayName(Context ctx, Uri treeUri) {
        try {
            final String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            final Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
            try (Cursor c = ctx.getContentResolver().query(
                    doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getString(0);
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Возвращает описание (label) съёмного тома по UUID через StorageManager (Android 7+). */
    private static @Nullable String getRemovableVolumeLabel(Context ctx, Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri); // e.g. "XXXX-XXXX:Folder/Sub"
            String[] parts = treeId.split(":", 2);
            String volId = parts.length > 0 ? parts[0] : null; // "primary" или "XXXX-XXXX"
            if (TextUtils.isEmpty(volId) || "primary".equalsIgnoreCase(volId)) {
                // primary — это внутренняя память; имя тома обычно не нужно
                return null;
            }
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return null;
            List<StorageVolume> vols;
            if (Build.VERSION.SDK_INT >= 24) {
                vols = sm.getStorageVolumes();
            } else {
                return null;
            }
            for (StorageVolume v : vols) {
                String uuid = v.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(volId)) {
                    return v.getDescription(ctx); // часто "USB storage" / "SanDisk 128GB"
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Возвращает относительный путь внутри дерева (после двоеточия в treeDocumentId). */
    private static String getRelativeFolderInTree(Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri); // "XXXX-XXXX:Movies/Camera"
            String[] parts = treeId.split(":", 2);
            String rel = parts.length == 2 ? parts[1] : "";
            if (TextUtils.isEmpty(rel)) return "/";
            // Страховка: иногда встречаются %2F (редко), нормализуем
            return rel.replace("%2F", "/");
        } catch (Exception ignore) {}
        return "/";
    }

    /** Строит «дружелюбную» строку для UI. */
    public static String buildDestSummary(Context ctx, Uri treeUri) {
        String label = getRemovableVolumeLabel(ctx, treeUri);
        if (TextUtils.isEmpty(label)) {
            // fallback: попробуем displayName от провайдера
            label = getTreeRootDisplayName(ctx, treeUri);
        }
        String rel = getRelativeFolderInTree(treeUri);

        // Варианты отображения:
        // - Если есть label: «Внешний накопитель: <label>\nПапка: /rel»
        // - Если нет label: показываем только папку + короткий URI
        if (!TextUtils.isEmpty(label)) {
            return ctx.getString(R.string.dest_summary_label, label, (rel.startsWith("/") ? rel : ("/" + rel)));
        } else {
            // урезанный URI на всякий случай
            String shortUri = treeUri.toString();
            if (shortUri.length() > 48) shortUri = shortUri.substring(0, 48) + "…";
            return ctx.getString(R.string.dest_summary_no_label, (rel.startsWith("/") ? rel : ("/" + rel)), shortUri);
        }
    }
}
