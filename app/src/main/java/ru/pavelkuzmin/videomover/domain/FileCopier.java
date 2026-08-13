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
import ru.pavelkuzmin.videomover.data.TransferJournal;
import ru.pavelkuzmin.videomover.util.HashUtil;

/**
 * Copies one source through an app-owned temporary document. A successful result always means that
 * the final destination was read back and compared with the bytes read from the source.
 */
public final class FileCopier {
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
    public static final String ERROR_STAGE_VERIFY_HASH = "verify_hash";
    public static final String ERROR_STAGE_DEST_RENAME = "dest_rename";
    public static final String ERROR_STAGE_UNKNOWN = "unknown";

    public static final String ERROR_CATEGORY_PERMISSION = "PERMISSION";
    public static final String ERROR_CATEGORY_SOURCE = "SOURCE_MISSING_OR_CHANGED";
    public static final String ERROR_CATEGORY_DESTINATION = "MEDIA_OR_PROVIDER";
    public static final String ERROR_CATEGORY_INTEGRITY = "INTEGRITY_FAILURE";
    public static final String ERROR_CATEGORY_AMBIGUOUS = "AMBIGUOUS_MUTATION";
    public static final String ERROR_CATEGORY_UNKNOWN = "UNKNOWN";

    private FileCopier() {
    }

    public interface ProgressCallback {
        void onProgress(long writtenBytes, long totalBytes);
    }

    public interface CancelChecker {
        boolean isCanceled();
    }

    /** Journal writes happen before the next externally visible side effect. */
    public interface LifecycleCallback {
        void onTemporaryCreated(Uri tempUri);

        void onVerificationStarted(String sourceHash, Uri tempUri);

        void onPublishingStarted(String sourceHash, Uri tempUri, String finalName);

        void onFinalVerified(boolean duplicate, String sourceHash, Uri finalUri, String finalName);
    }

    public static final class Result {
        public final boolean ok;
        public final boolean duplicate;
        public final boolean canceled;
        public final String finalName;
        public final long bytes;
        public final String sha256;
        public final String error;
        public final String errorStage;
        public final String errorCategory;
        public final boolean destinationIssue;
        public final Uri tempUri;
        public final Uri finalUri;

        private Result(boolean ok, boolean duplicate, boolean canceled, String finalName, long bytes,
                       String sha256, String error, String errorStage, String errorCategory,
                       boolean destinationIssue, Uri tempUri, Uri finalUri) {
            this.ok = ok;
            this.duplicate = duplicate;
            this.canceled = canceled;
            this.finalName = finalName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.error = error;
            this.errorStage = errorStage;
            this.errorCategory = errorCategory;
            this.destinationIssue = destinationIssue;
            this.tempUri = tempUri;
            this.finalUri = finalUri;
        }
    }

    public static Result copyWithSha256(Context context, Uri sourceUri, String displayName,
                                        long expectedSize, DocumentFile destination,
                                        ProgressCallback progressCallback,
                                        CancelChecker cancelChecker,
                                        LifecycleCallback lifecycleCallback,
                                        String temporaryName) {
        ContentResolver resolver = context.getContentResolver();
        Uri tempUri = null;
        String finalName = null;
        long written = 0L;
        String sourceHash = null;
        String stage = ERROR_STAGE_UNKNOWN;
        boolean destinationIssue = false;

        try {
            stage = ERROR_STAGE_DEST_ACCESS;
            destinationIssue = true;
            if (destination == null || !destination.exists() || !destination.canWrite()) {
                return error(context, stage, ERROR_CATEGORY_DESTINATION, true, written, null, null, null,
                        context.getString(R.string.no_write_access));
            }

            stage = ERROR_STAGE_DEST_CREATE_TEMP;
            DocumentFile temporary = destination.createFile("application/octet-stream", temporaryName);
            if (temporary == null) {
                return error(context, stage, ERROR_CATEGORY_DESTINATION, true, written, null, null, null,
                        context.getString(R.string.filecopier_err_create_temp));
            }
            tempUri = temporary.getUri();
            lifecycleCallback.onTemporaryCreated(tempUri);
            throwIfCanceled(cancelChecker);

            MessageDigest sourceDigest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];

            stage = ERROR_STAGE_SOURCE_OPEN;
            destinationIssue = false;
            try (InputStream input = resolver.openInputStream(sourceUri)) {
                if (input == null) {
                    return error(context, stage, ERROR_CATEGORY_SOURCE, false, written, null, tempUri, null,
                            context.getString(R.string.filecopier_err_stream_access));
                }

                stage = ERROR_STAGE_DEST_OPEN_TEMP;
                destinationIssue = true;
                try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(tempUri, "w");
                     OutputStream output = descriptor == null ? null
                             : new FileOutputStream(descriptor.getFileDescriptor())) {
                    if (output == null) {
                        return error(context, stage, ERROR_CATEGORY_DESTINATION, true, written, null, tempUri, null,
                                context.getString(R.string.filecopier_err_stream_access));
                    }
                    while (true) {
                        throwIfCanceled(cancelChecker);
                        stage = ERROR_STAGE_SOURCE_READ;
                        destinationIssue = false;
                        int read = input.read(buffer);
                        if (read == -1) {
                            break;
                        }
                        if (read == 0) {
                            throw new IllegalStateException("Source returned zero bytes without reaching EOF");
                        }
                        sourceDigest.update(buffer, 0, read);
                        stage = ERROR_STAGE_DEST_WRITE;
                        destinationIssue = true;
                        output.write(buffer, 0, read);
                        written += read;
                        if (progressCallback != null) {
                            progressCallback.onProgress(written, expectedSize);
                        }
                    }
                    stage = ERROR_STAGE_DEST_FLUSH;
                    output.flush();
                    stage = ERROR_STAGE_DEST_SYNC;
                    descriptor.getFileDescriptor().sync();
                }
            }

            throwIfCanceled(cancelChecker);
            stage = ERROR_STAGE_VERIFY_SIZE;
            if (expectedSize > 0 && written != expectedSize) {
                return error(context, stage, ERROR_CATEGORY_SOURCE, false, written, null, tempUri, null,
                        context.getString(R.string.filecopier_err_size_mismatch));
            }

            sourceHash = HashUtil.toHex(sourceDigest.digest());
            lifecycleCallback.onVerificationStarted(sourceHash, tempUri);
            stage = ERROR_STAGE_VERIFY_HASH;
            destinationIssue = true;
            String temporaryHash = sha256Of(context, tempUri);
            if (!sourceHash.equals(temporaryHash)) {
                return error(context, stage, ERROR_CATEGORY_INTEGRITY, true, written, sourceHash, tempUri, null,
                        context.getString(R.string.filecopier_err_hash_mismatch));
            }

            // A name and a size do not prove that a previous transfer has the same video.
            DocumentFile existing = destination.findFile(displayName);
            if (existing != null && existing.isFile()) {
                String existingHash = sha256Of(context, existing.getUri());
                if (sourceHash.equals(existingHash)) {
                    deleteTemporaryQuietly(resolver, tempUri);
                    lifecycleCallback.onFinalVerified(true, sourceHash, existing.getUri(), existing.getName());
                    return new Result(true, true, false, existing.getName(), written, sourceHash,
                            null, null, null, false, null, existing.getUri());
                }
            }

            finalName = ensureUniqueName(destination, displayName);
            lifecycleCallback.onPublishingStarted(sourceHash, tempUri, finalName);
            stage = ERROR_STAGE_DEST_RENAME;
            Uri renamed = DocumentsContract.renameDocument(resolver, tempUri, finalName);
            if (renamed == null) {
                return error(context, stage, ERROR_CATEGORY_AMBIGUOUS, true, written, sourceHash, tempUri, null,
                        context.getString(R.string.filecopier_err_rename_failed));
            }

            stage = ERROR_STAGE_VERIFY_HASH;
            String finalHash = sha256Of(context, renamed);
            if (!sourceHash.equals(finalHash)) {
                return error(context, stage, ERROR_CATEGORY_INTEGRITY, true, written, sourceHash, null, renamed,
                        context.getString(R.string.filecopier_err_hash_mismatch));
            }
            lifecycleCallback.onFinalVerified(false, sourceHash, renamed, finalName);
            return new Result(true, false, false, finalName, written, sourceHash,
                    null, null, null, false, null, renamed);
        } catch (CopyCanceledException ignored) {
            return new Result(false, false, true, finalName, written, sourceHash, null,
                    null, null, false, tempUri, null);
        } catch (SecurityException e) {
            return error(context, stage, ERROR_CATEGORY_PERMISSION, destinationIssue, written, sourceHash,
                    tempUri, null, errorText(e));
        } catch (Exception e) {
            String category = ERROR_STAGE_DEST_RENAME.equals(stage)
                    ? ERROR_CATEGORY_AMBIGUOUS
                    : (ERROR_STAGE_VERIFY_HASH.equals(stage) ? ERROR_CATEGORY_INTEGRITY
                    : (destinationIssue ? ERROR_CATEGORY_DESTINATION : ERROR_CATEGORY_SOURCE));
            return error(context, stage, category, destinationIssue, written, sourceHash, tempUri, null,
                    errorText(e));
        }
    }

    public static String temporaryName(long operationId, int itemIndex) {
        return TransferJournal.TEMP_PREFIX + operationId + "-" + itemIndex + "-"
                + System.nanoTime() + ".partial";
    }

    public static String sha256Of(Context context, Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IllegalStateException("Cannot open stream for hash");
            }
            while (true) {
                int read = input.read(buffer);
                if (read == -1) break;
                if (read == 0) throw new IllegalStateException("Stream returned zero bytes without EOF");
                digest.update(buffer, 0, read);
            }
        }
        return HashUtil.toHex(digest.digest());
    }

    public static void deleteOwnedTemporary(Context context, Uri uri) {
        if (uri == null) return;
        try {
            DocumentFile temporary = DocumentFile.fromSingleUri(context, uri);
            String name = temporary == null ? null : temporary.getName();
            if (name != null && name.startsWith(TransferJournal.TEMP_PREFIX)) {
                DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            }
        } catch (Exception ignored) {
            // The journal will retry cleanup after the next successful connection to the same tree.
        }
    }

    private static Result error(Context context, String stage, String category, boolean destinationIssue,
                                long written, String hash, Uri tempUri, Uri finalUri, String message) {
        return new Result(false, false, false, null, written, hash,
                TextUtils.isEmpty(message) ? context.getString(R.string.filecopier_err_unknown) : message,
                stage, category, destinationIssue, tempUri, finalUri);
    }

    private static String ensureUniqueName(DocumentFile directory, String requestedName) {
        NameParts parts = splitName(requestedName);
        String candidate = parts.base + parts.extension;
        int suffix = 1;
        while (directory.findFile(candidate) != null) {
            candidate = parts.base + " (" + suffix++ + ")" + parts.extension;
        }
        return candidate;
    }

    private static NameParts splitName(String displayName) {
        String base = TextUtils.isEmpty(displayName) ? "video" : displayName.trim();
        String extension = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1) {
            extension = base.substring(dot);
            base = base.substring(0, dot);
        }
        if (TextUtils.isEmpty(base)) base = "video";
        return new NameParts(base, extension);
    }

    private static void deleteTemporaryQuietly(ContentResolver resolver, Uri uri) {
        try {
            if (uri != null) DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
            // The verified target is still safe. Recovery will clean our uniquely named temporary file.
        }
    }

    private static void throwIfCanceled(CancelChecker cancelChecker) throws CopyCanceledException {
        if (cancelChecker != null && cancelChecker.isCanceled()) {
            throw new CopyCanceledException();
        }
    }

    private static String errorText(Exception error) {
        String text = error.getMessage();
        return error.getClass().getSimpleName() + (TextUtils.isEmpty(text) ? "" : ": " + text);
    }

    private static final class NameParts {
        final String base;
        final String extension;

        NameParts(String base, String extension) {
            this.base = base;
            this.extension = extension;
        }
    }

    private static final class CopyCanceledException extends Exception {
    }
}
