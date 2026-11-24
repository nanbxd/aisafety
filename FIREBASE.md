# Firebase: Android + Render

## Android

The Android app is `kz.safetyhack.audiotester`. Download its `google-services.json`
from Firebase Project settings → General and place it in `android/app/`.
The Google Services Gradle plugin reads this configuration when building the APK.
Do **not** upload this Android configuration to Render.

## Render server

1. In the **same Firebase project**, open Project settings → Service accounts →
   Firebase Admin SDK → Generate new private key. Keep the downloaded JSON private.
2. Open Render → `safetyhack-webrtc` → Environment and add a secret environment variable containing the complete server key JSON:

   ```text
   FIREBASE_SERVICE_ACCOUNT_JSON={...complete service-account JSON...}
   ```

   Alternatively, add a Render Secret File named `firebase-service-account.json` and set
   `GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json`.

4. In Firebase Project settings → Cloud Messaging, ensure Firebase Cloud Messaging
   API (V1) is enabled. The service account must have permission to send FCM messages.
5. Deploy the updated server code, build/install the updated APK on both phones.
   Allow microphone and notifications. On the receiver, save a phone address and enable incoming calls with consent.
   Wait for the status confirming push readiness, then test an incoming call.

The JSON key is a server secret: never add it to Git, Android assets or chat.
Local development can set the same variable to an absolute private file path.
Leaving the variable unset disables push; connected WebSocket calls still work.
No Firebase Functions, Firestore or paid Firebase plan is required by this integration.

## Behavior and current limits

- The available receiver registers its FCM token for the saved phone address.
  Address ownership is not verified by SMS in this MVP; another device cannot hold the same live receiver address at the same time.
- When the receiver socket is absent, the caller can invite via high-priority FCM.
  Android checks the pending call with the server before showing the ringtone and
  system Answer/Decline controls. Push itself never starts microphone recording.
- Answer opens the native activity, validates permissions and reconnects to the
  exact pending call. Expired or cancelled calls cannot be resumed. Decline is
  authenticated by the device token. Cancellation also removes the notification;
  its local timeout bounds ringing if cancellation delivery is delayed.
- Explicitly leaving disables receiving on that phone. Process death does not
  disable the saved registration. Android force-stop requires reopening the app;
  FCM cannot override force-stop, notification restrictions or missing Google Play services.
- This demo retains up to 128 receiver registrations **in server memory for 24 hours**.
  After a Render restart/redeploy, receivers must enable incoming calls again. This is not a durable
  verified user/contact directory. Multiple addresses are isolated, but the server still uses one process.
- FCM cannot preserve an already active WebRTC call after its process dies.
  Existing foreground-service behavior handles ordinary app backgrounding.
- Real phone delivery, lock-screen behavior and audio require an end-to-end test.
  Render credentials and remote deployment are not performed by a local APK build.

Sources: [Android setup](https://firebase.google.com/docs/android/setup),
[FCM receive](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[Render secret files](https://render.com/docs/configure-environment-variables#secret-files).
