package ru.pavelkuzmin.videomover;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
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

    // SAF: выбор дерева каталогов (папки назначения на флэшке)
    private final ActivityResultLauncher<Uri> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                // Просим персистентные разрешения на чтение/запись этой папки
                final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

                try {
                    getContentResolver().takePersistableUriPermission(uri, flags);
                } catch (SecurityException e) {
                    Toast.makeText(this, "Не удалось сохранить доступ к папке", Toast.LENGTH_LONG).show();
                    return;
                }

                DestStore.save(this, uri);
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
        binding.btnTransfer.setOnClickListener(v -> {
            // Пока заглушка — просто проверяем, что папка выбрана и есть разрешение на видео
            if (!ensureVideoPermission()) return;
            Uri dest = DestStore.get(this);
            if (dest == null) {
                Toast.makeText(this, "Сначала выберите папку назначения", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Ок, дальше будем переносить видео…", Toast.LENGTH_SHORT).show();
        });

        updateDestUi();
        ensureVideoPermission(); // запросим сразу, если нужно
    }

    private void openDestTree() {
        // Можно подсказать стартовую директорию, если хотим (не обязательно)
        // openTreeLauncher.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        openTreeLauncher.launch(null);
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
