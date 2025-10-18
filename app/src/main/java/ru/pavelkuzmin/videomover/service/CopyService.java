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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_DONE = "extra_done";
    public static final String EXTRA_FAIL = "extra_fail";
    public static final String EXTRA_OK = "extra_ok";
    public static final String EXTRA_TO_DELETE = "extra_to_delete"; // ArrayList<String> (Uri.toString)

    private static final String CHANNEL_ID = "copy_channel";
    private static final int NOTIF_ID = 1;

    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String destUriStr = intent.getStringExtra(EXTRA_DEST_URI);
        String relPrefix = intent.getStringExtra(EXTRA_REL_PREFIX);

        if (destUriStr == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Uri destTree = Uri.parse(destUriStr);
        DocumentFile destDir = DocumentFile.fromTreeUri(this, destTree);
        if (destDir == null || !destDir.canWrite()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Стартовое уведомление
        Notification startNotif = buildNotification(getString(R.string.notif_title), getString(R.string.notif_copy_in_progress, 0, 0), 0, 0, true);
        startForeground(NOTIF_ID, startNotif);

        // Работаем в фоне
        new Thread(() -> {
            List<MediaQuery.VideoItem> items = MediaQuery.findCameraVideosList(this, relPrefix);
            int total = items.size();

            AtomicInteger done = new AtomicInteger(0);
            AtomicInteger ok = new AtomicInteger(0);
            AtomicInteger fail = new AtomicInteger(0);

            ArrayList<String> toDelete = new ArrayList<>();

            for (MediaQuery.VideoItem vitem : items) {
                var res = FileCopier.copyWithSha256(this, vitem.uri(), vitem.displayName, vitem.size, destDir);
                if (res.ok) {
                    ok.incrementAndGet();
                    toDelete.add(vitem.uri().toString());
                } else {
                    fail.incrementAndGet();
                }
                int d = done.incrementAndGet();

                // Обновляем уведомление
                Notification n = buildNotification(
                        getString(R.string.notif_title),
                        getString(R.string.notif_copy_in_progress, d, total),
                        d, total, true);
                nm.notify(NOTIF_ID, n);

                // Шлём прогресс в Activity
                Intent progress = new Intent(ACTION_PROGRESS);
                progress.setPackage(getPackageName());
                progress.putExtra(EXTRA_DONE, d);
                progress.putExtra(EXTRA_TOTAL, total);
                progress.putExtra(EXTRA_FAIL, fail.get());
                progress.putExtra(EXTRA_OK, ok.get());
                sendBroadcast(progress);
            }

            // Финал
            Notification doneNotif = buildNotification(
                    getString(R.string.notif_title),
                    getString(R.string.notif_copy_done),
                    total, total, false);
            nm.notify(NOTIF_ID, doneNotif);

            Intent doneIntent = new Intent(ACTION_DONE);
            doneIntent.setPackage(getPackageName());
            doneIntent.putExtra(EXTRA_TOTAL, total);
            doneIntent.putExtra(EXTRA_FAIL, fail.get());
            doneIntent.putExtra(EXTRA_OK, ok.get());
            doneIntent.putStringArrayListExtra(EXTRA_TO_DELETE, toDelete);
            sendBroadcast(doneIntent);

            stopForeground(true);
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text, int progress, int max, boolean indeterminate) {
        // Нажатие по уведомлению откроет MainActivity
        Intent i = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification) // добавь любой 24px иконку в mipmap/drawable
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (max > 0) {
            b.setProgress(max, progress, indeterminate);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
