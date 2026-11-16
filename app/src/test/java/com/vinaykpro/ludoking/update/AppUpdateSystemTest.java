package com.vinaykpro.ludoking.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AppUpdateSystemTest {

    private static final String LUDO_PKG = "com.aistudio.ludoking.kxpvrz";
    private static final String CONTROLLER_PKG = "com.example.ludocontroller";
    private static final String VALID_SHA_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String VALID_SHA_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static String buildManifestJson(
            int latestCode,
            int minSupportedCode,
            boolean mandatory,
            String apkUrl,
            String sha256
    ) {
        return "{\n"
                + "  \"manifestVersion\": 1,\n"
                + "  \"generatedAt\": \"2026-06-01T12:00:00Z\",\n"
                + "  \"apps\": {\n"
                + "    \"ludo_king\": {\n"
                + "      \"appId\": \"ludo_king\",\n"
                + "      \"packageName\": \"" + LUDO_PKG + "\",\n"
                + "      \"displayName\": \"Ludo King\",\n"
                + "      \"latestVersionName\": \"2.0.0\",\n"
                + "      \"latestVersionCode\": " + latestCode + ",\n"
                + "      \"minSupportedVersionCode\": " + minSupportedCode + ",\n"
                + "      \"apkUrl\": \"" + apkUrl + "\",\n"
                + "      \"sha256\": \"" + sha256 + "\",\n"
                + "      \"releaseNotes\": \"Smooth token selection update.\",\n"
                + "      \"releaseDate\": \"2026-06-01\",\n"
                + "      \"mandatory\": " + mandatory + "\n"
                + "    },\n"
                + "    \"ludo_controller\": {\n"
                + "      \"appId\": \"ludo_controller\",\n"
                + "      \"packageName\": \"" + CONTROLLER_PKG + "\",\n"
                + "      \"displayName\": \"Ludo Controller\",\n"
                + "      \"latestVersionName\": \"3.1.0\",\n"
                + "      \"latestVersionCode\": 5,\n"
                + "      \"minSupportedVersionCode\": 2,\n"
                + "      \"apkUrl\": \"https://github.com/org/repo/releases/download/ctrl-v5/ctrl.apk\",\n"
                + "      \"sha256\": \"" + VALID_SHA_B + "\",\n"
                + "      \"releaseNotes\": \"Controller patch.\",\n"
                + "      \"releaseDate\": \"2026-06-01\",\n"
                + "      \"mandatory\": false\n"
                + "    }\n"
                + "  }\n"
                + "}";
    }

    @Test
    public void upToDate_returnsUpToDateWithoutPrompt() throws Exception {
        UpdateManifest manifest = UpdateManifestParser.parse(buildManifestJson(
                2, 1, false,
                "https://github.com/org/repo/releases/download/v2/ludo.apk",
                VALID_SHA_A
        ));
        UpdateEvaluator.EvaluationResult res = UpdateEvaluator.evaluate(
                manifest, LUDO_PKG, "ludo_king", 2, -1);

        assertEquals(UpdateEvaluator.Status.UP_TO_DATE, res.status);
        assertFalse(res.shouldShowAutoPrompt);
        assertFalse(res.shouldShowManualPrompt);
    }

    @Test
    public void optionalUpdate_promptsAndSupportsLaterSuppressionWithoutHidingManualCheck() throws Exception {
        UpdateManifest manifest = UpdateManifestParser.parse(buildManifestJson(
                3, 1, false,
                "https://github.com/org/repo/releases/download/v3/ludo.apk",
                VALID_SHA_A
        ));

        // First time: auto + manual prompts both true
        UpdateEvaluator.EvaluationResult first = UpdateEvaluator.evaluate(
                manifest, LUDO_PKG, "ludo_king", 1, -1);
        assertEquals(UpdateEvaluator.Status.OPTIONAL_UPDATE, first.status);
        assertTrue(first.shouldShowAutoPrompt);
        assertTrue(first.shouldShowManualPrompt);

        // After user taps Later (dismissedOptionalVersionCode == 3): auto suppressed, manual still shown
        UpdateEvaluator.EvaluationResult dismissed = UpdateEvaluator.evaluate(
                manifest, LUDO_PKG, "ludo_king", 1, 3);
        assertEquals(UpdateEvaluator.Status.OPTIONAL_UPDATE, dismissed.status);
        assertFalse(dismissed.shouldShowAutoPrompt);
        assertTrue(dismissed.shouldShowManualPrompt);
    }

    @Test
    public void belowMinSupportedVersion_becomesMandatoryEvenWhenMandatoryFlagFalse() throws Exception {
        UpdateManifest manifest = UpdateManifestParser.parse(buildManifestJson(
                5, 3, false,
                "https://github.com/org/repo/releases/download/v5/ludo.apk",
                VALID_SHA_A
        ));

        // Even if user previously dismissed version 5 when optional, below-min forces mandatory!
        UpdateEvaluator.EvaluationResult res = UpdateEvaluator.evaluate(
                manifest, LUDO_PKG, "ludo_king", 2, 5);
        assertEquals(UpdateEvaluator.Status.MANDATORY_UPDATE, res.status);
        assertTrue(res.belowMinSupportedVersion);
        assertTrue(res.shouldShowAutoPrompt);
        assertTrue(res.shouldShowManualPrompt);
    }

    @Test
    public void explicitMandatoryFlag_forcesMandatoryUpdate() throws Exception {
        UpdateManifest manifest = UpdateManifestParser.parse(buildManifestJson(
                2, 1, true,
                "https://github.com/org/repo/releases/download/v2/ludo.apk",
                VALID_SHA_A
        ));

        UpdateEvaluator.EvaluationResult res = UpdateEvaluator.evaluate(
                manifest, LUDO_PKG, "ludo_king", 1, 2);
        assertEquals(UpdateEvaluator.Status.MANDATORY_UPDATE, res.status);
        assertFalse(res.belowMinSupportedVersion);
        assertTrue(res.shouldShowAutoPrompt);
    }

    @Test(expected = UpdateManifestParser.ParseException.class)
    public void invalidJson_throwsParseException() throws Exception {
        UpdateManifestParser.parse("{ bad json");
    }

    @Test
    public void nonHttpsOrInvalidSha256_rejectedSafely() throws Exception {
        UpdateManifest httpManifest = UpdateManifestParser.parse(buildManifestJson(
                2, 1, false,
                "http://github.com/org/repo/releases/download/v2/ludo.apk",
                VALID_SHA_A
        ));
        UpdateEvaluator.EvaluationResult httpRes = UpdateEvaluator.evaluate(
                httpManifest, LUDO_PKG, "ludo_king", 1, -1);
        assertEquals(UpdateEvaluator.Status.INVALID_ENTRY, httpRes.status);
        assertFalse(httpRes.shouldShowAutoPrompt);
        assertFalse(httpRes.shouldShowManualPrompt);

        UpdateManifest badShaManifest = UpdateManifestParser.parse(buildManifestJson(
                2, 1, false,
                "https://github.com/org/repo/releases/download/v2/ludo.apk",
                "not-a-valid-sha256"
        ));
        UpdateEvaluator.EvaluationResult shaRes = UpdateEvaluator.evaluate(
                badShaManifest, LUDO_PKG, "ludo_king", 1, -1);
        assertEquals(UpdateEvaluator.Status.INVALID_ENTRY, shaRes.status);
    }

    @Test
    public void wrongPackageName_neverMatchesOtherAppEntry() throws Exception {
        UpdateManifest manifest = UpdateManifestParser.parse(buildManifestJson(
                3, 1, false,
                "https://github.com/org/repo/releases/download/v3/ludo.apk",
                VALID_SHA_A
        ));

        // Searching with an unknown package even passing preferredAppId "ludo_king" must return null!
        assertNull(manifest.findMatchingApp("com.wrong.package", "ludo_king"));
        UpdateEvaluator.EvaluationResult res = UpdateEvaluator.evaluate(
                manifest, "com.wrong.package", "ludo_king", 1, -1);
        assertEquals(UpdateEvaluator.Status.INVALID_ENTRY, res.status);

        // Controller resolves strictly to controller entry and never Ludo
        UpdateManifest.AppReleaseInfo ctrl = manifest.findMatchingApp(CONTROLLER_PKG, "ludo_controller");
        assertNotNull(ctrl);
        assertEquals(CONTROLLER_PKG, ctrl.packageName);
        assertEquals(5, ctrl.latestVersionCode);
    }

    @Test
    public void sha256FileVerification_acceptsExactMatchAndRejectsTamperedFile() throws Exception {
        File sampleApk = tempFolder.newFile("sample-release.apk");
        byte[] content = "simulated-apk-binary-payload-v1".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(sampleApk)) {
            fos.write(content);
        }

        String computed = Sha256Verifier.computeSha256(sampleApk);
        assertTrue(Sha256Verifier.isValidSha256Hex(computed));
        assertTrue(Sha256Verifier.verifyFileSha256(sampleApk, computed));
        assertFalse(Sha256Verifier.verifyFileSha256(sampleApk, VALID_SHA_A));
    }

    @Test
    public void placeholderManifestUrl_rejectedFromAutoAndManualFetch() {
        assertFalse(UpdateManifestParser.isConfiguredManifestUrl("https://raw.githubusercontent.com/<OWNER>/<REPO>/main/updates/version-manifest.json"));
        assertFalse(UpdateManifestParser.isConfiguredManifestUrl(""));
        assertFalse(UpdateManifestParser.isConfiguredManifestUrl("http://example.com/manifest.json"));
        assertTrue(UpdateManifestParser.isConfiguredManifestUrl("https://raw.githubusercontent.com/acme/ludo/main/updates/version-manifest.json"));
    }

    @Test
    public void signatureCompatibility_andSharedPreferencesIsolation() {
        Set<String> installed = new HashSet<>(Collections.singletonList(VALID_SHA_A));
        Set<String> sameKey = new HashSet<>(Collections.singletonList(VALID_SHA_A));
        Set<String> wrongKey = new HashSet<>(Collections.singletonList(VALID_SHA_B));

        assertTrue(ApkInstallVerifier.areCertificateDigestsCompatible(installed, sameKey));
        assertFalse(ApkInstallVerifier.areCertificateDigestsCompatible(installed, wrongKey));

        // Updater prefs namespace must never touch gameplay/cheating LudoModUser preferences
        assertNotEquals("LudoModUser", UpdateStateStore.Prefs.PREFS_NAME);

        UpdateStateStore.InMemory store = new UpdateStateStore.InMemory();
        long t0 = 1_000_000L;
        assertTrue(store.shouldRunAutomaticCheck(t0, UpdateStateStore.DEFAULT_CHECK_INTERVAL_MS));
        store.setLastCheckTimestampMs(t0);
        assertFalse(store.shouldRunAutomaticCheck(t0 + 60_000L, UpdateStateStore.DEFAULT_CHECK_INTERVAL_MS));
        assertTrue(store.shouldRunAutomaticCheck(
                t0 + UpdateStateStore.DEFAULT_CHECK_INTERVAL_MS + 1L,
                UpdateStateStore.DEFAULT_CHECK_INTERVAL_MS
        ));
    }
}
