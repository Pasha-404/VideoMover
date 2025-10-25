package ru.pavelkuzmin.videomover;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.util.StorageUtil;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Тема для настроек задана в манифесте: @style/Theme.VideoMover.Settings (Material3)
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle(R.string.settings);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
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
            // ВАЖНО: включаем единственный SummaryProvider и НЕ будем вручную сетать summary
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
                            toast("Источник: " + rel);
                        } else {
                            toast("Не удалось получить путь для выбранного видео");
                        }
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
                    new Thread(() -> {
                        String detected = MediaQuery.detectLikelyCameraRelPath(requireContext());
                        requireActivity().runOnUiThread(() -> {
                            if (!TextUtils.isEmpty(detected)) {
                                SettingsStore.setSourceRelPath(requireContext(), detected);
                                updateSourceRelSummary();
                                toast("Источник определён: " + detected);
                            } else {
                                toast("Не удалось определить источник автоматически");
                            }
                        });
                    }).start();
                    return true;
                });
            }

            if (prefSourcePickList != null) {
                prefSourcePickList.setOnPreferenceClickListener(p -> {
                    new Thread(() -> {
                        List<String> candidates = collectRelPathCandidates(requireContext());
                        requireActivity().runOnUiThread(() -> showRelPathSingleChoiceDialog(candidates));
                    }).start();
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
                    // провайдер сам обновит summary; обновим сам текст поля:
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

        // === UI helpers ===
        private void updateDestSummary() {
            if (prefDest == null) return;
            Uri dest = SettingsStore.getDestTreeUri(requireContext());
            if (dest == null) {
                prefDest.setSummary(getString(R.string.pref_dest_summary));
            } else {
                String pretty = StorageUtil.buildDestSummary(requireContext(), dest);
                prefDest.setSummary(pretty);
            }
        }

        private void updateSourceRelSummary() {
            if (prefSourceRelPath == null) return;
            String rel = SettingsStore.getSourceRelPath(requireContext());
            if (rel == null) rel = "";
            // НЕ setSummary() — это ломает провайдер. Просто обновляем текст самого преференса.
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
        private @Nullable String queryRelativePathForVideo(ContentResolver cr, Uri videoUri) {
            String[] projection = { MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media._ID };
            try (Cursor c = cr.query(videoUri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
                    if (iRel >= 0) {
                        String rel = c.getString(iRel);
                        if (!TextUtils.isEmpty(rel)) return rel;
                    }
                }
            } catch (Exception ignore) {}
            // fallback: попробуем по ID (если провайдер дал content://media/.../{id})
            try {
                String last = videoUri.getLastPathSegment();
                long id = last != null ? Long.parseLong(last.replaceAll("\\D+", "")) : -1L;
                if (id > 0) {
                    Uri u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    try (Cursor c = cr.query(u, new String[]{MediaStore.Video.Media.RELATIVE_PATH}, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            String rel = c.getString(0);
                            if (!TextUtils.isEmpty(rel)) return rel;
                        }
                    }
                }
            } catch (Exception ignore) {}
            return null;
        }

        private List<String> collectRelPathCandidates(android.content.Context ctx) {
            Set<String> set = new LinkedHashSet<>();
            ContentResolver cr = ctx.getContentResolver();
            String[] projection = { MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DATE_TAKEN };
            String order = MediaStore.Video.Media.DATE_TAKEN + " DESC";
            try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{"DCIM/%"},
                    order + " LIMIT 200")) {
                if (c != null) {
                    int iRel = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
                    while (c.moveToNext()) {
                        String rel = c.getString(iRel);
                        if (TextUtils.isEmpty(rel)) continue;
                        if (rel.contains("WhatsApp") || rel.contains("Telegram") || rel.contains("Instagram")) continue;
                        set.add(rel);
                        if (set.size() >= 25) break;
                    }
                }
            } catch (Exception ignore) {}
            return new ArrayList<>(set);
        }

        private void showRelPathSingleChoiceDialog(List<String> items) {
            if (items == null || items.isEmpty()) {
                toast("Не найдено путей в DCIM/*");
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
                        toast("Источник: " + chosen);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
