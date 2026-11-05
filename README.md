# HEXNET — GitHub-ready handoff bundle

This repository combines the latest available native Android source export with the separately exported Stitch design package.

## Repository layout

- `android/` — Android Gradle project (Kotlin, Jetpack Compose, BLE, protocol, crypto, routing, Room, tests).
- `design/DESIGN.md` — Stitch design system specification.
- `design/stitch/` — Original Stitch HTML screen exports and PNG previews.
- `reports/HANDOFF_STATUS.md` — Inspection notes and limitations.

## Open/build the Android project

Open the `android/` directory as the Gradle project root, or from PowerShell run:

```powershell
cd android
.\gradlew.bat tasks
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lint
```

Android SDK platform/build-tools matching `compileSdk = 35` must be installed and `ANDROID_HOME` (or `local.properties`) configured. Gradle may need network access to download its wrapper distribution and dependencies.

## Design handoff caveat

The Stitch export is a design/prototype handoff. Its HTML files are not native Jetpack Compose source. The Compose UI must be implemented against `design/DESIGN.md` and the included PNG/HTML references. This bundle does not claim that the Stitch screens are already integrated into the Android application.

## Verification status

- Both source ZIPs passed ZIP integrity checks before extraction.
- Android project contains 65 files.
- 9 Kotlin test files contain 106 `@Test` annotations. This is a source count, **not** a test-pass result.
- Android build/tests have not been confirmed passing by this packaging step.
