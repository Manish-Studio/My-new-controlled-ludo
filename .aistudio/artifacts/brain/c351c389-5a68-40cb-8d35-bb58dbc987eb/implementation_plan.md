# Switch GitHub Account & Push Repository

Switching the active GitHub authorization in Google AI Studio from the old account to your new target GitHub account, resolving authentication mismatches and completing the repository push.

## User Review & Critical Decisions

> [!IMPORTANT]
> Because you are currently logged in to your **old GitHub account** in this browser, attempting to connect without signing out will automatically re-use the old account's session cookies and reproduce the permission error. You must switch the active session on `github.com` before authorizing in AI Studio.

- **Confirmed Decision**: Re-authenticate through AI Studio's push dialog.
- **Confirmed State**: Currently logged in to the old account in this browser.

---

## 1. Overview & Core Concept

- **What It Does**: Unbinds the old GitHub session from Google AI Studio and connects your new GitHub account with full repository creation and write privileges.
- **Key Value**: Enables clean, uninterrupted synchronization of the Android Ludo King project and activates automated GitHub Actions APK releases under your new GitHub profile.

---

## 2. Step-by-Step Account Switch Walkthrough

Follow these sequential steps to connect your new account:

```
┌────────────────────────────────┐
│  Step 1: Sign out of old       │
│  account at github.com         │
└───────────────┬────────────────┘
                ▼
┌────────────────────────────────┐
│  Step 2: Sign into new         │
│  account at github.com         │
└───────────────┬────────────────┘
                ▼
┌────────────────────────────────┐
│  Step 3: In AI Studio, open    │
│  "Push to GitHub" / Connect    │
└───────────────┬────────────────┘
                ▼
┌────────────────────────────────┐
│  Step 4: Authorize AI Studio   │
│  with "All Repositories"       │
└───────────────┬────────────────┘
                ▼
┌────────────────────────────────┐
│  Step 5: Create fresh repo     │
│  & click Push                  │
└────────────────────────────────┘
```

### Detailed Steps:

1. **Sign Out of Old Account on GitHub**:
   - Open a new tab and go to [https://github.com](https://github.com).
   - Click on your profile icon in the top-right corner and click **Sign out**.

2. **Sign In to Your New Target GitHub Account**:
   - In that same tab, click **Sign in** and log into the new GitHub account you want to connect.
   - Verify that your new profile username appears in the top-right corner.

3. **Revoke Old AI Studio Token (Optional but Recommended)**:
   - If you still have access to the old account or want to ensure clean state, visit [https://github.com/settings/applications](https://github.com/settings/applications) and ensure no stale tokens are cached.

4. **Trigger Connection in AI Studio**:
   - Switch back to your Google AI Studio tab.
   - Click **Push to GitHub** (or **GitHub Sync** from top-right / settings menu).
   - When the GitHub authorization popup opens, confirm that it displays your **NEW GitHub account** name.
   - Choose **"All repositories"** (or specify your repository access) and click **Authorize google-ai-studio**.

5. **Create & Push**:
   - Select **"Create New Repository"**.
   - Enter a repository name (e.g. `ludo-king-android`).
   - Click **Push / Publish**.

---

## 3. Key Decisions & Rationale

- **Active Session Priority**:
  - *Chosen Approach*: Standard browser session swap rather than personal access tokens.
  - *Why*: AI Studio uses direct OAuth; ensuring the active browser tab is logged into the desired account prevents token collision.
- **Pre-Optimized Codebase**:
  - *Chosen Approach*: All heavy binaries (77MB APK, 19MB GIF) have already been removed/compressed and ignored in `.gitignore`.
  - *Why*: Push payload is reduced to ~31MB of clean code and assets, ensuring the initial commit uploads quickly without timing out.

---

## 4. Technical Architecture & Sync Flow

```
┌─────────────────────────┐       OAuth 2.0 Flow        ┌─────────────────────────┐
│     Google AI Studio    │ ──────────────────────────> │    New GitHub Account   │
│  (Managed Web Session)  │ <────────────────────────── │  (Verified Permissions) │
└───────────┬─────────────┘                             └────────────┬────────────┘
            │                                                        │
            │ Push Clean Commit (31MB)                               │
            ▼                                                        ▼
┌─────────────────────────┐                             ┌─────────────────────────┐
│ Android Source Code     │ ──────────────────────────> │  New Target Repository  │
│ (.github/workflows inc) │                             │ (Triggers Build Actions)│
└─────────────────────────┘                             └─────────────────────────┘
```
