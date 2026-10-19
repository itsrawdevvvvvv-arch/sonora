# Building Sonora

Sonora is a standard Android project built with Gradle.

## Prerequisites

- **JDK 17** (Temurin, or any JDK 17+).
- **Android SDK** with `platforms;android-34`, `build-tools;34.0.0` and `platform-tools`.
- Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK location.

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## Gradle build

```bash
git clone https://github.com/itsrawdevvvvvv-arch/sonora.git
cd sonora

./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release-unsigned.apk
```

### Signing a release build

```bash
keytool -genkeypair -v -keystore sonora-release.jks -alias sonora \
  -keyalg RSA -keysize 2048 -validity 10950

export SONORA_KEYSTORE="$PWD/sonora-release.jks"
export SONORA_STORE_PASSWORD="your-store-password"
export SONORA_KEY_ALIAS="sonora"
export SONORA_KEY_PASSWORD="your-key-password"
./gradlew assembleRelease
```

`app/build.gradle` reads those variables and signs the release build when
`SONORA_KEYSTORE` is set.

## Automated releases

`.github/workflows/release.yml` builds a signed APK whenever a `v*` tag is
pushed, and attaches it (plus a SHA-256 checksum) to the GitHub Release. Add
these repository secrets to enable automatic signing:

| Secret | Value |
| --- | --- |
| `SONORA_KEYSTORE_BASE64` | `base64 -w0 sonora-release.jks` |
| `SONORA_STORE_PASSWORD` | keystore password |
| `SONORA_KEY_ALIAS` | key alias (e.g. `sonora`) |
| `SONORA_KEY_PASSWORD` | key password |

Without these secrets the workflow still builds the APK and uploads it as a
workflow artifact, but leaves the Release untouched.

## Project notes

- `minSdk 24`, `targetSdk 34`, `compileSdk 34`.
- Java 17, no third-party dependencies, no AndroidX.
- The entire UI is `app/src/main/assets/index.html`; there are no bundled fonts.
- The page also runs in a normal desktop browser for fast UI iteration — open
  `app/src/main/assets/index.html` directly and it falls back to a small demo
  library. Use the URL hash to jump straight to a screen: `#home`, `#library`,
  `#profile`, `#player`.
