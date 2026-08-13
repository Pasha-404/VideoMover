package ru.pavelkuzmin.videomover.domain;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.List;

import ru.pavelkuzmin.videomover.data.TransferJournal;

/** Reconciles only app-owned temporary artifacts after an interrupted operation. */
public final class TransferRecovery {
    private TransferRecovery() {
    }

    public static void reconcileInterruptedOperations(Context context, TransferJournal journal) {
        List<TransferJournal.InterruptedItem> items = journal.interruptAndGetUnfinished();
        ContentResolver resolver = context.getContentResolver();
        for (TransferJournal.InterruptedItem item : items) {
            try {
                DocumentFile destination = DocumentFile.fromTreeUri(context, item.destinationTreeUri);
                if (destination == null || !destination.canRead()) {
                    continue;
                }

                // A rename may have completed even though its response was lost. Verify that first.
                DocumentFile published = item.finalUri == null ? null : DocumentFile.fromSingleUri(context, item.finalUri);
                if (published == null && item.finalName != null) {
                    published = destination.findFile(item.finalName);
                }
                if (published != null && published.isFile() && item.sourceHash != null
                        && item.sourceHash.equals(FileCopier.sha256Of(context, published.getUri()))) {
                    journal.markItemVerified(item.operationId, item.itemIndex, false, item.sourceHash,
                            published.getUri(), published.getName());
                    continue;
                }

                // A partial is safe to remove only when it has our per-operation private name.
                if (item.tempUri != null) {
                    DocumentFile temporary = DocumentFile.fromSingleUri(context, item.tempUri);
                    String name = temporary == null ? null : temporary.getName();
                    if (name != null && name.startsWith(TransferJournal.TEMP_PREFIX)) {
                        resolver.delete(item.tempUri, null, null);
                    }
                }
            } catch (Exception ignored) {
                // The target can disappear between any two calls. It stays marked for reconciliation.
            }
        }
    }
}
