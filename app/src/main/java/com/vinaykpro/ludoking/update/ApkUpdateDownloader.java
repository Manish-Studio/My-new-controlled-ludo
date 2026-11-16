package com.vinaykpro.ludoking.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.concurrent.ExecutorService;

/**
 * Handles reliable HTTPS APK downloading via Android DownloadManager into app-specific storage,
 * storage space checks, interrupted download cleanup, SHA-256 + signing verification, and
 * FileProvider installer launch.
 */
public final class ApkUpdateDownloader {

    public interface DownloadListener {
        void onDownloadStarted();
        void onDownloadProgress(int percent, long downloadedBytes, long totalBytes);
        void onVerifyingPackage();
        void onUnknownSourcesPermissionNeeded(File verifiedApkFile);
        void onInstallerLaunched(File verifiedApkFile);
        void onDownloadError(String errorMessage, boolean retryable);
    }

    private static final long MIN_REQUIRED_FREE_BYTES = 60L * 1024L * 1024L;
    private static final long POLL_INTERVAL_MS = 350L;

    private final Context appContext;
    private final UpdateStateStore stateStore;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long activeDownloadId = -1L;
    private File targetApkFile;
    private UpdateManifest.AppReleaseInfo activeRelease;
    private DownloadListener activeListener;
    private boolean canceled = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollDownloadStatus();
        }
    };

    public ApkUpdateDownloader(Context context, UpdateStateStore stateStore, ExecutorService executor) {
        this.appContext = context.getApplicationContext();
        this.stateStore = stateStore;
        this.executor = executor;
    }

    public boolean isDownloading() {
        return activeDownloadId != -1L && !canceled;
    }

    public void startDownload(UpdateManifest.AppReleaseInfo release, DownloadListener listener) {
        if (release == null || !UpdateManifestParser.isValidHttpsUrl(release.apkUrl)) {
            if (listener != null) {
                listener.onDownloadError("Invalid or non-HTTPS APK download URL.", false);
            }
            return;
        }
        if (!Sha256Verifier.isValidSha256Hex(release.sha256)) {
            if (listener != null) {
                listener.onDownloadError("Missing valid SHA-256 checksum in update manifest.", false);
            }
            return;
        }

        cancel();
        canceled = false;
        activeRelease = release;
        activeListener = listener;

        File downloadsRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsRoot == null) {
            notifyError("App download storage is currently unavailable.", true);
            return;
        }
        File updatesDir = new File(downloadsRoot, "updates");
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            notifyError("Could not create update download directory.", true);
            return;
        }

        if (!hasSufficientStorage(updatesDir)) {
            notifyError("Not enough free storage space (at least 60 MB recommended) to download update.", true);
            return;
        }

        cleanupStaleUpdateFiles(updatesDir);
        stateStore.clearPendingInstallApk();

        String safeFileName = sanitizeFileName(release.appId, release.latestVersionCode);
        targetApkFile = new File(updatesDir, safeFileName);
        if (targetApkFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetApkFile.delete();
        }

        DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            notifyError("Android DownloadManager service is unavailable.", false);
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl))
                    .setTitle(release.displayName + " " + release.latestVersionName)
                    .setDescription("Downloading verified application update")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalFilesDir(
                            appContext,
                            Environment.DIRECTORY_DOWNLOADS,
                            "updates/" + safeFileName
                    );

            activeDownloadId = dm.enqueue(request);
            if (activeListener != null) {
                activeListener.onDownloadStarted();
            }
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        } catch (Exception e) {
            activeDownloadId = -1L;
            notifyError("Failed to start APK download: " + e.getMessage(), true);
        }
    }

    public void cancel() {
        canceled = true;
        mainHandler.removeCallbacks(pollRunnable);
        if (activeDownloadId != -1L) {
            DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                try {
                    dm.remove(activeDownloadId);
                } catch (Exception ignored) {}
            }
            activeDownloadId = -1L;
        }
        if (targetApkFile != null && targetApkFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetApkFile.delete();
        }
    }

    public boolean canRequestPackageInstalls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return appContext.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public Intent buildUnknownSourcesSettingsIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + appContext.getPackageName())
            );
        }
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    public boolean launchPackageInstaller(Context context, File apkFile) {
        if (apkFile == null || !apkFile.isFile()) {
            return false;
        }
        try {
            String authority = appContext.getPackageName() + ".fileprovider";
            Uri contentUri = FileProvider.getUriForFile(appContext, authority, apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof Activity)) {
                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(installIntent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void pollDownloadStatus() {
        if (canceled || activeDownloadId == -1L) {
            return;
        }
        final long idSnapshot = activeDownloadId;
        executor.execute(() -> {
            DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                mainHandler.post(() -> notifyError("DownloadManager unavailable.", true));
                return;
            }
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(idSnapshot);
            try (Cursor cursor = dm.query(query)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    mainHandler.post(() -> {
                        if (!canceled && activeDownloadId == idSnapshot) {
                            activeDownloadId = -1L;
                            deleteTargetFile();
                            notifyError("Download was canceled or interrupted.", true);
                        }
                    });
                    return;
                }

                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));

                if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                    int pct = total > 0L ? (int) Math.min(100L, Math.max(0L, (downloaded * 100L) / total)) : -1;
                    mainHandler.post(() -> {
                        if (!canceled && activeDownloadId == idSnapshot && activeListener != null) {
                            activeListener.onDownloadProgress(pct, downloaded, total);
                            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                        }
                    });
                } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    mainHandler.post(() -> {
                        if (!canceled && activeDownloadId == idSnapshot) {
                            activeDownloadId = -1L;
                            verifyAndProceedInstall();
                        }
                    });
                } else if (status == DownloadManager.STATUS_FAILED) {
                    try {
                        dm.remove(idSnapshot);
                    } catch (Exception ignored) {}
                    mainHandler.post(() -> {
                        if (!canceled && activeDownloadId == idSnapshot) {
                            activeDownloadId = -1L;
                            deleteTargetFile();
                            notifyError(mapDownloadFailureReason(reason), true);
                        }
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!canceled && activeDownloadId == idSnapshot) {
                        activeDownloadId = -1L;
                        deleteTargetFile();
                        notifyError("Download tracking failed: " + e.getMessage(), true);
                    }
                });
            }
        });
    }

    private void verifyAndProceedInstall() {
        if (activeListener != null) {
            activeListener.onVerifyingPackage();
        }
        final File apkSnapshot = targetApkFile;
        final UpdateManifest.AppReleaseInfo releaseSnapshot = activeRelease;
        executor.execute(() -> {
            ApkInstallVerifier.VerificationResult res = ApkInstallVerifier.verifyApkForInstall(
                    appContext,
                    apkSnapshot,
                    releaseSnapshot != null ? releaseSnapshot.packageName : appContext.getPackageName(),
                    releaseSnapshot != null ? releaseSnapshot.sha256 : null
            );
            mainHandler.post(() -> {
                if (canceled) {
                    deleteTargetFile();
                    return;
                }
                if (!res.valid) {
                    deleteTargetFile();
                    stateStore.clearPendingInstallApk();
                    notifyError(res.errorMessage, !res.signatureMismatch);
                    return;
                }

                if (!canRequestPackageInstalls()) {
                    stateStore.setPendingInstallApk(
                            apkSnapshot.getAbsolutePath(),
                            releaseSnapshot != null ? releaseSnapshot.sha256 : ""
                    );
                    if (activeListener != null) {
                        activeListener.onUnknownSourcesPermissionNeeded(apkSnapshot);
                    }
                    return;
                }

                stateStore.clearPendingInstallApk();
                boolean launched = launchPackageInstaller(appContext, apkSnapshot);
                if (launched) {
                    if (activeListener != null) {
                        activeListener.onInstallerLaunched(apkSnapshot);
                    }
                } else {
                    notifyError("Unable to open system package installer.", true);
                }
            });
        });
    }

    private void notifyError(String message, boolean retryable) {
        if (activeListener != null) {
            activeListener.onDownloadError(message, retryable);
        }
    }

    private void deleteTargetFile() {
        if (targetApkFile != null && targetApkFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetApkFile.delete();
        }
    }

    private static boolean hasSufficientStorage(File dir) {
        try {
            StatFs statFs = new StatFs(dir.getAbsolutePath());
            return statFs.getAvailableBytes() >= MIN_REQUIRED_FREE_BYTES;
        } catch (Exception e) {
            return true;
        }
    }

    private static void cleanupStaleUpdateFiles(File updatesDir) {
        File[] files = updatesDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f != null && f.isFile() && f.getName().endsWith(".apk")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static String sanitizeFileName(String appId, int versionCode) {
        String base = (appId != null ? appId : "app").replaceAll("[^a-zA-Z0-9._-]", "_");
        return base + "-v" + versionCode + ".apk";
    }

    private static String mapDownloadFailureReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Download failed: insufficient device storage space.";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "Download failed due to an HTTP error while fetching the release APK.";
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Download interrupted and could not resume. Please retry.";
            default:
                return "Download failed or interrupted (code " + reason + "). Please retry.";
        }
    }
}
