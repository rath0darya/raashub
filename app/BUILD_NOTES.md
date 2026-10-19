# Native Android build notes

The Android project uses application ID `com.raashub` and is built by `.github/workflows/android-apk.yml`.

The CI job derives `onion_sources.json` from the repository's `data/` files during each build and copies the repository's existing RaaSHub logo into Android drawable resources. These generated assets are intentionally not hand-maintained lists.

The first CI APK is a debug/testing build. It must not be treated as a signed production release.
