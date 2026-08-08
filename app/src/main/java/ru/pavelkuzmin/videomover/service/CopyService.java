package ru.pavelkuzmin.videomover.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.pavelkuzmin.videomover.MainActivity;
import ru.pavelkuzmin.videomover.R;
import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.data.OperationStateStore;
import ru.pavelkuzmin.videomover.domain.FileCopier;
import ru.pavelkuzmin.videomover.util.StorageUtil;

public class CopyService extends Service {
    public static final String ACTION_START = "ru.pavelkuzmin.videomover.action.START_COPY";
    public static final String ACTION_CANCEL = "ru.pavelkuzmin.videomover.action.CANCEL_COPY";
    public static final String ACTION_PROGRESS = "ru.pavelkuzmin.videomover.action.COPY_PROGRESS";
    public static final String ACTION_DONE = "ru.pavelkuzmin.videomover.action.COPY_DONE";

    public static final String EXTRA_DEST_URI = "extra_dest_uri";
    public static final String EXTRA_REL_PREFIX = "extra_rel_prefix";
    public static final String EXTRA_USE_DCIM_ALL = "extra_use_dcim_all";

    public static final String EXTRA_STAGE = "extra_stage";
    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_DONE = "extra_done";
    public static final String EXTRA_FAIL = "extra_fail";
    public static final String EXTRA_OK = "extra_ok";
    public static final String EXTRA_DUPLICATES = "extra_duplicates";
    public static final String EXTRA_TO_DELETE = "extra_to_delete";
    public static final String EXTRA_CURRENT_NAME = "extra_current_name";
    public static final String EXTRA_CURRENT_BYTES = "extra_current_bytes";
    public static final String EXTRA_CURRENT_TOTAL_BYTES = "extra_current_total_bytes";
    public static final String EXTRA_COPIED_BYTES = "extra_copied_bytes";
    public static final String EXTRA_TOTAL_BYTES = "extra_total_bytes";
    public static final String EXTRA_AVAILABLE_BYTES = "extra_available_bytes";
    public static final String EXTRA_SPEED_BYTES_PER_SECOND = "extra_speed_bytes_per_second";
    public static final String EXTRA_ERROR_MESSAGE = "extra_error_message";
    public static final String EXTRA_ERROR_REPORT = "extra_error_report";

    public static final int STAGE_SEARCHING = 1;
    public static final int STAGE_CHECKING_SPACE = 2;
    public static final int STAGE_COPYING = 3;
    public static final int STAGE_VERIFYING = 4;
    public static final int STAGE_DONE = 5;
    public static final int STAGE_ERROR = 6;
    public static final int STAGE_STOPPING = 7;
    public static final int STAGE_CANCELED = 8;

    private static final String CHANNEL_ID = "copy_channel";
    private static final int NOTIF_ID = 1;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 750L;
    private static final long COPY_WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L;
    private static final int DESTINATION_ERROR_STREAK_LIMIT = 8;
    private static final int SPACE_RECHECK_FILE_INTERVAL = 10;

    private NotificationManager notificationManager;
    private Thread workerThread;
    private PowerManager.WakeLock copyWakeLock;
    private volatile boolean cancelRequested;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            requestCancel(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (workerThread != null && workerThread.isAlive()) {
            return START_NOT_STICKY;
        }
        cancelRequested = false;

        if (Build.VERSION.SDK_INT >= 33) {
            int state = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS);
            if (state != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                stopWithError(startId, getString(R.string.service_error_notifications_denied));
                return START_NOT_STICKY;
            }
        }

        String destUriStr = intent.getStringExtra(EXTRA_DEST_URI);
        if (TextUtils.isEmpty(destUriStr)) {
            stopWithError(startId, getString(R.string.service_error_destination_missing));
            return START_NOT_STICKY;
        }

        Uri destTree;
        DocumentFile destDir;
        try {
            destTree = Uri.parse(destUriStr);
            destDir = DocumentFile.fromTreeUri(this, destTree);
        } catch (Exception e) {
            stopWithError(startId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return START_NOT_STICKY;
        }
        if (destDir == null || !destDir.canWrite()) {
            stopWithError(startId, getString(R.string.no_write_access));
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIF_ID, buildNotification(
                    getString(R.string.notif_title),
                    getString(R.string.stage_searching),
                    0, 0, true, true));
            acquireCopyWakeLock();
        } catch (Exception e) {
            releaseCopyWakeLock();
            stopWithError(startId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return START_NOT_STICKY;
        }

        boolean useDcimAll = intent.getBooleanExtra(EXTRA_USE_DCIM_ALL, false);
        String relPrefix = intent.getStringExtra(EXTRA_REL_PREFIX);
        workerThread = new Thread(() -> runCopy(startId, destTree, destDir, useDcimAll, relPrefix),
                "VideoMoverCopy");
        workerThread.start();

        return START_NOT_STICKY;
    }

    private void requestCancel(int startId) {
        cancelRequested = true;
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
        notificationManager.notify(NOTIF_ID, buildNotification(
                getString(R.string.notif_title),
                getString(R.string.stage_stopping),
                0, 0, true, true));
        if (thread == null || !thread.isAlive()) {
            stopSelf(startId);
        }
    }

    private void stopWithError(int startId, String errorMessage) {
        OperationStateStore.saveFinished(this, STAGE_ERROR, 0, 0, 0, 0,
                0L, 0L, -1L, errorMessage, new ArrayList<>());

        Intent doneIntent = new Intent(ACTION_DONE);
        doneIntent.setPackage(getPackageName());
        doneIntent.putExtra(EXTRA_STAGE, STAGE_ERROR);
        doneIntent.putExtra(EXTRA_TOTAL, 0);
        doneIntent.putExtra(EXTRA_FAIL, 0);
        doneIntent.putExtra(EXTRA_OK, 0);
        doneIntent.putExtra(EXTRA_DUPLICATES, 0);
        doneIntent.putExtra(EXTRA_COPIED_BYTES, 0L);
        doneIntent.putExtra(EXTRA_TOTAL_BYTES, 0L);
        doneIntent.putExtra(EXTRA_AVAILABLE_BYTES, -1L);
        doneIntent.putStringArrayListExtra(EXTRA_TO_DELETE, new ArrayList<>());
        doneIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        doneIntent.putStringArrayListExtra(EXTRA_ERROR_REPORT, new ArrayList<>());
        sendBroadcast(doneIntent);
        stopSelf(startId);
    }

    private void runCopy(int startId, Uri destTree, DocumentFile destDir,
                         boolean useDcimAll, String relPrefix) {
        List<MediaQuery.VideoItem> items = new ArrayList<>();
        ArrayList<String> toDelete = new ArrayList<>();
        int[] copied = {0};
        int[] fail = {0};
        int[] duplicates = {0};
        long[] copiedBytes = {0L};
        long totalBytes = 0L;
        long availableBytes = -1L;
        String fatalError = null;
        boolean canceled = false;
        ErrorReport errorReport = new ErrorReport();
        int destinationErrorStreak = 0;

        try {
            sendProgress(STAGE_SEARCHING, 0, 0, copied[0], fail[0], duplicates[0],
                    null, 0, 0, copiedBytes[0], 0, -1, 0, null);

            items = useDcimAll
                    ? MediaQuery.findDcimVideosList(this)
                    : MediaQuery.findCameraVideosList(this, relPrefix);

            int total = items.size();
            totalBytes = sumKnownSizes(items);
            availableBytes = StorageUtil.getAvailableBytes(this, destTree);
            sendProgress(STAGE_CHECKING_SPACE, 0, total, copied[0], fail[0], duplicates[0],
                    null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, null);

            if (isCancelRequested()) {
                canceled = true;
                sendProgress(STAGE_STOPPING, 0, total, copied[0], fail[0], duplicates[0],
                        null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, null);
                return;
            }

            if (availableBytes >= 0 && totalBytes > availableBytes) {
                fatalError = getString(R.string.error_not_enough_space,
                        formatBytes(totalBytes), formatBytes(availableBytes));
                fail[0] = total;
                sendProgress(STAGE_ERROR, 0, total, copied[0], fail[0], duplicates[0],
                        null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, fatalError);
                return;
            }

            final long requiredBytesForProgress = totalBytes;
            final long[] availableBytesForProgress = {availableBytes};
            for (int index = 0; index < total; index++) {
                if (isCancelRequested()) {
                    canceled = true;
                    sendProgress(STAGE_STOPPING, index, total, copied[0], fail[0], duplicates[0],
                            null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, null);
                    break;
                }

                MediaQuery.VideoItem item = items.get(index);
                int doneBeforeCurrent = index;
                long[] lastProgressUpdate = {0L};
                long fileStartedAt = SystemClock.elapsedRealtime();

                sendProgress(STAGE_COPYING, doneBeforeCurrent, total, copied[0], fail[0], duplicates[0],
                        item.displayName, 0, item.size, copiedBytes[0], totalBytes, availableBytes, 0, null);

                FileCopier.Result result = FileCopier.copyWithSha256(
                        this,
                        item.uri,
                        item.displayName,
                        item.size,
                        destDir,
                        (writtenBytes, currentTotalBytes) -> {
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastProgressUpdate[0] >= PROGRESS_UPDATE_INTERVAL_MS
                                    || writtenBytes == currentTotalBytes) {
                                lastProgressUpdate[0] = now;
                                long elapsed = Math.max(1L, now - fileStartedAt);
                                long speed = writtenBytes * 1000L / elapsed;
                                int stage = currentTotalBytes > 0 && writtenBytes >= currentTotalBytes
                                        ? STAGE_VERIFYING
                                        : STAGE_COPYING;
                                sendProgress(stage, doneBeforeCurrent, total, copied[0], fail[0], duplicates[0],
                                        item.displayName, writtenBytes, currentTotalBytes,
                                        copiedBytes[0] + writtenBytes, requiredBytesForProgress,
                                        availableBytesForProgress[0], speed, null);
                            }
                        },
                        this::isCancelRequested);

                if (result.canceled) {
                    canceled = true;
                    sendProgress(STAGE_STOPPING, doneBeforeCurrent, total, copied[0], fail[0], duplicates[0],
                            item.displayName, result.bytes, item.size, copiedBytes[0],
                            totalBytes, availableBytes, 0, null);
                    break;
                } else if (result.ok) {
                    destinationErrorStreak = 0;
                    if (result.duplicate) {
                        duplicates[0]++;
                    } else {
                        copied[0]++;
                        copiedBytes[0] += Math.max(0L, result.bytes);
                        toDelete.add(item.uri.toString());
                    }
                } else {
                    fail[0]++;
                    addErrorReport(errorReport, index, total, item, result);
                    if (result.destinationIssue) {
                        destinationErrorStreak++;
                    } else {
                        destinationErrorStreak = 0;
                    }
                    if (destinationErrorStreak >= DESTINATION_ERROR_STREAK_LIMIT) {
                        fatalError = getString(R.string.error_destination_failure_streak,
                                destinationErrorStreak);
                        int remaining = Math.max(0, total - index - 1);
                        fail[0] += remaining;
                        sendProgress(STAGE_ERROR, index + 1, total, copied[0], fail[0], duplicates[0],
                                item.displayName, item.size, item.size, copiedBytes[0],
                                totalBytes, availableBytes, 0, fatalError);
                        return;
                    }
                }

                int done = index + 1;
                sendProgress(STAGE_VERIFYING, done, total, copied[0], fail[0], duplicates[0],
                        item.displayName, item.size, item.size, copiedBytes[0],
                        totalBytes, availableBytes, 0, result.error);

                if (shouldRecheckSpace(index, result)) {
                    long refreshedAvailable = StorageUtil.getAvailableBytes(this, destTree);
                    if (refreshedAvailable >= 0) {
                        availableBytes = refreshedAvailable;
                        availableBytesForProgress[0] = refreshedAvailable;
                        long remainingBytes = sumKnownSizesFrom(items, done);
                        if (remainingBytes > refreshedAvailable) {
                            fatalError = getString(R.string.error_not_enough_space_remaining,
                                    formatBytes(remainingBytes), formatBytes(refreshedAvailable));
                            int remaining = Math.max(0, total - done);
                            fail[0] += remaining;
                            sendProgress(STAGE_ERROR, done, total, copied[0], fail[0], duplicates[0],
                                    item.displayName, item.size, item.size, copiedBytes[0],
                                    totalBytes, availableBytes, 0, fatalError);
                            return;
                        }
                    }
                }
            }

            if (!canceled) {
                sendProgress(STAGE_DONE, items.size(), items.size(), copied[0], fail[0], duplicates[0],
                        null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, null);
            }
        } catch (Exception e) {
            if (isCancelRequested()) {
                canceled = true;
                sendProgress(STAGE_STOPPING, 0, items.size(), copied[0], fail[0], duplicates[0],
                        null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, null);
            } else {
                fatalError = e.getClass().getSimpleName() + ": " + e.getMessage();
                sendProgress(STAGE_ERROR, 0, items.size(), copied[0], fail[0], duplicates[0],
                        null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, fatalError);
            }
        } finally {
            int total = items.size();
            int terminalStage = canceled
                    ? STAGE_CANCELED
                    : (TextUtils.isEmpty(fatalError) ? STAGE_DONE : STAGE_ERROR);
            String notificationText = getString(R.string.notif_copy_done);
            if (terminalStage == STAGE_CANCELED) {
                notificationText = getString(R.string.notif_copy_canceled);
            } else if (terminalStage == STAGE_ERROR) {
                notificationText = getString(R.string.stage_problem);
            }
            notificationManager.notify(NOTIF_ID, buildNotification(
                    getString(R.string.notif_title),
                    notificationText,
                    total, total, false, false));

            Intent doneIntent = new Intent(ACTION_DONE);
            doneIntent.setPackage(getPackageName());
            doneIntent.putExtra(EXTRA_STAGE, terminalStage);
            doneIntent.putExtra(EXTRA_TOTAL, total);
            doneIntent.putExtra(EXTRA_FAIL, fail[0]);
            doneIntent.putExtra(EXTRA_OK, copied[0]);
            doneIntent.putExtra(EXTRA_DUPLICATES, duplicates[0]);
            doneIntent.putExtra(EXTRA_COPIED_BYTES, copiedBytes[0]);
            doneIntent.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
            doneIntent.putExtra(EXTRA_AVAILABLE_BYTES, availableBytes);
            if (!TextUtils.isEmpty(fatalError)) {
                doneIntent.putExtra(EXTRA_ERROR_MESSAGE, fatalError);
            }
            doneIntent.putStringArrayListExtra(EXTRA_TO_DELETE,
                    terminalStage == STAGE_CANCELED ? new ArrayList<>() : toDelete);
            ArrayList<String> errorReportLines = buildErrorReportLines(errorReport);
            doneIntent.putStringArrayListExtra(EXTRA_ERROR_REPORT, errorReportLines);
            OperationStateStore.saveFinished(this,
                    terminalStage,
                    total, copied[0], fail[0], duplicates[0], copiedBytes[0],
                    totalBytes, availableBytes, fatalError,
                    terminalStage == STAGE_CANCELED ? new ArrayList<>() : toDelete,
                    errorReportLines);
            sendBroadcast(doneIntent);

            releaseCopyWakeLock();
            cancelRequested = false;
            workerThread = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private long sumKnownSizes(List<MediaQuery.VideoItem> items) {
        return sumKnownSizesFrom(items, 0);
    }

    private long sumKnownSizesFrom(List<MediaQuery.VideoItem> items, int startIndex) {
        long total = 0L;
        for (int index = Math.max(0, startIndex); index < items.size(); index++) {
            MediaQuery.VideoItem item = items.get(index);
            if (item.size > 0) {
                total += item.size;
            }
        }
        return total;
    }

    private boolean shouldRecheckSpace(int index, FileCopier.Result result) {
        return result.destinationIssue
                || (index + 1) % SPACE_RECHECK_FILE_INTERVAL == 0;
    }

    private void addErrorReport(ErrorReport report, int index, int total,
                                MediaQuery.VideoItem item, FileCopier.Result result) {
        String relPath = TextUtils.isEmpty(item.relPath)
                ? getString(R.string.value_unknown)
                : item.relPath;
        String stage = describeCopyErrorStage(result.errorStage);
        String error = TextUtils.isEmpty(result.error)
                ? getString(R.string.filecopier_err_unknown)
                : sanitizeReportText(result.error);
        String line = getString(R.string.error_report_item,
                index + 1,
                total,
                sanitizeReportText(item.displayName),
                formatBytes(item.size),
                sanitizeReportText(relPath),
                stage,
                error);
        report.add(line);
    }

    private ArrayList<String> buildErrorReportLines(ErrorReport report) {
        ArrayList<String> out = new ArrayList<>(report.head);
        int omitted = report.total - report.head.size() - report.tail.size();
        if (omitted > 0) {
            out.add(getString(R.string.error_report_omitted, omitted));
        }
        out.addAll(report.tail);
        return out;
    }

    private String describeCopyErrorStage(@Nullable String stage) {
        if (FileCopier.ERROR_STAGE_DEST_ACCESS.equals(stage)) {
            return getString(R.string.error_stage_dest_access);
        }
        if (FileCopier.ERROR_STAGE_DEST_CREATE_TEMP.equals(stage)) {
            return getString(R.string.error_stage_dest_create_temp);
        }
        if (FileCopier.ERROR_STAGE_SOURCE_OPEN.equals(stage)) {
            return getString(R.string.error_stage_source_open);
        }
        if (FileCopier.ERROR_STAGE_DEST_OPEN_TEMP.equals(stage)) {
            return getString(R.string.error_stage_dest_open_temp);
        }
        if (FileCopier.ERROR_STAGE_SOURCE_READ.equals(stage)) {
            return getString(R.string.error_stage_source_read);
        }
        if (FileCopier.ERROR_STAGE_DEST_WRITE.equals(stage)) {
            return getString(R.string.error_stage_dest_write);
        }
        if (FileCopier.ERROR_STAGE_DEST_FLUSH.equals(stage)) {
            return getString(R.string.error_stage_dest_flush);
        }
        if (FileCopier.ERROR_STAGE_DEST_SYNC.equals(stage)) {
            return getString(R.string.error_stage_dest_sync);
        }
        if (FileCopier.ERROR_STAGE_VERIFY_SIZE.equals(stage)) {
            return getString(R.string.error_stage_verify_size);
        }
        if (FileCopier.ERROR_STAGE_DEST_RENAME.equals(stage)) {
            return getString(R.string.error_stage_dest_rename);
        }
        return getString(R.string.error_stage_unknown);
    }

    private String sanitizeReportText(@Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return getString(R.string.value_unknown);
        }
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void sendProgress(int stage, int done, int total, int copied, int fail, int duplicates,
                              @Nullable String currentName, long currentBytes,
                              long currentTotalBytes, long copiedBytes, long totalBytes,
                              long availableBytes, long speedBytesPerSecond,
                              @Nullable String errorMessage) {
        if (stage != STAGE_DONE && stage != STAGE_ERROR && stage != STAGE_CANCELED) {
            OperationStateStore.saveProgress(this, stage, done, total, copied, fail, duplicates,
                    currentName, currentBytes, currentTotalBytes, copiedBytes, totalBytes,
                    availableBytes, speedBytesPerSecond, errorMessage);
        }

        String text = buildNotificationText(stage, done, total, currentName);
        notificationManager.notify(NOTIF_ID, buildNotification(
                getString(R.string.notif_title),
                text,
                done, total, stage == STAGE_SEARCHING
                        || stage == STAGE_CHECKING_SPACE
                        || stage == STAGE_STOPPING, true));

        Intent progress = new Intent(ACTION_PROGRESS);
        progress.setPackage(getPackageName());
        progress.putExtra(EXTRA_STAGE, stage);
        progress.putExtra(EXTRA_DONE, done);
        progress.putExtra(EXTRA_TOTAL, total);
        progress.putExtra(EXTRA_FAIL, fail);
        progress.putExtra(EXTRA_OK, copied);
        progress.putExtra(EXTRA_DUPLICATES, duplicates);
        progress.putExtra(EXTRA_CURRENT_NAME, currentName);
        progress.putExtra(EXTRA_CURRENT_BYTES, currentBytes);
        progress.putExtra(EXTRA_CURRENT_TOTAL_BYTES, currentTotalBytes);
        progress.putExtra(EXTRA_COPIED_BYTES, copiedBytes);
        progress.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        progress.putExtra(EXTRA_AVAILABLE_BYTES, availableBytes);
        progress.putExtra(EXTRA_SPEED_BYTES_PER_SECOND, speedBytesPerSecond);
        progress.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        sendBroadcast(progress);
    }

    private String buildNotificationText(int stage, int done, int total, @Nullable String currentName) {
        if (stage == STAGE_SEARCHING) {
            return getString(R.string.stage_searching);
        }
        if (stage == STAGE_CHECKING_SPACE) {
            return getString(R.string.stage_checking_space);
        }
        if (stage == STAGE_VERIFYING) {
            return getString(R.string.stage_verifying);
        }
        if (stage == STAGE_DONE) {
            return getString(R.string.stage_done);
        }
        if (stage == STAGE_ERROR) {
            return getString(R.string.stage_problem);
        }
        if (stage == STAGE_STOPPING) {
            return getString(R.string.stage_stopping);
        }
        if (stage == STAGE_CANCELED) {
            return getString(R.string.stage_canceled);
        }
        return TextUtils.isEmpty(currentName)
                ? getString(R.string.notif_copy_in_progress, done, total)
                : getString(R.string.notif_copy_current_file, done, total, currentName);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String title, String text, int progress, int max,
                                           boolean indeterminate, boolean ongoing) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (max > 0) {
            builder.setProgress(max, progress, indeterminate);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024d);
    }

    private void acquireCopyWakeLock() {
        if (copyWakeLock != null && copyWakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        if (copyWakeLock == null) {
            copyWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ru.pavelkuzmin.videomover:CopyService");
            copyWakeLock.setReferenceCounted(false);
        }
        copyWakeLock.acquire(COPY_WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseCopyWakeLock() {
        if (copyWakeLock != null && copyWakeLock.isHeld()) {
            copyWakeLock.release();
        }
        copyWakeLock = null;
    }

    private boolean isCancelRequested() {
        return cancelRequested || Thread.currentThread().isInterrupted();
    }

    private static class ErrorReport {
        private static final int HEAD_LIMIT = 40;
        private static final int TAIL_LIMIT = 40;

        final ArrayList<String> head = new ArrayList<>();
        final ArrayDeque<String> tail = new ArrayDeque<>();
        int total;

        void add(String line) {
            if (TextUtils.isEmpty(line)) {
                return;
            }
            total++;
            if (head.size() < HEAD_LIMIT) {
                head.add(line);
                return;
            }
            tail.addLast(line);
            while (tail.size() > TAIL_LIMIT) {
                tail.removeFirst();
            }
        }
    }

    @Override
    public void onDestroy() {
        releaseCopyWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
