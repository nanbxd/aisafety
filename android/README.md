# Android native calling — 0.6-push-calls

The launcher opens the native Kotlin call workspace directly. No WebView, JavaScript bridge,
URL entry or browser dependency. Either participant can choose caller or receiver. The browser
client remains interoperable through the existing signaling protocol.

## Build and service configuration

JDK 17, Gradle 8.9, Android SDK 35. WebRTC dependency: `io.github.webrtc-sdk:android:125.6422.07`.
From the repository root:

```powershell
$env:JAVA_HOME = "$PWD/.tools/java/jdk-17.0.21+9"
$env:ANDROID_HOME = "$PWD/.tools/android-sdk"
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-cache"
& ./.tools/gradle/gradle-8.9/bin/gradle.bat -p android '-PRTC_BASE_URL=https://safetyhack-webrtc.onrender.com' assembleDebug testDebugUnitTest --no-daemon
```

The default server is `https://safetyhack-webrtc.onrender.com`; users never enter it. `RTC_BASE_URL` overrides it as a Gradle property or environment variable. It must be the HTTPS origin
without a path, matching the backend's `PUBLIC_BASE_URL`. It is a public service address,
not a secret. Never put `GEMINI_API_KEY` in the APK. Missing configuration shows a clear
unavailable state; the app does not guess a deployment address or ask users to paste a URL.

APK: [app-debug.apk](app/build/outputs/apk/debug/app-debug.apk).
Tests: [testDebugUnitTest](app/build/reports/tests/testDebugUnitTest/index.html).
Version code 6, version name `0.6-push-calls`. Firebase Android configuration is required at `app/google-services.json`; see [Firebase setup](../FIREBASE.md).

## First call

1. Open the app; choose «Принимаю» or «Звоню».
2. Share a room code using the Android share sheet. Both participants use that code and different roles.
3. Both explicitly consent to Google processing, then enter the room. Grant microphone and notification permissions; the foreground service starts while the activity is visible.
4. Caller presses «Позвонить». Receiver hears the system ringtone and answers in the app or the incoming notification.
5. Receiver opens «Анализ» for remote speech, risk reasons and analysis availability.
6. Home, Back, screen locking and recreation do not tear down the session. Return/end using the notification. «Выйти из комнаты» stops the service.

## Native media and lifecycle

OkHttp provides secure signaling and analysis sockets, with the configured Origin header.
The native WebRTC SDK supplies bidirectional audio and ICE/TURN configuration from the server.
Only the receiver's **decoded remote track** feeds analysis. PCM16 is downmixed/resampled to
16 kHz mono and batched in 100 ms packets, respecting the backend's frame-size/rate limits.
Single-use audio tickets remain in memory. Stale socket, SDP and PCM events are fenced by
session/call generations; the PCM queue is bounded. Permission continuations are bound to
the original call. Microphone and audio routing are released on teardown.

Foreground incoming calls use the joined session. FCM can deliver an incoming when the receiver socket is absent, after server credentials and the updated server are deployed. Reopen after force-stop; rejoin after server restart or the 24-hour registration expiry. Doze, vendor battery controls and network loss can interrupt availability. TURN may be required across mobile networks.
An analysis failure leaves the voice call running with an explicit incomplete/unavailable
message; a signaling failure ends the call. Analysis is not a guarantee of safety.

The separate 30-second microphone diagnostic remains under «Помощь» → «Проверить микрофон».
It is not used to capture the other participant's call audio.

## Verification limits

JVM tests cover PCM conversion, packet batching and existing origin policy. Device validation
must cover two-party native/browser calls lasting more than ten seconds, real Gemini output,
mic permissions, audio routes, interruption, repeated calls, light/dark theme and large fonts.
No device or emulator was attached during this migration, so native screenshots and real
microphone/WebRTC execution are not verified by the unit tests or APK compilation.

## Background ownership and notifications

[CallService](app/src/main/java/kz/safetyhack/audiotester/CallService.kt) owns the microphone
foreground session. The engine retains only application context; activities attach/detach UI
listeners. Task removal does not intentionally stop the service. A partial wake lock is held
while ringing/calling and released on call end or service destruction. Process death does not
silently resurrect consent or an obsolete WebRTC session (`START_NOT_STICKY`).

Incoming calls use a high-importance channel with the system ringtone, vibration and
`FLAG_INSISTENT`. Android 12+ uses native `CallStyle`; earlier releases get answer/decline actions.
The ringing notification is canceled on accept, reject, remote cancellation, timeout, disconnect
and service destruction. Notification actions are fenced by process/session/call identity.
Answer opens the activity directly, without a notification trampoline. Android 14+ full-screen
presentation is used only when allowed. «Настройки входящих звонков» opens notification and
full-screen permissions. Silent mode, Do Not Disturb and channel settings are respected.

Cold-process delivery uses FCM, a saved device registration and native call notifications.
Server-side credentials and deployment are required; see [Firebase setup and limitations](../FIREBASE.md).
Push does not bypass Android force-stop or restore a conversation whose media process died.
Registrations expire after 24 hours and are lost on server restart in this single-process demo.

## Additional device acceptance checks

- Two-device call across Home, Back, rotation, screen-off and task dismissal: audio continues.
- Incoming while receiver is joined and screen is off: ringtone, vibration, visible answer/decline.
- Notification answer, decline, hangup, caller cancellation and ring timeout all stop the ringtone.
- A stale notification from a previous call cannot answer or end the next one.
- Notification/microphone denial, full-screen denial, DND and silent mode have honest behavior.
- Explicit room exit stops the service; force-stop requires launching and rejoining.

No Android device/emulator was attached. Unit tests and compilation do not establish the
above hardware behaviors. Test on the target Vivo/Android version before relying on them.

Platform references: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[call notifications](https://developer.android.com/develop/ui/compose/notifications/call-style),
[full-screen notifications](https://developer.android.com/develop/ui/views/notifications/time-sensitive),
[PendingIntent launches](https://developer.android.com/guide/components/activities/secure-bal).
