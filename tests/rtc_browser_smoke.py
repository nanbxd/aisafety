"""Opt-in real Chromium/WebRTC smoke with synthetic microphones and fake analysis.

Not a handset, NAT/TURN, human speech, or live Gemini accuracy test.
Requires optional playwright package and a locally installed Chromium browser.
"""
import argparse
import asyncio
import os
from pathlib import Path
import socket
import threading
import time

import numpy as np
import uvicorn

from server.app import create_app

ROOT = Path(__file__).resolve().parents[1]

INIT = r"""
window.__testTracks = [];
window.__testPeers = [];
window.__testSockets = [];
const OriginalPeer = window.RTCPeerConnection;
window.RTCPeerConnection = class extends OriginalPeer {
  constructor(...args) { super(...args); window.__testPeers.push(this); }
};
const OriginalSocket = window.WebSocket;
window.WebSocket = class extends OriginalSocket {
  constructor(...args) { super(...args); window.__testSockets.push(this); }
};
navigator.mediaDevices.getUserMedia = async () => {
  const ctx = new AudioContext();
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  const dest = ctx.createMediaStreamDestination();
  osc.frequency.value = new URLSearchParams(location.search).get('role') === 'caller' ? 440 : 880;
  gain.gain.value = 0.25;
  osc.connect(gain); gain.connect(dest); osc.start();
  await ctx.resume();
  for (const track of dest.stream.getTracks()) {
    const stop = track.stop.bind(track);
    track.stop = () => { stop(); void ctx.close(); };
    window.__testTracks.push(track);
  }
  return dest.stream;
};
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", default="C:/Program Files/Google/Chrome/Application/chrome.exe")
    parser.add_argument("--serve-only", action="store_true", help="Run isolated synthetic analysis fixture for an external browser runner")
    parser.add_argument("--slow-first-asr", action="store_true", help="Delay first synthetic transport cleanup to exercise busy redial (legacy flag name)")
    args = parser.parse_args()

    os.environ.pop("PUBLIC_BASE_URL", None)
    os.environ["RTC_ICE_SERVERS_JSON"] = "[]"  # Local peers; no external STUN requests.
    os.environ["ANALYSIS_MODE"] = "diagnostic"
    evidence = {"frequencies": [], "fail_asr": False, "first": True}

    def check_pcm(pcm):
        if evidence["fail_asr"]:
            raise RuntimeError("synthetic ASR failure")
        samples = np.frombuffer(pcm, dtype="<i2").astype(float)
        assert np.sqrt(np.mean(samples ** 2)) > 100, "Remote audio is silent"
        spectrum = np.abs(np.fft.rfft(samples))
        hz = np.argmax(spectrum) * 16000 / len(samples)
        evidence["frequencies"].append(round(hz, 1))
        assert 420 < hz < 460, f"Expected caller 440Hz, got {hz}; receiver is 880Hz"
        return "Переведите деньги на безопасный счёт."

    class SyntheticAnalysis:
        async def run(self, queue, emit, ready, language):
            ready.set_result(None)
            buffer = bytearray()
            try:
                while True:
                    pcm = await queue.get()
                    if pcm is None:
                        return
                    buffer.extend(pcm)
                    if len(buffer) < 32000:
                        continue
                    text = check_pcm(bytes(buffer))
                    buffer.clear()
                    await emit({"type": "transcript", "text": text})
                    await emit({"type": "risk", "level": "high",
                                "reasons": ["Synthetic safe-account request"],
                                "message": "Синтетическое предупреждение для проверки интерфейса."})
            finally:
                # Delayed synthetic transport cleanup exercises bounded busy retry.
                # Production Gemini cancellation does not run local inference.
                if evidence["first"]:
                    evidence["first"] = False
                    if args.slow_first_asr:
                        await asyncio.sleep(3)

    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    app = create_app(analysis_backend=SyntheticAnalysis())
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error", access_log=False, ws_max_size=70000))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started:
        if time.monotonic() > deadline:
            raise RuntimeError("Test server failed to start")
        time.sleep(0.05)
    base = f"http://localhost:{port}"
    if args.serve_only:
        print(f"SYNTHETIC_FIXTURE_URL={base}", flush=True)
        try:
            while thread.is_alive():
                time.sleep(1)
        except KeyboardInterrupt:
            pass
        finally:
            server.should_exit = True
            thread.join(timeout=10)
            print("Observed synthetic caller frequencies:", evidence["frequencies"], flush=True)
        return
    from playwright.sync_api import sync_playwright, expect
    errors = []
    shots = ROOT / ".impeccable" / "review"
    shots.mkdir(parents=True, exist_ok=True)
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=args.browser, headless=True, args=["--autoplay-policy=no-user-gesture-required"])
            contexts = [browser.new_context(viewport={"width": 1440, "height": 1000}), browser.new_context(viewport={"width": 390, "height": 844})]
            pages = []
            for context, role in zip(contexts, ["caller", "receiver"]):
                context.add_init_script(INIT)
                page = context.new_page()
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(f"{base}/rtc?role={role}")
                pages.append(page)
            caller, receiver = pages
            # First and second joiner states, fixed room and explicit consent.
            for page in [receiver, caller]:
                page.locator("#room").fill("synthetic-room-123")
                page.locator("#consent").check()
                page.locator("#connect").click()
            expect(caller.locator("#invite")).to_be_enabled(timeout=15000)
            # Reject then reuse the existing signaling sessions.
            caller.locator("#invite").click()
            expect(receiver.locator("#accept")).to_be_visible(timeout=10000)
            receiver.locator("#reject").click()
            expect(caller.locator("#invite")).to_be_enabled(timeout=10000)
            caller.locator("#invite").click()
            receiver.locator("#accept").click()
            for page in pages:
                expect(page.locator("#call-state")).to_have_text("Разговор идёт", timeout=35000)
            expect(receiver.locator("#transcript")).to_contain_text("безопасный", timeout=25000)
            expect(receiver.locator("#risk")).to_contain_text("тревожные", timeout=5000)
            assert evidence["frequencies"], "No PCM reached ASR"
            caller.locator("#mute").click()
            expect(caller.locator("#mute")).to_have_attribute("aria-pressed", "true")
            caller.locator("#mute").click()
            expect(caller.locator("#mute")).to_have_attribute("aria-pressed", "false")
            for page, name in zip(pages, ["desktop", "mobile"]):
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), "Horizontal overflow"
                page.screenshot(path=str(shots / f"{name}.png"), full_page=True)
            # ASR failure must not terminate the functioning P2P call.
            evidence["fail_asr"] = True
            expect(receiver.locator("#incomplete")).to_be_visible(timeout=20000)
            expect(receiver.locator("#call-state")).to_have_text("Разговор идёт")
            receiver.locator("#hangup").click()
            expect(caller.locator("#invite")).to_be_enabled(timeout=10000)
            evidence["fail_asr"] = False
            # Second accepted call has fresh risk/analysis state.
            caller.locator("#invite").click()
            receiver.locator("#accept").click()
            expect(receiver.locator("#incomplete")).to_be_hidden()
            expect(receiver.locator("#transcript")).to_contain_text("безопасный", timeout=25000)
            # Android hook closes peer, sockets and tracks synchronously.
            receiver.evaluate("window.safetyRtcStop()")
            assert receiver.evaluate("window.__testTracks.every(t => t.readyState === 'ended')")
            assert receiver.evaluate("window.__testPeers.every(p => p.connectionState === 'closed')")
            expect(caller.locator("#call-state")).to_contain_text("отключился", timeout=10000)
            for page in pages:
                page.evaluate("window.safetyRtcStop()")
                assert page.evaluate("window.__testTracks.every(t => t.readyState === 'ended')")
            assert not errors, errors
            browser.close()
        print("PASS: real local P2P, caller-only PCM440Hz, synthetic ASR/risk, reject/reuse, mute, ASR failure isolation, second call, cleanup")
        print("Observed caller frequencies:", evidence["frequencies"])
        print("Screenshots:", shots)
    finally:
        server.should_exit = True
        thread.join(timeout=10)


if __name__ == "__main__":
    main()
