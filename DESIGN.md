---
name: SafetyHack
description: Russian voice-call workspace with visible consent and speech analysis states.
colors:
  forest: "#183e35"
  lime: "#d9efa8"
  ivory: "#f5f6f0"
  surface: "#ffffff"
  ink: "#233d35"
  muted: "#5d6c63"
  line: "#dce1d7"
typography:
  display:
    fontFamily: "Segoe UI, sans-serif"
    fontSize: "clamp(30px, 3.2vw, 46px)"
    fontWeight: 500
    lineHeight: 1.14
    letterSpacing: "-1.5px"
  headline:
    fontFamily: "Segoe UI, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: "Segoe UI, sans-serif"
    fontSize: "16px"
    fontWeight: 400
rounded:
  control: "8px"
  panel: "16px"
---

# Design System: SafetyHack

## Overview

The implemented interface uses a quiet ivory shell, white working surfaces and a forest-green call panel. Lime accents identify call actions. Russian copy, generous spacing and plain status text keep connection, calling and analysis understandable without presenting the service as a safety guarantee.

Source of truth: [page structure](server/static/rtc/index.html), [styles](server/static/rtc/rtc.css), [interaction states](server/static/rtc/client.js) and [product constraints](PRODUCT.md).

The [desktop](.impeccable/review/desktop.png) and [mobile](.impeccable/review/mobile.png) screenshots were verified; independent visual review returned a usability disposition of **ship**. Local browser integration passed with synthetic audio outside the sandbox. This visual review does not establish device compatibility, voice-call reliability or analysis accuracy.

## Colors

- **Forest:** call-panel background, primary connection button and core control accents.
- **Lime:** primary actions inside the dark call panel, its telephone icon and text selection.
- **Ivory / white:** page shell and working panels respectively.
- **Ink / muted:** primary text and explanatory copy. Pale borders divide regions without heavy outlines.
- **Semantic states:** amber warning surfaces identify incomplete analysis; medium risk uses amber and high risk uses red. Risk always has a textual label. The initial state says no assessment is available.

## Typography

Use the system Segoe UI stack throughout; there are no external font dependencies. The display heading uses the frontmatter scale, section headings use the headline scale, and smaller supporting text ranges from 11–15px. Paragraphs have a 1.6 line height; transcript text uses 15px with a 1.8 line height. At the mobile breakpoint the display heading is 34px and section headings are 21px.

## Layout

The shell has a maximum width of 1360px and desktop padding of 48px. The workspace has two columns in a 1:1.18 ratio, separated by 26px: setup and call controls on the left, analysis on the right. At 1050px or below the columns become equal, spacing tightens and page padding becomes 24px horizontally.

At 760px or below, the workspace stacks setup, call controls and analysis in reading order, with 20px horizontal page padding. The three-step guide also becomes a vertical list; the topbar tagline disappears and the footer wraps with bottom safe-area padding. Long content wraps within panels, while the transcript scrolls after 400px.

## Elevation & Depth

Surfaces have no shadows. Depth comes from white panels against ivory, thin borders, and the dark call panel. Preserve this flat treatment. Desktop workspace arrival uses a small 8px translation over 0.5 seconds; reduced-motion preferences disable animation, transitions and smooth scrolling.

## Shapes

Panels use the larger rounded token; form controls use the smaller token. Risk and connection summaries use intermediate 10–12px corners. The demo badge is pill-shaped; the analysis service tag is a small outlined rectangle. Inline outline icons support call and risk sections, and the empty transcript includes a decorative waveform.

## Components

- **Setup:** native labeled role selector, room field, room-generation action, required consent checkbox, expandable privacy explanation and full-width connection button. Consent explains external voice processing; it stays explicit.
- **Call panel:** state text followed by wrapping actions. Active primary actions use lime, secondary actions use an outline and pale text, and hang-up uses a pale red treatment. Unavailable controls are disabled at half opacity. Incoming-call and playback-recovery actions appear only when relevant.
- **Connected summary:** replaces setup while joined and displays room and role. Focus moves to the call heading when joining and back to the page heading when leaving.
- **Analysis:** service label, live status, incomplete-analysis alert, risk assessment and reasons, followed by the transcript. The empty waveform disappears when transcription arrives. Unavailable and incomplete states remain explicit; low risk never guarantees safety.
- **Controls and accessibility:** controls have a minimum height of 48px, with a 52px connection button. Keyboard focus uses a 3px green outline offset by 4px. Status regions use polite live announcements; incomplete analysis uses an alert. The transcript is keyboard-focusable and mute exposes its pressed state.
- **Navigation:** linked wordmark and an in-page guide link; no separate navigation drawer or application menu.

## Do's and Don'ts

- **Do** preserve Russian labels, explicit consent and visible foreground-call instructions.
- **Do** retain the white analysis surface, forest call panel and restrained lime action accents.
- **Do** keep disabled, unavailable, incomplete and assessed states visibly distinct with text.
- **Do** preserve keyboard focus, native form semantics and reduced-motion behavior.
- **Don't** populate empty states with invented speech, reassuring scores or fabricated live activity.
- **Don't** describe visual approval as proof of real-device calling or fraud-detection accuracy.
