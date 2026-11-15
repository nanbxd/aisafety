# Product

## Platform and stack
Android native Kotlin, foreground AudioRecord, OkHttp WebSocket. Internal sideloaded hardware-test prototype, no Play Store.

## User and task
A developer testing ordinary microphone capture during Vivo Y27s SIM speakerphone calls. Success is a locally replayable WAV proving what the hardware captures, followed by an explicitly consented server test.

## Constraints
Own only this Android directory. No accessibility or default-dialer bypass claims. Cap each local recording at 30 seconds, gate server audio on ready, bound outgoing queues, expose failures. The user requested no clarification questions; the native single-screen form is an implementation choice inferred from the minimal-usable-UI brief.
