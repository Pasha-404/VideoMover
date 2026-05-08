package ru.pavelkuzmin.videomover;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.databinding.ActivityMainBinding;
import ru.pavelkuzmin.videomover.service.CopyService;
import ru.pavelkuzmin.videomover.util.SafUtil;
import ru.pavelkuzmin.videomover.util.StorageUtil;

public class MainActivity extends AppCompatActivity {
    private static final int VIDEO_ACCESS_DENIED = 0;
    private static final int VIDEO_ACCESS_FULL = 1;
    private static final int VIDEO_ACCESS_PARTIAL = 2;

    private ActivityMainBinding binding;
    private boolean transferPendingAfterVideoPermission;
    private boolean transferPendingAfterNotificationPermission;

    private final ActivityResultLauncher<Intent> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                Uri treeUri = data.getData();
                if (treeUri == null) return;
                try {
                    SafUtil.persistTreePermission(this, data, treeUri);
                } catch (SecurityException e) {
                    Toast.makeText(this, getString(R.string.toast_dest_persist_failed), Toast.LENGTH_LONG).show();
                    return;
                }
                SettingsStore.setDestTreeUri(this, treeUri);
                updateDestUi();
                Toast.makeText(this, getString(R.string.toast_dest_selected), Toast.LENGTH_SHORT).show();
            });

    private final ActivityResultLauncher<String[]> videoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                int accessState = getVideoAccessState();
                if (accessState == VIDEO_ACCESS_DENIED) {
                    transferPendingAfterVideoPermission = false;
                    Toast.makeText(this, getString(R.string.perm_video_rationale), Toast.LENGTH_LONG).show();
                    return;
                }
                showVideoPermissionGrantedToast(accessState);
                maybeAutodetectSourceOnFirstRun();
                if (transferPendingAfterVideoPermission) {
                    transferPendingAfterVideoPermission = false;
                    onTransferAll();
                }
            });

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    transferPendingAfterNotificationPermission = false;
                    Toast.makeText(this, getString(R.string.perm_notifications_rationale), Toast.LENGTH_LONG).show();
                    return;
                }
                if (transferPendingAfterNotificationPermission) {
                    transferPendingAfterNotificationPermission = false;
                    onTransferAll();
                }
            });

    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.deleting_canceled), Toast.LENGTH_LONG).show();
                }
            });

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int done = intent.getIntExtra(CopyService.EXTRA_DONE, 0);
            int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
            int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);
            String currentName = intent.getStringExtra(CopyService.EXTRA_CURRENT_NAME);
            long currentBytes = intent.getLongExtra(CopyService.EXTRA_CURRENT_BYTES, 0);
            long currentTotalBytes = intent.getLongExtra(CopyService.EXTRA_CURRENT_TOTAL_BYTES, 0);

            binding.tvProgress.setText(buildProgressText(
                    done, total, fail, currentName, currentBytes, currentTotalBytes));
        }
    };

    private final BroadcastReceiver doneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int ok = intent.getIntExtra(CopyService.EXTRA_OK, 0);
            int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
            int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);
            String error = intent.getStringExtra(CopyService.EXTRA_ERROR_MESSAGE);

            ArrayList<String> toDeleteStr = intent.getStringArrayListExtra(CopyService.EXTRA_TO_DELETE);
            ArrayList<Uri> toDelete = new ArrayList<>();
            if (toDeleteStr != null) {
                for (String value : toDeleteStr) {
                    toDelete.add(Uri.parse(value));
                }
            }

            String result = fail > 0
                    ? getString(R.string.progress_with_errors, ok, total, fail)
                    : getString(R.string.progress_ok, ok, total);
            if (!TextUtils.isEmpty(error)) {
                result = getString(R.string.progress_failed_with_reason, result, error);
            }
            binding.tvProgress.setText(result);
            Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();

            if (SettingsStore.isDeleteAfter(MainActivity.this) && !toDelete.isEmpty()) {
                requestDeleteOriginals(toDelete);
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
        if (ensureVideoPermission(false)) {
            maybeAutodetectSourceOnFirstRun();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, progressReceiver,
                new IntentFilter(CopyService.ACTION_PROGRESS),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, doneReceiver,
                new IntentFilter(CopyService.ACTION_DONE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(progressReceiver);
        } catch (Exception ignore) {
        }
        try {
            unregisterReceiver(doneReceiver);
        } catch (Exception ignore) {
        }
    }

    private void openDestTree() {
        openTreeLauncher.launch(SafUtil.createOpenTreeIntent());
    }

    private boolean ensureVideoPermission(boolean continueTransferAfterGrant) {
        int accessState = getVideoAccessState();
        if (accessState != VIDEO_ACCESS_DENIED) {
            if (continueTransferAfterGrant && accessState == VIDEO_ACCESS_PARTIAL) {
                Toast.makeText(this, getString(R.string.perm_video_partial), Toast.LENGTH_LONG).show();
            }
            return true;
        }

        transferPendingAfterVideoPermission = continueTransferAfterGrant;
        videoPermLauncher.launch(getVideoPermissionsToRequest());
        return false;
    }

    private int getVideoAccessState() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (isPermissionGranted(Manifest.permission.READ_MEDIA_VIDEO)) {
                return VIDEO_ACCESS_FULL;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && isPermissionGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
                return VIDEO_ACCESS_PARTIAL;
            }
            return VIDEO_ACCESS_DENIED;
        }
        return isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
                ? VIDEO_ACCESS_FULL
                : VIDEO_ACCESS_DENIED;
    }

    private String[] getVideoPermissionsToRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            };
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean isPermissionGranted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void showVideoPermissionGrantedToast(int accessState) {
        int message = accessState == VIDEO_ACCESS_PARTIAL
                ? R.string.perm_video_partial
                : R.string.perm_video_granted;
        Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show();
    }

    private boolean ensureNotificationPermission(boolean continueTransferAfterGrant) {
        if (Build.VERSION.SDK_INT >= 33) {
            int state = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS);
            if (state != PackageManager.PERMISSION_GRANTED) {
                transferPendingAfterNotificationPermission = continueTransferAfterGrant;
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return false;
            }
        }
        return true;
    }

    private void updateDestUi() {
        updateDestUi(true);
    }

    private void updateDestUi(boolean clearProgress) {
        Uri uri = SettingsStore.getDestTreeUri(this);
        if (uri == null) {
            binding.tvDest.setText(getString(R.string.dest_not_selected));
            binding.btnTransfer.setEnabled(false);
            binding.tvStorageStatus.setBackgroundResource(R.drawable.bg_status_missing);
            binding.tvStorageStatus.setText(R.string.main_usb_missing);
            binding.tvStorageStatus.setTextColor(ContextCompat.getColor(this, R.color.vm_text));
            binding.ivDestCheck.setVisibility(View.INVISIBLE);
            if (clearProgress) binding.tvProgress.setText("");
            return;
        }

        String summary = StorageUtil.buildDestSummary(this, uri);
        boolean writable = SafUtil.hasPersistedWritePermission(this, uri) && SafUtil.canWriteTree(this, uri);
        binding.tvDest.setText(writable
                ? summary
                : getString(R.string.dest_summary_no_access, summary));
        binding.btnTransfer.setEnabled(writable);
        binding.tvStorageStatus.setBackgroundResource(writable
                ? R.drawable.bg_status_ready
                : R.drawable.bg_status_missing);
        binding.tvStorageStatus.setText(writable
                ? R.string.main_usb_ready
                : R.string.main_usb_missing);
        binding.tvStorageStatus.setTextColor(ContextCompat.getColor(this,
                writable ? R.color.vm_success : R.color.vm_text));
        binding.ivDestCheck.setVisibility(writable ? View.VISIBLE : View.INVISIBLE);
        if (clearProgress) binding.tvProgress.setText("");
    }

    private void maybeAutodetectSourceOnFirstRun() {
        String current = SettingsStore.getSourceRelPath(this);
        if (!TextUtils.isEmpty(current)) return;
        new Thread(() -> {
            String detected = ru.pavelkuzmin.videomover.data.MediaQuery.detectLikelyCameraRelPath(this);
            if (!TextUtils.isEmpty(detected)) {
                SettingsStore.setSourceRelPath(this, detected);
            }
        }).start();
    }

    private void onTransferAll() {
        if (!ensureVideoPermission(true)) return;

        Uri destTree = SettingsStore.getDestTreeUri(this);
        if (destTree == null) {
            Toast.makeText(this, getString(R.string.toast_pick_dest_first), Toast.LENGTH_LONG).show();
            return;
        }
        if (!SafUtil.canWriteTree(this, destTree)) {
            Toast.makeText(this, getString(R.string.no_write_access), Toast.LENGTH_LONG).show();
            updateDestUi();
            return;
        }

        if (!ensureNotificationPermission(true)) return;

        lockUiForCopy();
        boolean useDcimAll = SettingsStore.isUseDcimAll(this);

        Intent service = new Intent(this, CopyService.class);
        service.setAction(CopyService.ACTION_START);
        service.putExtra(CopyService.EXTRA_DEST_URI, destTree.toString());
        service.putExtra(CopyService.EXTRA_USE_DCIM_ALL, useDcimAll);

        if (!useDcimAll) {
            String rel = SettingsStore.getSourceRelPath(this);
            if (!TextUtils.isEmpty(rel)) {
                service.putExtra(CopyService.EXTRA_REL_PREFIX, rel);
            }
        }

        ContextCompat.startForegroundService(this, service);
        binding.tvProgress.setText(getString(R.string.progress_ok, 0, 0));
    }

    private void requestDeleteOriginals(ArrayList<Uri> toDelete) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                IntentSender sender = MediaStore
                        .createDeleteRequest(getContentResolver(), toDelete)
                        .getIntentSender();
                deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
            } catch (Exception e) {
                Toast.makeText(this,
                        getString(R.string.toast_delete_request_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        for (Uri uri : toDelete) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception ignore) {
            }
        }
        Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_SHORT).show();
    }

    private String buildProgressText(int done, int total, int fail, @Nullable String currentName,
                                     long currentBytes, long currentTotalBytes) {
        String base = fail > 0
                ? getString(R.string.progress_with_errors, done, total, fail)
                : getString(R.string.progress_ok, done, total);
        if (TextUtils.isEmpty(currentName) || currentTotalBytes <= 0) {
            return base;
        }
        return getString(R.string.progress_current_file,
                base,
                currentName,
                formatBytes(currentBytes),
                formatBytes(currentTotalBytes));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024d);
    }

    private void lockUiForCopy() {
        binding.btnTransfer.setEnabled(false);
        binding.btnChooseDest.setEnabled(false);
        binding.btnSettings.setEnabled(false);
    }

    private void unlockUi() {
        updateDestUi(false);
        binding.btnChooseDest.setEnabled(true);
        binding.btnSettings.setEnabled(true);
    }
}
