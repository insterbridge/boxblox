---
name: roblox-apk-compatibility
description: Use when evaluating, importing, updating, rolling back, or debugging an outdated or newer Roblox APK in this wrapper.
---

# Roblox APK compatibility workflow

Read `docs/apk-compatibility.md` first. Treat it as the current user-facing explanation; confirm version-sensitive claims against the current source before changing them.

## Keep three compatibility questions separate

1. **Import acceptance:** package identity, signer/payload, matching base/split version codes, required base DEX, and ABI native library.
2. **Wrapper compatibility:** the launcher warning target (`RobloxStore.HOOKED_VERSION`), native fingerprint-gated mouse enhancement, and independent Java/resource/loader assumptions.
3. **Roblox service acceptance:** whether Roblox still allows the client to sign in and join. Repository code cannot establish a current minimum version, cutoff, or server rejection cause.

Never infer service support from a successful import, wrapper warning, hook fingerprint, or version name alone. Never claim the entire client is compatible because the native mouse enhancement falls back safely.

## Investigate an unsupported or outdated build

- Record the base/split archive identities, version name/code, ABI and hashes; ensure base and split came from the same release.
- Read the actual import validators and launch warning path before describing whether the build is rejected. Current behavior: version mismatch is a warning, not a version gate; imports are user-initiated and do not auto-update retained builds.
- Separate a native fingerprint mismatch from Java/resource/loader failure and from a Roblox server rejection. Use `diag.txt` and the observed error; do not guess from the warning.
- For a new hook reference, obtain matching base DEX and native artifacts from the same version. Compare native getter/layout/helper fingerprints, Java button translation and captured/generic routing, guest classes, reflected fields, resources and theme. Do not merely bump the reference version, copy offsets across releases, or bypass signer/fingerprint checks.
- Keep a previous imported build until the new one passes sign-in, home/join, identity checks and the complete device mouse matrix. Rollback selects the preserved build; it does not restore per-build shared sign-in/data or guarantee Roblox will still accept the old client.
- Report observed facts, inferences and service-policy unknowns separately. A clean build or test run is not proof of device behavior.

## Reporting

State plainly whether the APK was rejected, only warned, imported, launched, or actually accepted by Roblox services. Include artifact versions/hashes and relevant diagnostics. Link `docs/apk-compatibility.md` for the supported update/rollback procedure.