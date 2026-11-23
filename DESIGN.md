---
name: SafetyHack
description: Native Android voice-call workspace with explicit consent and remote speech analysis.
colors:
  primary: "#2949BA"
  on-primary: "#FFFFFF"
  background: "#F5F6FA"
  surface: "#FFFFFF"
  ink: "#192237"
  secondary-text: "#526078"
  tonal: "#E8EDFF"
  danger: "#B3261E"
  dark-primary: "#B7C6FF"
  dark-on-primary: "#14245F"
  dark-background: "#11141B"
  dark-surface: "#1D2230"
  dark-ink: "#F1F2FA"
  dark-secondary-text: "#B8C2D8"
  dark-tonal: "#293450"
  dark-danger: "#FFB4AB"
---

# Design System: SafetyHack

## Overview

The primary app is a native Android call workspace built from Android views. Its cool neutral canvas, blue actions, rounded surfaces and Russian labels distinguish connection, conversation and analysis. The main screen contains no WebView or server-address entry; the service address is supplied with the build.

The implementation source is [RtcActivity](android/app/src/main/java/kz/safetyhack/audiotester/RtcActivity.kt), with state transitions in [NativeRtcClient](android/app/src/main/java/kz/safetyhack/audiotester/NativeRtcClient.kt). This document records the implementation, not an approved screenshot. **Native visual validation is pending:** no Android device or emulator was attached for this review. Small-screen layout, font scaling, TalkBack, keyboard behavior and light/dark rendering still need device inspection.

The separate legacy browser surface remains implemented by [HTML](server/static/rtc/index.html), [CSS](server/static/rtc/rtc.css) and [JavaScript](server/static/rtc/client.js). Its forest/ivory design and earlier browser screenshot review apply only to that surface; they do not establish approval of the native app. The microphone diagnostic screen is a separate utility reached through Help, not the main calling interface.

## Colors

The frontmatter contains the native palette. Blue primary actions and pale blue secondary surfaces sit on cool neutral backgrounds. The dark palette follows the Android system night setting. Primary text, supporting text and primary-button text each have explicit light and dark assignments. The application uses `Theme.SafetyHack`, with [light theme resources](android/app/src/main/res/values/styles.xml) and [night theme resources](android/app/src/main/res/values-night/styles.xml) setting the matching accent, window background and system-bar appearance. The separate microphone diagnostic activity explicitly retains the platform light theme.

High-risk text and the end-call action use the danger color. Medium and low assessments currently use ordinary text; all assessments have textual labels. Color must not imply that an unavailable assessment is safe.

The legacy browser uses forest (`#183e35`), lime (`#d9efa8`), ivory (`#f5f6f0`), white surfaces, green ink (`#233d35`), muted text (`#5d6c63`) and pale borders (`#dce1d7`). Keep those values scoped to its stylesheet rather than treating them as native tokens.

## Typography

Native text uses Android system type; emphasized text explicitly requests `sans-serif-medium`. Sizes use scalable pixels (sp), while spacing and shapes use density-independent pixels (dp). They are described here rather than misrepresented as CSS pixels in portable tokens.

- Display headings: 32sp, emphasized, including the two-line call introduction and analysis heading.
- App title and section headings: 22sp, emphasized.
- Call status and risk: 20sp; risk is emphasized.
- Transcript and supporting section titles: 18sp. Transcript adds 6dp line spacing and supports text selection.
- Body and connection summary: 16sp; button labels and analysis status: 15sp; explanatory copy: 14sp.

Buttons preserve sentence case. Android provides the default line metrics. The legacy browser retains its Segoe UI system stack and CSS responsive type scale.

## Layout

The native screen is a vertical layout with a fixed app title/Help row, one scrolling content region and a bottom pair of navigation buttons: Conversation and Analysis. Each tab selects a pane within the same scroll region. The scroll content is horizontally centered and capped at 680dp width. Main content has 24dp horizontal padding, 12dp top padding and 24dp bottom padding. Window insets are applied to the root.

The conversation pane presents the introduction, setup, connected room summary, call surface, exit action and background-call instruction. Setup contains paired role buttons, room field, paired room actions, consent and connection action. Setup disappears after joining. The call surface has 24dp internal padding; paired controls use equal widths. No native tablet breakpoint or two-column arrangement is implemented.

The analysis pane presents a risk surface, processing status, transcript heading, transcript and a caution about model error. Incoming calls switch to the conversation tab, reset scrolling and request focus on the answer button; manual tab changes also reset scrolling. Long content uses the main scroll region rather than a separate bounded transcript scroller.

The legacy browser remains a two-column desktop workspace (maximum width 1360px), stacking at 760px; its transcript scrolls after 400px. These are browser-specific layout rules.

## Elevation & Depth

The native composition uses tonal contrast between the root background, call surface and analysis surface. No custom shadow or elevation is assigned by the activity. Buttons use Android ripple drawables; no custom entrance animation is implemented. Platform dialog and control rendering remain subject to the Android theme and require device inspection.

The legacy browser uses flat panels, borders and a dark call panel. Its small arrival animation is disabled under reduced-motion preferences.

## Shapes

Native buttons have rounded corners (18dp), the risk surface uses 24dp corners and the call surface uses 28dp corners. Buttons have at least 52dp height and 12dp horizontal / 10dp vertical padding. The room field and consent control have at least 56dp height. The room field keeps its native underline with a primary tint.

Legacy browser panels use 16px corners and controls use 8px corners. Do not copy those pixel measurements into native views.

## Components

- **Setup:** explicit role buttons, a single-line room field, new-code and share actions, a Google Gemini consent checkbox and a full-width connection button. The field exposes an inline format error. Connecting disables the role, room, consent and connection controls; action labels communicate progress.
- **Call surface:** a textual status precedes actions. Invite appears for the caller; answer and reject appear for an incoming receiver call. Mute and route controls appear during the call, and the end-call action uses danger text. Labels reflect mute and speaker state. The native foreground service keeps the room and call alive when the activity is hidden or recreated. Incoming calls use a system ringtone and CallStyle notification; full-screen display follows Android permissions. Explicit room exit ends the service. Process death still requires rejoining; push is not configured.
- **Connected summary:** room code and participant role replace setup while connected. It is plain primary-colored text rather than a separate card.
- **Analysis surface:** risk text uses the tonal background and includes its label, explanation and reasons. Processing status reports unavailable or incomplete analysis. The initial state contains no fabricated speech or assessment; low-risk wording explicitly disclaims a safety guarantee.
- **Navigation:** two equal-width rounded buttons sit on the surface-colored bottom row. Selected tabs and roles use primary fill with on-primary text; unselected ones use tonal fill. There is no navigation drawer.
- **Help:** an Android dialog explains the three-step flow and links to the microphone diagnostic activity.
- **Accessibility:** status, risk and analysis text use polite accessibility live regions; the room field has a content description; transcript text is selectable. Touch targets have explicit minimum heights. Keyboard focus, selected-state announcements, large-font wrapping and actual contrast remain to be verified on Android.

The legacy browser retains its own guide link, outlined secondary call actions, lime primary call actions, explicit incomplete-analysis alert, keyboard focus treatment and pressed mute state. These do not describe native widget behavior.

## Do's and Don'ts

- **Do** preserve Russian labels, explicit external-processing consent and truthful background-call instructions.
- **Do** use the native light/dark color pairs together and retain textual risk labels.
- **Do** retain scalable text, generous touch targets and space for long transcript and error content.
- **Do** inspect real Android screens before declaring visual readiness, including large fonts, dark mode and incoming calls while viewing analysis.
- **Don't** introduce server-address entry or a WebView into the native call workflow.
- **Don't** populate empty states with invented activity, speech or reassuring scores.
- **Don't** treat browser screenshots as validation of native layout, device calling or analysis accuracy.
