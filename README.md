# Boxblox

Boxblox runs an unmodified official Roblox client on Android inside its own process. You import a Roblox build, Boxblox checks it, and then you can launch it from the app.

It doesn’t patch, repackage, or redistribute Roblox. Archives are imported as copies (from files or from the Roblox app already installed on your device) and are only used if they pass Boxblox’s package and signing checks.

## What it does

- **Runs imported Roblox builds in-process.** Boxblox loads the archive’s classes and resources, hosts the guest activity through Android instrumentation/context shims, and maps the guest’s native engine from the unpacked build.
- **Manages builds.** You can keep multiple imported builds, choose which one to launch, and remove ones you no longer need. Each build keeps its own unpacked data; app-level sign-in/data is shared separately.
- **Session modes.** `Mobile` stays stock. `Desktop` and `Touch` present a more desktop-like session by answering the platform feature checks the client uses for identity, with additional desktop-identity adjustments where the client reads them. `Touch` keeps the touchscreen reported as present.
- **Mouse support.** The wrapper works with the client’s own pointer-capture and input paths, following the engine’s intent where available. The native mouse enhancement is checked against a reference build at runtime; if the engine layout doesn’t match, it falls back instead of using bad offsets.
- **Fast flag overrides.** The UI includes a few wrapper controls and a curated set of allowlisted Roblox flags. Enabled overrides are written for the selected build and passed to the guest’s preload path when available. Writing or preloading a flag doesn’t guarantee the client will honor it.
- **Extras.** Optional server-location toast (from the guest’s join logs), an optional asset overlay from `files/overlay/` exposed through the Files app, and diagnostics in `files/diag.txt`.

## Compatibility

- Native mouse hooks are written against reference build **2.745.1142** (`RobloxStore.HOOKED_VERSION`). Other builds can be imported, but launching a different version shows a warning and mouse support may be limited or unavailable.
- Passing Boxblox’s import checks means the archive set was accepted by the wrapper. It doesn’t guarantee the client will run correctly or that Roblox will accept it for sign-in or joining.
- The current device workflow is **arm64-v8a**.
- See [docs/apk-compatibility.md](docs/apk-compatibility.md) for import, update, rollback, and maintainer details.

## Build

```bash
cd wrapper
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDebug
```

You’ll need JDK 21, Android SDK platform 36, NDK 29.0.14206865, and CMake 3.22.1. The debug APK is written to `wrapper/app/build/outputs/apk/debug/app-debug.apk`.

Useful checks:

```bash
cd wrapper
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:testDebugUnitTest
bash scripts/host_tests_mouse_lock_state.sh   # if the host test deps are available
```

## Using it

1. Install the Boxblox APK on an arm64 Android device.
2. Open Boxblox and go to **Builds → Import**:
   - **Choose an APK file:** pick a complete XAPK, or the matching base APK and arm64 split together.
   - **Use the installed Roblox:** copy archives from the official Roblox app already on the device.
3. Select a build and tap **Launch**. If the engine was already used in this process, Boxblox restarts so the next session starts clean.

`wrapper/scripts/device-setup.sh` is a local dev helper for staging archives on a connected device; it expects the archive files to be available locally.

## Diagnostics

Runtime diagnostics are written to `files/diag.txt` in app-private storage:

```bash
adb shell run-as app.boxblox cat files/diag.txt
```

Logcat tag: `RobloxWrapper`.

## Project layout

- `wrapper/app/src/main/java/app/boxblox/` — launcher UI, archive verification/import/storage (`RobloxStore`, `ApkSigningBlock`, `BuildFiles`), class/resource loading, guest instrumentation/context/package-manager shims, session/flag handling, mouse capture policy/controller, server location, and overlay provider.
- `wrapper/app/src/main/cpp/` — native wrapper: engine load hooks, mouse-lock integration, and fingerprinted engine-state access.
- `wrapper/app/src/test/` — JVM unit tests.
- `wrapper/scripts/` — device setup helper and host-side native/state tests.
- `docs/apk-compatibility.md` — compatibility, update/rollback, and maintainer guidance.
- `.omp/skills/roblox-apk-compatibility/` — project skill for APK compatibility work.
