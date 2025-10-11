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

import ru.pavelkuzmin.videomover.data.DestStore;
import ru.pavelkuzmin.videomover.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // SAF через явный Intent, чтобы получить реальные флаги из результата
    private final ActivityResultLauncher<Intent> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                Intent data = result.getData();
                Uri treeUri = data.getData();
                if (treeUri == null) return;

// Берём persistable-доступ явно для READ и для WRITE
                try {
                    getContentResolver().takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    Toast.makeText(this, "Не удалось сохранить доступ к папке", Toast.LENGTH_LONG).show();
                    return;
                }

                DestStore.save(this, treeUri);
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

        binding.btnChooseDest.setOnClickListener(v -> openDestTree());
        binding.btnTransfer.setOnClickListener(v -> {
            if (!ensureVideoPermission()) return;
            Uri dest = DestStore.get(this);
            if (dest == null) {
                Toast.makeText(this, "Сначала выберите папку назначения", Toast.LENGTH_SHORT).show();
                return;
            }
            // Пока заглушка — в следующих шагах сюда добавим перенос
            Toast.makeText(this, "Ок, дальше будем переносить видео…", Toast.LENGTH_SHORT).show();
        });

        updateDestUi();
        ensureVideoPermission(); // Запросим на старте, если нужно
    }

    private void openDestTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // Попросим возможность выдать нам постоянный доступ, плюс R/W на время выбора:
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        // Можно подсказать стартовую директорию, но не обязательно.
        // intent.putExtra("android.provider.extra.SHOW_ADVANCED", true);

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
        Uri uri = DestStore.get(this);
        if (uri == null) {
            binding.tvDest.setText("Папка назначения: не выбрана");
            binding.btnTransfer.setEnabled(false);
        } else {
            binding.tvDest.setText("Папка назначения:\n" + uri.toString());
            binding.btnTransfer.setEnabled(true);
        }
    }
}
