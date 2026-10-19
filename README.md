# RaaSHub for Android

RaaSHub is a **native Android app built with Kotlin and Jetpack Compose**. This repository is focused on the Android app, its bundled directory data, and APK build workflow. It does not contain a website frontend or a Node.js app runtime.

## App details

- **App name:** RaaSHub
- **Android application ID:** `com.raashub`
- **UI framework:** Kotlin + Jetpack Compose
- **Main screen source:** `app/src/main/java/com/raashub/MainActivity.kt`
- **Canonical directory dataset:** `data/directory.json`
- **Build-time source index generator:** `scripts/build_android_source_index.py`
- **APK destination:** `Download/RaaSHub.apk`
- **APK workflow:** [Build RaaSHub Android APK](.github/workflows/android-apk.yml)
- **Original logo:** `assets/images/raashub-logo.png`

## Native app and current capabilities

The app uses native Android Compose UI. It does not use a WebView for its interface and does not require Node.js, JavaScript, Python, or Termux at runtime. JSON files hold directory data; the Python script is only a build-time utility that prepares the bundled source index.

The current interface provides an overview, searchable source groups, all URLs within each group, copy/open actions, live health-check results grouped by source, an activity screen, and system-adaptive light/dark appearance. The source index generator scans retained JSON, TXT, and CSV files, normalizes and deduplicates onion URLs, and groups URLs by their source entity. File paths are kept out of the user-facing screens.

The app bundles the Guardian Project Tor Android runtime and starts it automatically for onion health checks. A source result reflects only the moment checked; it does not certify a site's trustworthiness or safety. The app checks up to 10 URLs per run and does not claim to route all device traffic through Tor.

## Build the debug APK

The GitHub Actions workflow installs the Android SDK, generates the source index, installs the original logo, builds the debug APK, checks that the APK is non-empty, copies it to `Download/RaaSHub.apk`, uploads the `RaaSHub-Android-APK` artifact, and attempts to commit the APK to the repository.

To build locally with Android Studio or Gradle:

1. Install Android Studio and a compatible JDK 17.
2. Open this repository as a Gradle project.
3. Ensure Android SDK Platform 35 and Build Tools 35.0.0 are installed.
4. Run `gradle assembleDebug` from the repository root.
5. Find the generated APK at `app/build/outputs/apk/debug/app-debug.apk`.

The CI build previously exposed a JVM-target mismatch (Java defaulted to 1.8 while Kotlin targeted 17). The Gradle configuration now explicitly aligns Java and Kotlin targets to **17**. Check the latest [GitHub Actions run](https://github.com/rath0darya/raashub/actions) for the current result. An APK is only confirmed when the workflow completes successfully and verifies `Download/RaaSHub.apk`.

## Package ID, signing and updates

The package ID is `com.raashub`. This must remain stable for compatible updates. The workflow currently produces a **debug/testing APK**, not a production-signed release. Android will only accept an in-place update when the application ID and signing certificate match the installed app. For production releases, configure a persistent private release keystore; never commit the private signing key to this repository.

## Repository layout

- `app/` — native Android source and resources
- `assets/` — original app logo
- `data/` — retained source datasets
- `scripts/build_android_source_index.py` — build-time source index generation
- `.github/workflows/android-apk.yml` — APK build workflow
- `.github/workflows/unpack-repository.yml` — guarded archive extraction workflow (only runs when the archive is present)

## Footer

Developed With ♥️ AnonVox
