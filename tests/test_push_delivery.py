from types import SimpleNamespace

from server import push


def test_disabled_push_does_not_read_credentials(monkeypatch):
    monkeypatch.delenv("GOOGLE_APPLICATION_CREDENTIALS", raising=False)
    assert push.configured() is False
    assert push.send("device-token", {"type": "incoming"}) is False


def test_fcm_http_v1_data_payload(monkeypatch):
    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "/private/key.json")
    monkeypatch.setattr(push, "_path", "/private/key.json")
    monkeypatch.setattr(push, "_credentials", SimpleNamespace(valid=True, token="oauth-token", project_id="test-project"))
    sent = []

    def post(url, **kwargs):
        sent.append((url, kwargs))
        return SimpleNamespace(is_success=True)

    monkeypatch.setattr(push.httpx, "post", post)
    data = {"type": "incoming", "room": "test-room", "call_id": "call", "expires_at": "123"}
    assert push.send("device-token", data)
    url, request = sent[0]
    assert url == "https://fcm.googleapis.com/v1/projects/test-project/messages:send"
    assert request["headers"]["Authorization"] == "Bearer oauth-token"
    assert request["json"]["message"] == {
        "token": "device-token", "data": data, "android": {"priority": "HIGH", "ttl": "60s"}}
    assert push.send("device-token", {"type": "cancel"})
    assert sent[1][1]["json"]["message"]["android"]["priority"] == "NORMAL"


def test_invalid_credentials_fail_closed(monkeypatch):
    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "/nonexistent/key.json")
    monkeypatch.setattr(push, "_credentials", None)
    assert push.send("device-token", {"type": "incoming"}) is False
