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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

import ru.pavelkuzmin.videomover.data.OperationStateStore;
import ru.pavelkuzmin.videomover.data.SettingsStore;
import ru.pavelkuzmin.videomover.databinding.ActivityMainBinding;
import ru.pavelkuzmin.videomover.service.CopyService;
import ru.pavelkuzmin.videomover.util.SafUtil;
import ru.pavelkuzmin.videomover.util.StorageUtil;
import ru.pavelkuzmin.videomover.util.VideoPermissionUtil;

public class MainActivity extends AppCompatActivity {
    private static final int PROGRESS_MAX = 1000;
    private static final long DESTINATION_RECHECK_WINDOW_MS = 20_000L;
    private static final long DESTINATION_RECHECK_INTERVAL_MS = 1_500L;

    private ActivityMainBinding binding;
    private boolean transferPendingAfterVideoPermission;
    private boolean transferPendingAfterNotificationPermission;
    private boolean copyRunning;
    private String pendingDeleteResultText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable destinationRecheckRunnable = this::runDestinationRecheck;
    private long destinationRecheckUntilMs;
    private boolean destinationRecheckScheduled;

    private final ActivityResultLauncher<String[]> videoPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                int accessState = VideoPermissionUtil.getAccessState(this);
                refreshMainInfoAndStartDestinationRecheck(true);
                if (accessState == VideoPermissionUtil.ACCESS_DENIED) {
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
                    markProcessFinished(getString(R.string.process_delete_done, pendingDeleteResultText), false);
                    Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_LONG).show();
                } else {
                    markProcessFinished(getString(R.string.process_delete_canceled, pendingDeleteResultText), true);
                    Toast.makeText(this, getString(R.string.deleting_canceled), Toast.LENGTH_LONG).show();
                }
                pendingDeleteResultText = null;
            });

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            copyRunning = true;
            stopDestinationRecheck();
            binding.btnTransfer.setEnabled(false);
            binding.btnSettings.setEnabled(false);
            binding.btnEjectHint.setVisibility(View.GONE);
            updateProcessFromProgress(intent);
        }
    };

    private final BroadcastReceiver doneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleDoneIntent(intent, true);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnTransfer.setOnClickListener(v -> onTransferAll());
        binding.btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        binding.btnEjectHint.setOnClickListener(v -> showEjectInstruction());

        refreshMainInfoAndStartDestinationRecheck(true);
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
    protected void onResume() {
        super.onResume();
        if (!restorePersistedOperationState() && !copyRunning) {
            refreshMainInfoAndStartDestinationRecheck(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopDestinationRecheck();
        try {
            unregisterReceiver(progressReceiver);
        } catch (Exception ignore) {
        }
        try {
            unregisterReceiver(doneReceiver);
        } catch (Exception ignore) {
        }
    }

    private boolean restorePersistedOperationState() {
        OperationStateStore.Snapshot snapshot = OperationStateStore.read(this);
        if (snapshot == null || snapshot.resultHandled || snapshot.stage == 0) {
            return false;
        }

        if (snapshot.stage == CopyService.STAGE_DONE || snapshot.stage == CopyService.STAGE_ERROR) {
            handleCopyFinished(snapshot.copied, snapshot.total, snapshot.fail,
                    snapshot.duplicates, snapshot.copiedBytes, snapshot.errorMessage,
                    snapshot.toDelete, false, snapshot.deleteRequested);
            return true;
        }

        copyRunning = true;
        stopDestinationRecheck();
        binding.btnTransfer.setEnabled(false);
        binding.btnSettings.setEnabled(false);
        binding.btnEjectHint.setVisibility(View.GONE);
        updateProcessFromSnapshot(snapshot);
        return true;
    }

    private void handleDoneIntent(Intent intent, boolean showToast) {
        int copied = intent.getIntExtra(CopyService.EXTRA_OK, 0);
        int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
        int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);
        int duplicates = intent.getIntExtra(CopyService.EXTRA_DUPLICATES, 0);
        long copiedBytes = intent.getLongExtra(CopyService.EXTRA_COPIED_BYTES, 0L);
        String error = intent.getStringExtra(CopyService.EXTRA_ERROR_MESSAGE);
        ArrayList<String> toDeleteStr = intent.getStringArrayListExtra(CopyService.EXTRA_TO_DELETE);

        handleCopyFinished(copied, total, fail, duplicates, copiedBytes, error,
                toDeleteStr == null ? new ArrayList<>() : toDeleteStr, showToast, false);
    }

    private void handleCopyFinished(int copied, int total, int fail, int duplicates,
                                    long copiedBytes, @Nullable String error,
                                    ArrayList<String> toDeleteStr, boolean showToast,
                                    boolean deleteAlreadyRequested) {
        ArrayList<Uri> toDelete = parseDeleteUris(toDeleteStr);
        if (!TextUtils.isEmpty(error)) {
            markProcessFinished(getString(R.string.process_failed_detail, error), true);
            if (showToast) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            }
            return;
        }

        String resultText = buildFinalSummary(copied, total, fail, duplicates, copiedBytes);
        if (SettingsStore.isDeleteAfter(MainActivity.this) && !toDelete.isEmpty()) {
            pendingDeleteResultText = resultText;
            copyRunning = true;
            binding.btnTransfer.setEnabled(false);
            binding.btnSettings.setEnabled(false);
            binding.btnEjectHint.setVisibility(View.GONE);
            setProcessStage(getString(R.string.stage_deleting), getString(R.string.process_delete_request_detail),
                    PROGRESS_MAX, false, false);
            if (!deleteAlreadyRequested) {
                requestDeleteOriginals(toDelete);
            }
            return;
        }

        markProcessFinished(resultText, fail > 0);
        if (showToast) {
            Toast.makeText(MainActivity.this, resultText, Toast.LENGTH_LONG).show();
        }
    }

    private ArrayList<Uri> parseDeleteUris(ArrayList<String> values) {
        ArrayList<Uri> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                out.add(Uri.parse(value));
            }
        }
        return out;
    }

    private boolean ensureVideoPermission(boolean continueTransferAfterGrant) {
        int accessState = VideoPermissionUtil.getAccessState(this);
        if (accessState != VideoPermissionUtil.ACCESS_DENIED) {
            if (continueTransferAfterGrant && accessState == VideoPermissionUtil.ACCESS_PARTIAL) {
                Toast.makeText(this, getString(R.string.perm_video_partial), Toast.LENGTH_LONG).show();
            }
            return true;
        }

        transferPendingAfterVideoPermission = continueTransferAfterGrant;
        videoPermLauncher.launch(VideoPermissionUtil.getPermissionsToRequest());
        return false;
    }

    private void showVideoPermissionGrantedToast(int accessState) {
        int message = accessState == VideoPermissionUtil.ACCESS_PARTIAL
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

    private void refreshMainInfoAndStartDestinationRecheck(boolean resetProcess) {
        boolean writable = refreshMainInfo(resetProcess);
        if (writable || copyRunning || SettingsStore.getDestTreeUri(this) == null) {
            stopDestinationRecheck();
        } else {
            startDestinationRecheckWindow();
        }
    }

    private boolean refreshMainInfo(boolean resetProcess) {
        boolean deleteAfter = SettingsStore.isDeleteAfter(this);
        binding.btnTransfer.setText(deleteAfter ? R.string.transfer : R.string.copy_videos);
        binding.tvActionMode.setText(deleteAfter ? R.string.main_action_move : R.string.main_action_copy);
        binding.tvSearchMode.setText(buildSearchModeText());
        int videoAccessState = VideoPermissionUtil.getAccessState(this);
        binding.tvVideoAccess.setText(getVideoAccessStatusText(videoAccessState));
        binding.tvVideoAccess.setTextColor(ContextCompat.getColor(this,
                videoAccessState == VideoPermissionUtil.ACCESS_FULL
                        ? R.color.vm_text_muted
                        : R.color.vm_warning));

        Uri uri = SettingsStore.getDestTreeUri(this);
        boolean writable = false;
        if (uri == null) {
            binding.tvDest.setText(getString(R.string.dest_not_selected));
            binding.tvFreeSpace.setText(R.string.main_free_space_unknown);
        } else {
            String summary = StorageUtil.buildDestSummary(this, uri);
            writable = isDestinationWritable(uri);
            binding.tvDest.setText(writable ? summary : getString(R.string.dest_summary_no_access));
            long available = StorageUtil.getAvailableBytes(this, uri);
            binding.tvFreeSpace.setText(available >= 0
                    ? getString(R.string.main_free_space, formatBytes(available))
                    : getString(R.string.main_free_space_unknown));
        }

        binding.btnTransfer.setEnabled(!copyRunning && writable && isSourceModeReady());
        binding.tvStorageStatus.setBackgroundResource(writable
                ? R.drawable.bg_status_ready
                : R.drawable.bg_status_missing);
        int storageStatusText = writable
                ? R.string.main_usb_ready
                : (uri == null ? R.string.main_usb_missing : R.string.main_usb_waiting);
        binding.tvStorageStatus.setText(storageStatusText);
        binding.tvStorageStatus.setTextColor(ContextCompat.getColor(this,
                writable ? R.color.vm_success : R.color.vm_text));

        if (resetProcess) {
            binding.btnEjectHint.setVisibility(View.GONE);
            if (!writable) {
                setIdleProcess(getString(R.string.process_setup_needed_title),
                        getString(R.string.process_setup_needed_detail));
            } else if (!isSourceModeReady()) {
                setIdleProcess(getString(R.string.process_setup_needed_title),
                        getString(R.string.toast_pick_source_first));
            } else {
                setIdleProcess(getString(deleteAfter
                                ? R.string.process_idle_move_title
                                : R.string.process_idle_copy_title),
                        getString(R.string.process_idle_detail));
            }
        }
        return writable;
    }

    private String getVideoAccessStatusText(int accessState) {
        if (accessState == VideoPermissionUtil.ACCESS_FULL) {
            return getString(R.string.main_video_access_full);
        }
        if (accessState == VideoPermissionUtil.ACCESS_PARTIAL) {
            return getString(R.string.main_video_access_partial);
        }
        return getString(R.string.main_video_access_denied);
    }

    private boolean isDestinationWritable(@Nullable Uri uri) {
        return uri != null
                && SafUtil.hasPersistedWritePermission(this, uri)
                && SafUtil.canWriteTree(this, uri);
    }

    private void startDestinationRecheckWindow() {
        destinationRecheckUntilMs = SystemClock.elapsedRealtime() + DESTINATION_RECHECK_WINDOW_MS;
        scheduleDestinationRecheck();
    }

    private void scheduleDestinationRecheck() {
        if (destinationRecheckScheduled || copyRunning || SettingsStore.getDestTreeUri(this) == null) {
            return;
        }
        destinationRecheckScheduled = true;
        mainHandler.postDelayed(destinationRecheckRunnable, DESTINATION_RECHECK_INTERVAL_MS);
    }

    private void runDestinationRecheck() {
        destinationRecheckScheduled = false;
        if (copyRunning || binding == null) {
            return;
        }

        boolean writable = refreshMainInfo(true);
        if (writable || SettingsStore.getDestTreeUri(this) == null) {
            stopDestinationRecheck();
            return;
        }

        if (SystemClock.elapsedRealtime() < destinationRecheckUntilMs) {
            scheduleDestinationRecheck();
        }
    }

    private void stopDestinationRecheck() {
        destinationRecheckScheduled = false;
        mainHandler.removeCallbacks(destinationRecheckRunnable);
    }

    private String buildSearchModeText() {
        if (SettingsStore.isUseDcimAll(this)) {
            return getString(R.string.main_search_all);
        }
        String rel = SettingsStore.getSourceRelPath(this);
        return TextUtils.isEmpty(rel)
                ? getString(R.string.main_search_manual_missing)
                : getString(R.string.main_search_manual, rel);
    }

    private boolean isSourceModeReady() {
        return SettingsStore.isUseDcimAll(this)
                || !TextUtils.isEmpty(SettingsStore.getSourceRelPath(this));
    }

    private void setIdleProcess(String title, String detail) {
        setProcessStage(title, detail, 0, false, false);
    }

    private void onTransferAll() {
        if (!ensureVideoPermission(true)) return;

        Uri destTree = SettingsStore.getDestTreeUri(this);
        if (destTree == null) {
            Toast.makeText(this, getString(R.string.toast_pick_dest_first), Toast.LENGTH_LONG).show();
            refreshMainInfoAndStartDestinationRecheck(true);
            return;
        }
        if (!isDestinationWritable(destTree)) {
            Toast.makeText(this, getString(R.string.no_write_access), Toast.LENGTH_LONG).show();
            refreshMainInfoAndStartDestinationRecheck(true);
            return;
        }
        if (!isSourceModeReady()) {
            Toast.makeText(this, getString(R.string.toast_pick_source_first), Toast.LENGTH_LONG).show();
            refreshMainInfoAndStartDestinationRecheck(true);
            return;
        }
        if (!ensureNotificationPermission(true)) return;

        OperationStateStore.clear(this);
        copyRunning = true;
        binding.btnTransfer.setEnabled(false);
        binding.btnSettings.setEnabled(false);
        binding.btnEjectHint.setVisibility(View.GONE);
        setProcessStage(getString(R.string.stage_searching), getString(R.string.process_searching_detail),
                0, true, false);

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

        try {
            ContextCompat.startForegroundService(this, service);
        } catch (Exception e) {
            copyRunning = false;
            binding.btnSettings.setEnabled(true);
            markProcessFinished(getString(R.string.process_failed_detail,
                    e.getClass().getSimpleName() + ": " + e.getMessage()), true);
        }
    }

    private void updateProcessFromProgress(Intent intent) {
        int stage = intent.getIntExtra(CopyService.EXTRA_STAGE, CopyService.STAGE_COPYING);
        int done = intent.getIntExtra(CopyService.EXTRA_DONE, 0);
        int total = intent.getIntExtra(CopyService.EXTRA_TOTAL, 0);
        int copied = intent.getIntExtra(CopyService.EXTRA_OK, 0);
        int fail = intent.getIntExtra(CopyService.EXTRA_FAIL, 0);
        int duplicates = intent.getIntExtra(CopyService.EXTRA_DUPLICATES, 0);
        String currentName = intent.getStringExtra(CopyService.EXTRA_CURRENT_NAME);
        long currentBytes = intent.getLongExtra(CopyService.EXTRA_CURRENT_BYTES, 0L);
        long currentTotalBytes = intent.getLongExtra(CopyService.EXTRA_CURRENT_TOTAL_BYTES, 0L);
        long copiedBytes = intent.getLongExtra(CopyService.EXTRA_COPIED_BYTES, 0L);
        long totalBytes = intent.getLongExtra(CopyService.EXTRA_TOTAL_BYTES, 0L);
        long availableBytes = intent.getLongExtra(CopyService.EXTRA_AVAILABLE_BYTES, -1L);
        long speed = intent.getLongExtra(CopyService.EXTRA_SPEED_BYTES_PER_SECOND, 0L);
        String error = intent.getStringExtra(CopyService.EXTRA_ERROR_MESSAGE);

        int progress = calculateProgress(stage, done, total, copiedBytes, totalBytes);
        boolean indeterminate = stage == CopyService.STAGE_SEARCHING || stage == CopyService.STAGE_CHECKING_SPACE;
        setProcessStage(getStageTitle(stage, done, total), buildStageDetail(stage, copied, fail, duplicates,
                        currentName, currentBytes, currentTotalBytes, copiedBytes, totalBytes,
                        availableBytes, speed, error),
                progress, indeterminate, stage == CopyService.STAGE_ERROR);
    }

    private void updateProcessFromSnapshot(OperationStateStore.Snapshot snapshot) {
        int progress = calculateProgress(snapshot.stage, snapshot.done, snapshot.total,
                snapshot.copiedBytes, snapshot.totalBytes);
        boolean indeterminate = snapshot.stage == CopyService.STAGE_SEARCHING
                || snapshot.stage == CopyService.STAGE_CHECKING_SPACE;
        setProcessStage(getStageTitle(snapshot.stage, snapshot.done, snapshot.total),
                buildStageDetail(snapshot.stage, snapshot.copied, snapshot.fail, snapshot.duplicates,
                        snapshot.currentName, snapshot.currentBytes, snapshot.currentTotalBytes,
                        snapshot.copiedBytes, snapshot.totalBytes, snapshot.availableBytes,
                        snapshot.speedBytesPerSecond, snapshot.errorMessage),
                progress, indeterminate, snapshot.stage == CopyService.STAGE_ERROR);
    }

    private int calculateProgress(int stage, int done, int total, long copiedBytes, long totalBytes) {
        if (stage == CopyService.STAGE_DONE) return PROGRESS_MAX;
        if (stage == CopyService.STAGE_ERROR) return 0;
        if (totalBytes > 0) {
            return (int) Math.max(0, Math.min(PROGRESS_MAX, copiedBytes * PROGRESS_MAX / totalBytes));
        }
        if (total > 0) {
            return (int) Math.max(0, Math.min(PROGRESS_MAX, done * PROGRESS_MAX / total));
        }
        return 0;
    }

    private String getStageTitle(int stage, int done, int total) {
        switch (stage) {
            case CopyService.STAGE_SEARCHING:
                return getString(R.string.stage_searching);
            case CopyService.STAGE_CHECKING_SPACE:
                return getString(R.string.stage_checking_space);
            case CopyService.STAGE_VERIFYING:
                return getString(R.string.stage_verifying);
            case CopyService.STAGE_DONE:
                return getString(R.string.stage_done);
            case CopyService.STAGE_ERROR:
                return getString(R.string.stage_problem);
            case CopyService.STAGE_COPYING:
            default:
                return getString(R.string.stage_copying, Math.min(done + 1, Math.max(total, 1)), total);
        }
    }

    private String buildStageDetail(int stage, int copied, int fail, int duplicates,
                                    @Nullable String currentName, long currentBytes,
                                    long currentTotalBytes, long copiedBytes, long totalBytes,
                                    long availableBytes, long speed, @Nullable String error) {
        if (!TextUtils.isEmpty(error)) {
            return getString(R.string.process_failed_detail, error);
        }
        if (stage == CopyService.STAGE_SEARCHING) {
            return getString(R.string.process_searching_detail);
        }
        if (stage == CopyService.STAGE_CHECKING_SPACE) {
            return availableBytes >= 0
                    ? getString(R.string.process_space_detail, formatBytes(totalBytes), formatBytes(availableBytes))
                    : getString(R.string.process_space_unknown_detail, formatBytes(totalBytes));
        }
        if (stage == CopyService.STAGE_DONE) {
            return getString(R.string.stage_done);
        }

        String status = getString(R.string.process_counters, copied, duplicates, fail);
        if (TextUtils.isEmpty(currentName)) {
            return status;
        }
        String bytes = currentTotalBytes > 0
                ? getString(R.string.process_file_bytes, formatBytes(currentBytes), formatBytes(currentTotalBytes))
                : formatBytes(copiedBytes);
        String speedText = speed > 0
                ? getString(R.string.process_speed, formatRate(speed), formatEta(totalBytes - copiedBytes, speed))
                : getString(R.string.process_speed_waiting);
        return getString(R.string.process_file_detail, status, currentName, bytes, speedText);
    }

    private void setProcessStage(String title, String detail, int progress,
                                 boolean indeterminate, boolean problem) {
        binding.tvProcessStage.setText(title);
        binding.tvProcessStage.setTextColor(ContextCompat.getColor(this,
                problem ? R.color.vm_warning : R.color.vm_text));
        binding.tvProgress.setText(detail);
        binding.processProgress.setIndeterminate(indeterminate);
        if (!indeterminate) {
            binding.processProgress.setProgress(Math.max(0, Math.min(PROGRESS_MAX, progress)));
        }
    }

    private String buildFinalSummary(int copied, int total, int fail, int duplicates, long copiedBytes) {
        if (total == 0) {
            return getString(R.string.process_done_no_files);
        }
        if (fail > 0) {
            return getString(R.string.process_done_with_errors,
                    copied, duplicates, fail, formatBytes(copiedBytes));
        }
        if (duplicates > 0) {
            return getString(R.string.process_done_with_duplicates,
                    copied, duplicates, formatBytes(copiedBytes));
        }
        return getString(R.string.process_done_success, copied, formatBytes(copiedBytes));
    }

    private void markProcessFinished(String detail, boolean problem) {
        OperationStateStore.markResultHandled(this);
        copyRunning = false;
        setProcessStage(getString(problem ? R.string.stage_problem : R.string.stage_done),
                detail, problem ? 0 : PROGRESS_MAX, false, problem);
        binding.btnEjectHint.setVisibility(View.VISIBLE);
        binding.btnSettings.setEnabled(true);
        refreshMainInfo(false);
    }

    private void requestDeleteOriginals(ArrayList<Uri> toDelete) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                IntentSender sender = MediaStore
                        .createDeleteRequest(getContentResolver(), toDelete)
                        .getIntentSender();
                OperationStateStore.markDeleteRequestStarted(this);
                deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
            } catch (Exception e) {
                markProcessFinished(getString(R.string.toast_delete_request_failed, e.getMessage()), true);
            }
            return;
        }

        for (Uri uri : toDelete) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception ignore) {
            }
        }
        markProcessFinished(getString(R.string.process_delete_done, pendingDeleteResultText), false);
        Toast.makeText(this, getString(R.string.deleting_done), Toast.LENGTH_SHORT).show();
        pendingDeleteResultText = null;
    }

    private void showEjectInstruction() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.eject_ready_title)
                .setMessage(R.string.eject_ready_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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

    private String formatBytes(long bytes) {
        if (bytes < 0) return getString(R.string.value_unknown);
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024d);
    }

    private String formatRate(long bytesPerSecond) {
        return getString(R.string.value_per_second, formatBytes(bytesPerSecond));
    }

    private String formatEta(long remainingBytes, long speedBytesPerSecond) {
        if (remainingBytes <= 0 || speedBytesPerSecond <= 0) {
            return getString(R.string.value_eta_short);
        }
        long seconds = Math.max(1L, remainingBytes / speedBytesPerSecond);
        if (seconds < 60) {
            return getString(R.string.value_eta_seconds, seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return getString(R.string.value_eta_minutes, minutes);
        }
        long hours = minutes / 60;
        return getString(R.string.value_eta_hours, hours);
    }
}
