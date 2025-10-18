package ru.pavelkuzmin.videomover;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.Context;
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
                    Toast.makeText(this, "Не удалось сохранить доступ к папке", Toast.LENGTH_LONG).show();
                    return;
                }
                SettingsStore.setDestTreeUri(this, treeUri);
                updateDestUi();
                Toast.makeText(this, "Папка назначения выбрана", Toast.LENGTH_SHORT).show();
            });

    // === Разрешения: видео (Android 13+) ===
    private final ActivityResultLauncher<String> videoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Для работы нужно разрешение на чтение видео", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Разрешение получено", Toast.LENGTH_SHORT).show();
                }
            });

    // === Разрешения: уведомления (Android 13+) ===
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Разрешите уведомления, чтобы видеть прогресс копирования", Toast.LENGTH_LONG).show();
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
        @Override public void onReceive(android.content.Context context, Intent intent) {
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
        @Override public void onReceive(android.content.Context context, Intent intent) {
            int ok = intent.getIntExtra(CopyService.EXTRA_OK, 0);
            int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
            int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);
            ArrayList<String> toDeleteStr = intent.getStringArrayListExtra(CopyService.EXTRA_TO_DELETE);
            ArrayList<Uri> toDelete = new ArrayList<>();
            if (toDeleteStr != null) {
                for (String s : toDeleteStr) toDelete.add(Uri.parse(s));
            }

            Toast.makeText(MainActivity.this,
                    "Готово: " + ok + " из " + total + (fail > 0 ? (" с ошибками: " + fail) : ""),
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
                        Toast.makeText(MainActivity.this, "Не удалось запросить удаление: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
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
        // Регистрируем ресиверы на время видимости Activity
        registerReceiver(
                progressReceiver,
                new IntentFilter(CopyService.ACTION_PROGRESS),
                Context.RECEIVER_NOT_EXPORTED
        );
        registerReceiver(
                doneReceiver,
                new IntentFilter(CopyService.ACTION_DONE),
                Context.RECEIVER_NOT_EXPORTED
        );
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
            binding.tvDest.setText("Папка назначения:\n" + uri);
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
            Toast.makeText(this, "Сначала выберите папку назначения (кнопка ниже или в Настройках)", Toast.LENGTH_LONG).show();
            return;
        }
        DocumentFile destDir = DocumentFile.fromTreeUri(this, destTree);
        if (destDir == null || !destDir.canWrite()) {
            Toast.makeText(this, "Нет доступа на запись к выбранной папке", Toast.LENGTH_LONG).show();
            return;
        }

        // Android 13+: попросим разрешение на уведомления, прежде чем запускать сервис
        if (!ensureNotificationPermission()) {
            // пользователь увидит системный диалог; перенос можно запустить повторно
            return;
        }

        lockUiForCopy();

        // Стартуем Foreground Service
        Intent svc = new Intent(this, CopyService.class);
        svc.setAction(CopyService.ACTION_START);
        svc.putExtra(CopyService.EXTRA_DEST_URI, destTree.toString());
        String rel = SettingsStore.getSourceRelPath(this);
        if (rel != null) svc.putExtra(CopyService.EXTRA_REL_PREFIX, rel);

        ContextCompat.startForegroundService(this, svc);
        binding.tvProgress.setText(getString(R.string.progress_ok, 0, 0)); // краткий стартовый текст
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
