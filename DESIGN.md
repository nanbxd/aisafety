# Skepticall design

The web and native Android interfaces use a Google Meet-inspired layout: white lobby, blue primary actions, rounded controls, a charcoal call stage, and a separate conversation-analysis surface. Skepticall retains its own name and shield identity. The reference is Google's [desktop Meet layout](https://support.google.com/meet/answer/10550593).

## Surfaces

- Web lobby: connection form on the left, a character preview on the right. At narrow widths these stack. After connecting, the stage expands, analysis appears on the right, and call controls sit below. On mobile the controls stay fixed at the bottom; the analysis panel stacks and can be closed/reopened.
- Android lobby: personal address, recipient, explicit analysis consent, call/incoming actions, preview, and help/settings. During a call, setup and help are hidden; the character takes the central scroll region. Call actions remain outside the scroll region, above the Conversation/Analysis navigation. The analysis tab preserves access to call controls. Platform dialogs and the microphone diagnostic utility use matching blue/neutral colors.
- Analysis remains textual as well as visual: model risk, reasons, transcript, availability, and the warning that low risk does not guarantee safety.

## Tokens

Light background `#FFFFFF`, primary `#0B57D0`, tonal `#D3E3FD`, text `#202124`, secondary `#5F6368`. Call background `#202124`, tile `#292A2D`, text `#E8EAED`, secondary `#BDC1C6`, end call `#EA4335`. Android follows system dark mode outside the call too.

Web uses the local Arial/Segoe UI sans-serif stack; Android uses system sans-serif. No remotely loaded font is required. Native sizes use sp/dp. Controls have accessible names and at least 48px/54dp touch targets, keyboard focus on web, and text labels for risk. Web mobile layout begins at 760px. Native content is capped at 680dp and scrolls for large text or short screens.

## Character and assets

See [asset replacement instructions](server/static/rtc/assets/README.md). All new SVG artwork lives separately in `server/static/rtc/assets/icons/`; Android bundles that exact source folder. All future Lottie compositions live in `assets/animations/`, with filenames mapped by `manifest.json`. No final animation artwork is supplied: separate state-specific SVG faces remain visible until compositions are added.

Idle, ringing, listening, low, medium, high, and unavailable are driven by actual call/analysis state. High risk uses a red face and an explicit possible-fraud label. Missing analysis never implies safety. Missing or invalid animation files fall back to SVG. Reduced motion and hidden-window behavior pause animation playback. Legacy platform launcher/notification vectors remain separate XML resources.

## Verification

Automated checks cover the existing signaling/media logic plus character transitions, failure precedence, receiver-only risk, and call reset. The native APK and unit-test results must be reported from the current build. Visual desktop/mobile and physical-device inspection remains pending: the browser bridge was unavailable and native Chrome UI access timed out. No pixel-identical comparison to Meet or real-device Lottie playback is claimed.
