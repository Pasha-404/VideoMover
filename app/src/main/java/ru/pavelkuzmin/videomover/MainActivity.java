package ru.pavelkuzmin.videomover;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.databinding.ActivityMainBinding;
import ru.pavelkuzmin.videomover.domain.FileCopier;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // SAF через явный Intent, чтобы гарантированно получить persistable-доступ
    private final ActivityResultLauncher<Intent> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                Intent data = result.getData();
                Uri treeUri = data.getData();
                if (treeUri == null) return;

                try {
                    // Берём persistable-доступ явно и для READ, и для WRITE
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Кнопки
        binding.btnChooseDest.setOnClickListener(v -> openDestTree());
        binding.btnTransfer.setOnClickListener(v -> onTransferClick());
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

    private void onTransferClick() {
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

        new Thread(() -> {
            try {
                String relPrefix = SettingsStore.getSourceRelPath(this);
                var items = MediaQuery.findCameraVideos(this, 1, relPrefix);
                if (items.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this,
                            (relPrefix == null || relPrefix.isEmpty())
                                    ? "Источник не задан. Укажите в Настройках."
                                    : "Видео не найдено в источнике: " + relPrefix,
                            Toast.LENGTH_LONG).show());
                    return;
                }
                var vitem = items.get(0);

                var res = FileCopier.copyWithSha256(this, vitem.uri(), vitem.displayName, vitem.size, destDir);

                runOnUiThread(() -> {
                    if (res.ok) {
                        Toast.makeText(this,
                                "Скопировано: " + res.finalName + "\nИз: " + (vitem.relativePath == null ? "" : vitem.relativePath),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Ошибка копирования: " + res.error, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
