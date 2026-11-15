"""Offline Twilio-protocol smoke with real Whisper; never contacts Twilio.

Usage: python -m tests.twilio_asr_smoke .tools/synthetic-ru.wav
Input is a short, consented/synthetic 16-kHz mono PCM16 WAV (0.5 to 9 seconds).
This is NOT evidence of a real phone call, FCM delivery or a valid Twilio account.
"""
import base64
import json
import sys
import time
import wave
from unittest.mock import patch

import numpy as np
from fastapi.testclient import TestClient
from twilio.request_validator import RequestValidator

from server.app import create_app
from server.asr import WhisperTranscriber


def main(path):
    with wave.open(path, "rb") as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (16000, 1, 2):
            raise ValueError("Expected mono PCM16 16000Hz WAV")
        if not 8000 <= wav.getnframes() <= 144000:
            raise ValueError("Use a short 0.5 to 9 second synthetic WAV")
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")[::2].astype(np.int32)
    # G.711 nearest-code encoding for a bounded offline fixture; no audioop dependency.
    encoded = np.arange(256, dtype=np.int32)
    complemented = encoded ^ 255
    magnitude = ((complemented & 15) * 8 + 132) << ((complemented >> 4) & 7)
    table = np.where(complemented & 128, 132 - magnitude, magnitude - 132)
    payload = b"".join(np.abs(chunk[:, None] - table).argmin(axis=1).astype(np.uint8).tobytes()
                       for chunk in np.array_split(samples, max(1, len(samples) // 1000)))
    account = "AC" + "1" * 32
    call = "CA" + "2" * 32
    stream = "MZ" + "3" * 32
    base = "https://offline.example.com"
    token = "offline-test-token-not-a-real-secret"
    config = {"TWILIO_ACCOUNT_SID": account, "TWILIO_AUTH_TOKEN": "offline-auth",
              "TWILIO_API_KEY_SID": "SK" + "4" * 32, "TWILIO_API_KEY_SECRET": "offline-key",
              "TWILIO_PUSH_CREDENTIAL_SID": "", "TWILIO_CLIENT_ID": "senior",
              "PUBLIC_BASE_URL": base, "ASR_MODE": "whisper"}
    # Model is real; only the provider/account and signed transport are fixtures.
    transcriber = WhisperTranscriber()
    signed = RequestValidator("offline-auth")
    began = time.monotonic()
    with patch.dict("os.environ", config), TestClient(create_app(transcriber=transcriber, token=token)) as client:
        with client.websocket_connect("/ws/events", headers={"Authorization": "Bearer " + token}) as events:
            events.receive_json()
            form = {"AccountSid": account, "CallSid": call}
            response = client.post("/twilio/voice", data=form, headers={
                "X-Twilio-Signature": signed.compute_signature(base + "/twilio/voice", form)})
            response.raise_for_status()
            with client.websocket_connect("/twilio/media", headers={
                    "X-Twilio-Signature": signed.compute_signature(base + "/twilio/media", {})}) as ws:
                ws.send_json({"event": "start", "streamSid": stream, "start": {
                    "accountSid": account, "callSid": call, "streamSid": stream,
                    "tracks": ["inbound"], "mediaFormat": {
                        "encoding": "audio/x-mulaw", "sampleRate": 8000, "channels": 1}}})
                for pos in range(0, len(payload), 160):
                    ws.send_json({"event": "media", "streamSid": stream, "media": {
                        "track": "inbound", "payload": base64.b64encode(payload[pos:pos + 160]).decode()}})
                ws.send_json({"event": "stop", "streamSid": stream, "stop": {"callSid": call, "accountSid": account}})
                found = []
                while True:
                    event = events.receive_json()
                    print(json.dumps(event, ensure_ascii=False), flush=True)
                    found.append(event)
                    if event["type"] == "error":
                        raise RuntimeError("Media smoke failed; see error event")
                    if event.get("status") == "stream-stopped":
                        break
                if not any(e["type"] == "transcript" for e in found):
                    raise RuntimeError("No transcript returned")
    print(f"Offline real-ASR smoke completed in {time.monotonic() - began:.2f}s (not live-call latency)")


if __name__ == "__main__":
    main(sys.argv[1])
