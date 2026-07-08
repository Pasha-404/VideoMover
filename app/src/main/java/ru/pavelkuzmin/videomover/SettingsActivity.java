package ru.pavelkuzmin.videomover;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

import ru.pavelkuzmin.videomover.data.MediaQuery;
import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.util.SafUtil;
import ru.pavelkuzmin.videomover.util.StorageUtil;
import ru.pavelkuzmin.videomover.util.VideoPermissionUtil;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.settings);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_arrow_back_24);
        }
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
        private static final int SOURCE_SCAN_LIMIT = 400;
        private static final int SOURCE_RESULT_LIMIT = 25;

        private Preference prefDest;
        private Preference prefVideoAccess;
        private Preference prefSourceDetect;
        private Preference prefSourcePickList;
        private Preference prefSourcePickVideo;
        private EditTextPreference prefSourceRelPath;
        private SwitchPreferenceCompat prefDeleteAfter;
        private SwitchPreferenceCompat prefUseDcimAll;

        private ActivityResultLauncher<Intent> openTreeLauncher;
        private ActivityResultLauncher<String> openVideoLauncher;
        private ActivityResultLauncher<String[]> permLauncher;
        private Runnable pendingAfterPermission;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs, rootKey);

            prefDest = findPreference("pref_dest");
            prefVideoAccess = findPreference("pref_video_access");
            prefSourceDetect = findPreference("pref_source_detect");
            prefSourcePickList = findPreference("pref_source_pick_list");
            prefSourcePickVideo = findPreference("pref_source_pick_video");
            prefSourceRelPath = findPreference("pref_source_relpath");
            prefDeleteAfter = findPreference("pref_delete_after");
            prefUseDcimAll = findPreference("pref_use_dcim_all");

            registerLaunchers();
            bindPreferences();
            updateDestSummary();
            updateVideoAccessSummary();
            updateSourceRelSummary();
            applyUseDcimAllEnabledState(SettingsStore.isUseDcimAll(requireContext()));
        }

        @Override
        public void onResume() {
            super.onResume();
            updateVideoAccessSummary();
        }

        private void registerLaunchers() {
            openTreeLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != AppCompatActivity.RESULT_OK || result.getData() == null) return;
                        Uri treeUri = result.getData().getData();
                        if (treeUri == null) return;
                        try {
                            SafUtil.persistTreePermission(requireContext(), result.getData(), treeUri);
                        } catch (SecurityException e) {
                            toast(getString(R.string.toast_dest_persist_failed));
                            return;
                        }
                        SettingsStore.setDestTreeUri(requireContext(), treeUri);
                        updateDestSummary();
                        toast(getString(R.string.toast_dest_selected));
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

            permLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    grantMap -> {
                        boolean granted = false;
                        for (Boolean value : grantMap.values()) {
                            if (value != null && value) {
                                granted = true;
                                break;
                            }
                        }
                        if (granted && pendingAfterPermission != null) {
                            pendingAfterPermission.run();
                        } else if (!granted) {
                            toast(getString(R.string.permission_media_denied));
                        }
                        updateVideoAccessSummary();
                        pendingAfterPermission = null;
                    });
        }

        private void bindPreferences() {
            if (prefDest != null) {
                prefDest.setOnPreferenceClickListener(p -> {
                    openTreeLauncher.launch(SafUtil.createOpenTreeIntent());
                    return true;
                });
            }

            if (prefVideoAccess != null) {
                prefVideoAccess.setOnPreferenceClickListener(p -> {
                    pendingAfterPermission = null;
                    permLauncher.launch(VideoPermissionUtil.getPermissionsToRequest());
                    return true;
                });
            }

            if (prefSourceDetect != null) {
                prefSourceDetect.setOnPreferenceClickListener(p -> {
                    ensureMediaPermission(this::detectSourceInBackground);
                    return true;
                });
            }

            if (prefSourcePickList != null) {
                prefSourcePickList.setOnPreferenceClickListener(p -> {
                    ensureMediaPermission(this::showSourceListInBackground);
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
                prefSourceRelPath.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
                prefSourceRelPath.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = newValue == null ? "" : newValue.toString().trim();
                    SettingsStore.setSourceRelPath(requireContext(), MediaQuery.normalizeRelPath(value));
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
                boolean useDcimAll = SettingsStore.isUseDcimAll(requireContext());
                prefUseDcimAll.setChecked(useDcimAll);
                prefUseDcimAll.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (Boolean) newValue;
                    SettingsStore.setUseDcimAll(requireContext(), enabled);
                    applyUseDcimAllEnabledState(enabled);
                    return true;
                });
            }
        }

        private void detectSourceInBackground() {
            Context appContext = requireContext().getApplicationContext();
            new Thread(() -> {
                List<String> candidates = MediaQuery.collectCameraFolderCandidates(
                        appContext, SOURCE_SCAN_LIMIT, SOURCE_RESULT_LIMIT);
                String detected = MediaQuery.pickBestCameraRelPath(candidates);
                runOnUiThreadIfAdded(() -> {
                    if (!TextUtils.isEmpty(detected)) {
                        SettingsStore.setSourceRelPath(requireContext(), detected);
                        updateSourceRelSummary();
                        toast(getString(R.string.source_detected, detected));
                    } else {
                        toast(getString(R.string.source_detect_failed));
                    }
                });
            }).start();
        }

        private void showSourceListInBackground() {
            Context appContext = requireContext().getApplicationContext();
            new Thread(() -> {
                List<String> candidates = MediaQuery.collectCameraFolderCandidates(
                        appContext, SOURCE_SCAN_LIMIT, SOURCE_RESULT_LIMIT);
                runOnUiThreadIfAdded(() -> showRelPathSingleChoiceDialog(candidates));
            }).start();
        }

        private String[] requiredMediaPermissions() {
            return VideoPermissionUtil.getPermissionsToRequest();
        }

        private boolean hasAnyMediaPermission() {
            return VideoPermissionUtil.hasAnyAccess(requireContext());
        }

        private void ensureMediaPermission(Runnable afterGranted) {
            if (hasAnyMediaPermission()) {
                afterGranted.run();
            } else {
                pendingAfterPermission = afterGranted;
                permLauncher.launch(requiredMediaPermissions());
            }
        }

        private void updateDestSummary() {
            if (prefDest == null) return;
            Uri dest = SettingsStore.getDestTreeUri(requireContext());
            if (dest == null) {
                prefDest.setSummary(getString(R.string.pref_dest_summary, ""));
            } else {
                prefDest.setSummary(StorageUtil.buildDestSummary(requireContext(), dest));
            }
        }

        private void updateVideoAccessSummary() {
            if (prefVideoAccess == null) return;
            int state = VideoPermissionUtil.getAccessState(requireContext());
            if (state == VideoPermissionUtil.ACCESS_FULL) {
                prefVideoAccess.setSummary(R.string.pref_video_access_full);
            } else if (state == VideoPermissionUtil.ACCESS_PARTIAL) {
                prefVideoAccess.setSummary(R.string.pref_video_access_partial);
            } else {
                prefVideoAccess.setSummary(R.string.pref_video_access_denied);
            }
        }

        private void updateSourceRelSummary() {
            if (prefSourceRelPath == null) return;
            String rel = SettingsStore.getSourceRelPath(requireContext());
            prefSourceRelPath.setText(rel == null ? "" : rel);
        }

        private void applyUseDcimAllEnabledState(boolean useDcimAll) {
            if (prefSourceDetect != null) prefSourceDetect.setEnabled(!useDcimAll);
            if (prefSourcePickList != null) prefSourcePickList.setEnabled(!useDcimAll);
            if (prefSourcePickVideo != null) prefSourcePickVideo.setEnabled(!useDcimAll);
            if (prefSourceRelPath != null) prefSourceRelPath.setEnabled(!useDcimAll);
        }

        private @Nullable String queryRelativePathForVideo(ContentResolver cr, Uri videoUri) {
            String[] projection = {MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media._ID};
            try (Cursor c = cr.query(videoUri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
                    if (iRel >= 0) {
                        String rel = MediaQuery.normalizeRelPath(c.getString(iRel));
                        if (!TextUtils.isEmpty(rel)) return rel;
                    }
                }
            } catch (Exception ignore) {
            }

            try {
                String last = videoUri.getLastPathSegment();
                long id = last != null ? Long.parseLong(last.replaceAll("\\D+", "")) : -1L;
                if (id > 0) {
                    Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    try (Cursor c = cr.query(uri,
                            new String[]{MediaStore.Video.Media.RELATIVE_PATH},
                            null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            String rel = MediaQuery.normalizeRelPath(c.getString(0));
                            if (!TextUtils.isEmpty(rel)) return rel;
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            return null;
        }

        private void showRelPathSingleChoiceDialog(List<String> items) {
            if (items == null || items.isEmpty()) {
                toast(getString(R.string.dcim_paths_not_found));
                return;
            }
            final String[] values = items.toArray(new String[0]);
            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.pref_source_pick_list_title))
                    .setSingleChoiceItems(values, -1, (dialog, which) -> {
                        String chosen = values[which];
                        SettingsStore.setSourceRelPath(requireContext(), chosen);
                        updateSourceRelSummary();
                        dialog.dismiss();
                        toast(getString(R.string.toast_source_set, chosen));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void runOnUiThreadIfAdded(Runnable runnable) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) {
                    runnable.run();
                }
            });
        }

        private void toast(String text) {
            if (isAdded()) {
                android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
