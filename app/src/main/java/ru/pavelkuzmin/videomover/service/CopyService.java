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
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

import ru.pavelkuzmin.videomover.MainActivity;
import ru.pavelkuzmin.videomover.R;
import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.domain.FileCopier;

public class CopyService extends Service {
    public static final String ACTION_START = "ru.pavelkuzmin.videomover.action.START_COPY";
    public static final String ACTION_PROGRESS = "ru.pavelkuzmin.videomover.action.COPY_PROGRESS";
    public static final String ACTION_DONE = "ru.pavelkuzmin.videomover.action.COPY_DONE";

    public static final String EXTRA_DEST_URI = "extra_dest_uri";
    public static final String EXTRA_REL_PREFIX = "extra_rel_prefix";
    public static final String EXTRA_USE_DCIM_ALL = "extra_use_dcim_all";

    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_DONE = "extra_done";
    public static final String EXTRA_FAIL = "extra_fail";
    public static final String EXTRA_OK = "extra_ok";
    public static final String EXTRA_TO_DELETE = "extra_to_delete";
    public static final String EXTRA_CURRENT_NAME = "extra_current_name";
    public static final String EXTRA_CURRENT_BYTES = "extra_current_bytes";
    public static final String EXTRA_CURRENT_TOTAL_BYTES = "extra_current_total_bytes";
    public static final String EXTRA_ERROR_MESSAGE = "extra_error_message";

    private static final String CHANNEL_ID = "copy_channel";
    private static final int NOTIF_ID = 1;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 750L;

    private NotificationManager notificationManager;
    private Thread workerThread;

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
                getString(R.string.notif_copy_in_progress, 0, 0),
                0, 0, true, true));

        boolean useDcimAll = intent.getBooleanExtra(EXTRA_USE_DCIM_ALL, false);
        String relPrefix = intent.getStringExtra(EXTRA_REL_PREFIX);
        workerThread = new Thread(() -> runCopy(startId, destDir, useDcimAll, relPrefix),
                "VideoMoverCopy");
        workerThread.start();

        return START_NOT_STICKY;
    }

    private void runCopy(int startId, DocumentFile destDir, boolean useDcimAll, String relPrefix) {
        List<MediaQuery.VideoItem> items = new ArrayList<>();
        ArrayList<String> toDelete = new ArrayList<>();
        int[] ok = {0};
        int[] fail = {0};
        String fatalError = null;

        try {
            items = useDcimAll
                    ? MediaQuery.findDcimVideosList(this)
                    : MediaQuery.findCameraVideosList(this, relPrefix);

            int total = items.size();
            sendProgress(0, total, 0, 0, null, 0, 0);

            for (int index = 0; index < total; index++) {
                MediaQuery.VideoItem item = items.get(index);
                int doneBeforeCurrent = index;
                long[] lastProgressUpdate = {0L};

                FileCopier.Result result = FileCopier.copyWithSha256(
                        this,
                        item.uri,
                        item.displayName,
                        item.size,
                        destDir,
                        (writtenBytes, totalBytes) -> {
                            long now = System.currentTimeMillis();
                            if (now - lastProgressUpdate[0] >= PROGRESS_UPDATE_INTERVAL_MS
                                    || writtenBytes == totalBytes) {
                                lastProgressUpdate[0] = now;
                                sendProgress(doneBeforeCurrent, total, ok[0], fail[0],
                                        item.displayName, writtenBytes, totalBytes);
                            }
                        });

                if (result.ok) {
                    ok[0]++;
                    toDelete.add(item.uri.toString());
                } else {
                    fail[0]++;
                }

                int done = index + 1;
                sendProgress(done, total, ok[0], fail[0], item.displayName, item.size, item.size);
            }
        } catch (Exception e) {
            fatalError = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            int total = items.size();
            notificationManager.notify(NOTIF_ID, buildNotification(
                    getString(R.string.notif_title),
                    getString(R.string.notif_copy_done),
                    total, total, false, false));

            Intent doneIntent = new Intent(ACTION_DONE);
            doneIntent.setPackage(getPackageName());
            doneIntent.putExtra(EXTRA_TOTAL, total);
            doneIntent.putExtra(EXTRA_FAIL, fail[0]);
            doneIntent.putExtra(EXTRA_OK, ok[0]);
            if (!TextUtils.isEmpty(fatalError)) {
                doneIntent.putExtra(EXTRA_ERROR_MESSAGE, fatalError);
            }
            doneIntent.putStringArrayListExtra(EXTRA_TO_DELETE, toDelete);
            sendBroadcast(doneIntent);

            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private void sendProgress(int done, int total, int ok, int fail, @Nullable String currentName,
                              long currentBytes, long currentTotalBytes) {
        String text = TextUtils.isEmpty(currentName)
                ? getString(R.string.notif_copy_in_progress, done, total)
                : getString(R.string.notif_copy_current_file, done, total, currentName);
        notificationManager.notify(NOTIF_ID, buildNotification(
                getString(R.string.notif_title),
                text,
                done, total, total == 0, true));

        Intent progress = new Intent(ACTION_PROGRESS);
        progress.setPackage(getPackageName());
        progress.putExtra(EXTRA_DONE, done);
        progress.putExtra(EXTRA_TOTAL, total);
        progress.putExtra(EXTRA_FAIL, fail);
        progress.putExtra(EXTRA_OK, ok);
        progress.putExtra(EXTRA_CURRENT_NAME, currentName);
        progress.putExtra(EXTRA_CURRENT_BYTES, currentBytes);
        progress.putExtra(EXTRA_CURRENT_TOTAL_BYTES, currentTotalBytes);
        sendBroadcast(progress);
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
