import asyncio
import contextlib
from types import SimpleNamespace as NS

import pytest
from fastapi.testclient import TestClient
from google.genai import types

from server.app import create_app
from server.asr import AudioAnalysis, AnalysisError, GeminiLive, DEFAULT_MODEL, validate_report
from tests.fake_analysis import FakeAnalysis
from tests.test_rtc import ORIGIN, ROOM, pair, accept, start

REPORT = {"level": "high", "reasons": ["Code requested"], "message": "Do not share codes."}


@pytest.fixture(autouse=True)
def environment(monkeypatch):
    monkeypatch.setenv("ANALYSIS_MODE", "diagnostic")
    monkeypatch.delenv("PUBLIC_BASE_URL", raising=False)


class MockSession:
    def __init__(self, failure=None):
        self.incoming = asyncio.Queue()
        self.sent = []
        self.replies = []
        self.closed = False
        self.failure = failure

    async def send_realtime_input(self, **kwargs):
        self.sent.append(kwargs)
        if "audio_stream_end" in kwargs:
            await self.incoming.put(types.LiveServerMessage(server_content=types.LiveServerContent(turn_complete=True)))
        elif self.failure:
            await self.incoming.put(self.failure)

    async def send_tool_response(self, **kwargs):
        self.replies.extend(kwargs["function_responses"])

    async def receive(self):
        while True:
            event = await self.incoming.get()
            if isinstance(event, Exception):
                raise event
            yield event
            if event.server_content and event.server_content.turn_complete:
                return


class MockClient:
    def __init__(self, session, connect_failure=None, close_failure=False):
        self.session = session
        self.connect_failure = connect_failure
        self.close_failure = close_failure
        self.connections = []
        self.closed = False
        self.aio = NS(live=NS(connect=self.connect), aclose=self.aclose)

    @contextlib.asynccontextmanager
    async def connect(self, **kwargs):
        self.connections.append(kwargs)
        if self.connect_failure:
            raise self.connect_failure
        try:
            yield self.session
        finally:
            self.session.closed = True

    async def aclose(self):
        self.closed = True
        if self.close_failure:
            raise RuntimeError("PRIVATE_CLOSE_EXCEPTION")


def adapter(client):
    return GeminiLive(client_factory=lambda **kwargs: client)


def test_sdk_streaming_tool_transcript_audio_discard_and_multiple_turns():
    async def run():
        session = MockSession()
        client = MockClient(session)
        events = []
        async def emit(event):
            events.append(event)
        analysis = AudioAnalysis(adapter(client), emit, "ru")
        await analysis.start()
        cfg = client.connections[0]
        types.LiveConnectConfig.model_validate(cfg["config"])
        assert cfg["model"] == DEFAULT_MODEL
        assert cfg["config"]["response_modalities"] == ["AUDIO"]
        assert cfg["config"]["input_audio_transcription"] == {}
        # Both silent and voiced chunks are forwarded immediately, once, unchanged.
        pcm = b"\x00\x00" * 160
        analysis.feed(pcm)
        await session.incoming.put(types.LiveServerMessage(
            server_content=types.LiveServerContent(
                input_transcription=types.Transcription(text="PRIVATE_TRANSCRIPT"),
                model_turn=types.Content(parts=[types.Part(inline_data=types.Blob(
                    data=b"PRIVATE_GENERATED_AUDIO", mime_type="audio/pcm;rate=24000"))]),
                turn_complete=True),
            tool_call=types.LiveServerToolCall(function_calls=[
                types.FunctionCall(id="valid", name="report_fraud", args=REPORT),
                types.FunctionCall(id="invalid", name="report_fraud", args={"level": "impossible"}),
                types.FunctionCall(id="unknown", name="execute", args={}),
            ])))
        await asyncio.sleep(0.02)
        await session.incoming.put(types.LiveServerMessage(
            server_content=types.LiveServerContent(input_transcription=types.Transcription(text="second turn"))))
        await asyncio.sleep(0.02)
        await analysis.finish()
        await analysis.close()
        assert session.sent[0]["audio"].data == pcm
        assert session.sent[0]["audio"].mime_type == "audio/pcm;rate=16000"
        assert session.sent[-1] == {"audio_stream_end": True}
        assert [e["type"] for e in events] == ["transcript", "risk", "transcript"]
        assert events[1] == {"type": "risk", **REPORT}
        assert [r.id for r in session.replies] == ["valid", "invalid", "unknown"]
        assert session.replies[0].response == {"result": "accepted"}
        assert "error" in session.replies[1].response and "error" in session.replies[2].response
        assert client.closed and session.closed
    asyncio.run(run())


@pytest.mark.parametrize("args", [
    None, [], {}, {**REPORT, "level": []}, {**REPORT, "level": "critical"},
    {**REPORT, "reasons": "text"}, {**REPORT, "reasons": [123]},
    {**REPORT, "reasons": ["x"] * 9}, {**REPORT, "reasons": ["x" * 301]},
    {**REPORT, "message": ""}, {**REPORT, "message": "x" * 1001},
    {**REPORT, "extra": True},
])
def test_invalid_reports_rejected(args):
    assert validate_report(args) is None


@pytest.mark.parametrize("where", ["constructor", "connect", "after_ready"])
def test_provider_failure_sanitized_immediate_no_fallback(where, caplog):
    async def run():
        exc = RuntimeError("RESOURCE_EXHAUSTED PRIVATE_KEY PRIVATE_TRANSCRIPT")
        client = MockClient(MockSession(failure=exc if where == "after_ready" else None),
                            connect_failure=exc if where == "connect" else None)
        def factory(**kwargs):
            if where == "constructor":
                raise exc
            return client
        analysis = AudioAnalysis(GeminiLive(client_factory=factory), lambda event: None, "en")
        try:
            with pytest.raises(AnalysisError) as result:
                if where == "after_ready":
                    await analysis.start()
                    analysis.feed(b"\0\0")
                    await asyncio.sleep(0.02)
                    analysis.check()
                else:
                    await asyncio.wait_for(analysis.start(), 0.5)
            assert result.value.code == "quota_exceeded"
            assert "PRIVATE" not in str(result.value)
            assert len(client.connections) <= 1
        finally:
            await analysis.close()
        assert "PRIVATE_KEY" not in caplog.text and "PRIVATE_TRANSCRIPT" not in caplog.text
    asyncio.run(run())


def test_rtc_provider_failure_keeps_signaling_and_releases_slot():
    backend = FakeAnalysis(failure=AnalysisError("quota_exceeded", "Gemini quota exhausted."))
    app = create_app(analysis_backend=backend)
    with TestClient(app) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["mode"] == "gemini"
            ws.send_bytes(bytes(320))
            assert ws.receive_json()["code"] == "quota_exceeded"
        assert not app.state.busy and backend.closed
        caller.send_json({"type": "ice", "call_id": cid, "candidate": None})
        assert receiver.receive_json()["type"] == "ice"
        caller.send_json({"type": "hangup", "call_id": cid})
        assert caller.receive_json()["type"] == "ended"
        assert receiver.receive_json()["type"] == "ended"


@pytest.mark.parametrize("provider", [None, "not-google"])
def test_rtc_provider_consent_before_ticket_consumption(provider):
    backend = FakeAnalysis()
    app = create_app(analysis_backend=backend)
    with TestClient(app) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket, analysis_provider=provider)["code"] == "consent_required"
        assert app.state.rtc.ticket == ticket and not app.state.busy
        assert not backend.closed and not backend.frames
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["mode"] == "gemini"
            ws.send_json({"type": "stop"})
            assert ws.receive_json()["type"] == "done"


@pytest.mark.parametrize("path", ["/ws/audio", "/ws/rtc-audio"])
def test_disconnect_and_cleanup_exception_release_slot(path):
    backend = FakeAnalysis(cleanup_failure=True)
    app = create_app(analysis_backend=backend)
    with TestClient(app) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        headers = ORIGIN if path.endswith("rtc-audio") else {}
        with client.websocket_connect(path, headers=headers) as ws:
            if path.endswith("rtc-audio"):
                assert start(ws, cid, ticket)["type"] == "ready"
            else:
                ws.send_json({"type": "start", "sample_rate": 16000, "channels": 1,
                              "format": "pcm_s16le", "consent": True, "analysis_provider": "google"})
                assert ws.receive_json()["type"] == "ready"
            ws.close()
        assert not app.state.busy and backend.closed


def test_legacy_requires_explicit_google_consent():
    backend = FakeAnalysis()
    with TestClient(create_app(analysis_backend=backend)) as client:
        with client.websocket_connect("/ws/audio", headers={}) as ws:
            ws.send_json({"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
            assert ws.receive_json()["code"] == "consent_required"
    assert not backend.frames and not backend.closed


def test_bounded_queue_and_disconnect_closes_sdk():
    async def run():
        session = MockSession()
        client = MockClient(session)
        analysis = AudioAnalysis(adapter(client), lambda e: None, "en")
        await analysis.start()
        for _ in range(64):
            analysis.feed(bytes(320))
        with pytest.raises(AnalysisError, match="cannot keep up"):
            analysis.feed(bytes(320))
        await analysis.close()
        assert session.closed and client.closed
    asyncio.run(run())


def test_missing_secret_and_invalid_mode_fail_closed(monkeypatch):
    monkeypatch.setenv("ANALYSIS_MODE", "gemini")
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    with pytest.raises(RuntimeError, match="GEMINI_API_KEY"):
        with TestClient(create_app()):
            pass
    monkeypatch.setenv("ANALYSIS_MODE", "whisper")
    with pytest.raises(RuntimeError, match="ANALYSIS_MODE"):
        with TestClient(create_app()):
            pass


def test_hangup_cancels_pending_upstream_connection():
    async def run():
        entered = asyncio.Event()
        closed = asyncio.Event()
        class Connecting:
            async def run(self, queue, emit, ready, language):
                entered.set()
                try:
                    await asyncio.Event().wait()
                finally:
                    closed.set()
        ended = asyncio.Event()
        analysis = AudioAnalysis(Connecting(), lambda e: None, "ru")
        starting = asyncio.create_task(analysis.start(ended))
        await entered.wait()
        ended.set()
        assert await asyncio.wait_for(starting, 0.5) is False
        await analysis.close()
        assert closed.is_set()
    asyncio.run(run())


def test_model_override_goaway_and_sdk_close_error(monkeypatch, caplog):
    monkeypatch.setenv("GEMINI_MODEL", "operator-selected-model")
    async def run():
        session = MockSession()
        client = MockClient(session, close_failure=True)
        analysis = AudioAnalysis(adapter(client), lambda e: None, "ru")
        await analysis.start()
        assert client.connections[0]["model"] == "operator-selected-model"
        await session.incoming.put(types.LiveServerMessage(go_away=types.LiveServerGoAway(time_left="5s")))
        await asyncio.sleep(0.02)
        with pytest.raises(AnalysisError) as failure:
            analysis.check()
        assert failure.value.code == "session_expired"
        await analysis.close()
        assert session.closed and client.closed
        assert len(client.connections) == 1
        assert "PRIVATE_CLOSE_EXCEPTION" not in caplog.text
    asyncio.run(run())
