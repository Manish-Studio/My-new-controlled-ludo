package com.vinaykpro.ludoking.update;

/**
 * Evaluates a parsed UpdateManifest against the running application's version, packageName,
 * and optional-update dismissal state.
 */
public final class UpdateEvaluator {

    public enum Status {
        UP_TO_DATE,
        OPTIONAL_UPDATE,
        MANDATORY_UPDATE,
        INVALID_ENTRY
    }

    public static final class EvaluationResult {
        public final Status status;
        public final UpdateManifest.AppReleaseInfo releaseInfo;
        public final boolean shouldShowAutoPrompt;
        public final boolean shouldShowManualPrompt;
        public final boolean belowMinSupportedVersion;
        public final String message;

        public EvaluationResult(
                Status status,
                UpdateManifest.AppReleaseInfo releaseInfo,
                boolean shouldShowAutoPrompt,
                boolean shouldShowManualPrompt,
                boolean belowMinSupportedVersion,
                String message
        ) {
            this.status = status;
            this.releaseInfo = releaseInfo;
            this.shouldShowAutoPrompt = shouldShowAutoPrompt;
            this.shouldShowManualPrompt = shouldShowManualPrompt;
            this.belowMinSupportedVersion = belowMinSupportedVersion;
            this.message = message;
        }
    }

    private UpdateEvaluator() {}

    public static EvaluationResult evaluate(
            UpdateManifest manifest,
            String expectedPackageName,
            String preferredAppId,
            int installedVersionCode,
            int dismissedOptionalVersionCode
    ) {
        if (manifest == null) {
            return new EvaluationResult(
                    Status.INVALID_ENTRY, null, false, false, false,
                    "Update manifest is null.");
        }
        UpdateManifest.AppReleaseInfo entry = manifest.findMatchingApp(expectedPackageName, preferredAppId);
        if (entry == null) {
            return new EvaluationResult(
                    Status.INVALID_ENTRY, null, false, false, false,
                    "No matching update entry found for package: " + expectedPackageName);
        }
        if (!expectedPackageName.equals(entry.packageName)) {
            return new EvaluationResult(
                    Status.INVALID_ENTRY, null, false, false, false,
                    "Manifest entry packageName mismatch.");
        }

        if (entry.latestVersionCode <= installedVersionCode) {
            return new EvaluationResult(
                    Status.UP_TO_DATE,
                    entry,
                    false,
                    false,
                    false,
                    "You are on the latest version (" + entry.latestVersionName + ")."
            );
        }

        // Update is available: validate HTTPS APK URL and SHA-256 strictly before offering download!
        if (!UpdateManifestParser.isValidHttpsUrl(entry.apkUrl)) {
            return new EvaluationResult(
                    Status.INVALID_ENTRY, entry, false, false, false,
                    "Update rejected: APK download URL must use valid HTTPS.");
        }
        if (!Sha256Verifier.isValidSha256Hex(entry.sha256)) {
            return new EvaluationResult(
                    Status.INVALID_ENTRY, entry, false, false, false,
                    "Update rejected: Manifest missing valid 64-hex SHA-256 checksum.");
        }

        boolean belowMin = installedVersionCode < entry.minSupportedVersionCode;
        boolean mandatory = entry.mandatory || belowMin;

        if (mandatory) {
            String msg = belowMin
                    ? "Your installed version is no longer supported. Update to " + entry.latestVersionName + " is required."
                    : "A required update (" + entry.latestVersionName + ") is available.";
            return new EvaluationResult(
                    Status.MANDATORY_UPDATE,
                    entry,
                    true,
                    true,
                    belowMin,
                    msg
            );
        }

        boolean dismissed = dismissedOptionalVersionCode == entry.latestVersionCode;
        return new EvaluationResult(
                Status.OPTIONAL_UPDATE,
                entry,
                !dismissed,
                true,
                false,
                "Version " + entry.latestVersionName + " is available."
        );
    }
}
