package ru.pavelkuzmin.videomover.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public final class OperationStateStore {
    private static final String PREFS = "videomover_operation_state";

    private static final String KEY_HAS_STATE = "has_state";
    private static final String KEY_RESULT_HANDLED = "result_handled";
    private static final String KEY_DELETE_REQUESTED = "delete_requested";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_DONE = "done";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_COPIED = "copied";
    private static final String KEY_FAIL = "fail";
    private static final String KEY_DUPLICATES = "duplicates";
    private static final String KEY_CURRENT_NAME = "current_name";
    private static final String KEY_CURRENT_BYTES = "current_bytes";
    private static final String KEY_CURRENT_TOTAL_BYTES = "current_total_bytes";
    private static final String KEY_COPIED_BYTES = "copied_bytes";
    private static final String KEY_TOTAL_BYTES = "total_bytes";
    private static final String KEY_AVAILABLE_BYTES = "available_bytes";
    private static final String KEY_SPEED_BYTES_PER_SECOND = "speed_bytes_per_second";
    private static final String KEY_ERROR_MESSAGE = "error_message";
    private static final String KEY_TO_DELETE = "to_delete";
    private static final String KEY_ERROR_REPORT = "error_report";
    private static final String KEY_OPERATION_ID = "operation_id";

    private OperationStateStore() {}

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static void saveProgress(Context context, int stage, int done, int total,
                                    int copied, int fail, int duplicates,
                                    String currentName, long currentBytes,
                                    long currentTotalBytes, long copiedBytes,
                                    long totalBytes, long availableBytes,
                                    long speedBytesPerSecond, String errorMessage) {
        prefs(context).edit()
                .putBoolean(KEY_HAS_STATE, true)
                .putBoolean(KEY_RESULT_HANDLED, false)
                .putBoolean(KEY_DELETE_REQUESTED, false)
                .putInt(KEY_STAGE, stage)
                .putInt(KEY_DONE, done)
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_COPIED, copied)
                .putInt(KEY_FAIL, fail)
                .putInt(KEY_DUPLICATES, duplicates)
                .putString(KEY_CURRENT_NAME, currentName)
                .putLong(KEY_CURRENT_BYTES, currentBytes)
                .putLong(KEY_CURRENT_TOTAL_BYTES, currentTotalBytes)
                .putLong(KEY_COPIED_BYTES, copiedBytes)
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putLong(KEY_AVAILABLE_BYTES, availableBytes)
                .putLong(KEY_SPEED_BYTES_PER_SECOND, speedBytesPerSecond)
                .putString(KEY_ERROR_MESSAGE, errorMessage)
                .putString(KEY_TO_DELETE, "")
                .putString(KEY_ERROR_REPORT, "")
                .apply();
    }

    public static void saveFinished(Context context, int stage, int total, int copied,
                                    int fail, int duplicates, long copiedBytes,
                                    long totalBytes, long availableBytes,
                                    String errorMessage, List<String> toDelete) {
        saveFinished(context, stage, total, copied, fail, duplicates, copiedBytes,
                totalBytes, availableBytes, errorMessage, toDelete, new ArrayList<>(), 0L);
    }

    public static void saveFinished(Context context, int stage, int total, int copied,
                                    int fail, int duplicates, long copiedBytes,
                                    long totalBytes, long availableBytes,
                                    String errorMessage, List<String> toDelete,
                                    List<String> errorReport) {
        saveFinished(context, stage, total, copied, fail, duplicates, copiedBytes, totalBytes,
                availableBytes, errorMessage, toDelete, errorReport, 0L);
    }

    public static void saveFinished(Context context, int stage, int total, int copied,
                                    int fail, int duplicates, long copiedBytes,
                                    long totalBytes, long availableBytes,
                                    String errorMessage, List<String> toDelete,
                                    List<String> errorReport, long operationId) {
        prefs(context).edit()
                .putBoolean(KEY_HAS_STATE, true)
                .putBoolean(KEY_RESULT_HANDLED, false)
                .putBoolean(KEY_DELETE_REQUESTED, false)
                .putInt(KEY_STAGE, stage)
                .putInt(KEY_DONE, total)
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_COPIED, copied)
                .putInt(KEY_FAIL, fail)
                .putInt(KEY_DUPLICATES, duplicates)
                .putString(KEY_CURRENT_NAME, null)
                .putLong(KEY_CURRENT_BYTES, 0L)
                .putLong(KEY_CURRENT_TOTAL_BYTES, 0L)
                .putLong(KEY_COPIED_BYTES, copiedBytes)
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putLong(KEY_AVAILABLE_BYTES, availableBytes)
                .putLong(KEY_SPEED_BYTES_PER_SECOND, 0L)
                .putString(KEY_ERROR_MESSAGE, errorMessage)
                .putString(KEY_TO_DELETE, joinLines(toDelete))
                .putString(KEY_ERROR_REPORT, joinLines(errorReport))
                .putLong(KEY_OPERATION_ID, operationId)
                .apply();
    }

    public static void markDeleteRequestStarted(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_DELETE_REQUESTED, true)
                .apply();
    }

    public static void markResultHandled(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_RESULT_HANDLED, true)
                .apply();
    }

    public static Snapshot read(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_HAS_STATE, false)) {
            return null;
        }
        return new Snapshot(
                prefs.getBoolean(KEY_RESULT_HANDLED, false),
                prefs.getBoolean(KEY_DELETE_REQUESTED, false),
                prefs.getInt(KEY_STAGE, 0),
                prefs.getInt(KEY_DONE, 0),
                prefs.getInt(KEY_TOTAL, 0),
                prefs.getInt(KEY_COPIED, 0),
                prefs.getInt(KEY_FAIL, 0),
                prefs.getInt(KEY_DUPLICATES, 0),
                prefs.getString(KEY_CURRENT_NAME, null),
                prefs.getLong(KEY_CURRENT_BYTES, 0L),
                prefs.getLong(KEY_CURRENT_TOTAL_BYTES, 0L),
                prefs.getLong(KEY_COPIED_BYTES, 0L),
                prefs.getLong(KEY_TOTAL_BYTES, 0L),
                prefs.getLong(KEY_AVAILABLE_BYTES, -1L),
                prefs.getLong(KEY_SPEED_BYTES_PER_SECOND, 0L),
                prefs.getString(KEY_ERROR_MESSAGE, null),
                splitLines(prefs.getString(KEY_TO_DELETE, "")),
                splitLines(prefs.getString(KEY_ERROR_REPORT, "")),
                prefs.getLong(KEY_OPERATION_ID, 0L));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String joinLines(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.length() == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static ArrayList<String> splitLines(String value) {
        ArrayList<String> out = new ArrayList<>();
        if (value == null || value.length() == 0) {
            return out;
        }
        String[] lines = value.split("\\n");
        for (String line : lines) {
            if (line.length() > 0) {
                out.add(line);
            }
        }
        return out;
    }

    public static final class Snapshot {
        public final boolean resultHandled;
        public final boolean deleteRequested;
        public final int stage;
        public final int done;
        public final int total;
        public final int copied;
        public final int fail;
        public final int duplicates;
        public final String currentName;
        public final long currentBytes;
        public final long currentTotalBytes;
        public final long copiedBytes;
        public final long totalBytes;
        public final long availableBytes;
        public final long speedBytesPerSecond;
        public final String errorMessage;
        public final ArrayList<String> toDelete;
        public final ArrayList<String> errorReport;
        public final long operationId;

        private Snapshot(boolean resultHandled, boolean deleteRequested, int stage,
                         int done, int total, int copied, int fail, int duplicates,
                         String currentName, long currentBytes, long currentTotalBytes,
                         long copiedBytes, long totalBytes, long availableBytes,
                         long speedBytesPerSecond, String errorMessage,
                         ArrayList<String> toDelete, ArrayList<String> errorReport, long operationId) {
            this.resultHandled = resultHandled;
            this.deleteRequested = deleteRequested;
            this.stage = stage;
            this.done = done;
            this.total = total;
            this.copied = copied;
            this.fail = fail;
            this.duplicates = duplicates;
            this.currentName = currentName;
            this.currentBytes = currentBytes;
            this.currentTotalBytes = currentTotalBytes;
            this.copiedBytes = copiedBytes;
            this.totalBytes = totalBytes;
            this.availableBytes = availableBytes;
            this.speedBytesPerSecond = speedBytesPerSecond;
            this.errorMessage = errorMessage;
            this.toDelete = toDelete;
            this.errorReport = errorReport;
            this.operationId = operationId;
        }
    }
}
