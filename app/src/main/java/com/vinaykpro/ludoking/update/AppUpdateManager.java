package com.vinaykpro.ludoking.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.BuildConfig;
import com.example.R;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Coordinates automatic & manual GitHub Releases APK update checks, caching, state persistence,
 * and download/install orchestration for Ludo King.
 */
public final class AppUpdateManager {

    public static final String APP_ID_LUDO = "ludo_king";

    public interface CheckCallback {
        void onCheckCompleted(boolean success, UpdateEvaluator.EvaluationResult evaluation, String statusText);
    }

    private static volatile AppUpdateManager instance;

    private final Context appContext;
    private final UpdateStateStore stateStore;
    private final ExecutorService executor;
    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ApkUpdateDownloader downloader;

    private UpdateEvaluator.EvaluationResult latestEvaluation;

    private AppUpdateManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.stateStore = new UpdateStateStore.Prefs(this.appContext);
        this.executor = Executors.newSingleThreadExecutor();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.downloader = new ApkUpdateDownloader(this.appContext, this.stateStore, this.executor);
    }

    public static AppUpdateManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AppUpdateManager.class) {
                if (instance == null) {
                    instance = new AppUpdateManager(context);
                }
            }
        }
        return instance;
    }

    public String getInstalledVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    public int getInstalledVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    public String getLastCheckStatus() {
        return stateStore.getLastCheckStatus();
    }

    public UpdateEvaluator.EvaluationResult getLatestEvaluation() {
        if (latestEvaluation != null) {
            return latestEvaluation;
        }
        String cachedJson = stateStore.getCachedManifestJson();
        if (cachedJson != null) {
            try {
                UpdateManifest manifest = UpdateManifestParser.parse(cachedJson);
                latestEvaluation = UpdateEvaluator.evaluate(
                        manifest,
                        appContext.getPackageName(),
                        APP_ID_LUDO,
                        BuildConfig.VERSION_CODE,
                        stateStore.getDismissedOptionalVersionCode()
                );
                return latestEvaluation;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public ApkUpdateDownloader getDownloader() {
        return downloader;
    }

    public void dismissOptionalUpdate(int versionCode) {
        stateStore.setDismissedOptionalVersionCode(versionCode);
    }

    public void checkAutomaticallyIfDue(CheckCallback callback) {
        String manifestUrl = appContext.getString(R.string.apk_update_manifest_url);
        if (!UpdateManifestParser.isConfiguredManifestUrl(manifestUrl)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!stateStore.shouldRunAutomaticCheck(now, UpdateStateStore.DEFAULT_CHECK_INTERVAL_MS)) {
            // Re-evaluate cached manifest if a mandatory update was previously discovered
            UpdateEvaluator.EvaluationResult cached = getLatestEvaluation();
            if (cached != null && cached.status == UpdateEvaluator.Status.MANDATORY_UPDATE && callback != null) {
                callback.onCheckCompleted(true, cached, stateStore.getLastCheckStatus());
            }
            return;
        }
        fetchAndEvaluate(manifestUrl, false, callback);
    }

    public void checkManually(CheckCallback callback) {
        String manifestUrl = appContext.getString(R.string.apk_update_manifest_url);
        if (!UpdateManifestParser.isConfiguredManifestUrl(manifestUrl)) {
            String msg = "Manifest URL not configured yet (set apk_update_manifest_url in strings.xml)";
            stateStore.setLastCheckStatus(msg);
            if (callback != null) {
                mainHandler.post(() -> callback.onCheckCompleted(false, null, msg));
            }
            return;
        }
        fetchAndEvaluate(manifestUrl, true, callback);
    }

    public boolean resumePendingInstallIfPermitted(Context activityContext, ApkUpdateDownloader.DownloadListener listener) {
        String pendingPath = stateStore.getPendingInstallApkPath();
        String expectedSha = stateStore.getPendingInstallExpectedSha256();
        if (pendingPath == null || pendingPath.isEmpty() || !downloader.canRequestPackageInstalls()) {
            return false;
        }
        File pendingFile = new File(pendingPath);
        if (!pendingFile.isFile()) {
            stateStore.clearPendingInstallApk();
            return false;
        }
        ApkInstallVerifier.VerificationResult verification = ApkInstallVerifier.verifyApkForInstall(
                appContext,
                pendingFile,
                appContext.getPackageName(),
                expectedSha
        );
        stateStore.clearPendingInstallApk();
        if (!verification.valid) {
            //noinspection ResultOfMethodCallIgnored
            pendingFile.delete();
            if (listener != null) {
                listener.onDownloadError(verification.errorMessage, !verification.signatureMismatch);
            }
            return false;
        }
        boolean launched = downloader.launchPackageInstaller(activityContext, pendingFile);
        if (launched && listener != null) {
            listener.onInstallerLaunched(pendingFile);
        }
        return launched;
    }

    private void fetchAndEvaluate(String manifestUrl, boolean isManual, CheckCallback callback) {
        executor.execute(() -> {
            String timeStr = new SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(new Date());
            try {
                if (!UpdateManifestParser.isValidHttpsUrl(manifestUrl)) {
                    throw new IOException("Manifest URL must use HTTPS.");
                }
                Request req = new Request.Builder()
                        .url(manifestUrl.trim())
                        .header("Accept", "application/json")
                        .build();

                String bodyJson;
                try (Response response = httpClient.newCall(req).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP " + response.code() + " while fetching manifest.");
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("Empty response body from manifest server.");
                    }
                    bodyJson = body.string();
                }

                UpdateManifest manifest = UpdateManifestParser.parse(bodyJson);
                UpdateEvaluator.EvaluationResult eval = UpdateEvaluator.evaluate(
                        manifest,
                        appContext.getPackageName(),
                        APP_ID_LUDO,
                        BuildConfig.VERSION_CODE,
                        stateStore.getDismissedOptionalVersionCode()
                );

                stateStore.setLastCheckTimestampMs(System.currentTimeMillis());
                if (eval.status != UpdateEvaluator.Status.INVALID_ENTRY) {
                    stateStore.setCachedManifestJson(bodyJson);
                }
                latestEvaluation = eval;

                String statusText = eval.message + " (" + timeStr + ")";
                stateStore.setLastCheckStatus(statusText);

                if (callback != null) {
                    mainHandler.post(() -> callback.onCheckCompleted(
                            eval.status != UpdateEvaluator.Status.INVALID_ENTRY, eval, statusText));
                }
            } catch (Exception e) {
                // Graceful fallback to cached manifest if available
                UpdateEvaluator.EvaluationResult cachedEval = getLatestEvaluation();
                String statusText = (isManual ? "Check failed: " : "Offline / check skipped: ")
                        + e.getMessage() + " (" + timeStr + ")";
                stateStore.setLastCheckStatus(statusText);
                if (callback != null) {
                    mainHandler.post(() -> callback.onCheckCompleted(false, cachedEval, statusText));
                }
            }
        });
    }
}
