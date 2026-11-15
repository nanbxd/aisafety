import base64
import threading
import xml.etree.ElementTree as ET

import jwt
import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from twilio.request_validator import RequestValidator

from server.app import create_app
from server.telephony import MulawDecoder
from server import telephony

TOKEN = "test-token-at-least-sixteen"
AUTH = {"Authorization": "Bearer " + TOKEN}
BASE = "https://voice.example.com"
ACCOUNT = "AC" + "1" * 32
CALL = "CA" + "2" * 32
STREAM = "MZ" + "3" * 32
SECRET = "test-secret-at-least-thirty-two-characters"


@pytest.fixture(autouse=True)
def config(monkeypatch):
    for key, value in {"ACCOUNT_SID": ACCOUNT, "API_KEY_SID": "SK" + "4" * 32,
                       "API_KEY_SECRET": SECRET, "AUTH_TOKEN": "auth-token",
                       "CLIENT_ID": "senior", "PUSH_CREDENTIAL_SID": ""}.items():
        monkeypatch.setenv("TWILIO_" + key, value)
    monkeypatch.setenv("PUBLIC_BASE_URL", BASE)
    monkeypatch.setenv("ASR_MODE", "diagnostic")


def signature(path, data=None, base=BASE):
    return {"X-Twilio-Signature": RequestValidator("auth-token").compute_signature(base + path, data or {})}


def post(client, path, **fields):
    data = {"AccountSid": ACCOUNT, "CallSid": CALL, **fields}
    return client.post(path, data=data, headers=signature(path, data))


def start(ws, call=CALL, stream=STREAM):
    ws.send_json({"event": "connected", "protocol": "Call", "version": "1.0.0"})
    ws.send_json({"event": "start", "streamSid": stream, "start": {
        "accountSid": ACCOUNT, "callSid": call, "streamSid": stream,
        "tracks": ["inbound"], "mediaFormat": {"encoding": "audio/x-mulaw", "sampleRate": 8000, "channels": 1}}})


def audio(ws, stream=STREAM, payload=None):
    ws.send_json({"event": "media", "streamSid": stream, "media": {"track": "inbound",
                 "payload": payload or base64.b64encode(bytes([0, 128]) * 2000).decode()}})


def stop(ws, call=CALL, stream=STREAM):
    ws.send_json({"event": "stop", "streamSid": stream, "stop": {"callSid": call, "accountSid": ACCOUNT}})


def test_auth_and_missing_configuration(monkeypatch):
    with TestClient(create_app(token=TOKEN)) as client:
        for path in ("/api/voice/token", "/api/voice/status"):
            assert client.get(path).status_code == 401
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/ws/events"):
                pass
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/twilio/media"):
                pass
        monkeypatch.delenv("TWILIO_AUTH_TOKEN")
        assert client.get("/api/voice/status", headers=AUTH).json() == {"configured": False, "push_configured": False}
        response = client.get("/api/voice/token", headers=AUTH)
        assert response.status_code == 503
        assert "TWILIO_AUTH_TOKEN" in response.text
        assert post(client, "/twilio/voice").status_code == 503


def test_jwt_fixed_identity_incoming_only_and_push(monkeypatch):
    monkeypatch.setenv("TWILIO_PUSH_CREDENTIAL_SID", "CR" + "5" * 32)
    with TestClient(create_app(token=TOKEN)) as client:
        http_response = client.get("/api/voice/token?identity=attacker", headers=AUTH)
        assert http_response.headers["cache-control"] == "no-store"
        response = http_response.json()
        assert response["identity"] == "senior"
        payload = jwt.decode(response["token"], SECRET, algorithms=["HS256"], options={"verify_aud": False})
        assert payload["sub"] == ACCOUNT
        assert payload["iss"] == "SK" + "4" * 32
        assert payload["exp"] - payload["nbf"] == response["expires_in"] == 3600
        assert payload["grants"] == {"identity": "senior", "voice": {
            "incoming": {"allow": True}, "push_credential_sid": "CR" + "5" * 32}}
        assert client.get("/api/voice/status", headers=AUTH).json() == {"configured": True, "push_configured": True}


def test_signatures_canonical_url_and_twiml():
    with TestClient(create_app(token=TOKEN)) as client:
        data = {"AccountSid": ACCOUNT, "CallSid": CALL}
        assert client.post("/twilio/voice", data=data).status_code == 403
        headers = {**signature("/twilio/voice", data, "https://attacker.example"), "Host": "attacker.example",
                   "X-Forwarded-Host": "attacker.example", "X-Forwarded-Proto": "https"}
        assert client.post("/twilio/voice", data=data, headers=headers).status_code == 403
        headers.update(signature("/twilio/voice", data))
        result = client.post("/twilio/voice", data=data, headers=headers)
        assert result.status_code == 200
        root = ET.fromstring(result.text)
        assert [child.tag for child in root] == ["Say", "Start", "Dial"]
        assert "анализироваться" in root.find("Say").text
        assert root.find("Start/Stream").attrib == {"url": "wss://voice.example.com/twilio/media",
            "track": "inbound_track", "statusCallback": BASE + "/twilio/stream-status", "statusCallbackMethod": "POST"}
        assert root.find("Dial/Client/Identity").text == "senior"
        assert root.find("Dial/Client/Parameter").attrib == {"name": "parentCallSid", "value": CALL}
        assert root.find("Dial").get("action") == BASE + "/twilio/status"
        assert client.post("/twilio/status", data=data).status_code == 403
        assert client.post("/twilio/stream-status", data=data).status_code == 403
        assert post(client, "/twilio/voice", AccountSid="AC" + "0" * 32).status_code == 403


def test_codec_known_g711_values_and_chunk_continuity():
    raw = bytes([255, 127, 0, 128, 254, 126])
    result = np.frombuffer(MulawDecoder().decode(raw), dtype="<i2")
    assert result[1::2].tolist() == [0, 0, -32124, 32124, 8, -8]
    decoder = MulawDecoder()
    assert decoder.decode(raw[:3]) + decoder.decode(raw[3:]) == result.tobytes()
    assert MulawDecoder().decode(bytes([255]) * 8000) == bytes(32000)


def test_media_transcription_status_and_risk_isolation():
    received = []
    def transcribe(pcm, language):
        received.append((len(pcm), language))
        return "Продиктуйте код из СМС" if len(received) == 1 else "Добрый день"
    with TestClient(create_app(token=TOKEN, transcriber=transcribe)) as client:
        with client.websocket_connect("/ws/events", headers=AUTH) as events:
            assert events.receive_json()["type"] == "ready"
            for call, stream, risk in ((CALL, STREAM, "high"), ("CA" + "6" * 32, "MZ" + "7" * 32, "low")):
                assert post(client, "/twilio/voice", CallSid=call).status_code == 200
                assert events.receive_json() == {"type": "call", "call_sid": call, "status": "incoming"}
                with client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
                    start(ws, call, stream)
                    assert events.receive_json()["status"] == "stream-started"
                    audio(ws, stream)
                    stop(ws, call, stream)
                    transcript = events.receive_json()
                    assert transcript["type"] == "transcript" and transcript["call_sid"] == call
                    assert transcript["stream_sid"] == stream
                    assert events.receive_json()["level"] == risk
                    assert events.receive_json()["status"] == "stream-stopped"
                    with pytest.raises(WebSocketDisconnect):
                        ws.receive_json()
                assert post(client, "/twilio/status", CallSid="CA" + "8" * 32, ParentCallSid=call,
                            CallStatus="completed").status_code == 200
                event = events.receive_json()
                assert event["call_sid"] == call and event["status"] == "completed"
            assert received == [(16000, "ru"), (16000, "ru")]
            assert post(client, "/twilio/status", CallSid="CA" + "9" * 32, CallStatus="completed").status_code == 404
            assert post(client, "/twilio/stream-status", StreamSid="MZ" + "0" * 32, StreamEvent="stream-stopped").status_code == 409


@pytest.mark.parametrize("failure", ["unknown", "codec", "mismatch", "base64", "no_asr", "model"])
def test_media_errors_not_success(failure):
    def transcribe(pcm, language):
        if failure == "model":
            raise RuntimeError("secret model path")
        return "hello"
    with TestClient(create_app(token=TOKEN, transcriber=None if failure == "no_asr" else transcribe)) as client:
        post(client, "/twilio/voice")
        with client.websocket_connect("/ws/events", headers=AUTH) as events, client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
            events.receive_json()
            if failure == "codec":
                ws.send_json({"event": "start", "start": {"accountSid": ACCOUNT, "callSid": CALL,
                              "streamSid": STREAM, "mediaFormat": {}}})
            else:
                start(ws, "CA" + "9" * 32 if failure == "unknown" else CALL)
                if failure in ("mismatch", "base64", "model"):
                    audio(ws, "MZ" + "9" * 32 if failure == "mismatch" else STREAM,
                          "%%%" if failure == "base64" else None)
                if failure == "model":
                    stop(ws)
            event = events.receive_json()
            if event["type"] == "call":
                event = events.receive_json()
            assert event["type"] == "error"
            assert "secret" not in event["message"]
            with pytest.raises(WebSocketDisconnect):
                ws.receive_json()


def test_media_overload_is_explicit_and_keeps_model_slot(monkeypatch):
    monkeypatch.setattr(telephony, "MEDIA_QUEUE", 1)
    entered, release = threading.Event(), threading.Event()
    def slow(pcm, language):
        entered.set()
        assert release.wait(10)
        return "hello"
    with TestClient(create_app(token=TOKEN, transcriber=slow)) as client:
        post(client, "/twilio/voice")
        with client.websocket_connect("/ws/events", headers=AUTH) as events, client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
            events.receive_json()
            try:
                start(ws)
                for _ in range(8):
                    audio(ws)
                assert entered.wait(5)
                for _ in range(12):
                    audio(ws)
                assert events.receive_json()["status"] == "stream-started"
                event = events.receive_json()
                assert event["type"] == "error" and "overflow" in event["message"]
                assert client.get("/health").json()["busy"] is True
            finally:
                release.set()


def test_subscriber_overflow_is_explicit(monkeypatch):
    monkeypatch.setattr(telephony, "EVENT_BUFFER", 1)
    with TestClient(create_app(token=TOKEN, transcriber=lambda pcm, language: "hello")) as client:
        post(client, "/twilio/voice")
        with client.websocket_connect("/ws/events", headers=AUTH) as events:
            events.receive_json()
            with client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
                start(ws)
                events.receive_json()
                audio(ws)
                stop(ws)
                event = events.receive_json()
                assert event["type"] == "error" and "overflow" in event["message"]
                with pytest.raises(WebSocketDisconnect):
                    ws.receive_json()


@pytest.mark.parametrize("base,suffix", [(BASE, ""), (BASE, "/"), ("wss://voice.example.com", "")])
def test_media_handshake_configured_variants(base, suffix):
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/twilio/media", headers=signature("/twilio/media" + suffix, base=base)) as ws:
            ws.send_json({"event": "stop"})
            with pytest.raises(WebSocketDisconnect):
                ws.receive_json()
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/twilio/media", headers={
                **signature("/twilio/media", base="https://attacker.example"), "host": "attacker.example"}):
                pass


@pytest.mark.parametrize("key,value", [("TWILIO_ACCOUNT_SID", "wrong"), ("TWILIO_API_KEY_SID", "AC" + "1" * 32),
    ("TWILIO_PUSH_CREDENTIAL_SID", "wrong"), ("PUBLIC_BASE_URL", BASE + "/prefix"),
    ("PUBLIC_BASE_URL", "http://voice.example.com"), ("PUBLIC_BASE_URL", BASE + "?x=1"),
    ("PUBLIC_BASE_URL", "https://[broken"), ("PUBLIC_BASE_URL", BASE + ":8443")])
def test_invalid_configuration_is_unavailable(monkeypatch, key, value):
    monkeypatch.setenv(key, value)
    with TestClient(create_app(token=TOKEN)) as client:
        assert client.get("/api/voice/status", headers=AUTH).json()["configured"] is False
        assert client.get("/api/voice/token", headers=AUTH).status_code == 503


@pytest.mark.parametrize("raw", [bytes([255]) * 4000, bytes([0]) * 3999])
def test_silent_or_short_final_audio_never_transcribed(raw):
    received = []
    with TestClient(create_app(token=TOKEN, transcriber=lambda *args: received.append(args))) as client:
        post(client, "/twilio/voice")
        with client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
            start(ws)
            audio(ws, payload=base64.b64encode(raw).decode())
            stop(ws)
            with pytest.raises(WebSocketDisconnect) as closed:
                ws.receive_json()
            assert closed.value.code == 1000
        assert received == []


def test_probe_busy_failure_persists_in_reconnect_snapshot():
    with TestClient(create_app(token=TOKEN, transcriber=lambda *args: "hello")) as client:
        post(client, "/twilio/voice")
        with client.websocket_connect("/ws/audio", headers=AUTH) as probe:
            probe.send_json({"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
            assert probe.receive_json()["type"] == "ready"
            with client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
                start(ws)
                with pytest.raises(WebSocketDisconnect) as closed:
                    ws.receive_json()
                assert closed.value.code == 1011
            assert client.get("/health").json()["busy"] is True
            other = "CA" + "a" * 32
            post(client, "/twilio/voice", CallSid=other)
            with client.websocket_connect("/ws/events", headers=AUTH) as events:
                ready = events.receive_json()
                assert ready["asr_configured"] is True
                snapshots = {call["call_sid"]: call for call in ready["calls"]}
                assert snapshots[CALL]["analysis_status"] == "error"
                assert "busy" in snapshots[CALL]["message"]
                assert snapshots[other]["analysis_status"] == "pending"
                assert snapshots[other]["message"] == ""
            probe.send_json({"type": "stop"})
            assert probe.receive_json()["type"] == "done"


def test_active_twilio_rejects_probe_without_losing_slot():
    with TestClient(create_app(token=TOKEN, transcriber=lambda *args: "hello")) as client:
        post(client, "/twilio/voice")
        with client.websocket_connect("/ws/events", headers=AUTH) as events, client.websocket_connect("/twilio/media", headers=signature("/twilio/media")) as ws:
            events.receive_json()
            start(ws)
            assert events.receive_json()["status"] == "stream-started"
            with client.websocket_connect("/ws/audio", headers=AUTH) as probe:
                assert probe.receive_json()["type"] == "error"
            assert client.get("/health").json()["busy"] is True
            stop(ws)
            assert events.receive_json()["status"] == "stream-stopped"


def test_dotenv_loaded_only_at_deployment_startup(monkeypatch):
    import importlib
    app_module = importlib.import_module("server.app")
    loaded = []
    monkeypatch.setattr(app_module, "load_dotenv", lambda **kwargs: loaded.append(kwargs))
    with TestClient(create_app(token=TOKEN)):
        assert loaded == []
    deployed = create_app(token=TOKEN, load_environment=True)
    assert loaded == []
    with TestClient(deployed):
        assert loaded == [{"override": False}]
