package ru.pavelkuzmin.videomover;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.util.StorageUtil;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        // Toolbar как ActionBar
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Заголовок и "вверх" через ActionBar + свой индикатор
        androidx.appcompat.app.ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setTitle(R.string.settings);
            ab.setDisplayHomeAsUpEnabled(true);       // показать кнопку "вверх"
            ab.setHomeButtonEnabled(true);
            ab.setHomeAsUpIndicator(R.drawable.ic_arrow_back_24); // наша стрелка
        }

        // На всякий случай: клик по стрелке сразу закрывает экран
        toolbar.setNavigationOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private Preference prefDest;
        private Preference prefSourceDetect;
        private Preference prefSourcePickList;
        private Preference prefSourcePickVideo;
        private EditTextPreference prefSourceRelPath;
        private SwitchPreferenceCompat prefDeleteAfter;
        private SwitchPreferenceCompat prefUseDcimAll;

        private ActivityResultLauncher<Intent> openTreeLauncher;
        private ActivityResultLauncher<String> openVideoLauncher;

        // --- Разрешения: просим несколько, чтобы покрыть разные версии Android
        private ActivityResultLauncher<String[]> permLauncher;
        private Runnable pendingAfterPermission;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs, rootKey);

            prefDest            = findPreference("pref_dest");
            prefSourceDetect    = findPreference("pref_source_detect");
            prefSourcePickList  = findPreference("pref_source_pick_list");
            prefSourcePickVideo = findPreference("pref_source_pick_video");
            prefSourceRelPath   = findPreference("pref_source_relpath");
            prefDeleteAfter     = findPreference("pref_delete_after");
            prefUseDcimAll      = findPreference("pref_use_dcim_all");

            // === Инициализация значений ===
            updateDestSummary();
            if (prefSourceRelPath != null) {
                prefSourceRelPath.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            }
            updateSourceRelSummary();
            applyUseDcimAllEnabledState();

            // === Лончеры SAF / GetContent ===
            openTreeLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != AppCompatActivity.RESULT_OK || result.getData() == null) return;
                        Uri treeUri = result.getData().getData();
                        if (treeUri == null) return;
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (SecurityException ignore) {}
                        SettingsStore.setDestTreeUri(requireContext(), treeUri);
                        updateDestSummary();
                    });

            openVideoLauncher = registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        String rel = queryRelativePathForVideo(requireContext().getContentResolver(), uri);
                        if (!TextUtils.isEmpty(rel)) {
                            SettingsStore.setSourceRelPath(requireContext(), rel);
                            updateSourceRelSummary();
                            toast(getString(R.string.toast_source_set, rel));
                        } else {
                            toast(getString(R.string.source_get_from_video_failed));
                        }
                    });

            // === Лончер разрешений (мульти) ===
            permLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    grantMap -> {
                        boolean granted = false;
                        for (Boolean v : grantMap.values()) { if (v != null && v) { granted = true; break; } }
                        if (granted && pendingAfterPermission != null) {
                            pendingAfterPermission.run();
                        } else if (!granted) {
                            toast(getString(R.string.permission_media_denied));
                        }
                        pendingAfterPermission = null;
                    });

            // === Обработчики кликов ===
            if (prefDest != null) {
                prefDest.setOnPreferenceClickListener(p -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    openTreeLauncher.launch(intent);
                    return true;
                });
            }

            if (prefSourceDetect != null) {
                prefSourceDetect.setOnPreferenceClickListener(p -> {
                    ensureMediaPermission(() -> {
                        new Thread(() -> {
                            // 1) Собираем кандидатов из медиатеки
                            List<String> candidates = collectRelPathCandidates(requireContext(), /*maxScan*/400);
                            // 2) Пробуем найти явный DCIM/Camera
                            String detected = pickBestCameraPath(candidates);
                            requireActivity().runOnUiThread(() -> {
                                if (!TextUtils.isEmpty(detected)) {
                                    SettingsStore.setSourceRelPath(requireContext(), detected);
                                    updateSourceRelSummary();
                                    toast(getString(R.string.source_detected, detected));
                                } else {
                                    toast(getString(R.string.source_detect_failed));
                                }
                            });
                        }).start();
                    });
                    return true;
                });
            }

            if (prefSourcePickList != null) {
                prefSourcePickList.setOnPreferenceClickListener(p -> {
                    ensureMediaPermission(() -> {
                        new Thread(() -> {
                            List<String> candidates = collectRelPathCandidates(requireContext(), /*maxScan*/400);
                            requireActivity().runOnUiThread(() -> showRelPathSingleChoiceDialog(candidates));
                        }).start();
                    });
                    return true;
                });
            }

            if (prefSourcePickVideo != null) {
                prefSourcePickVideo.setOnPreferenceClickListener(p -> {
                    openVideoLauncher.launch("video/*");
                    return true;
                });
            }

            if (prefSourceRelPath != null) {
                prefSourceRelPath.setOnPreferenceChangeListener((preference, newValue) -> {
                    String v = (newValue == null) ? "" : newValue.toString().trim();
                    SettingsStore.setSourceRelPath(requireContext(), v);
                    updateSourceRelSummary();
                    return true;
                });
            }

            if (prefDeleteAfter != null) {
                prefDeleteAfter.setChecked(SettingsStore.isDeleteAfter(requireContext()));
                prefDeleteAfter.setOnPreferenceChangeListener((preference, newValue) -> {
                    SettingsStore.setDeleteAfter(requireContext(), (Boolean) newValue);
                    return true;
                });
            }

            if (prefUseDcimAll != null) {
                prefUseDcimAll.setChecked(SettingsStore.isUseDcimAll(requireContext()));
                prefUseDcimAll.setOnPreferenceChangeListener((preference, newValue) -> {
                    SettingsStore.setUseDcimAll(requireContext(), (Boolean) newValue);
                    applyUseDcimAllEnabledState();
                    return true;
                });
            }
        }

        // === Разрешения ===
        private String[] requiredMediaPermissions() {
            if (Build.VERSION.SDK_INT >= 33) {
                return new String[]{ android.Manifest.permission.READ_MEDIA_VIDEO };
            } else {
                return new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE };
            }
        }

        private boolean hasAnyMediaPermission() {
            for (String p : requiredMediaPermissions()) {
                if (ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }
            return false;
        }

        private void ensureMediaPermission(Runnable afterGranted) {
            if (hasAnyMediaPermission()) {
                afterGranted.run();
            } else {
                pendingAfterPermission = afterGranted;
                permLauncher.launch(requiredMediaPermissions());
            }
        }

        // === UI helpers ===
        private void updateDestSummary() {
            if (prefDest == null) return;
            Uri dest = SettingsStore.getDestTreeUri(requireContext());
            if (dest == null) {
                prefDest.setSummary(getString(R.string.pref_dest_summary, ""));
            } else {
                String pretty = StorageUtil.buildDestSummary(requireContext(), dest);
                prefDest.setSummary(pretty);
            }
        }

        private void updateSourceRelSummary() {
            if (prefSourceRelPath == null) return;
            String rel = SettingsStore.getSourceRelPath(requireContext());
            if (rel == null) rel = "";
            prefSourceRelPath.setText(rel);
        }

        private void applyUseDcimAllEnabledState() {
            boolean dcimAll = SettingsStore.isUseDcimAll(requireContext());
            if (prefSourceDetect != null)     prefSourceDetect.setEnabled(!dcimAll);
            if (prefSourcePickList != null)   prefSourcePickList.setEnabled(!dcimAll);
            if (prefSourcePickVideo != null)  prefSourcePickVideo.setEnabled(!dcimAll);
            if (prefSourceRelPath != null)    prefSourceRelPath.setEnabled(!dcimAll);
        }

        private void toast(String s) {
            android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_LONG).show();
        }

        // === Утилиты источника ===

        // читаем RELATIVE_PATH из конкретного видео (работает с picker'ом)
        private @Nullable String queryRelativePathForVideo(ContentResolver cr, Uri videoUri) {
            String[] projection = { MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media._ID };
            try (Cursor c = cr.query(videoUri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
                    if (iRel >= 0) {
                        String rel = normalizeRelPath(c.getString(iRel));
                        if (!TextUtils.isEmpty(rel)) return rel;
                    }
                }
            } catch (Exception ignore) {}
            // fallback по ID (если URI вида content://media/.../{id})
            try {
                String last = videoUri.getLastPathSegment();
                long id = last != null ? Long.parseLong(last.replaceAll("\\D+", "")) : -1L;
                if (id > 0) {
                    Uri u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    try (Cursor c = cr.query(u, new String[]{MediaStore.Video.Media.RELATIVE_PATH}, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            String rel = normalizeRelPath(c.getString(0));
                            if (!TextUtils.isEmpty(rel)) return rel;
                        }
                    }
                }
            } catch (Exception ignore) {}
            return null;
        }

        // собираем кандидатов БЕЗ SQL-LIKE, фильтруем в коде
        private List<String> collectRelPathCandidates(android.content.Context ctx, int maxScan) {
            Set<String> set = new LinkedHashSet<>();
            ContentResolver cr = ctx.getContentResolver();
            String[] projection = { MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DATE_TAKEN };
            String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";

            // читаем без selection; ограничиваемся maxScan вручную
            try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, order)) {
                if (c != null) {
                    int iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
                    int scanned = 0;
                    while (c.moveToNext() && scanned < maxScan) {
                        scanned++;
                        String rel = (iRel >= 0) ? normalizeRelPath(c.getString(iRel)) : null;
                        if (TextUtils.isEmpty(rel)) continue;

                        // отбрасываем очевидные соцсети
                        if (rel.contains("WhatsApp") || rel.contains("Telegram") || rel.contains("Instagram")) continue;

                        // оставляем только более-менее «камерные» и DCIM-пути
                        if (looksLikeCameraPath(rel)) {
                            set.add(rel);
                            if (set.size() >= 25) break; // отображаем не больше 25
                        }
                    }
                }
            } catch (Exception ignore) {}
            return new ArrayList<>(set);
        }

        // авто-выбор «лучшего» пути с приоритетом DCIM/Camera
        private String pickBestCameraPath(List<String> candidates) {
            if (candidates == null || candidates.isEmpty()) return null;

            // 1) явный приоритет
            for (String rel : candidates) {
                if ("DCIM/Camera/".equals(rel) || "DCIM/Camera".equals(rel)) {
                    return "DCIM/Camera/"; // нормализуем на слэш в конце
                }
            }
            // 2) самая частая папка среди усечённого сканирования (подсчёт)
            Map<String, Integer> freq = new LinkedHashMap<>();
            for (String rel : candidates) {
                freq.put(rel, freq.getOrDefault(rel, 0) + 1);
            }
            String best = null;
            int bestN = -1;
            for (Map.Entry<String, Integer> e : freq.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            return best;
        }

        private boolean looksLikeCameraPath(String rel) {
            // всё DCIM/*
            if (rel.startsWith("DCIM/")) return true;
            // распространённые варианты камер
            String low = rel.toLowerCase();
            return (low.contains("camera") || low.contains("100media") || low.contains("100andro") || low.contains("openCamera".toLowerCase()));
        }

        private String normalizeRelPath(String rel) {
            if (rel == null) return null;
            rel = rel.trim();
            if (rel.isEmpty()) return rel;
            // Убираем ведущие слэши, приводим к формату c завершающим слэшем
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (!rel.endsWith("/")) rel = rel + "/";
            return rel;
        }

        private void showRelPathSingleChoiceDialog(List<String> items) {
            if (items == null || items.isEmpty()) {
                toast(getString(R.string.dcim_paths_not_found));
                return;
            }
            final String[] arr = items.toArray(new String[0]);
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.pref_source_pick_list_title))
                    .setSingleChoiceItems(arr, -1, (dialog, which) -> {
                        String chosen = arr[which];
                        SettingsStore.setSourceRelPath(requireContext(), chosen);
                        updateSourceRelSummary();
                        dialog.dismiss();
                        toast(getString(R.string.toast_source_set, chosen));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
