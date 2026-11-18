---
name: Android support calls and microphone tester
description: Russian native setup and diagnostics around a foreground-only WebRTC receiver.
colors:
  warm-background: "#f5f6f2"
  dark-text: "#172b29"
---

# Android design

## Overview

Russian native Android forms use system typography, a warm light background and dark text. The support-call setup wraps a trusted server's WebRTC page; the separate microphone tester remains a diagnostic utility. This document describes source code only: no rendered, device or accessibility-service verification has been performed.

## Colors

The warm background and dark text tokens apply to native labels and containers. Buttons, fields, dialogs and disabled states retain Android system styling; the loaded web page owns its own visual design.

## Typography

Native setup and diagnostic titles are 28sp; body labels and form buttons are 18sp. Diagnostic section/status headings are 20sp. The receiver's foreground notice is 16sp. Button labels use sentence case rather than all capitals.

## Layout

Setup and diagnostics are single-column, fill-viewport scrollable forms with 24dp horizontal, 20dp top and 32dp bottom padding. Labels have 12dp top and 8dp bottom spacing. Window insets add space for system bars, including Android edge-to-edge layouts. The receiver places a native notice and close control above a WebView that fills the remaining height.

## Components

- **Call setup:** “Звонки с поддержкой” precedes an explicit foreground-only notice: minimizing, rotating or pressing Back ends the call, and background incoming calls are unavailable. The HTTPS origin field has a visible “Доверенный сервер HTTPS” label linked through `labelFor`, a URL keyboard and inline validation. Only the origin is saved; room entry belongs to the server page.
- **Touch targets:** setup buttons have a 56dp minimum height; the origin field and receiver close button have a 48dp minimum. Diagnostic buttons are at least 56dp; source selection and consent are at least 48dp. Preserve targets of at least 48dp for added interactive controls; the existing diagnostic text fields do not explicitly set that minimum.
- **Receiver toolbar:** a persistent foreground notice and “Закрыть звонок и вернуться” button remain outside the WebView. Closing returns to setup with textual feedback. An origin-specific native microphone consent dialog uses explicit allow/cancel actions after Android permission has already been granted from the setup Open action. The WebView never requests system permission mid-call.
- **Diagnostics:** readiness, local sound level, source selection, Start, Stop, Replay and Delete precede optional network configuration. Source and network settings lock while recording/finalizing; unavailable actions use native disabled states. Risk includes a textual level, so meaning does not rely on color. Server output is selectable; diagnostic-mode copy explicitly states that speech recognition and risk assessment are unavailable.
- **Accessibility:** diagnostic status and setup feedback use polite live regions. The sound meter has a content description and adjacent numerical text. Native text uses scalable sp units, and forms scroll to accommodate content growth. Screen-reader order, enlarged text and device rendering remain unverified.

## Do's and Don'ts

- Do preserve Russian labels, visible instructions, native control states and the foreground-only warning.
- Do keep diagnostic recording separate from the active receiver session.
- Don't imply that the native wrapper supplies a detector or background calling; room setup and WebRTC interaction belong to the trusted page.
- Don't claim visual or device validation from source inspection alone.

