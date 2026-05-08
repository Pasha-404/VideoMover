package ru.pavelkuzmin.videomover.domain;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

import ru.pavelkuzmin.videomover.R;
import ru.pavelkuzmin.videomover.util.HashUtil;

public class FileCopier {
    private static final int BUFFER_SIZE = 1024 * 1024;

    public interface ProgressCallback {
        void onProgress(long writtenBytes, long totalBytes);
    }

    public static class Result {
        public final boolean ok;
        public final String finalName;
        public final long bytes;
        public final String sha256;
        public final String error;

        public Result(boolean ok, String finalName, long bytes, String sha256, String error) {
            this.ok = ok;
            this.finalName = finalName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.error = error;
        }
    }

    public static Result copyWithSha256(Context ctx, Uri srcUri, String displayName,
                                        long expectedSize, DocumentFile destDir) {
        return copyWithSha256(ctx, srcUri, displayName, expectedSize, destDir, null);
    }

    public static Result copyWithSha256(Context ctx, Uri srcUri, String displayName,
                                        long expectedSize, DocumentFile destDir,
                                        ProgressCallback progressCallback) {
        ContentResolver cr = ctx.getContentResolver();
        Uri tempUri = null;
        long written = 0;
        String hash = null;

        try {
            if (destDir == null || !destDir.canWrite()) {
                return new Result(false, null, 0, null, ctx.getString(R.string.no_write_access));
            }

            NameParts parts = splitName(displayName);
            String finalName = ensureUniqueName(destDir, parts.base, parts.ext);
            DocumentFile tempFile = destDir.createFile("video/*", finalName + ".partial");
            if (tempFile == null) {
                return new Result(false, null, 0, null, ctx.getString(R.string.filecopier_err_create_temp));
            }
            tempUri = tempFile.getUri();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];

            try (InputStream in = cr.openInputStream(srcUri);
                 ParcelFileDescriptor outDescriptor = cr.openFileDescriptor(tempUri, "w");
                 OutputStream out = outDescriptor == null
                         ? null
                         : new FileOutputStream(outDescriptor.getFileDescriptor())) {
                if (in == null || out == null) {
                    deleteQuietly(cr, tempUri);
                    return new Result(false, null, written, null,
                            ctx.getString(R.string.filecopier_err_stream_access));
                }

                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    written += read;
                    if (progressCallback != null) {
                        progressCallback.onProgress(written, expectedSize);
                    }
                }
                out.flush();
                if (outDescriptor != null) {
                    outDescriptor.getFileDescriptor().sync();
                }
            }

            if (expectedSize > 0 && written != expectedSize) {
                deleteQuietly(cr, tempUri);
                return new Result(false, null, written, null,
                        ctx.getString(R.string.filecopier_err_size_mismatch));
            }

            hash = HashUtil.toHex(digest.digest());
            Uri renamed = DocumentsContract.renameDocument(cr, tempUri, finalName);
            if (renamed == null) {
                deleteQuietly(cr, tempUri);
                return new Result(false, null, written, hash,
                        ctx.getString(R.string.filecopier_err_rename_failed));
            }

            return new Result(true, finalName, written, hash, null);
        } catch (SecurityException e) {
            deleteQuietly(cr, tempUri);
            return new Result(false, null, written, hash, "SecurityException: " + e.getMessage());
        } catch (Exception e) {
            deleteQuietly(cr, tempUri);
            return new Result(false, null, written, hash,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static NameParts splitName(String displayName) {
        String base = TextUtils.isEmpty(displayName) ? "video" : displayName.trim();
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1) {
            ext = base.substring(dot);
            base = base.substring(0, dot);
        }
        if (TextUtils.isEmpty(base)) {
            base = "video";
        }
        return new NameParts(base, ext);
    }

    private static String ensureUniqueName(DocumentFile dir, String base, String ext) {
        String candidate = base + ext;
        int n = 1;
        while (dir.findFile(candidate) != null || dir.findFile(candidate + ".partial") != null) {
            candidate = base + " (" + n + ")" + ext;
            n++;
        }
        return candidate;
    }

    private static void deleteQuietly(ContentResolver cr, Uri uri) {
        if (uri == null) return;
        try {
            DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception ignore) {
        }
    }

    private static class NameParts {
        final String base;
        final String ext;

        NameParts(String base, String ext) {
            this.base = base;
            this.ext = ext;
        }
    }
}
