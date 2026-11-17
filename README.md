# LumaRec Pro

A native Android video recorder built on **Camera2** and **MediaRecorder**, with a foreground recording service, an encrypted local vault, a private player and home-screen widgets. Everything stays on the device: the app has no `INTERNET` permission, no ads and no analytics SDKs.

`com.lumarec.pro` · version 1.4.0 · minSdk 30 (Android 11) · targetSdk 36 (Android 16) · Java 17

---

## Features

**Recording**

- Every camera, resolution and frame rate the device advertises through Camera2, including a 4K/60 attempt with an automatic HEVC retry on capable hardware.
- Live zoom and exposure compensation, white balance, continuous/infinity focus, LED torch during capture, and exposure/white-balance locks that reset per session.
- Orientation override (sensor, portrait, landscape, reversed), automatic or custom bitrate, AAC at 64–256 kbps, and microphone / voice-optimised / unprocessed audio sources when the platform reports support.
- Timers (1–30 s), optional duration and size limits, and an estimated MB/min figure.
- Reusable profiles that store camera, quality, audio, controls, orientation, naming and limits — never vault keys.

**Background recording**

- A foreground service of type `camera|microphone`, so recording continues with the screen locked.
- Three notification styles: full controls, stop-only status, or a compact “LumaRec” line.
- Three independent widgets: 1×1 camera, a status/controls panel, and a transparent 1×1 icon with a configurable image and internal name.
- A Quick Settings tile that starts and stops recording.

**Vault**

- Local encrypted storage using chunked **AES-256-GCM**, so videos can be seeked and played without exporting a decrypted copy.
- **RSA-OAEP/SHA-256** quick-seal: a public capture key lets new recordings be sealed while the vault is locked; opening, sharing or exporting still requires unlocking.
- Vault key protected with **PBKDF2** combined with a non-exportable **Android Keystore** HMAC key.
- Unlock with a written password or a four-tap pattern (256 combinations, with attempt lockout).
- Encrypted files are not visible to the gallery or other apps; sharing or exporting creates a plain MP4 only after an explicit confirmation and keeps the encrypted original.
- Vault windows block screenshots and overlays; vault data is excluded from backup and device transfer.

**Library and player**

- Search, sorting, favourites, custom internal names, details and multi-select delete with confirmation.
- Export folder chosen through the Storage Access Framework, with validated, path-safe filenames.
- Private player with 0.5×–2× speed and 15-second skips that reads the encrypted file directly.

> Actual 4K/60, manual focus and similar extras depend on the device's camera HAL. Failed live requests are rolled back to the settings that are already recording. There is no password or pattern recovery: uninstalling the app or clearing its data destroys the vault and its keys.

## Requirements

| | |
|---|---|
| JDK | 17 |
| Android SDK | Platform 36, Build-Tools 36.x |
| Gradle | 8.13 (via wrapper) |
| Android Gradle Plugin | 8.10.1 |

## Build

```bash
git clone https://github.com/tuangel134/lumarec-pro.git
cd lumarec-pro

# Point Gradle at your SDK (or export ANDROID_HOME)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # unsigned release APK
```

The release build is intentionally unsigned: signing material is never committed. To produce an installable release, add your own keystore and a `signingConfigs` block to `app/build.gradle`.

## Install the included APK

```bash
adb install -r apk/lumarec-pro-1.4.0.apk
```

`apk/lumarec-pro-1.4.0.apk` is the signed 1.4.0 build. It is signed with a private key that is **not** part of this repository, so an APK you build and sign yourself will not update it in place unless it uses the same key — uninstall the existing copy first, or sign with your own key from the start.

## Project structure

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

## Permissions

| Permission | Why |
|---|---|
| `CAMERA` | Capture through Camera2 |
| `RECORD_AUDIO` | Audio track (microphone is an optional feature) |
| `POST_NOTIFICATIONS` | Recording notification and its controls |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | Keep recording with the screen off |
| `WAKE_LOCK` | Keep the CPU alive while recording |
| `HIDE_OVERLAY_WINDOWS` | Block tapjacking over vault screens |

There is no `INTERNET` permission. The `LocalMediaProvider` is not exported and hands out read-only URIs that expire after 15 minutes.

## Tests

`tests/` holds self-contained JVM classes with a `main` method — no JUnit runner needed. They exercise the vault container, quick-seal capture and export naming against the compiled app classes. Build once, then compile and run them against that output:

```bash
./gradlew assembleDebug

CP="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$ANDROID_HOME/platforms/android-36/android.jar"
javac -cp "$CP" -d /tmp/lumarec-tests tests/com/lumarec/pro/*.java

java -cp "/tmp/lumarec-tests:$CP" com.lumarec.pro.QuickSealTest    # 22 checks
java -cp "/tmp/lumarec-tests:$CP" com.lumarec.pro.VaultFileTest    # 788 checks
java -cp "/tmp/lumarec-tests:$CP" com.lumarec.pro.VideoNamesTest   # 153 checks
```

`tests/android/WidgetSmokeInstrumentation.java` is an instrumentation class; it needs a connected device and is not part of the APK.

## License

This repository ships no license file, so no rights are granted by default. If you want others to reuse and redistribute the code, add a license of your choice (for example MIT or Apache-2.0) before publishing.
