# AgentWeb Android

Native Android client for AgentWeb, built with Kotlin and Jetpack Compose. The app is packaged as `com.karewinkcloud.agentweb.client` (version 0.5.0). It connects to an existing AgentWeb server; the server and its deployment are maintained separately.

This repository contains the native `:native` module, its Gradle wrapper, the shared API contract, and the fixtures needed by its JVM tests. It does not contain the older Trusted Web Activity shell.

## Features

- Conversations with live streaming, Markdown, attachments, projects, model selection, and explicit stop, queue, and resume controls.
- A Create tab for image, video, and audio workflows, plus Claude Code session history and handoff.
- Kare Access sign-in using Android Credential Manager with a browser fallback. Session tokens are encrypted with Android Keystore and bound to the selected server origin.
- Chinese and English interfaces, light and dark themes, and a local server setting. The default server is `https://agent.karewinkcloud.com`.

The client follows the protocol in [`conformance/contract/openapi-v1.yaml`](conformance/contract/openapi-v1.yaml). The included `conformance/fixtures/` are test inputs; they are not packaged into the APK.

## Diagnostics and updates

Settings > Diagnostics & updates offers two actions:

- **Upload diagnostic log** sends the app's own log to the AgentWeb server, where the server owner can read it: requests (method, path, status, timing), stream reconnects, errors shown, crashes, why earlier processes ended, main-thread stalls, and app start timing. It never contains message text, attachments, query strings, headers, or tokens. Until uploaded, the log stays on the phone (at most 2 MB; the oldest lines are dropped first).
- **Check for updates** offers a newer build published on the server, verifies its SHA-256, and hands it to the system installer. The first time, Android asks the user to allow the app to install apps. Android accepts the update only when it is signed with the same key as the installed app.

The version code counts minutes since 2026-01-01 at build time, so every build is newer than the one before. Installing an older build over adb needs `adb install -r -d`. The server side is described in AgentWeb's `docs/APP_DIAGNOSTICS.md`.

## Build and test

Install JDK 21 and Android SDK Platform 36 with Build Tools 36.1.0. Set `JAVA_HOME` and `ANDROID_HOME` to your local installations, then run from the repository root:

```sh
./gradlew :native:testDebugUnitTest :native:assembleDebug :native:lintDebug
```

GitHub Actions runs the same command on every push and pull request (`.github/workflows/android.yml`) and uploads the debug APK and test and lint reports as workflow artifacts.

The debug APK is `native/build/outputs/apk/debug/native-debug.apk`. Install it with `adb install -r native/build/outputs/apk/debug/native-debug.apk`.

The Gradle wrapper uses Gradle 9.8.0. Plugin and library versions, including the Android Gradle Plugin and Credential Manager, are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Initial builds need access to Google Maven and Maven Central. If your network requires a proxy, configure Gradle or pass its proxy properties for that build.

## Release signing

Release signing credentials must stay outside this repository. Point `AGENTWEB_ANDROID_KEYSTORE_PROPERTIES` to a private Java properties file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. When this variable is absent, release tasks fail rather than producing an unsigned release. Debug builds use Android's debug key.

## Scope

This is the Android client only. Sign-in, chat, creation workflows, and Claude session handoff require a compatible AgentWeb and Kare Access server. The local test fixtures exercise selected protocol cases; they are not a substitute for device acceptance against a live server.
