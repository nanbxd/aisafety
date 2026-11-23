import time

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server import push

ROOM = "push-room-123"
TOKEN = "test-device-token-long-enough"
ORIGIN = {"origin": "http://localhost:8080"}


@pytest.fixture
def client(monkeypatch):
    monkeypatch.delenv("PUBLIC_BASE_URL", raising=False)
    monkeypatch.setenv("ANALYSIS_MODE", "diagnostic")
    monkeypatch.setattr(push, "configured", lambda: True)
    monkeypatch.setattr(push, "send", lambda *args: True)
    with TestClient(create_app()) as client:
        yield client


def join(ws, role, **kwargs):
    ws.send_json(dict(type="join", role=role, room=ROOM, consent=True, **kwargs))
    return ws.receive_json()


def register_then_disconnect(client, caller):
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as receiver:
        assert join(receiver, "receiver")["type"] == "ready"
        assert caller.receive_json()["type"] == "peer-joined"
        receiver.send_json({"type": "push-register", "token": TOKEN})
        assert receiver.receive_json() == {"type": "push-ready", "enabled": True}
    assert caller.receive_json()["push_available"] is True


def test_offline_receiver_can_answer_current_call(client):
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as caller:
        join(caller, "caller")
        register_then_disconnect(client, caller)
        caller.send_json({"type": "invite"})
        incoming = caller.receive_json()
        assert incoming["type"] == "incoming"
        payload = dict(room=ROOM, call_id=incoming["call_id"], token=TOKEN, action="check")
        assert client.post("/api/rtc/push", json=payload).status_code == 200
        assert client.post("/api/rtc/push", json={**payload, "token": "wrong"}).status_code == 404
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as receiver:
            assert join(receiver, "receiver", resume_call=incoming["call_id"])["type"] == "ready"
            assert receiver.receive_json() == incoming
            assert caller.receive_json()["type"] == "peer-joined"
            receiver.send_json({"type": "accept", "call_id": incoming["call_id"]})
            assert caller.receive_json()["type"] == "accepted"
            assert receiver.receive_json()["type"] == "accepted"
            assert client.post("/api/rtc/push", json=payload).status_code == 404


def test_reject_stale_and_cancelled_push(client):
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as caller:
        join(caller, "caller")
        register_then_disconnect(client, caller)
        caller.send_json({"type": "invite"})
        incoming = caller.receive_json()
        payload = dict(room=ROOM, call_id=incoming["call_id"], token=TOKEN, action="reject")
        assert client.post("/api/rtc/push", json=payload).status_code == 200
        assert caller.receive_json()["type"] == "rejected"
        assert client.post("/api/rtc/push", json=payload).status_code == 404
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as receiver:
            assert join(receiver, "receiver", resume_call=incoming["call_id"])["type"] == "error"


def test_unregistration_and_expiry(client):
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as caller:
        join(caller, "caller")
        with client.websocket_connect("/ws/rtc", headers=ORIGIN) as receiver:
            join(receiver, "receiver")
            caller.receive_json()
            receiver.send_json({"type": "push-register", "token": TOKEN})
            assert receiver.receive_json()["type"] == "push-ready"
            receiver.send_json({"type": "push-unregister"})
        assert caller.receive_json()["push_available"] is False
        assert ROOM not in client.app.state.rtc.devices
    client.app.state.rtc.devices[ROOM] = (TOKEN, time.time() - 1)
    assert client.app.state.rtc.device(ROOM) is None


def test_push_body_limit(client):
    assert client.post("/api/rtc/push", content=b"x" * 8193).status_code == 413


def test_delivery_failure_ends_pending_call(client, monkeypatch):
    monkeypatch.setattr(push, "send", lambda *args: False)
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as caller:
        join(caller, "caller")
        register_then_disconnect(client, caller)
        caller.send_json({"type": "invite"})
        assert caller.receive_json()["type"] == "incoming"
        result = caller.receive_json()
        assert result["type"] == "rejected"
        assert result["reason"] == "push_unavailable"


def test_caller_cancel_invalidates_pending_push(client):
    with client.websocket_connect("/ws/rtc", headers=ORIGIN) as caller:
        join(caller, "caller")
        register_then_disconnect(client, caller)
        caller.send_json({"type": "invite"})
        call_id = caller.receive_json()["call_id"]
        caller.send_json({"type": "hangup", "call_id": call_id})
        assert caller.receive_json()["type"] == "ended"
        assert client.post("/api/rtc/push", json=dict(room=ROOM, call_id=call_id, token=TOKEN, action="check")).status_code == 404
