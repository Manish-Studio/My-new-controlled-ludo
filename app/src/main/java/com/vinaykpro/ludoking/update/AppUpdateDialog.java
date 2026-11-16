package com.vinaykpro.ludoking.update;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.R;

import java.io.File;
import java.util.Locale;

/**
 * Clean optional / mandatory APK update prompt with download progress, SHA-256 verification status,
 * Unknown Sources permission guidance, and retry handling.
 */
public final class AppUpdateDialog implements ApkUpdateDownloader.DownloadListener {

    private final Activity activity;
    private final AppUpdateManager updateManager;
    private final UpdateEvaluator.EvaluationResult evaluation;
    private final boolean isMandatory;
    private Dialog dialog;

    private TextView errorText;
    private View progressContainer;
    private ProgressBar progressBar;
    private TextView progressText;
    private Button laterBtn;
    private Button updateBtn;

    private boolean awaitingInstallPermission = false;

    public AppUpdateDialog(
            Activity activity,
            AppUpdateManager updateManager,
            UpdateEvaluator.EvaluationResult evaluation
    ) {
        this.activity = activity;
        this.updateManager = updateManager;
        this.evaluation = evaluation;
        this.isMandatory = evaluation.status == UpdateEvaluator.Status.MANDATORY_UPDATE;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void show() {
        if (activity.isFinishing() || activity.isDestroyed() || evaluation.releaseInfo == null) {
            return;
        }
        UpdateManifest.AppReleaseInfo info = evaluation.releaseInfo;

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_update);
        dialog.setCancelable(!isMandatory);
        dialog.setCanceledOnTouchOutside(!isMandatory);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView titleView = dialog.findViewById(R.id.updateDialogTitle);
        TextView versionBadge = dialog.findViewById(R.id.updateDialogVersionBadge);
        TextView dateText = dialog.findViewById(R.id.updateDialogDateText);
        TextView mandatoryBanner = dialog.findViewById(R.id.updateDialogMandatoryBanner);
        TextView notesText = dialog.findViewById(R.id.updateDialogReleaseNotes);
        progressContainer = dialog.findViewById(R.id.updateDialogProgressContainer);
        progressBar = dialog.findViewById(R.id.updateDialogProgressBar);
        progressText = dialog.findViewById(R.id.updateDialogProgressText);
        errorText = dialog.findViewById(R.id.updateDialogErrorText);
        laterBtn = dialog.findViewById(R.id.updateDialogLaterBtn);
        updateBtn = dialog.findViewById(R.id.updateDialogUpdateBtn);

        titleView.setText(isMandatory ? "Required Update" : "Update Available");
        versionBadge.setText(String.format(
                Locale.US,
                "Installed: v%s (%d)  ->  Latest: v%s (%d)",
                updateManager.getInstalledVersionName(),
                updateManager.getInstalledVersionCode(),
                info.latestVersionName,
                info.latestVersionCode
        ));

        if (!info.releaseDate.isEmpty()) {
            dateText.setText("Released: " + info.releaseDate);
            dateText.setVisibility(View.VISIBLE);
        } else {
            dateText.setVisibility(View.GONE);
        }

        if (isMandatory) {
            mandatoryBanner.setText(evaluation.belowMinSupportedVersion
                    ? "Your installed version is no longer supported. Please update to continue."
                    : "A mandatory update is required before continuing.");
            mandatoryBanner.setVisibility(View.VISIBLE);
            laterBtn.setVisibility(View.GONE);
        } else {
            mandatoryBanner.setVisibility(View.GONE);
            laterBtn.setVisibility(View.VISIBLE);
            laterBtn.setOnClickListener(v -> {
                updateManager.getDownloader().cancel();
                updateManager.dismissOptionalUpdate(info.latestVersionCode);
                dialog.dismiss();
            });
        }

        notesText.setText(info.releaseNotes.isEmpty()
                ? "Includes bug fixes and stability improvements."
                : info.releaseNotes);

        updateBtn.setText("Update");
        updateBtn.setOnClickListener(v -> {
            if (awaitingInstallPermission) {
                activity.startActivity(updateManager.getDownloader().buildUnknownSourcesSettingsIntent());
                return;
            }
            errorText.setVisibility(View.GONE);
            updateBtn.setEnabled(false);
            updateBtn.setText("Downloading...");
            updateManager.getDownloader().startDownload(info, this);
        });

        dialog.show();
    }

    public void onActivityResumed() {
        if (awaitingInstallPermission && dialog != null && dialog.isShowing()) {
            updateManager.resumePendingInstallIfPermitted(activity, this);
        }
    }

    @Override
    public void onDownloadStarted() {
        if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
        if (progressBar != null) {
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
        }
        if (progressText != null) progressText.setText("Starting HTTPS download...");
    }

    @Override
    public void onDownloadProgress(int percent, long downloadedBytes, long totalBytes) {
        if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
        if (progressBar != null) {
            if (percent >= 0) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(percent);
            } else {
                progressBar.setIndeterminate(true);
            }
        }
        if (progressText != null) {
            if (percent >= 0 && totalBytes > 0) {
                double mbDone = downloadedBytes / (1024.0 * 1024.0);
                double mbTotal = totalBytes / (1024.0 * 1024.0);
                progressText.setText(String.format(Locale.US, "Downloading... %d%% (%.1f / %.1f MB)", percent, mbDone, mbTotal));
            } else {
                progressText.setText("Downloading update APK...");
            }
        }
    }

    @Override
    public void onVerifyingPackage() {
        if (progressBar != null) progressBar.setIndeterminate(true);
        if (progressText != null) progressText.setText("Verifying SHA-256 checksum & package signature...");
    }

    @Override
    public void onUnknownSourcesPermissionNeeded(File verifiedApkFile) {
        awaitingInstallPermission = true;
        if (progressContainer != null) progressContainer.setVisibility(View.GONE);
        if (errorText != null) {
            errorText.setTextColor(Color.parseColor("#FFD700"));
            errorText.setText("Allow 'Install unknown apps' permission for Ludo King to complete the update.");
            errorText.setVisibility(View.VISIBLE);
        }
        if (updateBtn != null) {
            updateBtn.setEnabled(true);
            updateBtn.setText("Allow & Install");
        }
    }

    @Override
    public void onInstallerLaunched(File verifiedApkFile) {
        awaitingInstallPermission = false;
        if (!isMandatory && dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        } else if (updateBtn != null) {
            updateBtn.setEnabled(true);
            updateBtn.setText("Install Update");
        }
    }

    @Override
    public void onDownloadError(String errorMessage, boolean retryable) {
        awaitingInstallPermission = false;
        if (progressContainer != null) progressContainer.setVisibility(View.GONE);
        if (errorText != null) {
            errorText.setTextColor(Color.parseColor("#FF6B6B"));
            errorText.setText(errorMessage != null ? errorMessage : "Update failed.");
            errorText.setVisibility(View.VISIBLE);
        }
        if (updateBtn != null) {
            updateBtn.setEnabled(true);
            updateBtn.setText(retryable ? "Retry" : "Close");
            if (!retryable && !isMandatory) {
                updateBtn.setOnClickListener(v -> {
                    if (dialog != null) dialog.dismiss();
                });
            }
        }
    }
}
