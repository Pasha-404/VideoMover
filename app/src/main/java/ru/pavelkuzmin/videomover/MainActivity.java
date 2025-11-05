package ru.pavelkuzmin.videomover;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;

import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.databinding.ActivityMainBinding;
import ru.pavelkuzmin.videomover.service.CopyService;
import ru.pavelkuzmin.videomover.util.StorageUtil;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // === SAF: выбор папки назначения ===
    private final ActivityResultLauncher<Intent> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                Uri treeUri = data.getData();
                if (treeUri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    Toast.makeText(this, getString(R.string.toast_dest_persist_failed), Toast.LENGTH_LONG).show();
                    return;
                }
                SettingsStore.setDestTreeUri(this, treeUri);
                updateDestUi();
                Toast.makeText(this, getString(R.string.toast_dest_selected), Toast.LENGTH_SHORT).show();
            });

    // === Разрешение: доступ к видео (Android 13+) ===
    private final ActivityResultLauncher<String> videoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, getString(R.string.perm_video_rationale), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.perm_video_granted), Toast.LENGTH_SHORT).show();
                }
            });

    // === Разрешение: уведомления (Android 13+) ===
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, getString(R.string.perm_notifications_rationale), Toast.LENGTH_LONG).show();
                }
            });

    // === Диалог системного удаления исходников ===
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.deleting_canceled), Toast.LENGTH_LONG).show();
                }
            });

    // === Broadcasts from CopyService ===
    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int done = intent.getIntExtra(CopyService.EXTRA_DONE, 0);
            int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
            int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);

            String msg = (fail > 0)
                    ? getString(R.string.progress_with_errors, done, total, fail)
                    : getString(R.string.progress_ok, done, total);
            binding.tvProgress.setText(msg);
        }
    };

    private final BroadcastReceiver doneReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int ok = intent.getIntExtra(CopyService.EXTRA_OK, 0);
            int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
            int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);
            ArrayList<String> toDeleteStr = intent.getStringArrayListExtra(CopyService.EXTRA_TO_DELETE);
            ArrayList<Uri> toDelete = new ArrayList<>();
            if (toDeleteStr != null) {
                for (String s : toDeleteStr) toDelete.add(Uri.parse(s));
            }

            Toast.makeText(MainActivity.this,
                    getString((fail > 0 ? R.string.progress_with_errors : R.string.progress_ok), ok, total, fail),
                    Toast.LENGTH_LONG).show();

            // Если включено — запросим удаление
            if (SettingsStore.isDeleteAfter(MainActivity.this) && !toDelete.isEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        IntentSender sender = MediaStore
                                .createDeleteRequest(getContentResolver(), toDelete)
                                .getIntentSender();
                        deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, getString(R.string.toast_delete_request_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                } else {
                    // до Android 11 диалог не обязателен
                    for (Uri u : toDelete) {
                        try { getContentResolver().delete(u, null, null); } catch (Exception ignore) {}
                    }
                    Toast.makeText(MainActivity.this, getString(R.string.deleting_done), Toast.LENGTH_SHORT).show();
                }
            }

            unlockUi();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnChooseDest.setOnClickListener(v -> openDestTree());
        binding.btnTransfer.setOnClickListener(v -> onTransferAll());
        binding.btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        updateDestUi();
        ensureVideoPermission();
        maybeAutodetectSourceOnFirstRun();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Регистрируем ресиверы на время видимости Activity (API 34+ требуют явного флага)
        registerReceiver(progressReceiver,
                new IntentFilter(CopyService.ACTION_PROGRESS),
                Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(doneReceiver,
                new IntentFilter(CopyService.ACTION_DONE),
                Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(progressReceiver); } catch (Exception ignore) {}
        try { unregisterReceiver(doneReceiver); } catch (Exception ignore) {}
    }

    // === Helpers ===

    private void openDestTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        openTreeLauncher.launch(intent);
    }

    private boolean ensureVideoPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            int state = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO);
            if (state != PackageManager.PERMISSION_GRANTED) {
                videoPermLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO);
                return false;
            }
        }
        return true;
    }

    private boolean ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            int s = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS);
            if (s != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return false;
            }
        }
        return true;
    }

    private void updateDestUi() {
        Uri uri = SettingsStore.getDestTreeUri(this);
        if (uri == null) {
            binding.tvDest.setText(getString(R.string.dest_not_selected));
            binding.btnTransfer.setEnabled(false);
        } else {
            String pretty = StorageUtil.buildDestSummary(this, uri);
            binding.tvDest.setText(pretty);
            binding.btnTransfer.setEnabled(true);
        }
        binding.tvProgress.setText("");
    }

    private void maybeAutodetectSourceOnFirstRun() {
        String cur = SettingsStore.getSourceRelPath(this);
        if (cur != null && !cur.isEmpty()) return;
        new Thread(() -> {
            String detected = ru.pavelkuzmin.videomover.data.MediaQuery.detectLikelyCameraRelPath(this);
            if (detected != null) {
                SettingsStore.setSourceRelPath(this, detected);
            }
        }).start();
    }

    private void onTransferAll() {
        if (!ensureVideoPermission()) return;

        Uri destTree = SettingsStore.getDestTreeUri(this);
        if (destTree == null) {
            Toast.makeText(this, getString(R.string.toast_pick_dest_first), Toast.LENGTH_LONG).show();
            return;
        }
        DocumentFile destDir = DocumentFile.fromTreeUri(this, destTree);
        if (destDir == null || !destDir.canWrite()) {
            Toast.makeText(this, getString(R.string.no_write_access), Toast.LENGTH_LONG).show();
            return;
        }

        // Android 13+: уведомления перед запуском Foreground Service
        if (!ensureNotificationPermission()) {
            // пользователь увидит системный диалог; запустит перенос повторно
            return;
        }

        lockUiForCopy();

        // Режим источника
        boolean useDcimAll = SettingsStore.isUseDcimAll(this);

        // Стартуем Foreground Service
        Intent svc = new Intent(this, CopyService.class);
        svc.setAction(CopyService.ACTION_START);
        svc.putExtra(CopyService.EXTRA_DEST_URI, destTree.toString());
        svc.putExtra(CopyService.EXTRA_USE_DCIM_ALL, useDcimAll);

        if (!useDcimAll) {
            // В обычном режиме передаём конкретный RELATIVE_PATH
            String rel = SettingsStore.getSourceRelPath(this);
            if (rel != null) svc.putExtra(CopyService.EXTRA_REL_PREFIX, rel);
        }

        ContextCompat.startForegroundService(this, svc);
        binding.tvProgress.setText(getString(R.string.progress_ok, 0, 0)); // стартовая строка
    }

    private void lockUiForCopy() {
        binding.btnTransfer.setEnabled(false);
        binding.btnChooseDest.setEnabled(false);
        binding.btnSettings.setEnabled(false);
    }

    private void unlockUi() {
        binding.btnTransfer.setEnabled(true);
        binding.btnChooseDest.setEnabled(true);
        binding.btnSettings.setEnabled(true);
    }
}
