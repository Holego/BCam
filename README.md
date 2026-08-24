# Background Camera

A native Android video recorder that, once set up, starts and stops **without opening the
app** — from a home-screen widget or the Stop button in the notification. It keeps
recording with the screen off and does nothing else.

Kotlin + CameraX + Foreground Service + Storage Access Framework. No Compose, no WebView,
no Flutter, no React Native, no heavy third-party libraries. 

---

## Building

```bash
gradlew assembleDebug     # installable, debug-signed, debuggable
gradlew assembleRelease   # unsigned unless a keystore is configured
```

The APK declares no `INTERNET` permission.

### Signing a release build

Create a keystore once, and keep it **outside** the repository:

```bash
keytool -genkey -v -keystore bcam-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias bcam
```

Then point `local.properties` at it — that file is git-ignored, so no key material
enters the repository:

```properties
RELEASE_STORE_FILE=C:/path/outside/the/repo/bcam-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=bcam
RELEASE_KEY_PASSWORD=...
```

The same four values are also read from the environment, which is what CI should use.
With none of them set, `assembleRelease` still succeeds and simply produces an unsigned
APK, so cloning and building the project never requires a key.

Keep this keystore safe and backed up: Android identifies an app by package name **and**
signing key, so losing it means no future version can update an installed one.

---

## Build requirements

JDK 17 (AGP 8.5 does not run on JDK 25) and Android SDK Platform 34.

> Keep `!` out of the project path. Kotlin's compiler treats `!/` as the JAR-entry
> separator and fails on the generated `R.jar`. Android Studio behaves the same way.

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| CameraX | 1.3.4 |

---

## Layout

```
app/src/main/java/com/example/videorecorder/
├─ MainActivity.kt                     setup screen and manual start/stop
├─ AboutActivity.kt                    what the app is, privacy, contact links
├─ recording/
│   ├─ RecordingService.kt             owns the camera, the recording, the file, the state
│   ├─ RecordingNotifications.kt       ongoing notification + Stop action
│   └─ RecordingState.kt               state machine and shared state holder
├─ camera/
│   ├─ CameraRepository.kt             camera enumeration, selector, qualities, frame rates
│   └─ CameraInfoModel.kt              camera model, quality and compression options
├─ storage/
│   └─ VideoStorageManager.kt          SAF folder, .mp4 creation, descriptors, free space
├─ settings/
│   └─ SettingsRepository.kt           SharedPreferences
└─ widget/
    └─ RecordingWidgetProvider.kt      home-screen button
```

All active recording lives in `RecordingService`. `MainActivity` only configures and sends
commands — closing it, or Android destroying it, does not affect a running recording.

---

## Why the widget can start the camera with the app closed

Android imposes two separate restrictions:

1. **Android 12+** forbids starting a foreground service from the background.
2. **Android 11+** additionally denies camera and microphone access to a service that was
   started from the background ("while-in-use" permission restrictions).

Interacting with a widget is a documented exemption from **both**:

> *"The user performs an action on a UI element related to your app. For example, they
> might interact with a bubble, notification, widget, or activity."*
> *"The service starts by interacting with app widgets."*

So the app needs **no** `SYSTEM_ALERT_WINDOW`, no invisible trampoline Activity, and no
battery-optimisation opt-out. To keep the exemption intact, the widget's `PendingIntent`
starts the service directly via `PendingIntent.getForegroundService()` — no intermediate
`BroadcastReceiver`.

The tap sends a single `TOGGLE` action; the service decides start-vs-stop from its own
state, so a stale widget rendering can never send the wrong command.

---

## Recording state

`IDLE → STARTING → RECORDING → STOPPING → IDLE`, plus `ERROR`.

Every transition goes through `RecordingStateHolder` on the main thread, which rules out
double starts, two camera instances, two `Recording` objects, a double `stop()`, and two
files being written at once. Pressing Stop during `STARTING` is remembered and applied as
soon as the `Start` event arrives. A 15-second watchdog fails cleanly if the camera never
opens (for example when another app is holding it).

A file counts as recorded **only** after `VideoRecordEvent.Finalize`. Empty files and
`ERROR_NO_VALID_DATA` results are deleted automatically.

---

## Recording with the screen locked

A `PARTIAL_WAKE_LOCK` is held while a recording is active.

Why: with the screen off many devices suspend the application processor. The camera and
encoder hold their own kernel wake locks, but the app-side code feeding them does not, and
on a number of devices that is enough to truncate or corrupt a long screen-off recording.

It is partial only: it never turns the screen on, never keeps it on, and does not stop the
device from locking. It is taken on `Start` and released on `Finalize`, so it cannot
outlive a recording, with a hard 4-hour cap on top. Set `USE_WAKE_LOCK = false` in
`RecordingService` to measure a device without it.

The app does not ask you to disable battery optimisation.

### Required test

1. Start recording, wait 10 seconds.
2. Lock the phone, leave the screen off for 2–5 minutes.
3. Unlock, stop the recording (app, widget or notification).
4. Open the resulting MP4 in a normal player.
5. Check that the duration covers the screen-off period, that audio did not drop out, and
   that the file is properly finalized.

---

## Settings

| Setting | Notes |
|---|---|
| Video folder | SAF directory, persisted across reboots |
| Camera | every lens CameraX can bind (see limitations) |
| Video quality | only resolutions the selected camera reports; default 1080p |
| File size | Standard / Light (~40% smaller) / Smallest (~65% smaller) |
| Frame rate | Auto / 30 / 60, only if the camera advertises it |
| Record audio | on by default; `RECORD_AUDIO` requested only when you enable it |

Files are named `VID_yyyy-MM-dd_HH-mm-ss.mp4`.

---

## Limitations, stated honestly

### File size and formats

CameraX 1.3 records MP4/H.264 and exposes no API to choose a container or a codec. The
only real lever on file size — short of lowering resolution or frame rate — is the target
encoding bitrate, which is what the **File size** setting drives via
`Recorder.Builder.setTargetVideoEncodingBitRate()`. `Standard` deliberately sets nothing
and leaves the device's own tuned bitrate in place.

If you want genuinely smaller files at the same resolution, the next step is HEVC/H.265
(roughly half the size of H.264). That needs the recording pipeline rewritten on
`MediaCodec` + `MediaMuxer` directly instead of CameraX, which is a large change and a lot
more device-specific fragility. It is not a setting that can simply be switched on.

### Physical lenses (ultrawide / telephoto)

CameraX can only bind cameras returned by `getAvailableCameraInfos()`. On many phones the
OEM hides the ultrawide and telephoto modules inside a single logical camera, where they
are reachable only as `CameraCharacteristics.getPhysicalCameraIds()` — and CameraX has no
supported way to bind a physical sub-camera on its own.

When the OEM publishes the lenses as separate camera ids (many devices do), they appear in
the list individually as *Back Main*, *Back Ultrawide*, *Back Telephoto*. When it does
not, you get only the logical back and front cameras.

**On downloading other tools for this: there are none that solve it.** No library can
unlock a lens the vendor has not exposed. The only real alternative is dropping CameraX
and writing the capture pipeline against Camera2 directly, where
`OutputConfiguration.setPhysicalCameraId()` (Android 9+) can target a physical sensor of a
logical multi-camera. That means reimplementing session management, encoding and rotation
by hand, and it still only works on devices whose logical camera actually advertises those
physical ids. This app deliberately does not use private or vendor APIs.

### Frame rate

`VideoCapture.Builder.setTargetFrameRate()` is a request, not a guarantee. Devices clamp
it in low light or when the chosen quality has no matching camcorder profile. Only rates
the camera advertises are offered, and the setting defaults to Auto.

---

## Privacy

The app collects, stores and transmits nothing. No account, no analytics, no crash
reporting, no telemetry, and no `INTERNET` permission at all. Videos are written straight
into the folder you choose and never leave the device. Settings live in the app's private
storage.

| Permission | Why |
|---|---|
| `CAMERA` | recording video |
| `RECORD_AUDIO` | requested only when you turn sound on |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` | the recording service |
| `FOREGROUND_SERVICE_MICROPHONE` | declared only when audio is actually captured |
| `POST_NOTIFICATIONS` | Android 13+, for the recording indicator |
| `WAKE_LOCK` | see the screen-locked section |

No `WRITE_EXTERNAL_STORAGE`: everything goes through the SAF folder.

---

## Logs

Tags: `RecorderApp`, `RecordingService`, `CameraManager`, `Widget`.

```bash
adb logcat -s RecorderApp RecordingService CameraManager Widget
```

Logged: camera list and selection, camera id, chosen quality / bitrate / frame rate,
initialisation, output file URI, start, stop, `Finalize`, and every error.
`VideoRecordEvent.Status` is deliberately not logged so it cannot flood logcat.
