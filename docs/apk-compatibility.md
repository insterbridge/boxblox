# Roblox APK compatibility and updates

## What “outdated” means

Three different versions matter, and they are not the same thing:

- **Older Roblox APK:** older than some other available client release. Older by itself does not make an archive invalid to import.
- **Wrapper reference target:** `RobloxStore.HOOKED_VERSION` is **2.745.1142**. This is the reference the native mouse hooks were written against, not a supported-version range and not a promise that every client build works end to end.
- **Roblox service minimum:** the client versions Roblox currently accepts for sign-in and joining. That is service policy, not a constant in this repo.

**Service policy is unknown here.** This repository does not establish a current minimum version, cutoff, or exact server rejection. An imported build can pass local checks and still fail to sign in or join.

## What the wrapper does today

Boxblox does **not** download or update Roblox for you. Imports are explicit copies.

- **Import from files:** choose a complete XAPK, or the matching base APK and arm64 split together.
- **Use the installed Roblox:** copies the archives from the official app already on the device. Updating the official app later does not update that retained copy; import again to pick up newer archives.

Both older and newer imports are allowed if they pass the actual checks:

- Every archive must belong to `com.roblox.client` and pass the pinned Roblox signing-certificate and signed-payload verification.
- Base and splits must share the same **version code**.
- The set must contain exactly one base APK with DEX and the required `libroblox.so` for the selected ABI (the current device workflow is arm64).

These checks decide whether an archive can be imported, not whether the client will run correctly or be accepted by Roblox services. See [RobloxStore][store] (`importInstalledRoblox`, `prepare`, `prepareSnapshot`, `ROBLOX_CERT_SHA256`, `ROBLOX_PACKAGE`, `ABI`, `REQUIRED_LIB`).

On launch, a version name different from **2.745.1142** shows **“Untested Roblox version”**. That warning does not reject the build by itself: `MainActivity.onLaunch` warns and continues toward starting the guest. Other preparation or runtime failures can still stop launch. See [MainActivity][launcher] and [strings][strings].

The mouse enhancement checks native signatures/layout fingerprints before using fixed offsets. If they do not match, it disables that enhancement instead of reading unknown offsets. That fallback does **not** mean the rest of the client is compatible.

Other assumptions can break independently: guest application/activity classes, `NativeInputInterface`, obfuscated listeners/button translation, reflected `sg.a1` fields, resources/theme, and loader behavior. See [GuestInstrumentation][instrumentation], [GuestContext][context], [GuestClassLoader][loader], and the mouse-capture implementation in this tree. Importing a different Roblox build does not upgrade the wrapper’s hooks; hooks ship with the wrapper.

### Evidence limit

The native fixture is **2.745.1142**, but the retained Java evidence is **2.743.1136**. Matching 2.745.1142 Java DEX was not available for that analysis, so Java button ordinals, listener routing, and capture-swallow behavior still need confirmation against the matching client. The current mouse design is not fully device-validated even at the reference version.

## Trying another build safely

1. Keep the previous imported build in **Builds** before testing a new one. Record the wrapper commit/version, Roblox version/code, and build hash. Save the full diagnostic log before switching:
   `adb shell run-as app.boxblox cat files/diag.txt > diag-before-switch.txt`
2. Import a complete XAPK, or the matching base APK and arm64 split together. Do not mix releases. If you use the installed Roblox, import the archives from the specific installed version you mean to test.
3. Select the imported build in **Builds** and press **Launch**. A version warning is informational. If the engine was already used in this process, the launcher restarts for a clean session.
4. Verify sign-in, the home screen, joining an experience, and the intended desktop/session identity. For mouse changes, test the full matrix: shift lock on/off, edge-crossing right-drag, stationary hold, repeated drags, menu/ESC/F1, and focus/pause transitions. Record visible behavior together with diagnostics, not just a capture-request message.
5. Remove the previous build only after the new one passes the checks you care about. To roll back, select the preserved previous build and launch it again. Rollback is only useful if Roblox still accepts that client.

Selection and restart are handled in [MainActivity][launcher] (`showImportChooser`, `selectBuild`, `onLaunch`). Shared app-level sign-in/data are **not** per-build rollback snapshots: selecting an older build does not undo shared data changes or restore an earlier session. Deleting a build removes its retained files and build-local data. See [RobloxStore][store] (`selectedBuild`, `select`, `delete`) and [GuestContext][context].

## Maintainer checklist for a new hook reference

- Obtain matching base DEX and native library from the same new version. Record version name/code, archive hashes, native hash, and ABI.
- Compare native JNI signatures, getter/helper code, and layout fingerprints. Separately compare Java button translation, generic/captured listeners, reflection targets, resources/theme, and loader assumptions.
- Do not just bump `HOOKED_VERSION`, repin constants to a mismatch, or bypass signer/fingerprint checks. Derive changed fingerprints and offsets from matching artifacts and reviewed access paths; a warning-free launch is not evidence.
- Update only the assumptions that are established to have changed, along with tests/docs. Run the host/native and JVM suites, then verify on-device (sign-in, home, joining, identity, and the full mouse matrix). Report artifact versions and observations separately from service-policy unknowns.

[store]: ../wrapper/app/src/main/java/app/boxblox/RobloxStore.java
[launcher]: ../wrapper/app/src/main/java/app/boxblox/MainActivity.java
[strings]: ../wrapper/app/src/main/res/values/strings.xml
[native]: ../wrapper/app/src/main/cpp/guest_native.cpp
[instrumentation]: ../wrapper/app/src/main/java/app/boxblox/GuestInstrumentation.java
[context]: ../wrapper/app/src/main/java/app/boxblox/GuestContext.java
[loader]: ../wrapper/app/src/main/java/app/boxblox/GuestClassLoader.java
