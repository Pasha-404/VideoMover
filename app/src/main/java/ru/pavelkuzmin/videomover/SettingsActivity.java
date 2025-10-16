package ru.pavelkuzmin.videomover;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.data.SettingsStore;

public class SettingsActivity extends AppCompatActivity {

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
        setTitle(getString(R.string.pref_title));

        // Включаем «стрелку назад» в ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish(); // назад на главный экран
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private Preference destPref;
        private Preference detectSourcePref;
        private EditTextPreference sourceRelPathPref;
        private SwitchPreferenceCompat deleteAfterPref;

        private final ActivityResultLauncher<Intent> openTreeLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != AppCompatActivity.RESULT_OK || result.getData() == null) return;
                    Intent data = result.getData();
                    Uri uri = data.getData();
                    if (uri == null) return;

                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (SecurityException e) {
                        Toast.makeText(requireContext(), "Не удалось сохранить доступ к папке", Toast.LENGTH_LONG).show();
                        return;
                    }
                    SettingsStore.setDestTreeUri(requireContext(), uri);
                    updateDestSummary();
                });

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs, rootKey);

            destPref = findPreference("pref_dest");
            detectSourcePref = findPreference("pref_source_detect");
            sourceRelPathPref = findPreference("pref_source_relpath");
            deleteAfterPref = findPreference("pref_delete_after");

            if (destPref != null) {
                destPref.setOnPreferenceClickListener(p -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    openTreeLauncher.launch(intent);
                    return true;
                });
                updateDestSummary();
            }

            if (detectSourcePref != null) {
                detectSourcePref.setOnPreferenceClickListener(p -> {
                    String detected = MediaQuery.detectLikelyCameraRelPath(requireContext());
                    if (detected != null) {
                        SettingsStore.setSourceRelPath(requireContext(), detected);
                        if (sourceRelPathPref != null) sourceRelPathPref.setText(detected);
                        Toast.makeText(requireContext(), "Источник определён: " + detected, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(), "Не удалось найти камерную папку. Укажите вручную.", Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
            }

            if (sourceRelPathPref != null) {
                String current = SettingsStore.getSourceRelPath(requireContext());
                if (current != null) sourceRelPathPref.setText(current);
                sourceRelPathPref.setOnPreferenceChangeListener((pref, newValue) -> {
                    SettingsStore.setSourceRelPath(requireContext(), String.valueOf(newValue));
                    return true;
                });
            }

            if (deleteAfterPref != null) {
                deleteAfterPref.setChecked(SettingsStore.isDeleteAfter(requireContext()));
                deleteAfterPref.setOnPreferenceChangeListener((pref, newVal) -> {
                    SettingsStore.setDeleteAfter(requireContext(), (Boolean) newVal);
                    return true;
                });
            }
        }

        private void updateDestSummary() {
            if (destPref == null) return;
            Uri u = SettingsStore.getDestTreeUri(requireContext());
            destPref.setSummary(getString(R.string.pref_dest_summary, u == null ? "не выбрана" : u.toString()));
        }

        @Override
        public void onViewCreated(android.view.View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // Сдвигаем список префов вниз, чтобы визуально было ближе к центру
            int topPaddingDp = 48; // можно настроить
            int px = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, topPaddingDp, getResources().getDisplayMetrics());
            getListView().setPadding(getListView().getPaddingLeft(), px,
                    getListView().getPaddingRight(), getListView().getPaddingBottom());
            getListView().setClipToPadding(false);
        }
    }
}
