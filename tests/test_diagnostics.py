import logging
import pytest
from fastapi.testclient import TestClient
from server.app import create_app
from tests.fake_analysis import FakeAnalysis
from tests.test_rtc import TOKEN, ROOM, ORIGIN, pair, accept, start


@pytest.fixture(autouse=True)
def environment(monkeypatch, caplog):
    monkeypatch.setenv("ANALYSIS_MODE", "diagnostic")
    monkeypatch.delenv("PUBLIC_BASE_URL", raising=False)
    caplog.set_level(logging.INFO, logger="uvicorn.error.safety")


@pytest.mark.parametrize("failure", [False, True])
def test_audio_logs_without_content(failure, caplog):
    backend = FakeAnalysis(failure=RuntimeError("PRIVATE_EXCEPTION_MARKER") if failure else None)
    with TestClient(create_app(token=TOKEN, analysis_backend=backend)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["type"] == "ready"
            ws.send_bytes(b"\x00\x30" * 4000)
            if not failure:
                assert ws.receive_json()["type"] == "transcript"
                assert ws.receive_json()["type"] == "risk"
                ws.send_json({"type": "stop"})
            assert ws.receive_json()["type"] == ("error" if failure else "done")
    for marker in [TOKEN, ROOM, ticket, cid, "PRIVATE_TRANSCRIPT_MARKER", "PRIVATE_EXCEPTION_MARKER"]:
        assert marker not in caplog.text
    for marker in ["ANALYSIS_SERVICE_READY", "RTC_AUDIO_READY", "RTC_PCM_FIRST", "RTC_AUDIO_CLOSING", "ANALYSIS_SLOT_RELEASED"]:
        assert marker in caplog.text
