package ru.pavelkuzmin.videomover;

import android.Manifest;
import android.content.Intent;
import android.content.IntentSender;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.databinding.ActivityMainBinding;
import ru.pavelkuzmin.videomover.domain.FileCopier;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // SAF — выбор папки назначения
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

    // Разрешение на чтение видео (Android 13+)
    private final ActivityResultLauncher<String> videoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Для работы нужно разрешение на чтение видео", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Разрешение получено", Toast.LENGTH_SHORT).show();
                }
            });

    // Диалог системного удаления исходников после копирования
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.deleting_canceled), Toast.LENGTH_LONG).show();
                }
            });

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
            String detected = MediaQuery.detectLikelyCameraRelPath(this);
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

        binding.btnTransfer.setEnabled(false);
        binding.btnChooseDest.setEnabled(false);
        binding.btnSettings.setEnabled(false);
        binding.tvProgress.setText("Готовим список файлов…");

        new Thread(() -> {
            try {
                String relPrefix = SettingsStore.getSourceRelPath(this);
                List<MediaQuery.VideoItem> items = MediaQuery.findCameraVideosList(this, relPrefix);
                int total = items.size();
                if (total == 0) {
                    runOnUiThread(() -> {
                        binding.tvProgress.setText("");
                        Toast.makeText(this,
                                (relPrefix == null || relPrefix.isEmpty())
                                        ? "Источник не задан. Укажите в Настройках."
                                        : "Видео не найдено в источнике: " + relPrefix,
                                Toast.LENGTH_LONG).show();
                        unlockUi();
                    });
                    return;
                }

                AtomicInteger done = new AtomicInteger(0);
                AtomicInteger ok = new AtomicInteger(0);
                AtomicInteger fail = new AtomicInteger(0);

                // Список исходных Uri, которые успешно скопировали (для дальнейшего удаления)
                ArrayList<Uri> toDelete = new ArrayList<>();

                for (MediaQuery.VideoItem vitem : items) {
                    var res = FileCopier.copyWithSha256(this, vitem.uri(), vitem.displayName, vitem.size, destDir);
                    if (res.ok) {
                        ok.incrementAndGet();
                        toDelete.add(vitem.uri()); // добавим исходник в список на удаление
                    } else {
                        fail.incrementAndGet();
                    }
                    int d = done.incrementAndGet();
                    final String msg = "Перенесено " + d + " из " + total
                            + (fail.get() > 0 ? (" (ошибок: " + fail.get() + ")") : "");
                    runOnUiThread(() -> binding.tvProgress.setText(msg));
                }

                runOnUiThread(() -> {
                    String fin = "Готово: " + ok.get() + " из " + total
                            + (fail.get() > 0 ? (" с ошибками: " + fail.get()) : "");
                    Toast.makeText(this, fin, Toast.LENGTH_LONG).show();

                    // Если включено «удалять исходники» и есть что удалять — запросим системное подтверждение
                    if (SettingsStore.isDeleteAfter(this) && !toDelete.isEmpty()) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
                                IntentSender sender = MediaStore
                                        .createDeleteRequest(getContentResolver(), toDelete)
                                        .getIntentSender();
                                deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                                Toast.makeText(this, getString(R.string.deleting_request_started), Toast.LENGTH_SHORT).show();
                            } else {
                                // на старых версиях Android просто удаляем через ContentResolver.delete()
                                for (Uri u : toDelete) {
                                    try {
                                        getContentResolver().delete(u, null, null);
                                    } catch (Exception ignore) {}
                                }
                                Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Не удалось запросить удаление: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    } else if (SettingsStore.isDeleteAfter(this) && toDelete.isEmpty()) {
                        Toast.makeText(this, getString(R.string.deleting_nothing), Toast.LENGTH_SHORT).show();
                    }

                    unlockUi();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.tvProgress.setText("");
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    unlockUi();
                });
            }
        }).start();
    }

    private void unlockUi() {
        binding.btnTransfer.setEnabled(true);
        binding.btnChooseDest.setEnabled(true);
        binding.btnSettings.setEnabled(true);
    }
}
