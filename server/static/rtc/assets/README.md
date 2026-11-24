# Replaceable visual assets

Web and Android use this same folder. Gradle bundles it into the APK; rebuild Android after changing assets. The web serves it at `/rtc-assets/assets/`.

## SVG

Every illustration and interface icon is a separate file in `icons/`. Replace a file, retaining its name and `viewBox`. Web icons use `<img>` or CSS masks; Android reads SVG with AndroidSVG. Control icons are tinted by the UI. Keep SVGs self-contained, with no scripts or external resources. Platform launcher/notification icons remain Android vector XML resources in `android/app/src/main/res/drawable/`.

## Your Lottie animations

Place your exported Lottie JSON files in `animations/`, then set the corresponding entry in `animations/manifest.json` from `null` to its filename, for example `"high": "high.json"`. Both clients read this manifest. Use plain filenames containing letters, digits, `_` or `-`; no directories. Export self-contained vector animations without expressions, fonts or image layers (vector shapes only), a square composition, and a loop that can start at frame zero.

| Key | When it appears | SVG fallback |
| --- | --- | --- |
| idle | No active call | face-idle.svg |
| ringing | Dialing, ringing, connecting | face-ringing.svg |
| listening | In conversation, no assessment yet; caller cannot see receiver analysis | face-listening.svg |
| low | Model reports low risk | face-low.svg |
| medium | Model reports medium risk | face-medium.svg |
| high | Model reports high risk | face-high.svg |
| unavailable | Receiver's analysis failed | face-unavailable.svg |

Risk comes from the real analysis events. No assessment is not presented as safety. Analysis failure takes priority over an earlier risk result; the analysis panel retains the result with its unavailable/incomplete message. Ending a call resets the character to idle; a new call clears previous assessment. A missing/invalid animation leaves the SVG visible. Motion follows reduced-motion settings; players pause when hidden. Test custom animations on both Canvas (web) and Android Lottie because renderer support varies.

Web player: vendored lottie-web 5.12.2 Canvas build (MIT); no CDN request during a call. Native player: Lottie Android 6.6.2; SVG: AndroidSVG 1.4.
