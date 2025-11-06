# VideoMover

Move/copy videos from your device to a user-selected destination via **SAF**.  
Instant language switch (EN default, RU), auto-detects `DCIM/Camera`, optional delete-after-copy.

## ✨ Features
- 📂 Copy videos to a chosen folder (SAF) with persistable access
- 🗂 Auto-detection of `DCIM/Camera` + manual source selection
- 🌍 Localization: English (default) + Russian; language switches instantly in Settings
- 🔔 Copy progress & notifications
- 🧹 Optional delete-after-copy

## ✅ Requirements
- Android **minSdk**: 26 (Android 8.0, Oreo)
- Ensure enough free space at the destination

## 🔧 Installation
- Download **`app-release.apk`** from **Releases** and install on device  
  (enable installation from unknown sources).
- On first run, select the **Destination folder** via the system SAF dialog.

## ⚙️ Settings
- **Language** — instant switch & persisted choice
- **Destination** — select target folder (SAF), persistable access
- **Detect Source** — attempts to find `DCIM/Camera` automatically
- **Pick from List** — candidate paths under `DCIM/*`
- **Pick by Video** — derive `RELATIVE_PATH` from a chosen video
- **RELATIVE_PATH** — manual input if not using `DCIM/Camera`
- **Delete After Copy** — removes the original upon successful copy

## 🔒 Permissions & Safety
- Uses **Storage Access Framework** (no broad disk access)
- Explicit read/write & persistable permissions
- Clear error/status messages to the user

## 🧠 Localization
- All user-visible strings are in `res/values` and `res/values-ru`
- English is the default (base `values`)
- Language switch applied immediately in Settings

## 🏗 Build
- Open the project in Android Studio.
- **Debug:** Run as usual.
- **Release APK:** `Build → Generate Signed Bundle / APK → APK → release`
  - Create/choose your keystore (JKS) and keep it safe (not in the repo).

### Versioning
- `versionCode` increments per release
- `versionName` uses semantic style (e.g., `1.0.0`)

## 🧪 Test Scenarios
- Copy to an empty destination
- Delete-after-copy ON/OFF
- Not enough free space at destination
- Source via `DCIM/Camera` and custom paths
- App restart with persistable access
- On-the-fly language change (Settings → Language)

## 🧹 Resource Health
- Unused string keys removed
- No hardcoded RU strings in Java
- PRs for i18n (new locales) welcome

## 🤝 Contributing / Issues
- Describe the issue and reproduction steps
- Feature ideas and small mockups are welcome

