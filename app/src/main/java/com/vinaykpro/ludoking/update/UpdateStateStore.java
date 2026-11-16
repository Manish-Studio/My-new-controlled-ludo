package com.vinaykpro.ludoking.update;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Isolated storage for APK updater state (check throttle, cached manifest, dismissed optional version,
 * pending verified APK path). Kept completely separate from gameplay SharedPreferences ("LudoModUser").
 */
public interface UpdateStateStore {

    long DEFAULT_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 hours

    long getLastCheckTimestampMs();
    void setLastCheckTimestampMs(long timestampMs);

    String getLastCheckStatus();
    void setLastCheckStatus(String status);

    String getCachedManifestJson();
    void setCachedManifestJson(String json);

    int getDismissedOptionalVersionCode();
    void setDismissedOptionalVersionCode(int versionCode);

    String getPendingInstallApkPath();
    String getPendingInstallExpectedSha256();
    void setPendingInstallApk(String apkPath, String expectedSha256);
    void clearPendingInstallApk();

    default boolean shouldRunAutomaticCheck(long nowMs, long minIntervalMs) {
        long last = getLastCheckTimestampMs();
        return last <= 0L || (nowMs - last) >= minIntervalMs;
    }

    final class InMemory implements UpdateStateStore {
        private long lastCheckTimestampMs = 0L;
        private String lastCheckStatus = "Never checked";
        private String cachedManifestJson = null;
        private int dismissedOptionalVersionCode = -1;
        private String pendingApkPath = null;
        private String pendingExpectedSha256 = null;

        @Override public long getLastCheckTimestampMs() { return lastCheckTimestampMs; }
        @Override public void setLastCheckTimestampMs(long timestampMs) { this.lastCheckTimestampMs = timestampMs; }
        @Override public String getLastCheckStatus() { return lastCheckStatus; }
        @Override public void setLastCheckStatus(String status) { this.lastCheckStatus = status; }
        @Override public String getCachedManifestJson() { return cachedManifestJson; }
        @Override public void setCachedManifestJson(String json) { this.cachedManifestJson = json; }
        @Override public int getDismissedOptionalVersionCode() { return dismissedOptionalVersionCode; }
        @Override public void setDismissedOptionalVersionCode(int versionCode) { this.dismissedOptionalVersionCode = versionCode; }
        @Override public String getPendingInstallApkPath() { return pendingApkPath; }
        @Override public String getPendingInstallExpectedSha256() { return pendingExpectedSha256; }
        @Override
        public void setPendingInstallApk(String apkPath, String expectedSha256) {
            this.pendingApkPath = apkPath;
            this.pendingExpectedSha256 = expectedSha256;
        }
        @Override
        public void clearPendingInstallApk() {
            this.pendingApkPath = null;
            this.pendingExpectedSha256 = null;
        }
    }

    final class Prefs implements UpdateStateStore {
        public static final String PREFS_NAME = "LudoAppUpdater";
        private static final String KEY_LAST_CHECK_MS = "last_check_ms";
        private static final String KEY_LAST_STATUS = "last_status";
        private static final String KEY_CACHED_MANIFEST = "cached_manifest_json";
        private static final String KEY_DISMISSED_OPTIONAL_CODE = "dismissed_optional_code";
        private static final String KEY_PENDING_APK_PATH = "pending_apk_path";
        private static final String KEY_PENDING_APK_SHA256 = "pending_apk_sha256";

        private final SharedPreferences prefs;

        public Prefs(Context context) {
            this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        @Override
        public long getLastCheckTimestampMs() {
            return prefs.getLong(KEY_LAST_CHECK_MS, 0L);
        }

        @Override
        public void setLastCheckTimestampMs(long timestampMs) {
            prefs.edit().putLong(KEY_LAST_CHECK_MS, timestampMs).apply();
        }

        @Override
        public String getLastCheckStatus() {
            return prefs.getString(KEY_LAST_STATUS, "Never checked");
        }

        @Override
        public void setLastCheckStatus(String status) {
            prefs.edit().putString(KEY_LAST_STATUS, status != null ? status : "").apply();
        }

        @Override
        public String getCachedManifestJson() {
            return prefs.getString(KEY_CACHED_MANIFEST, null);
        }

        @Override
        public void setCachedManifestJson(String json) {
            prefs.edit().putString(KEY_CACHED_MANIFEST, json).apply();
        }

        @Override
        public int getDismissedOptionalVersionCode() {
            return prefs.getInt(KEY_DISMISSED_OPTIONAL_CODE, -1);
        }

        @Override
        public void setDismissedOptionalVersionCode(int versionCode) {
            prefs.edit().putInt(KEY_DISMISSED_OPTIONAL_CODE, versionCode).apply();
        }

        @Override
        public String getPendingInstallApkPath() {
            return prefs.getString(KEY_PENDING_APK_PATH, null);
        }

        @Override
        public String getPendingInstallExpectedSha256() {
            return prefs.getString(KEY_PENDING_APK_SHA256, null);
        }

        @Override
        public void setPendingInstallApk(String apkPath, String expectedSha256) {
            prefs.edit()
                    .putString(KEY_PENDING_APK_PATH, apkPath)
                    .putString(KEY_PENDING_APK_SHA256, expectedSha256)
                    .apply();
        }

        @Override
        public void clearPendingInstallApk() {
            prefs.edit()
                    .remove(KEY_PENDING_APK_PATH)
                    .remove(KEY_PENDING_APK_SHA256)
                    .apply();
        }
    }
}
