# Android WebRTC receiver — 0.3-webrtc

The launcher is a native Russian server setup screen followed by a hardened WebView receiver.
Save only the trusted HTTPS origin, for example `https://your-server.example`, without a path,
query, credentials or fragment. The wrapper opens `/rtc?role=receiver` on that origin.

## Setup and use

1. Deploy the backend and its WebRTC page on one HTTPS origin with a valid public certificate.
2. Install the APK and enter that origin in **Сохранить и открыть**. Grant Android microphone permission before the receiver page opens.
3. On the page, enter the room and SAFETY_TOKEN, consent to audio processing and join as receiver.
   The page keeps the token in memory only. Android neither reads nor injects it.
4. Allow the origin-specific native microphone consent prompt. Runtime permission is never requested during a WebView call; if revoked, return to setup and reopen.
5. The caller opens the same server's `/rtc?role=caller` in a browser and joins the same room.
6. Keep the Android application open. Backgrounding, Back and rotation close the page and call.
   Return to setup and explicitly reopen to reconnect. There is no push or always-on incoming mode.

The Android app uses WebRTC and has no external calling-service SDK or registration. Hosting, ASR and any TURN relay still
depend on your deployment; free peer-to-peer connectivity cannot be guaranteed across every NAT.

## Page integration contract

- Serve HTML, scripts and navigation on exactly the configured HTTPS origin, including port.
  No third-party assets, redirects, external login pages, popups or native JS bridge.
- The page owns room selection, authentication, explicit consent before getUserMedia,
  WebRTC signaling/media and remote PCM ASR. Use same-origin secure endpoints.
- Export `window.safetyRtcStop()`. It must synchronously stop every local media track and
  remote audio processing graph, close all RTCPeerConnections and sockets, stop ASR and
  clear in-memory credentials. Also clean up on pagehide.
- Android calls that hook before navigating to a blank page and destroying the WebView.
  Destruction has a 250 ms fallback if the renderer does not answer.
- Never store bearer tokens in cookies, local/session storage, URLs or form persistence.
  DOM storage, database storage, cookies, autofill and form saving are disabled by the wrapper.

## Security and lifecycle

Only RESOURCE_AUDIO_CAPTURE is eligible, and only for the exact configured origin while
foreground, with explicit native consent and Android RECORD_AUDIO permission. Mixed audio/video
requests, camera and unknown permissions are denied. File/content access, mixed content,
cleartext traffic and WebView debugging are disabled. SSL errors are rejected. No
JavascriptInterface is exposed. Setup preferences contain only the server origin; WebView state
is never saved across recreation.

**Проверить микрофон отдельно** opens the existing native 30-second microphone diagnostic.
Its optional network endpoint now requires encrypted `wss://` because application cleartext is
disabled. This diagnostic is separate from received WebRTC audio. Finish recording before opening
the receiver. Receiver teardown completes before diagnostics can open; native capture also rejects
an active receiver. Diagnostic foreground-service/notification permissions remain for this feature.

## Build on Windows

JDK 17, Gradle 8.9 and Android SDK 35 are required. From the repository root, using the warmed tools:

```powershell
$env:JAVA_HOME = "$PWD/.tools/java/jdk-17.0.21+9"
$env:ANDROID_HOME = "$PWD/.tools/android-sdk"
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-cache"
& ./.tools/gradle/gradle-8.9/bin/gradle.bat -p android assembleDebug testDebugUnitTest --no-daemon
```

APK: [app-debug.apk](app/build/outputs/apk/debug/app-debug.apk).
Unit report: [testDebugUnitTest](app/build/reports/tests/testDebugUnitTest/index.html).
Version: versionCode 3, versionName 0.3-webrtc.

Pure JVM RtcPolicy tests cover origin canonicalization, malicious/foreign origins,
navigation and microphone permission gates. Device testing remains required for Android WebView
media, audio routing, permission dialogs, lifecycle interruption, two-party signaling and remote
ASR; no attached device was available during this migration.

