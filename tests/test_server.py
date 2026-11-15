import math
import struct

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.app import create_app, level
from server.risk import RiskEngine

TOKEN = "test-token-at-least-sixteen"
START = {"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le", "language": "ru"}
HEADERS = {"authorization": "Bearer " + TOKEN}


def tone(seconds=1):
    return b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 16000))) for i in range(int(16000 * seconds)))


def test_level_silence_and_signal():
    assert level(bytes(8000)) == -120
    assert -15 < level(tone()) < -8


def test_authentication():
    with TestClient(create_app(token=TOKEN)) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/ws/audio"):
                pass


def test_diagnostic_never_invents_transcript():
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/audio", headers=HEADERS) as ws:
            ws.send_json(START)
            assert ws.receive_json() == {"type": "ready", "mode": "diagnostic"}
            ws.send_bytes(bytes(8000))
            assert ws.receive_json()["silent"] is True
            ws.send_json({"type": "stop"})
            assert ws.receive_json()["type"] == "done"


@pytest.mark.parametrize("bad", [b"x", bytes(16002), b""])
def test_bad_pcm(bad):
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/audio", headers=HEADERS) as ws:
            ws.send_json(START)
            ws.receive_json()
            ws.send_bytes(bad)
            assert ws.receive_json()["type"] == "error"


def test_invalid_start():
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/audio", headers=HEADERS) as ws:
            ws.send_json({**START, "sample_rate": 48000})
            assert ws.receive_json()["type"] == "error"


def test_busy_and_disconnect_release():
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/audio", headers=HEADERS) as first:
            first.send_json(START)
            first.receive_json()
            with client.websocket_connect("/ws/audio", headers=HEADERS) as second:
                assert second.receive_json()["type"] == "error"
            first.send_json({"type": "stop"})
            assert first.receive_json()["type"] == "done"
        with client.websocket_connect("/ws/audio", headers=HEADERS) as third:
            third.send_json(START)
            assert third.receive_json()["type"] == "ready"
            third.send_json({"type": "stop"})
            third.receive_json()


def test_risk_examples_and_no_bank_keyword_false_alarm():
    assert RiskEngine().assess("Добрый день, банк закрывается в шесть")["level"] == "low"
    assert RiskEngine().assess("Переведите на безопасный счет")["level"] == "high"
    assert RiskEngine().assess("Кодты айтыңыз")["level"] == "high"
    engine = RiskEngine()
    engine.assess("Срочно, прямо сейчас")
    assert engine.assess("переведите деньги")["level"] == "high"
