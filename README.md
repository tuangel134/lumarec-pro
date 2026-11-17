<div align="center">

# LumaRec Pro

**A native Android video recorder with a locally encrypted vault.**

Camera2 + MediaRecorder · foreground recording · AES-256-GCM storage · no network permission

<br>

<a href="https://github.com/tuangel134/lumarec-pro/releases/latest/download/lumarec-pro-1.4.0.apk">
  <img src="https://img.shields.io/badge/%E2%AC%87%EF%B8%8F_DESCARGAR_APK-1.4.0-E11D2F?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="44">
</a>

<sub>122 KB · Android 11+ · signed · <a href="https://github.com/tuangel134/lumarec-pro/releases/latest">all releases</a></sub>

<br><br>

![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![API](https://img.shields.io/badge/API-30%20%E2%86%92%2036-1F6FEB?style=flat-square)
![Java](https://img.shields.io/badge/Java-17-E76F00?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-2EA043?style=flat-square)
![No internet](https://img.shields.io/badge/INTERNET-no%20permission-8957E5?style=flat-square)

</div>

---

## 📲 Install

**On the phone:** download the APK with the button above and open it. Android will ask you to allow installs from your browser or file manager.

**With ADB:**

```bash
adb install -r lumarec-pro-1.4.0.apk
```

<div align="center"><sub>

Requires Android 11 or newer. The published APK is signed with a private key that is **not** in this repository — a build you sign yourself will not update it in place, so uninstall the existing copy first.

</sub></div>

## ✨ Features

<table>
<tr><td width="50%" valign="top">

### 🎥 Recording

- Every camera, resolution and frame rate Camera2 advertises, including a 4K/60 attempt with automatic HEVC retry
- Live zoom, exposure compensation, white balance, focus modes, LED torch
- Exposure and white-balance locks that reset per session
- Orientation override, custom bitrate, AAC 64–256 kbps
- Timer, duration and size limits, estimated MB/min
- Reusable profiles for camera, quality, audio and limits

</td><td width="50%" valign="top">

### 🔒 Vault

- Chunked **AES-256-GCM** encryption — seek and play without decrypting to disk
- **RSA-OAEP/SHA-256** quick-seal: record while the vault stays locked
- Key wrapped with **PBKDF2** + a non-exportable **Android Keystore** key
- Unlock with a password or a four-tap pattern (256 combinations, attempt lockout)
- Files hidden from the gallery; export creates a plain MP4 only after confirmation
- Screenshots and overlays blocked on vault screens

</td></tr>
<tr><td width="50%" valign="top">

### 🎬 Background use

- Foreground service (`camera|microphone`) — keeps recording with the screen off
- Three notification styles: full controls, stop-only, or a compact line
- Three widgets: 1×1 camera, status/controls panel, transparent 1×1 icon
- Quick Settings tile to start and stop

</td><td width="50%" valign="top">

### 📚 Library & player

- Search, sorting, favourites, custom names, details, multi-select delete
- Export folder via the Storage Access Framework, with path-safe filenames
- Private player with 0.5×–2× speed and 15-second skips
- No ads, no analytics, no `INTERNET` permission

</td></tr>
</table>

> Actual 4K/60, manual focus and similar extras depend on the device's camera HAL. Failed live requests are rolled back to the settings already recording. There is **no password recovery**: uninstalling the app or clearing its data destroys the vault and its keys.

## 🛠 Build

| | |
|---|---|
| JDK | 17 |
| Android SDK | Platform 36, Build-Tools 36.x |
| Gradle | 8.13 (via wrapper) |
| Android Gradle Plugin | 8.10.1 |

```bash
git clone https://github.com/tuangel134/lumarec-pro.git
cd lumarec-pro

# Point Gradle at your SDK (or export ANDROID_HOME)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # unsigned release APK
```

The release build is intentionally unsigned: signing material is never committed. To produce an installable release, add your own keystore and a `signingConfigs` block to `app/build.gradle`.

## 📁 Project structure

```
app/
  build.gradle                     module config (namespace, SDK levels, signing)
  src/main/AndroidManifest.xml     permissions, activities, service, tile, widgets, provider
  src/main/java/com/lumarec/pro/
    MainActivity.java              camera UI, settings, library, profiles
    RecordingService.java          foreground service, MediaRecorder lifecycle
    CameraCatalog.java             Camera2 capability discovery
    CameraControls.java            live capture-request updates
    RecordingPresets.java          quality/audio/orientation presets
    SecureVault.java               AES-GCM chunked container, key management
    VaultFile.java                 on-disk vault format
    QuickSeal.java                 RSA-OAEP quick-seal capture
    QuickVault.java                vault state shared with widgets and the service
    VideoLibrary.java              listing, search, ordering, deletion
    ExportFolder.java              SAF export and filename validation
    PrivatePlayerActivity.java     encrypted-file player
    LocalMediaProvider.java        short-lived, read-only share URIs
    QuickRecordActivity.java       transparent launch activity for widgets/tile
    QuickRecordWidget.java         1×1 transparent widget
    CameraRecordWidget.java        1×1 camera widget
    RecordingPanelWidget.java      status/controls panel widget
    RecordTile.java                Quick Settings tile
    RecordRequest.java             intent contract between UI and service
    KnockCodeView.java             four-tap unlock grid
    VideoNames.java / VideoNotes.java / PrivacyUi.java / LumaRecApp.java
  src/main/res/                    layouts, drawables, strings, styles, widget metadata
tests/                             standalone JVM checks (see below)
apk/                               signed reference build
```

## 🔐 Permissions

| Permission | Why |
|---|---|
| `CAMERA` | Capture through Camera2 |
| `RECORD_AUDIO` | Audio track (microphone is an optional feature) |
| `POST_NOTIFICATIONS` | Recording notification and its controls |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | Keep recording with the screen off |
| `WAKE_LOCK` | Keep the CPU alive while recording |
| `HIDE_OVERLAY_WINDOWS` | Block tapjacking over vault screens |

There is no `INTERNET` permission. The `LocalMediaProvider` is not exported and hands out read-only URIs that expire after 15 minutes.

## 🧪 Tests

`tests/` holds self-contained JVM classes with a `main` method — no JUnit runner needed. They exercise the vault container, quick-seal capture and export naming against the compiled app classes:

```bash
./gradlew assembleDebug

CP="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$ANDROID_HOME/platforms/android-36/android.jar"
javac -cp "$CP" -d /tmp/lumarec-tests tests/com/lumarec/pro/*.java

java -cp "/tmp/lumarec-tests:$CP" com.lumarec.pro.QuickSealTest    # 22 checks
java -cp "/tmp/lumarec-tests:$CP" com.lumarec.pro.VaultFileTest    # 788 checks
java -cp "/tmp/lumarec-tests:$CP" com.lumarec.pro.VideoNamesTest   # 153 checks
```

`tests/android/WidgetSmokeInstrumentation.java` is an instrumentation class; it needs a connected device and is not part of the APK.

## 📄 License

[MIT](LICENSE) © 2026 tuangel134 — free to use, modify, merge, publish, distribute, sublicense and sell, provided the copyright notice and permission notice are kept. The software is provided without warranty.
