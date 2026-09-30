# AgentWeb Android

Native Android client for AgentWeb, built with Kotlin and Jetpack Compose. The app is packaged as `com.karewinkcloud.agentweb.client` (version 0.5.0). It connects to an existing AgentWeb server; the server and its deployment are maintained separately.

This repository contains the native `:native` module, its Gradle wrapper, the shared API contract, and the fixtures needed by its JVM tests. It does not contain the older Trusted Web Activity shell.

## Features

- Conversations with live streaming, Markdown, attachments, projects, model selection, and explicit stop, queue, and resume controls.
- A Create tab for image, video, and audio workflows, plus Claude Code session history and handoff.
- Kare Access sign-in using Android Credential Manager with a browser fallback. Session tokens are encrypted with Android Keystore and bound to the selected server origin.
- Chinese and English interfaces, light and dark themes, and a local server setting. The default server is `https://agent.karewinkcloud.com`.

The client follows the protocol in [`conformance/contract/openapi-v1.yaml`](conformance/contract/openapi-v1.yaml). The included `conformance/fixtures/` are test inputs; they are not packaged into the APK.

## Build and test

Install JDK 21 and Android SDK Platform 36 with Build Tools 36.1.0. Set `JAVA_HOME` and `ANDROID_HOME` to your local installations, then run from the repository root:

```sh
./gradlew :native:testDebugUnitTest :native:assembleDebug :native:lintDebug
```

The debug APK is `native/build/outputs/apk/debug/native-debug.apk`. Install it with `adb install -r native/build/outputs/apk/debug/native-debug.apk`.

The Gradle wrapper uses Gradle 9.8.0 and the Android Gradle Plugin is pinned to 9.4.1. Initial builds need access to Google Maven and Maven Central. If your network requires a proxy, configure Gradle or pass its proxy properties for that build.

## Release signing

Release signing credentials must stay outside this repository. Point `AGENTWEB_ANDROID_KEYSTORE_PROPERTIES` to a private Java properties file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. When this variable is absent, release tasks fail rather than producing an unsigned release. Debug builds use Android's debug key.

## Scope

This is the Android client only. Sign-in, chat, creation workflows, and Claude session handoff require a compatible AgentWeb and Kare Access server. The local test fixtures exercise selected protocol cases; they are not a substitute for device acceptance against a live server.
