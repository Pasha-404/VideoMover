package ru.pavelkuzmin.videomover.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Short, app-private history for recoverable transfer boundaries. This database is not a log file
 * on the phone or USB drive and is pruned automatically.
 */
public final class TransferJournal extends SQLiteOpenHelper {
    public static final String TEMP_PREFIX = ".videomover-";

    public static final String OP_ACTIVE = "ACTIVE";
    public static final String OP_INTERRUPTED = "INTERRUPTED";
    public static final String OP_DONE = "DONE";
    public static final String OP_CANCELED = "CANCELED";
    public static final String OP_ERROR = "ERROR";

    public static final String ITEM_QUEUED = "QUEUED";
    public static final String ITEM_COPYING = "COPYING";
    public static final String ITEM_VERIFYING = "VERIFYING";
    public static final String ITEM_PUBLISHING = "PUBLISHING";
    public static final String ITEM_VERIFIED = "VERIFIED";
    public static final String ITEM_DUPLICATE = "DUPLICATE";
    public static final String ITEM_DELETION_PENDING = "DELETION_PENDING";
    public static final String ITEM_DELETED = "DELETED";
    public static final String ITEM_SOURCE_RETAINED = "SOURCE_RETAINED";
    public static final String ITEM_FAILED = "FAILED";
    public static final String ITEM_CANCELED = "CANCELED";
    public static final String ITEM_NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";

    private static final String DB_NAME = "transfer_journal.db";
    private static final int DB_VERSION = 1;
    private static final long RETENTION_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final int MAX_TERMINAL_OPERATIONS = 8;

    private static volatile TransferJournal instance;

    public static TransferJournal get(Context context) {
        if (instance == null) {
            synchronized (TransferJournal.class) {
                if (instance == null) {
                    instance = new TransferJournal(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private TransferJournal(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operations ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "state TEXT NOT NULL, destination_uri TEXT NOT NULL,"
                + "delete_after INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE items ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "operation_id INTEGER NOT NULL, item_index INTEGER NOT NULL,"
                + "source_uri TEXT NOT NULL, display_name TEXT NOT NULL, expected_size INTEGER NOT NULL,"
                + "state TEXT NOT NULL, source_sha256 TEXT, temp_uri TEXT, final_uri TEXT, final_name TEXT,"
                + "error_category TEXT, error_message TEXT, updated_at INTEGER NOT NULL,"
                + "UNIQUE(operation_id, item_index))");
        db.execSQL("CREATE INDEX items_operation_state ON items(operation_id, state)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS items");
        db.execSQL("DROP TABLE IF EXISTS operations");
        onCreate(db);
    }

    public synchronized long beginOperation(Uri destinationUri, boolean deleteAfter) {
        prune();
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("state", OP_ACTIVE);
        values.put("destination_uri", destinationUri.toString());
        values.put("delete_after", deleteAfter ? 1 : 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        return getWritableDatabase().insertOrThrow("operations", null, values);
    }

    public synchronized void addItems(long operationId, List<MediaQuery.VideoItem> values) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int index = 0; index < values.size(); index++) {
                MediaQuery.VideoItem item = values.get(index);
                ContentValues row = new ContentValues();
                row.put("operation_id", operationId);
                row.put("item_index", index);
                row.put("source_uri", item.uri.toString());
                row.put("display_name", item.displayName);
                row.put("expected_size", item.size);
                row.put("state", ITEM_QUEUED);
                row.put("updated_at", System.currentTimeMillis());
                db.insertOrThrow("items", null, row);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void markItemCopying(long operationId, int index, Uri tempUri) {
        updateItem(operationId, index, ITEM_COPYING, null, null, tempUri, null, null, null);
    }

    public synchronized void markItemVerifying(long operationId, int index, String sourceHash, Uri tempUri) {
        updateItem(operationId, index, ITEM_VERIFYING, sourceHash, null, tempUri, null, null, null);
    }

    public synchronized void markItemPublishing(long operationId, int index, String sourceHash,
                                                Uri tempUri, String finalName) {
        updateItem(operationId, index, ITEM_PUBLISHING, sourceHash, finalName, tempUri, null, null, null);
    }

    public synchronized void markItemVerified(long operationId, int index, boolean duplicate,
                                              String sourceHash, Uri finalUri, String finalName) {
        ContentValues values = baseValues(duplicate ? ITEM_DUPLICATE : ITEM_VERIFIED);
        values.put("source_sha256", sourceHash);
        values.put("final_name", finalName);
        values.put("final_uri", finalUri.toString());
        values.putNull("temp_uri");
        getWritableDatabase().update("items", values, "operation_id=? AND item_index=?",
                new String[]{String.valueOf(operationId), String.valueOf(index)});
    }

    public synchronized void markItemFailed(long operationId, int index, String category,
                                            String error, Uri tempUri) {
        updateItem(operationId, index, ITEM_FAILED, null, null, tempUri, null, category, error);
    }

    public synchronized void markItemCanceled(long operationId, int index, Uri tempUri) {
        updateItem(operationId, index, ITEM_CANCELED, null, null, tempUri, null, null, null);
    }

    public synchronized void markItemsForDeletion(long operationId, List<Uri> sourceUris) {
        SQLiteDatabase db = getWritableDatabase();
        for (Uri sourceUri : sourceUris) {
            ContentValues values = baseValues(ITEM_DELETION_PENDING);
            db.update("items", values, "operation_id=? AND source_uri=?",
                    new String[]{String.valueOf(operationId), sourceUri.toString()});
        }
    }

    public synchronized void finishDeletion(long operationId, List<Uri> sourceUris, boolean deleted) {
        SQLiteDatabase db = getWritableDatabase();
        for (Uri sourceUri : sourceUris) {
            ContentValues values = baseValues(deleted ? ITEM_DELETED : ITEM_SOURCE_RETAINED);
            db.update("items", values, "operation_id=? AND source_uri=?",
                    new String[]{String.valueOf(operationId), sourceUri.toString()});
        }
    }

    public synchronized List<DeleteCandidate> getDeleteCandidates(long operationId) {
        ArrayList<DeleteCandidate> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("items",
                new String[]{"source_uri", "source_sha256"},
                "operation_id=? AND state IN (?,?)", new String[]{String.valueOf(operationId), ITEM_VERIFIED, ITEM_DUPLICATE},
                null, null, "item_index ASC")) {
            while (cursor.moveToNext()) {
                String source = cursor.getString(0);
                String hash = cursor.getString(1);
                if (!TextUtils.isEmpty(source) && !TextUtils.isEmpty(hash)) {
                    out.add(new DeleteCandidate(Uri.parse(source), hash));
                }
            }
        }
        return out;
    }

    public synchronized ArrayList<Uri> getPendingDeletionUris(long operationId) {
        ArrayList<Uri> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("items", new String[]{"source_uri"},
                "operation_id=? AND state=?", new String[]{String.valueOf(operationId), ITEM_DELETION_PENDING},
                null, null, "item_index ASC")) {
            while (cursor.moveToNext()) {
                String source = cursor.getString(0);
                if (!TextUtils.isEmpty(source)) out.add(Uri.parse(source));
            }
        }
        return out;
    }

    public synchronized void finishOperation(long operationId, String state) {
        ContentValues values = baseValues(state);
        getWritableDatabase().update("operations", values, "_id=?", new String[]{String.valueOf(operationId)});
        prune();
    }

    public synchronized List<InterruptedItem> interruptAndGetUnfinished() {
        SQLiteDatabase db = getWritableDatabase();
        ArrayList<InterruptedItem> out = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT i._id, i.operation_id, i.item_index, i.temp_uri, i.final_uri,"
                        + " i.final_name, i.source_sha256, o.destination_uri FROM items i"
                        + " JOIN operations o ON o._id=i.operation_id"
                        + " WHERE i.temp_uri IS NOT NULL AND i.state NOT IN (?,?,?)",
                new String[]{ITEM_VERIFIED, ITEM_DUPLICATE, ITEM_DELETED})) {
            while (cursor.moveToNext()) {
                out.add(new InterruptedItem(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2),
                        stringUri(cursor, 3), stringUri(cursor, 4), cursor.getString(5), cursor.getString(6),
                        Uri.parse(cursor.getString(7))));
            }
        }
        ContentValues itemState = baseValues(ITEM_NEEDS_RECONCILIATION);
        db.update("items", itemState, "operation_id IN (SELECT _id FROM operations WHERE state=?)"
                        + " AND state IN (?,?,?,?)",
                new String[]{OP_ACTIVE, ITEM_COPYING, ITEM_VERIFYING, ITEM_PUBLISHING, ITEM_DELETION_PENDING});
        ContentValues operationState = baseValues(OP_INTERRUPTED);
        db.update("operations", operationState, "state=?", new String[]{OP_ACTIVE});
        return out;
    }

    public synchronized boolean hasInterruptedOperation() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM operations WHERE state=? LIMIT 1",
                new String[]{OP_INTERRUPTED})) {
            return cursor.moveToFirst();
        }
    }

    private void updateItem(long operationId, int index, String state, String sourceHash,
                            String finalName, Uri tempUri, Uri finalUri, String category, String error) {
        ContentValues values = baseValues(state);
        if (sourceHash != null) values.put("source_sha256", sourceHash);
        if (finalName != null) values.put("final_name", finalName);
        if (tempUri != null) values.put("temp_uri", tempUri.toString());
        if (finalUri != null) values.put("final_uri", finalUri.toString());
        if (category != null) values.put("error_category", category);
        if (error != null) values.put("error_message", error);
        getWritableDatabase().update("items", values, "operation_id=? AND item_index=?",
                new String[]{String.valueOf(operationId), String.valueOf(index)});
    }

    private ContentValues baseValues(String state) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("updated_at", System.currentTimeMillis());
        return values;
    }

    private void prune() {
        SQLiteDatabase db = getWritableDatabase();
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        ArrayList<Long> staleIds = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT _id FROM operations WHERE state IN (?,?,?) AND updated_at<?",
                new String[]{OP_DONE, OP_CANCELED, OP_ERROR, String.valueOf(cutoff)})) {
            while (cursor.moveToNext()) staleIds.add(cursor.getLong(0));
        }
        try (Cursor cursor = db.rawQuery("SELECT _id FROM operations WHERE state IN (?,?,?)"
                        + " ORDER BY updated_at DESC LIMIT -1 OFFSET ?",
                new String[]{OP_DONE, OP_CANCELED, OP_ERROR, String.valueOf(MAX_TERMINAL_OPERATIONS)})) {
            while (cursor.moveToNext()) staleIds.add(cursor.getLong(0));
        }
        for (Long id : staleIds) {
            db.delete("items", "operation_id=?", new String[]{String.valueOf(id)});
            db.delete("operations", "_id=?", new String[]{String.valueOf(id)});
        }
    }

    private static Uri stringUri(Cursor cursor, int column) {
        String value = cursor.getString(column);
        return TextUtils.isEmpty(value) ? null : Uri.parse(value);
    }

    public static final class DeleteCandidate {
        public final Uri sourceUri;
        public final String sourceHash;

        DeleteCandidate(Uri sourceUri, String sourceHash) {
            this.sourceUri = sourceUri;
            this.sourceHash = sourceHash;
        }
    }

    public static final class InterruptedItem {
        public final long itemId;
        public final long operationId;
        public final int itemIndex;
        public final Uri tempUri;
        public final Uri finalUri;
        public final String finalName;
        public final String sourceHash;
        public final Uri destinationTreeUri;

        InterruptedItem(long itemId, long operationId, int itemIndex, Uri tempUri, Uri finalUri,
                        String finalName, String sourceHash, Uri destinationTreeUri) {
            this.itemId = itemId;
            this.operationId = operationId;
            this.itemIndex = itemIndex;
            this.tempUri = tempUri;
            this.finalUri = finalUri;
            this.finalName = finalName;
            this.sourceHash = sourceHash;
            this.destinationTreeUri = destinationTreeUri;
        }
    }
}
