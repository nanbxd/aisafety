# Product

## Platform
android

## Stack
Native Kotlin Android Views, native WebRTC SDK and an Android microphone foreground service. Shared FastAPI signaling/analysis server and Google Gemini Live. The separate HTML/CSS/JavaScript browser client remains available.

## User and task
Russian-speaking participants join the same room, place or receive an internet voice call, and inspect speech and fraud-risk analysis on the receiver side. This description is inferred from the repository and the requested redesign; no new product claims are introduced.

## Constraints
One room, two roles. Native calls continue through activity backgrounding while the foreground service and signaling connection remain alive. FCM incoming delivery is implemented for an absent receiver socket and requires server credentials/deployment; registrations last 24 hours in server memory. Force-stop requires reopening, server restart requires rejoining. Preserve explicit consent, origin checks, one-use audio tickets, truthful unavailable/incomplete analysis states and call lifecycle. Remove the shared access-secret mechanism as explicitly requested. Do not imply low risk guarantees safety. Keep Google API credentials on the server.

## Design brief
User requested a native Android calling interface without a URL-entry step, background calling, and incoming ringtone/answer controls. The supplied public server is https://safetyhack-webrtc.onrender.com. Use existing Russian product content and preserve all controls. The separate web client retains its own design and foreground-only lifecycle.
