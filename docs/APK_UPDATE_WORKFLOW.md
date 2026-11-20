# Centralized GitHub Releases APK Update Workflow

- **Repository Owner**: `Manish-Studio`
- **Repository Name**: `My-new-controlled-ludo`
- **Default Branch**: `main`
- **Configured Manifest URL**:
  `https://raw.githubusercontent.com/Manish-Studio/My-new-controlled-ludo/main/updates/version-manifest.json`
- **Ludo App Package Name (`applicationId`)**: `com.aistudio.ludoking.kxpvrz`

This project uses the centralized HTTPS JSON manifest at `updates/version-manifest.json` to distribute APK updates via GitHub Releases for both **Ludo King** (`ludo_king`) and the separate **Ludo Controller** (`ludo_controller`) Android app without embedding any GitHub tokens, passwords, or signing credentials.

---

## 1. Current Repository & Signing Safety Status

- **Gradle Version & Signing Config (`app/build.gradle.kts`)**:
  - `applicationId`: `com.aistudio.ludoking.kxpvrz`
  - Current `versionCode`: `1`, `versionName`: `"1.0"`
  - `release` signing config expects `KEYSTORE_PATH` (defaulting to `${rootDir}/my-upload-key.jks`), `STORE_PASSWORD`, `keyAlias = "upload"`, and `KEY_PASSWORD`.
- **GitHub Actions Workflows (`.github/workflows/build-apk.yml` & `.github/workflows/publish-apk.yml`)**:
  - Both existing workflows currently build **debug APKs only** (`assembleDebug`) signed with temporary/debug keys (`debug.keystore` / `androiddebugkey`), **not** production-signed release APKs.
  - **Do NOT point `updates/version-manifest.json` at those debug-build GitHub Releases** for production updates. Temporary debug certificates are unstable and incompatible with release-signed installs.
- **No Update Marked Available Yet**:
  - `updates/version-manifest.json` currently keeps `latestVersionCode: 1` (matching installed `versionCode = 1`) and placeholder `apkUrl` / `sha256` fields until a verified, consistently signed release APK is published.

---

## 2. Fields to Fill After Publishing a Signed Release APK

In `updates/version-manifest.json`, update the `ludo_king` entry (and `ludo_controller` when releasing Controller) **only after** uploading a real signed release `.apk` to GitHub Releases:

1. `latestVersionName`: Matches `versionName` in `app/build.gradle.kts` (e.g. `"1.1.0"`).
2. `latestVersionCode`: Strictly greater integer than the previous release (`versionCode` in `app/build.gradle.kts`, e.g. `2`).
3. `minSupportedVersionCode`: Lowest `versionCode` allowed to continue without a forced update (e.g. `1` or `2`).
4. `apkUrl`: Exact HTTPS GitHub Release asset URL, e.g.:
   `https://github.com/Manish-Studio/My-new-controlled-ludo/releases/download/<RELEASE_TAG>/<SIGNED_RELEASE_APK>`
5. `sha256`: Exact 64-character lowercase hexadecimal SHA-256 checksum of that uploaded `.apk`.
6. `releaseNotes`: Human-readable changelog shown in `AppUpdateDialog`.
7. `releaseDate`: ISO date (`YYYY-MM-DD`).
8. `mandatory`: `true` if users must update immediately, otherwise `false` (note: any user with `installedVersionCode < minSupportedVersionCode` is automatically treated as mandatory).

> **Safety Enforcement**: `UpdateEvaluator` rejects any update if `apkUrl` is not valid `https://`, if `sha256` is not a valid 64-hex string, or if `packageName` does not match `com.aistudio.ludoking.kxpvrz`.

---

## 3. Release & Verification Steps Before Publishing

1. **Increment Version** in `app/build.gradle.kts` (`versionCode` and `versionName`).
2. **Build Signed Release APK** using your consistent upload keystore (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`, alias `upload`)—either locally or via a dedicated release CI workflow backed by encrypted GitHub Actions Secrets (never commit `.jks` or passwords to git).
3. **Verify Signing Certificate Match**:
   Confirm the new release APK's SHA-256 signing certificate fingerprint matches the currently installed app's signing certificate before distributing:
   ```bash
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   # Or:
   keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
   ```
   Compare the SHA-256 certificate digest against the existing installed APK (or `keytool -list -v -keystore <your-upload-keystore> -alias upload`). Android will reject any in-place update whose certificate differs.
4. **Compute APK SHA-256 Checksum**:
   - Linux / macOS:
     ```bash
     sha256sum app/build/outputs/apk/release/app-release.apk
     ```
   - Windows PowerShell:
     ```powershell
     Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
     ```
5. **Upload to GitHub Releases**:
   Create a release tag in `Manish-Studio/My-new-controlled-ludo`, attach the verified signed release `.apk`, and copy its direct `https://github.com/Manish-Studio/My-new-controlled-ludo/releases/download/.../*.apk` URL.
6. **Commit Updated `updates/version-manifest.json`** to `main`:
   Once pushed to `main`, Ludo King reads it from:
   `https://raw.githubusercontent.com/Manish-Studio/My-new-controlled-ludo/main/updates/version-manifest.json`.

---

## 4. Preserving Local User Data

Updating via Android's `FileProvider` package installer performs an in-place upgrade and preserves:
- `SharedPreferences` (`"LudoModUser"` including Player Cheating Selection unlock state, game-duration counters, stats, sound/music, and network preferences).

---

## 5. Integrating the Separate Ludo Controller App

Because the Ludo Controller app lives in its own codebase:
1. Reuse the `com.vinaykpro.ludoking.update` package (or matching JSON schema contract).
2. Add `REQUEST_INSTALL_PACKAGES` and a matching `FileProvider` (`${applicationId}.fileprovider` + `res/xml/update_file_paths.xml`) in Controller's `AndroidManifest.xml`.
3. Configure Controller's `apk_update_manifest_url` to the exact same URL:
   `https://raw.githubusercontent.com/Manish-Studio/My-new-controlled-ludo/main/updates/version-manifest.json`
4. Pass Controller's `BuildConfig.APPLICATION_ID` as `expectedPackageName` and `"ludo_controller"` as preferred `appId`, and replace `<CONTROLLER_PACKAGE_NAME>` in `updates/version-manifest.json` with Controller's real `applicationId`.
5. Sign all Controller releases with Controller's own consistent release keystore.
