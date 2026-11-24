# Android design

The current shared design specification is [../DESIGN.md](../DESIGN.md).

The native call screen is implemented in `RtcActivity.kt`, with a `CallAvatar` view using native Lottie and AndroidSVG. The shared visual assets and replacement instructions are in [../server/static/rtc/assets/README.md](../server/static/rtc/assets/README.md).

The lobby uses white/blue surfaces (system dark mode supported); active calls use a charcoal stage with a centered character, fixed call controls and Conversation/Analysis navigation. Setup is hidden during calls. Analysis events choose the character state; failed analysis takes priority over a previous risk assessment. No WebView is used.

SVG/Lottie assets are bundled by Gradle from the web asset directory. Rebuild the APK after replacing them. System launcher and notification vectors remain individual Android XML resources.

Physical-device layout, large fonts, orientation and custom Lottie playback still require device review.
