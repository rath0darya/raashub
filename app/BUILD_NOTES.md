# Native Android build notes

- Application ID: `com.raashub`
- UI: Kotlin + Jetpack Compose + Material 3
- Build workflow: `.github/workflows/android-apk.yml`
- Release asset: `RaaSHub.apk`
- Source index: generated from `data/` during CI; do not hand-edit generated assets.
- Release signing: persistent GitHub Actions secrets; never commit the keystore.

The CI pipeline validates source records, builds and tests the app, verifies the release APK signature and package ID, refreshes `Download/RaaSHub.apk`, and publishes the signed APK. GitHub provides ZIP and TAR.GZ source archives for each release tag.
