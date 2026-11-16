# Centralized GitHub Releases APK Update Workflow

This project uses a single public HTTPS JSON manifest (`updates/version-manifest.json`) to distribute APK updates via GitHub Releases for both **Ludo King** (`ludo_king`) and the separate **Ludo Controller** (`ludo_controller`) Android app without embedding any GitHub tokens, secrets, or credentials.

---

## 1. Centralized Manifest Format (`updates/version-manifest.json`)

```json
{
  "manifestVersion": 1,
  "generatedAt": "2026-01-01T00:00:00Z",
  "apps": {
    "ludo_king": {
      "appId": "ludo_king",
      "packageName": "com.aistudio.ludoking.kxpvrz",
      "displayName": "Ludo King",
      "latestVersionName": "1.1.0",
      "latestVersionCode": 2,
      "minSupportedVersionCode": 1,
      "apkUrl": "https://github.com/<OWNER>/<REPO>/releases/download/ludo-v1.1.0/ludo-king-v1.1.0-release.apk",
      "sha256": "<64-HEX-CHAR-SHA256>",
      "releaseNotes": "Bug fixes and improvements.",
      "releaseDate": "2026-02-01",
      "mandatory": false
    },
    "ludo_controller": {
      "appId": "ludo_controller",
      "packageName": "<CONTROLLER_PACKAGE_NAME>",
      "displayName": "Ludo Controller",
      "latestVersionName": "1.1.0",
      "latestVersionCode": 2,
      "minSupportedVersionCode": 1,
      "apkUrl": "https://github.com/<OWNER>/<REPO>/releases/download/controller-v1.1.0/ludo-controller-v1.1.0-release.apk",
      "sha256": "<64-HEX-CHAR-SHA256>",
      "releaseNotes": "Controller improvements.",
      "releaseDate": "2026-02-01",
      "mandatory": false
    }
  }
}
```

### Key Rules
- `apkUrl` **must** use `https://`. Non-HTTPS URLs are rejected.
- `packageName` **must** match the app's `applicationId` (`com.aistudio.ludoking.kxpvrz` for this Ludo app). The updater refuses any entry whose `packageName` does not match the installed app, preventing cross-app installation.
- `latestVersionCode` is compared monotonically (`latestVersionCode > BuildConfig.VERSION_CODE`).
- An update becomes **Mandatory** whenever `mandatory == true` OR `installedVersionCode < minSupportedVersionCode`.
- `sha256` must be the exact 64-character hexadecimal SHA-256 checksum of the release `.apk` file. Mismatched downloads are rejected and deleted automatically before installation.

---

## 2. Hosting & Configuring the Manifest URL

1. Host `updates/version-manifest.json` on a public HTTPS endpoint such as:
   - Raw GitHub URL: `https://raw.githubusercontent.com/<OWNER>/<REPO>/main/updates/version-manifest.json`
   - Or GitHub Pages / a stable latest-release manifest URL.
2. Open `app/src/main/res/values/strings.xml` and set `apk_update_manifest_url` to your public HTTPS manifest URL:
   ```xml
   <string name="apk_update_manifest_url">https://raw.githubusercontent.com/&lt;OWNER&gt;/&lt;REPO&gt;/main/updates/version-manifest.json</string>
   ```

---

## 3. Releasing a New Version

1. **Increment version** in `app/build.gradle.kts`:
   - Increase `versionCode` (e.g., `1` -> `2`)
   - Update `versionName` (e.g., `"1.0"` -> `"1.1.0"`)
2. **Build signed release APK** using the **same upload/release keystore** (`KEYSTORE_PATH` / `upload` alias).
3. **Compute APK SHA-256 checksum**:
   - Linux / macOS:
     ```bash
     sha256sum app/build/outputs/apk/release/app-release.apk
     ```
   - Windows PowerShell:
     ```powershell
     Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
     ```
4. **Create a GitHub Release** and upload the signed `.apk` asset. Copy its direct `https://github.com/.../releases/download/.../*.apk` asset URL.
5. **Update `updates/version-manifest.json`** (`latestVersionName`, `latestVersionCode`, `minSupportedVersionCode`, `apkUrl`, `sha256`, `releaseNotes`, `releaseDate`, `mandatory`) and push to your repository branch.

---

## 4. Signing Key Preservation (Critical)

Android enforces **identical signing certificates** for in-place APK upgrades:
- Always sign every released APK with the exact same keystore (`KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD`, or consistent debug keystore during local QA).
- Installing a release-signed APK over a debug-signed APK (or vice versa) will fail in the Android Package Installer.
- Keep keystore files and passwords **outside** the repository and never embed them in `version-manifest.json` or source code.

---

## 5. Preserving Local User Data

Updating via Android's standard `FileProvider` package installer performs an in-place upgrade and preserves:
- `SharedPreferences` (`"LudoModUser"` including Player Cheating Selection unlock state, game-duration counters, stats, sound/music, and network preferences).

---

## 6. Integrating the Separate Ludo Controller App

Because the Ludo Controller app lives in its own repository/project:
1. Copy the `com.vinaykpro.ludoking.update` package (or keep its schema contract identical).
2. Add `REQUEST_INSTALL_PACKAGES` and a matching `FileProvider` (`${applicationId}.fileprovider` + `res/xml/update_file_paths.xml`) in Controller's `AndroidManifest.xml`.
3. Pass Controller's `BuildConfig.APPLICATION_ID` as `expectedPackageName` and `"ludo_controller"` as preferred `appId`.
4. Point Controller to the **same** `apk_update_manifest_url` — the updater automatically resolves only the `ludo_controller` entry matching Controller's `packageName`.
5. Keep Controller's own signing key consistent across all Controller GitHub Releases.
