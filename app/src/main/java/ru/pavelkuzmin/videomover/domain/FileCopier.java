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

    public static final String ERROR_STAGE_DEST_ACCESS = "dest_access";
    public static final String ERROR_STAGE_DEST_CREATE_TEMP = "dest_create_temp";
    public static final String ERROR_STAGE_SOURCE_OPEN = "source_open";
    public static final String ERROR_STAGE_DEST_OPEN_TEMP = "dest_open_temp";
    public static final String ERROR_STAGE_SOURCE_READ = "source_read";
    public static final String ERROR_STAGE_DEST_WRITE = "dest_write";
    public static final String ERROR_STAGE_DEST_FLUSH = "dest_flush";
    public static final String ERROR_STAGE_DEST_SYNC = "dest_sync";
    public static final String ERROR_STAGE_VERIFY_SIZE = "verify_size";
    public static final String ERROR_STAGE_DEST_RENAME = "dest_rename";
    public static final String ERROR_STAGE_UNKNOWN = "unknown";

    public interface ProgressCallback {
        void onProgress(long writtenBytes, long totalBytes);
    }

    public interface CancelChecker {
        boolean isCanceled();
    }

    public static class Result {
        public final boolean ok;
        public final boolean duplicate;
        public final boolean canceled;
        public final String finalName;
        public final long bytes;
        public final String sha256;
        public final String error;
        public final String errorStage;
        public final boolean destinationIssue;

        public Result(boolean ok, boolean duplicate, String finalName, long bytes, String sha256, String error) {
            this(ok, duplicate, false, finalName, bytes, sha256, error);
        }

        public Result(boolean ok, boolean duplicate, boolean canceled, String finalName,
                      long bytes, String sha256, String error) {
            this(ok, duplicate, canceled, finalName, bytes, sha256, error, null, false);
        }

        public Result(boolean ok, boolean duplicate, boolean canceled, String finalName,
                      long bytes, String sha256, String error, String errorStage,
                      boolean destinationIssue) {
            this.ok = ok;
            this.duplicate = duplicate;
            this.canceled = canceled;
            this.finalName = finalName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.error = error;
            this.errorStage = errorStage;
            this.destinationIssue = destinationIssue;
        }
    }

    public static Result copyWithSha256(Context ctx, Uri srcUri, String displayName,
                                        long expectedSize, DocumentFile destDir) {
        return copyWithSha256(ctx, srcUri, displayName, expectedSize, destDir, null);
    }

    public static Result copyWithSha256(Context ctx, Uri srcUri, String displayName,
                                        long expectedSize, DocumentFile destDir,
                                        ProgressCallback progressCallback) {
        return copyWithSha256(ctx, srcUri, displayName, expectedSize, destDir, progressCallback, null);
    }

    public static Result copyWithSha256(Context ctx, Uri srcUri, String displayName,
                                        long expectedSize, DocumentFile destDir,
                                        ProgressCallback progressCallback,
                                        CancelChecker cancelChecker) {
        ContentResolver cr = ctx.getContentResolver();
        Uri tempUri = null;
        long written = 0;
        String hash = null;
        String errorStage = ERROR_STAGE_UNKNOWN;
        boolean destinationIssue = false;

        try {
            errorStage = ERROR_STAGE_DEST_ACCESS;
            destinationIssue = true;
            if (destDir == null || !destDir.canWrite()) {
                return error(ctx, ERROR_STAGE_DEST_ACCESS, true, 0, null,
                        ctx.getString(R.string.no_write_access));
            }

            NameParts parts = splitName(displayName);
            DocumentFile existing = destDir.findFile(displayName);
            if (existing != null && existing.isFile() && expectedSize > 0 && existing.length() == expectedSize) {
                return new Result(true, true, displayName, expectedSize, null, null);
            }

            String finalName = ensureUniqueName(destDir, parts.base, parts.ext);
            errorStage = ERROR_STAGE_DEST_CREATE_TEMP;
            DocumentFile tempFile = destDir.createFile("video/*", finalName + ".partial");
            if (tempFile == null) {
                return error(ctx, ERROR_STAGE_DEST_CREATE_TEMP, true, 0, null,
                        ctx.getString(R.string.filecopier_err_create_temp));
            }
            tempUri = tempFile.getUri();
            if (isCanceled(cancelChecker)) {
                deleteQuietly(cr, tempUri);
                return canceled(written, hash);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];

            errorStage = ERROR_STAGE_SOURCE_OPEN;
            destinationIssue = false;
            try (InputStream in = cr.openInputStream(srcUri)) {
                if (in == null) {
                    deleteQuietly(cr, tempUri);
                    return error(ctx, ERROR_STAGE_SOURCE_OPEN, false, written, null,
                            ctx.getString(R.string.filecopier_err_stream_access));
                }

                errorStage = ERROR_STAGE_DEST_OPEN_TEMP;
                destinationIssue = true;
                try (ParcelFileDescriptor outDescriptor = cr.openFileDescriptor(tempUri, "w");
                     OutputStream out = outDescriptor == null
                             ? null
                             : new FileOutputStream(outDescriptor.getFileDescriptor())) {
                    if (out == null) {
                        deleteQuietly(cr, tempUri);
                        return error(ctx, ERROR_STAGE_DEST_OPEN_TEMP, true, written, null,
                                ctx.getString(R.string.filecopier_err_stream_access));
                    }

                    int read;
                    while (true) {
                        throwIfCanceled(cancelChecker);
                        errorStage = ERROR_STAGE_SOURCE_READ;
                        destinationIssue = false;
                        read = in.read(buffer);
                        if (read == -1) {
                            break;
                        }

                        digest.update(buffer, 0, read);
                        errorStage = ERROR_STAGE_DEST_WRITE;
                        destinationIssue = true;
                        out.write(buffer, 0, read);
                        written += read;
                        if (progressCallback != null) {
                            progressCallback.onProgress(written, expectedSize);
                        }
                        throwIfCanceled(cancelChecker);
                    }
                    errorStage = ERROR_STAGE_DEST_FLUSH;
                    destinationIssue = true;
                    out.flush();
                    if (outDescriptor != null) {
                        errorStage = ERROR_STAGE_DEST_SYNC;
                        outDescriptor.getFileDescriptor().sync();
                    }
                }
            }

            if (isCanceled(cancelChecker)) {
                deleteQuietly(cr, tempUri);
                return canceled(written, hash);
            }

            errorStage = ERROR_STAGE_VERIFY_SIZE;
            destinationIssue = false;
            if (expectedSize > 0 && written != expectedSize) {
                deleteQuietly(cr, tempUri);
                return error(ctx, ERROR_STAGE_VERIFY_SIZE, false, written, null,
                        ctx.getString(R.string.filecopier_err_size_mismatch));
            }

            hash = HashUtil.toHex(digest.digest());
            errorStage = ERROR_STAGE_DEST_RENAME;
            destinationIssue = true;
            Uri renamed = DocumentsContract.renameDocument(cr, tempUri, finalName);
            if (renamed == null) {
                deleteQuietly(cr, tempUri);
                return error(ctx, ERROR_STAGE_DEST_RENAME, true, written, hash,
                        ctx.getString(R.string.filecopier_err_rename_failed));
            }

            return new Result(true, false, finalName, written, hash, null);
        } catch (CopyCanceledException e) {
            deleteQuietly(cr, tempUri);
            return canceled(written, hash);
        } catch (SecurityException e) {
            deleteQuietly(cr, tempUri);
            return error(ctx, errorStage, destinationIssue, written, hash,
                    "SecurityException: " + e.getMessage());
        } catch (Exception e) {
            deleteQuietly(cr, tempUri);
            return error(ctx, errorStage, destinationIssue, written, hash,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Result canceled(long written, String hash) {
        return new Result(false, false, true, null, written, hash, null);
    }

    private static Result error(Context ctx, String errorStage, boolean destinationIssue,
                                long written, String hash, String message) {
        return new Result(false, false, false, null, written, hash,
                TextUtils.isEmpty(message) ? ctx.getString(R.string.filecopier_err_unknown) : message,
                errorStage, destinationIssue);
    }

    private static boolean isCanceled(CancelChecker cancelChecker) {
        return cancelChecker != null && cancelChecker.isCanceled();
    }

    private static void throwIfCanceled(CancelChecker cancelChecker) throws CopyCanceledException {
        if (isCanceled(cancelChecker)) {
            throw new CopyCanceledException();
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

    private static class CopyCanceledException extends Exception {
    }
}
