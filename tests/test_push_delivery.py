from types import SimpleNamespace

from server import push


def test_disabled_push_does_not_read_credentials(monkeypatch):
    monkeypatch.delenv("GOOGLE_APPLICATION_CREDENTIALS", raising=False)
    monkeypatch.delenv("FIREBASE_SERVICE_ACCOUNT_JSON", raising=False)
    assert push.configured() is False
    assert push.send("device-token", {"type": "incoming"}) is False


def test_fcm_http_v1_data_payload(monkeypatch):
    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "/private/key.json")
    monkeypatch.delenv("FIREBASE_SERVICE_ACCOUNT_JSON", raising=False)
    monkeypatch.setattr(push, "_source", ("file", "/private/key.json"))
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
    monkeypatch.delenv("FIREBASE_SERVICE_ACCOUNT_JSON", raising=False)
    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "/nonexistent/key.json")
    monkeypatch.setattr(push, "_credentials", None)
    assert push.send("device-token", {"type": "incoming"}) is False


def test_inline_service_account_json_for_cloud_hosts(monkeypatch):
    monkeypatch.delenv("GOOGLE_APPLICATION_CREDENTIALS", raising=False)
    monkeypatch.setenv("FIREBASE_SERVICE_ACCOUNT_JSON", '{"project_id":"test-project"}')
    monkeypatch.setattr(push, "_credentials", None)
    monkeypatch.setattr(push, "_source", None)
    created = []

    def credentials(info, scopes):
        created.append((info, scopes))
        return SimpleNamespace(valid=True, token="oauth-token", project_id="test-project")

    sent = []
    monkeypatch.setattr(push.service_account.Credentials, "from_service_account_info", credentials)
    monkeypatch.setattr(push.httpx, "post", lambda url, **kwargs: sent.append((url, kwargs)) or SimpleNamespace(is_success=True))

    assert push.configured() is True
    assert push.send("device-token", {"type": "incoming"}) is True
    assert created[0][0] == {"project_id": "test-project"}
    assert created[0][1] == ["https://www.googleapis.com/auth/firebase.messaging"]
    assert sent[0][0].endswith("/projects/test-project/messages:send")
