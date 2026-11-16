package com.vinaykpro.ludoking.update;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable model representing the centralized multi-app GitHub Releases update manifest.
 */
public final class UpdateManifest {

    public static final class AppReleaseInfo {
        public final String appId;
        public final String packageName;
        public final String displayName;
        public final String latestVersionName;
        public final int latestVersionCode;
        public final int minSupportedVersionCode;
        public final String apkUrl;
        public final String sha256;
        public final String releaseNotes;
        public final String releaseDate;
        public final boolean mandatory;

        public AppReleaseInfo(
                String appId,
                String packageName,
                String displayName,
                String latestVersionName,
                int latestVersionCode,
                int minSupportedVersionCode,
                String apkUrl,
                String sha256,
                String releaseNotes,
                String releaseDate,
                boolean mandatory
        ) {
            this.appId = appId != null ? appId : "";
            this.packageName = packageName != null ? packageName : "";
            this.displayName = displayName != null ? displayName : "";
            this.latestVersionName = latestVersionName != null ? latestVersionName : "";
            this.latestVersionCode = latestVersionCode;
            this.minSupportedVersionCode = minSupportedVersionCode;
            this.apkUrl = apkUrl != null ? apkUrl : "";
            this.sha256 = sha256 != null ? sha256 : "";
            this.releaseNotes = releaseNotes != null ? releaseNotes : "";
            this.releaseDate = releaseDate != null ? releaseDate : "";
            this.mandatory = mandatory;
        }
    }

    public final int manifestVersion;
    public final String generatedAt;
    public final Map<String, AppReleaseInfo> apps;

    public UpdateManifest(int manifestVersion, String generatedAt, Map<String, AppReleaseInfo> apps) {
        this.manifestVersion = manifestVersion;
        this.generatedAt = generatedAt != null ? generatedAt : "";
        this.apps = apps != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(apps))
                : Collections.emptyMap();
    }

    /**
     * Resolves an entry safely by matching exact packageName first, then optionally appId
     * while still requiring packageName equality.
     */
    public AppReleaseInfo findMatchingApp(String expectedPackageName, String preferredAppId) {
        if (expectedPackageName == null || expectedPackageName.trim().isEmpty()) {
            return null;
        }
        String targetPkg = expectedPackageName.trim();
        if (preferredAppId != null && apps.containsKey(preferredAppId)) {
            AppReleaseInfo byKey = apps.get(preferredAppId);
            if (byKey != null && targetPkg.equals(byKey.packageName)) {
                return byKey;
            }
        }
        for (AppReleaseInfo info : apps.values()) {
            if (targetPkg.equals(info.packageName)) {
                return info;
            }
        }
        return null;
    }
}
