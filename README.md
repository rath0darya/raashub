<div align="center">
  <img src="https://raw.githubusercontent.com/rath0darya/raashub/main/assets/images/raashub-logo.png" alt="RaaSHub" width="148" />
  <h1>RaaSHub</h1>
  <p><strong>Native Android · Onion source directory · Live availability checks</strong></p>
  <p>
    <a href="https://github.com/rath0darya/raashub/actions/workflows/android-apk.yml"><img src="https://github.com/rath0darya/raashub/actions/workflows/android-apk.yml/badge.svg?branch=main" alt="Android build" /></a>
    <a href="https://github.com/rath0darya/raashub/releases/latest"><img src="https://img.shields.io/github/v/release/rath0darya/raashub?display_name=tag" alt="Latest release" /></a>
    <a href="https://github.com/rath0darya/raashub/releases/latest"><img src="https://img.shields.io/github/downloads/rath0darya/raashub/total" alt="Downloads" /></a>
    <img src="https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android&logoColor=white" alt="Android 26+" />
    <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose" />
  </p>
  <p><a href="https://github.com/rath0darya/raashub/releases/latest"><strong>⬇ Download RaaSHub.apk</strong></a> · <a href="https://github.com/rath0darya/raashub/releases">All releases</a> · <a href="https://github.com/rath0darya/raashub/actions">Build status</a></p>
</div>

---

<table>
  <tr>
    <td align="center" width="25%"><strong>⌕ Search</strong><br/>Find sources and URLs</td>
    <td align="center" width="25%"><strong>◈ Directory</strong><br/>Browse grouped sources</td>
    <td align="center" width="25%"><strong>◎ Monitor</strong><br/>Check HTTP availability</td>
    <td align="center" width="25%"><strong>◐ Themes</strong><br/>Dark and light modes</td>
  </tr>
</table>

## App

RaaSHub is a **native Android application** built with Kotlin and Jetpack Compose. It organizes onion and clear-web source links, lets you search and browse source groups, and checks availability without downloading page content.

| | |
|---|---|
| **Package ID** | `com.raashub` |
| **Platform** | Android 8.0+ (API 26+) |
| **UI** | Kotlin · Jetpack Compose · Material 3 |
| **Network** | Direct HTTP/HTTPS for clear-web URLs; Tor SOCKS for `.onion` URLs |
| **Navigation** | Home · Sources · Activity · Settings |
| **Data** | Bundled offline source index; latest scan saved locally |

## Screens & features

- **Home** — source totals, latest scan snapshot and quick actions.
- **Sources** — searchable, category-filtered source groups with copy/open actions.
- **Activity** — live progress, worker activity, HTTP response classes and previous scan results.
- **Settings** — dark/light appearance, notification controls and routing information.
- **Scanner** — independent direct and Tor-only onion crawlers, parallel HTTP/HTTPS probes, bounded queues and response-header limits.
- **Privacy** — no external CTI backend is required; no device-wide traffic routing is claimed.

## Download

<div align="center">
  <a href="https://github.com/rath0darya/raashub/releases/latest">
    <img src="https://img.shields.io/badge/Download-RaaSHub.apk-FF2D95?style=for-the-badge&logo=android&logoColor=white" alt="Download RaaSHub APK" />
  </a>
  <p><sub>Install the APK from the latest GitHub Release. Allow installation from your chosen browser or file manager when Android asks.</sub></p>
</div>

## Build

GitHub Actions validates the source dataset, generates Android launcher resources, runs unit tests, builds a signed release APK, verifies its signature and package ID, and publishes the release asset as `RaaSHub.apk`.

| Secret | Purpose |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded persistent release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Signing key password |

The signing key must remain the same for every update. **Never commit the keystore or passwords.**

## Source layout

| Path | Purpose |
|---|---|
| `app/` | Native Android application |
| `data/directory.json` | Canonical source directory |
| `data/groups/` | Additional source records |
| `scripts/` | Data validation and source-index generation |
| `assets/images/` | RaaSHub brand artwork |
| `.github/workflows/` | CI, signed APK and archive workflows |

## Safety

A successful HTTP response only indicates that a server responded at check time. It does **not** establish that a source is trustworthy, safe, legal or free of malware. Onion URLs are checked through Tor only; clear-web URLs use the device's direct network connection.

<div align="center"><sub>Built with Kotlin & Jetpack Compose · RaaSHub · AnonVox</sub></div>
