import contextlib
import json
import threading
import time

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.app import create_app
from server import rtc

TOKEN = "rtc-test-token-at-least-sixteen"
ORIGIN = {"origin": "http://localhost:8080"}
ROOM = "random-room-123"


@pytest.fixture(autouse=True)
def environment(monkeypatch):
    monkeypatch.delenv("PUBLIC_BASE_URL", raising=False)
    monkeypatch.delenv("RTC_ICE_SERVERS_JSON", raising=False)
    monkeypatch.setenv("ANALYSIS_MODE", "diagnostic")


def join(ws, selected_role, **extra):
    ws.send_json({"type": "join", "token": TOKEN, "room": ROOM, "role": selected_role, "consent": True, **extra})
    return ws.receive_json()


@contextlib.contextmanager
def pair(client):
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as caller:
        assert join(caller, "caller")["type"] == "ready"
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as receiver:
            assert join(receiver, "receiver")["type"] == "ready"
            assert caller.receive_json()["type"] == "peer-joined"
            yield caller, receiver


def accept(caller, receiver):
    caller.send_json({"type": "invite"})
    incoming = receiver.receive_json()
    assert caller.receive_json() == incoming
    cid = incoming["call_id"]
    receiver.send_json({"type": "accept", "call_id": cid})
    assert caller.receive_json() == {"type": "accepted", "call_id": cid}
    return cid, receiver.receive_json()["audio_ticket"]


def start(ws, cid, ticket, **extra):
    ws.send_json({"type": "start", "room": ROOM, "call_id": cid, "audio_ticket": ticket,
                  "sample_rate": 16000, "channels": 1, "format": "pcm_s16le", "consent": True, "analysis_provider": "google", **extra})
    return ws.receive_json()


def test_status_does_not_expose_turn_credentials(monkeypatch):
    monkeypatch.setenv("RTC_ICE_SERVERS_JSON", json.dumps([{"urls": "turn:example.test", "username": "secret-user", "credential": "secret-pass"}]))
    with TestClient(create_app(token=TOKEN)) as client:
        assert client.get("/api/rtc/status").json() == {"asr_configured": False, "turn_configured": True}
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as ws:
            assert join(ws, "receiver")["ice_servers"][0]["credential"] == "secret-pass"


@pytest.mark.parametrize("headers", [{}, {"origin": "https://evil.test"}, {"origin": "null"}, {"origin": "http://localhost.evil.test"}])
def test_origin_denied(headers):
    with TestClient(create_app(token=TOKEN)) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/ws/rtc", headers=headers):
                pass


def test_production_origin(monkeypatch):
    monkeypatch.setenv("PUBLIC_BASE_URL", "https://safe.example/base")
    with TestClient(create_app(token=TOKEN)) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/ws/rtc", headers=ORIGIN):
                pass
        with client.websocket_connect("/ws/rtc", headers={"origin": "https://safe.example"}) as ws:
            assert join(ws, "caller")["type"] == "ready"


@pytest.mark.parametrize("change", [{"token": "wrong"}, {"consent": False}, {"room": "short"}, {"role": "outsider"}, {"token": "nonascii-\u0430"}])
def test_join_validation(change):
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as ws:
            assert join(ws, "caller", **change)["type"] == "error"


def test_join_timeout(monkeypatch):
    # read_json's default is bound at definition; patch the route's read helper.
    original = rtc.read_json
    async def fast(ws, timeout=0.01):
        return await original(ws, timeout)
    monkeypatch.setattr(rtc, "read_json", fast)
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as ws:
            assert ws.receive_json()["type"] == "error"


def test_one_room_and_unique_roles():
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as first:
            join(first, "caller")
            for role, room in [("caller", ROOM), ("receiver", "another-room")]:
                with client.websocket_connect("/ws/rtc", headers=ORIGIN) as ws:
                    assert join(ws, role, room=room)["type"] == "error"


def test_full_lifecycle_and_reinvite():
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        assert len(ticket) >= 32
        for sender, peer, event in [(caller, receiver, {"type": "offer", "sdp": "v=0"}), (receiver, caller, {"type": "answer", "sdp": "v=0"}), (caller, receiver, {"type": "ice", "candidate": None})]:
            event["call_id"] = cid
            sender.send_json(event)
            assert peer.receive_json() == event
        caller.send_json({"type": "hangup", "call_id": cid})
        assert caller.receive_json()["type"] == "ended"
        assert receiver.receive_json()["type"] == "ended"
        next_id, _ = accept(caller, receiver)
        assert next_id != cid


def test_reject_and_timeout(monkeypatch):
    monkeypatch.setattr(rtc, "RING_SECONDS", 0.05)
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        caller.send_json({"type": "invite"})
        incoming = receiver.receive_json()
        caller.receive_json()
        receiver.send_json({"type": "reject", "call_id": incoming["call_id"]})
        assert receiver.receive_json()["type"] == "rejected"
        assert caller.receive_json()["type"] == "rejected"
        caller.send_json({"type": "invite"})
        caller.receive_json()
        receiver.receive_json()
        assert caller.receive_json()["reason"] == "timeout"
        assert receiver.receive_json()["reason"] == "timeout"


@pytest.mark.parametrize("event", [{"type": "offer", "sdp": "v=0"}, {"type": "ice", "candidate": {}}])
def test_no_relay_before_accept(event):
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        caller.send_json({"type": "invite"})
        cid = caller.receive_json()["call_id"]
        receiver.receive_json()
        caller.send_json({**event, "call_id": cid})
        assert caller.receive_json()["type"] == "error"
        assert receiver.receive_json()["type"] == "peer-left"


@pytest.mark.parametrize("kind,payload", [("offer", {"sdp": "wrong direction"}), ("ice", {"candidate": "bad"}), ("answer", {"sdp": "no offer"})])
def test_bad_relay(kind, payload):
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, _ = accept(caller, receiver)
        receiver.send_json({"type": kind, "call_id": cid, **payload})
        assert receiver.receive_json()["type"] == "error"
        assert caller.receive_json()["type"] == "peer-left"


def test_audio_ticket_and_diagnostic():
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, TOKEN)["type"] == "error"
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            ready = start(ws, cid, ticket)
            assert ready["mode"] == "diagnostic"
            assert "not configured" in ready["message"]
            ws.send_bytes(bytes(8000))
            ws.send_json({"type": "stop"})
            assert ws.receive_json()["type"] == "done"
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["type"] == "error"


@pytest.mark.parametrize("raw", [b"x", b"", bytes(8002)])
def test_audio_frame_bounds(raw):
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            start(ws, cid, ticket)
            ws.send_bytes(raw)
            assert ws.receive_json()["type"] == "error"


def test_hangup_closes_audio():
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            start(ws, cid, ticket)
            caller.send_json({"type": "hangup", "call_id": cid})
            assert caller.receive_json()["type"] == "ended"
            assert receiver.receive_json()["type"] == "ended"
            assert ws.receive_json()["reason"] == "call-ended"


@pytest.mark.parametrize("path", ["/ws/rtc?token=hidden", "/ws/rtc-audio?token=hidden"])
def test_query_credentials_rejected(path):
    with TestClient(create_app(token=TOKEN)) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect(path, headers=ORIGIN):
                pass


def test_oversized_signaling():
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as ws:
            join(ws, "caller")
            ws.send_text("x" * (rtc.MAX_JSON + 1))
            assert ws.receive_json()["type"] == "error"


def test_stale_call_ticket_invalid_after_hangup():
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        caller.send_json({"type": "hangup", "call_id": cid})
        caller.receive_json()
        receiver.receive_json()
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["type"] == "error"


def test_legacy_busy_slot_prevents_rtc_audio():
    app = create_app(token=TOKEN)
    with TestClient(app) as client, pair(client) as (caller, receiver):
        cid, ticket = accept(caller, receiver)
        with client.websocket_connect("/ws/audio", headers={"authorization": "Bearer " + TOKEN}) as legacy:
            legacy.send_json({"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
            legacy.receive_json()
            with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as invalid:
                error = start(invalid, cid, "invalid-ticket")
                assert error["type"] == "error"
                assert "code" not in error
            with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
                assert start(ws, cid, ticket) == {"type": "error", "code": "busy", "message": "Analysis is busy"}
                with pytest.raises(WebSocketDisconnect):
                    ws.receive_json()
            assert app.state.rtc.ticket == ticket
            assert app.state.busy
            legacy.send_json({"type": "stop"})
            assert legacy.receive_json()["type"] == "done"
        with client.websocket_connect("/ws/rtc-audio", headers=ORIGIN) as ws:
            assert start(ws, cid, ticket)["type"] == "ready"
            ws.send_json({"type": "stop"})
            assert ws.receive_json()["type"] == "done"


def test_signaling_rate_limit():
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, _ = accept(caller, receiver)
        for _ in range(119):
            caller.send_json({"type": "ice", "call_id": cid, "candidate": None})
            assert receiver.receive_json()["type"] == "ice"
        caller.send_json({"type": "ice", "call_id": cid, "candidate": None})
        assert caller.receive_json()["type"] == "error"
        assert receiver.receive_json()["type"] == "peer-left"


@pytest.mark.parametrize("first_role", ["receiver", "caller"])
def test_ready_reports_existing_peer(first_role):
    second_role = "caller" if first_role == "receiver" else "receiver"
    with TestClient(create_app(token=TOKEN)) as client:
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as first:
            assert join(first, first_role)["peer_present"] is False
            with client.websocket_connect("/ws/rtc", headers=ORIGIN) as second:
                assert join(second, second_role)["peer_present"] is True
                assert first.receive_json() == {"type": "peer-joined", "role": second_role}
                caller, receiver = (second, first) if first_role == "receiver" else (first, second)
                accept(caller, receiver)


@pytest.mark.parametrize("base,public,websocket", [
    ("http://localhost:8080", "", "ws://localhost:8080"),
    ("http://127.0.0.1:9000", "", "ws://127.0.0.1:9000"),
    ("http://[::1]:8080", "", "ws://[::1]:8080"),
    ("http://testserver", "https://safe.example:8443/base", "wss://safe.example:8443"),
])
def test_rtc_page_security_headers(monkeypatch, base, public, websocket):
    if public:
        monkeypatch.setenv("PUBLIC_BASE_URL", public)
    with TestClient(create_app(token=TOKEN), base_url=base) as client:
        response = client.get("/rtc")
        assert response.status_code in {200, 503}
        headers = response.headers
        csp = headers["content-security-policy"]
        assert f"connect-src 'self' {websocket};" in csp
        for directive in ["script-src 'self'", "style-src 'self'", "worker-src 'self'", "frame-ancestors 'none'", "media-src 'self' blob:"]:
            assert directive in csp
        assert "unsafe-inline" not in csp
        assert headers["permissions-policy"] == "microphone=(self), camera=()"
        assert headers["cache-control"] == "no-store"
        assert headers["referrer-policy"] == "no-referrer"
        assert headers["x-content-type-options"] == "nosniff"


def test_untrusted_host_not_in_csp():
    with TestClient(create_app(token=TOKEN), base_url="http://evil.example") as client:
        csp = client.get("/rtc").headers["content-security-policy"]
        assert "connect-src 'self';" in csp
        assert "evil.example" not in csp


@pytest.mark.parametrize("first_hangup", ["caller", "receiver"])
def test_simultaneous_hangup_and_late_messages_allow_redial(first_hangup):
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, _ = accept(caller, receiver)
        first, second = (caller, receiver) if first_hangup == "caller" else (receiver, caller)
        first.send_json({"type": "hangup", "call_id": cid})
        second.send_json({"type": "hangup", "call_id": cid})
        assert caller.receive_json()["type"] == "ended"
        assert receiver.receive_json()["type"] == "ended"
        for sender, kind in [(caller, "ice"), (receiver, "ice"), (caller, "offer"), (receiver, "answer"), (receiver, "reject")]:
            sender.send_json({"type": kind, "call_id": cid, "candidate": None, "sdp": "v=0"})
        next_id, _ = accept(caller, receiver)
        # Late old-call messages cannot mutate or terminate the replacement call.
        receiver.send_json({"type": "hangup", "call_id": cid})
        caller.send_json({"type": "ice", "call_id": cid, "candidate": None})
        caller.send_json({"type": "offer", "call_id": next_id, "sdp": "v=0"})
        assert receiver.receive_json() == {"type": "offer", "call_id": next_id, "sdp": "v=0"}


@pytest.mark.parametrize("kind", ["ice", "offer", "answer", "hangup", "reject"])
def test_unknown_call_still_fails_closed(kind):
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        cid, _ = accept(caller, receiver)
        caller.send_json({"type": "hangup", "call_id": cid})
        caller.receive_json()
        receiver.receive_json()
        caller.send_json({"type": kind, "call_id": "unknown-call-id", "candidate": None, "sdp": "v=0"})
        assert caller.receive_json()["type"] == "error"
        assert receiver.receive_json()["type"] == "peer-left"


def test_ended_call_history_bounded_expiring_and_session_scoped(monkeypatch):
    from types import SimpleNamespace
    clock = [100.0]
    monkeypatch.setattr(rtc, "time", SimpleNamespace(monotonic=lambda: clock[0]))
    hub = rtc.Hub()
    original, replacement = object(), object()
    hub.room = ROOM
    hub.peers = {"caller": original}
    for i in range(rtc.ENDED_CALL_LIMIT + 1):
        hub.call_id = str(i)
        hub.reset()
    assert len(hub.ended_calls) == rtc.ENDED_CALL_LIMIT
    assert not hub.is_ended_call("0", original)
    assert hub.is_ended_call("1", original)
    assert not hub.is_ended_call("1", replacement)
    hub.room = "another-room"
    assert not hub.is_ended_call("1", original)
    hub.room = ROOM
    clock[0] += rtc.ENDED_CALL_TTL
    assert not hub.is_ended_call("1", original)
    assert not hub.ended_calls


def test_late_accept_after_caller_hangup_preserves_redial():
    with TestClient(create_app(token=TOKEN)) as client, pair(client) as (caller, receiver):
        caller.send_json({"type": "invite"})
        cid = caller.receive_json()["call_id"]
        assert receiver.receive_json()["call_id"] == cid
        caller.send_json({"type": "hangup", "call_id": cid})
        assert caller.receive_json() == {"type": "ended", "call_id": cid}
        assert receiver.receive_json() == {"type": "ended", "call_id": cid}
        receiver.send_json({"type": "accept", "call_id": cid})
        next_id, _ = accept(caller, receiver)
        assert next_id != cid
        caller.send_json({"type": "offer", "call_id": next_id, "sdp": "v=0"})
        assert receiver.receive_json() == {"type": "offer", "call_id": next_id, "sdp": "v=0"}
