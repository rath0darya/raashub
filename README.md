# RaaSHub Android

RaaSHub is a **native Android Kotlin + Jetpack Compose application**. This repository is maintained for the Android APK, native source code, and data bundled into the app. Website pages, website styling/scripts, Node.js server files, Termux workflows, and standalone scanner/maintenance tools have been removed.

## App identity

- App name: **RaaSHub**
- Android application ID: `com.raashub`
- Native entry point: `app/src/main/java/com/raashub/MainActivity.kt`
- Android build configuration: `app/build.gradle.kts`
- Canonical directory dataset: `data/directory.json`
- Source index generator: `scripts/build_android_source_index.py`
- APK destination: `Download/RaaSHub.apk`
- CI workflow: `.github/workflows/android-apk.yml`

## Native implementation

The Android interface is written in Kotlin with Jetpack Compose. The app does not embed the website in a WebView and does not run Node.js, JavaScript, Python, or Termux as an app runtime. Source data is stored as JSON, and the build-time index generator is a development/build utility, not an app runtime dependency.

The build script scans retained JSON, TXT, and CSV data for onion URLs, normalizes and deduplicates them, and preserves source-file provenance. The app bundles the resulting index locally and uses the original RaaSHub logo as its icon resource.

The current native app includes an overview, searchable source directory, source details/provenance, copy/open actions, an activity snapshot, and system-adaptive light/dark appearance. Live availability remains unknown unless measured. **Integrated Tor transport and live health checking are not yet implemented**; opening onion links requires a compatible Tor-capable browser or another configured Tor route. The app does not claim that all device traffic uses Tor.

## Build and APK output

The **Build RaaSHub Android APK** GitHub Actions workflow builds a debug/testing APK, checks that the output is non-empty, copies it to `Download/RaaSHub.apk`, uploads an artifact named `RaaSHub-Android-APK`, and attempts to commit the APK to the repository.

A successful workflow run is required before an APK can be considered ready. Earlier workflow runs failed before build steps started, so those runs did not verify an APK. If GitHub Actions runner limits prevent jobs from starting, build the project with Android Studio/Gradle on another machine.

## Signing and updates

The current workflow targets a **debug/testing APK**, not a production release. Android updates must use the same application ID and signing certificate as the installed version. Keep `com.raashub` stable. For repeatable production updates, configure a persistent private release keystore and never commit the private signing key to this repository.

## Data and safety

The retained `data/` directory contains source datasets used to populate the app. A dataset entry does not prove that a service is online, legitimate, or safe. The app is offline-first and does not use an external backend or cloud database; refreshing the bundled source index requires a new build.
