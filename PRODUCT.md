# Product

## Platform
android

## Stack
Native Kotlin Android Views, native WebRTC SDK and an Android microphone foreground service. Shared FastAPI signaling/analysis server and Google Gemini Live. The separate HTML/CSS/JavaScript browser client remains available.

## User and task
Russian-speaking participants use a phone number as an in-app address, place or receive an internet voice call, and inspect speech and fraud-risk analysis on the receiver side. The number does not initiate a carrier call and is not verified by SMS.

## Constraints
Independent phone-address sessions with one caller and one receiver per address. Native calls continue through activity backgrounding while the foreground service and signaling connection remain alive. FCM incoming delivery is implemented for an absent receiver socket and requires server credentials/deployment; registrations last 24 hours in server memory. Force-stop requires reopening, server restart requires enabling incoming calls again. Preserve explicit consent, origin checks, one-use audio tickets, truthful unavailable/incomplete analysis states and call lifecycle. Phone-address ownership is not verified in this MVP. Do not imply low risk guarantees safety. Keep Google API credentials on the server.

## Design brief
User requested a native Android calling interface without rooms or a URL-entry step: users save their own phone number as an address and dial another saved number. Background calling and incoming ringtone/answer controls remain. The supplied public server is https://safetyhack-webrtc.onrender.com. Use existing Russian product content and preserve all controls. The separate web client retains its own design and foreground-only lifecycle.
