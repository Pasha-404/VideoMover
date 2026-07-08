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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.pavelkuzmin.videomover.MainActivity;
import ru.pavelkuzmin.videomover.R;
import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.domain.FileCopier;
import ru.pavelkuzmin.videomover.util.StorageUtil;

public class CopyService extends Service {
    public static final String ACTION_START = "ru.pavelkuzmin.videomover.action.START_COPY";
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

    public static final int STAGE_SEARCHING = 1;
    public static final int STAGE_CHECKING_SPACE = 2;
    public static final int STAGE_COPYING = 3;
    public static final int STAGE_VERIFYING = 4;
    public static final int STAGE_DONE = 5;
    public static final int STAGE_ERROR = 6;

    private static final String CHANNEL_ID = "copy_channel";
    private static final int NOTIF_ID = 1;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 750L;
    private static final long COPY_WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L;

    private NotificationManager notificationManager;
    private Thread workerThread;
    private PowerManager.WakeLock copyWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            int state = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS);
            if (state != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }

        String destUriStr = intent.getStringExtra(EXTRA_DEST_URI);
        if (TextUtils.isEmpty(destUriStr)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Uri destTree = Uri.parse(destUriStr);
        DocumentFile destDir = DocumentFile.fromTreeUri(this, destTree);
        if (destDir == null || !destDir.canWrite()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification(
                getString(R.string.notif_title),
                getString(R.string.stage_searching),
                0, 0, true, true));
        acquireCopyWakeLock();

        boolean useDcimAll = intent.getBooleanExtra(EXTRA_USE_DCIM_ALL, false);
        String relPrefix = intent.getStringExtra(EXTRA_REL_PREFIX);
        workerThread = new Thread(() -> runCopy(startId, destTree, destDir, useDcimAll, relPrefix),
                "VideoMoverCopy");
        workerThread.start();

        return START_NOT_STICKY;
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

            if (availableBytes >= 0 && totalBytes > availableBytes) {
                fatalError = getString(R.string.error_not_enough_space,
                        formatBytes(totalBytes), formatBytes(availableBytes));
                fail[0] = total;
                sendProgress(STAGE_ERROR, 0, total, copied[0], fail[0], duplicates[0],
                        null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, fatalError);
                return;
            }

            final long requiredBytesForProgress = totalBytes;
            final long availableBytesForProgress = availableBytes;
            for (int index = 0; index < total; index++) {
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
                                        availableBytesForProgress, speed, null);
                            }
                        });

                if (result.ok) {
                    if (result.duplicate) {
                        duplicates[0]++;
                    } else {
                        copied[0]++;
                        copiedBytes[0] += Math.max(0L, result.bytes);
                        toDelete.add(item.uri.toString());
                    }
                } else {
                    fail[0]++;
                }

                int done = index + 1;
                sendProgress(STAGE_VERIFYING, done, total, copied[0], fail[0], duplicates[0],
                        item.displayName, item.size, item.size, copiedBytes[0],
                        totalBytes, availableBytes, 0, result.error);
            }

            sendProgress(STAGE_DONE, items.size(), items.size(), copied[0], fail[0], duplicates[0],
                    null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, null);
        } catch (Exception e) {
            fatalError = e.getClass().getSimpleName() + ": " + e.getMessage();
            sendProgress(STAGE_ERROR, 0, items.size(), copied[0], fail[0], duplicates[0],
                    null, 0, 0, copiedBytes[0], totalBytes, availableBytes, 0, fatalError);
        } finally {
            int total = items.size();
            notificationManager.notify(NOTIF_ID, buildNotification(
                    getString(R.string.notif_title),
                    getString(TextUtils.isEmpty(fatalError)
                            ? R.string.notif_copy_done
                            : R.string.stage_problem),
                    total, total, false, false));

            Intent doneIntent = new Intent(ACTION_DONE);
            doneIntent.setPackage(getPackageName());
            doneIntent.putExtra(EXTRA_STAGE, TextUtils.isEmpty(fatalError) ? STAGE_DONE : STAGE_ERROR);
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
            doneIntent.putStringArrayListExtra(EXTRA_TO_DELETE, toDelete);
            sendBroadcast(doneIntent);

            releaseCopyWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private long sumKnownSizes(List<MediaQuery.VideoItem> items) {
        long total = 0L;
        for (MediaQuery.VideoItem item : items) {
            if (item.size > 0) {
                total += item.size;
            }
        }
        return total;
    }

    private void sendProgress(int stage, int done, int total, int copied, int fail, int duplicates,
                              @Nullable String currentName, long currentBytes,
                              long currentTotalBytes, long copiedBytes, long totalBytes,
                              long availableBytes, long speedBytesPerSecond,
                              @Nullable String errorMessage) {
        String text = buildNotificationText(stage, done, total, currentName);
        notificationManager.notify(NOTIF_ID, buildNotification(
                getString(R.string.notif_title),
                text,
                done, total, stage == STAGE_SEARCHING || stage == STAGE_CHECKING_SPACE, true));

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
