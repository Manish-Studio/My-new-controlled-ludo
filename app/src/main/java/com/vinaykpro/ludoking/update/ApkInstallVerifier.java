package com.vinaykpro.ludoking.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies downloaded APK package identity, version, and signing key compatibility
 * prior to launching the Android system package installer.
 */
public final class ApkInstallVerifier {

    public static final class VerificationResult {
        public final boolean valid;
        public final boolean signatureMismatch;
        public final String errorMessage;

        public VerificationResult(boolean valid, boolean signatureMismatch, String errorMessage) {
            this.valid = valid;
            this.signatureMismatch = signatureMismatch;
            this.errorMessage = errorMessage;
        }
    }

    private ApkInstallVerifier() {}

    /**
     * Pure JVM-testable helper: verifies whether two sets of SHA-256 signing certificate digests
     * share at least one signing certificate. Returns true if either set is empty/unknown.
     */
    public static boolean areCertificateDigestsCompatible(Set<String> installedDigests, Set<String> candidateDigests) {
        if (installedDigests == null || installedDigests.isEmpty()
                || candidateDigests == null || candidateDigests.isEmpty()) {
            return true;
        }
        for (String digest : installedDigests) {
            if (candidateDigests.contains(digest)) {
                return true;
            }
        }
        return false;
    }

    public static VerificationResult verifyApkForInstall(
            Context context,
            File apkFile,
            String expectedPackageName,
            String expectedSha256Hex
    ) {
        if (apkFile == null || !apkFile.isFile() || apkFile.length() == 0L) {
            return new VerificationResult(false, false, "Downloaded APK file is missing or empty.");
        }

        // 1. Mandatory SHA-256 cryptographic checksum check
        if (!Sha256Verifier.verifyFileSha256(apkFile, expectedSha256Hex)) {
            return new VerificationResult(
                    false,
                    false,
                    "APK integrity verification failed (SHA-256 checksum mismatch). The file was removed."
            );
        }

        // 2. Inspect APK archive package metadata & signing certificate
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
        if (archiveInfo == null) {
            return new VerificationResult(
                    false,
                    false,
                    "Downloaded file is not a valid Android APK package."
            );
        }

        if (expectedPackageName != null && !expectedPackageName.equals(archiveInfo.packageName)) {
            return new VerificationResult(
                    false,
                    false,
                    "Package mismatch: APK belongs to '" + archiveInfo.packageName
                            + "' instead of '" + expectedPackageName + "'."
            );
        }

        // 3. Verify signing key compatibility against currently installed package if exposed by PackageManager
        try {
            PackageInfo installedInfo = pm.getPackageInfo(context.getPackageName(), flags);
            Set<String> installedCerts = extractSignatureDigests(installedInfo);
            Set<String> candidateCerts = extractSignatureDigests(archiveInfo);
            if (!areCertificateDigestsCompatible(installedCerts, candidateCerts)) {
                return new VerificationResult(
                        false,
                        true,
                        "Signing key mismatch: this APK was signed with a different certificate than the installed app. "
                                + "Android requires all updates to use the same signing key."
                );
            }
        } catch (Exception ignored) {
            // Fall back to system PackageInstaller enforcement if installed certs cannot be queried
        }

        return new VerificationResult(true, false, null);
    }

    @SuppressWarnings("deprecation")
    private static Set<String> extractSignatureDigests(PackageInfo info) {
        if (info == null) {
            return Collections.emptySet();
        }
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo != null) {
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            }
        }
        if ((signatures == null || signatures.length == 0) && info.signatures != null) {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            return Collections.emptySet();
        }
        Set<String> digests = new HashSet<>();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature sig : signatures) {
                if (sig == null) continue;
                byte[] hash = md.digest(sig.toByteArray());
                StringBuilder sb = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    sb.append(String.format(Locale.US, "%02x", b & 0xFF));
                }
                digests.add(sb.toString());
            }
        } catch (Exception e) {
            return Collections.emptySet();
        }
        return digests;
    }
}
