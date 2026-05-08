package ru.pavelkuzmin.videomover.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

public final class SafUtil {
    private static final int URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private SafUtil() {}

    public static Intent createOpenTreeIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(URI_PERMISSION_FLAGS
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    public static void persistTreePermission(Context context, Intent resultData, Uri treeUri) {
        int takeFlags = resultData.getFlags() & URI_PERMISSION_FLAGS;
        if (takeFlags == 0) {
            takeFlags = URI_PERMISSION_FLAGS;
        }
        context.getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
    }

    public static boolean hasPersistedWritePermission(Context context, Uri treeUri) {
        ContentResolver resolver = context.getContentResolver();
        String tree = treeUri.toString();
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isWritePermission() && tree.equals(permission.getUri().toString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean canWriteTree(Context context, Uri treeUri) {
        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
        return dir != null && dir.exists() && dir.isDirectory() && dir.canWrite();
    }
}
