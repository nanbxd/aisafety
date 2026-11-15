import logging

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from tests.test_rtc import TOKEN, ROOM, ORIGIN, pair, accept, start


@pytest.fixture(autouse=True)
def environment(monkeypatch, caplog):
    monkeypatch.setenv("ASR_MODE", "diagnostic")
    monkeypatch.delenv("PUBLIC_BASE_URL", raising=False)
    caplog.set_level(logging.INFO, logger="uvicorn.error.safety")


@pytest.mark.parametrize("mode", ["text", "empty", "silent", "failure", "disabled"])
def test_audio_logs_without_content(mode, caplog):
    private_text = "PRIVATE_TRANSCRIPT_MARKER"
    def transcribe(pcm, language):
        if mode == "failure":
            raise RuntimeError("PRIVATE_EXCEPTION_MARKER")
        return "" if mode == "empty" else private_text

    with TestClient(create_app(token=TOKEN, transcriber=None if mode == "disabled" else transcribe)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["type"] == "ready"
            ws.send_bytes((b"\0\0" if mode == "silent" else b"\xff\x1f") * 4000)
            ws.send_bytes((b"\0\0" if mode == "silent" else b"\xff\x1f") * 4000)
            ws.send_json({"type": "stop"})
            while ws.receive_json()["type"] not in {"done", "error"}:
                pass
    logs = caplog.text
    for marker in [TOKEN, ROOM, ticket, cid, private_text, "PRIVATE_EXCEPTION_MARKER"]:
        assert marker not in logs
    for marker in ["ASR_SERVICE_READY", "RTC_AUDIO_READY", "RTC_PCM_FIRST", "RTC_AUDIO_CLOSING", "ASR_SLOT_RELEASED"]:
        assert marker in logs
    if mode in {"silent", "disabled"}:
        assert "ASR_WINDOW_SKIP" in logs
        assert "ASR_INFERENCE_START" not in logs
    elif mode == "failure":
        assert "ASR_INFERENCE_FAILED" in logs and "RuntimeError" in logs
    else:
        assert "ASR_INFERENCE_DONE" in logs
        assert ("ASR_RESULT_SENT" in logs) == (mode == "text")


def test_model_startup_logs(monkeypatch, caplog):
    import server.asr
    monkeypatch.setenv("ASR_MODE", "whisper")
    monkeypatch.setattr(server.asr, "WhisperTranscriber", lambda: lambda pcm, language: "")
    with TestClient(create_app(token=TOKEN)) as client:
        assert client.get("/health").json()["mode"] == "whisper"
    assert "ASR_MODEL_LOADING" in caplog.text
    assert "ASR_MODEL_READY" in caplog.text
