"""Optional FCM HTTP v1 delivery. Credentials stay on the server."""
import os
import threading

import httpx
from google.auth.transport.requests import Request
from google.oauth2 import service_account

from server.diagnostics import log

_lock = threading.Lock()
_credentials = None
_path = None


def configured():
    return bool(os.getenv("GOOGLE_APPLICATION_CREDENTIALS"))


def send(token, data):
    global _credentials, _path
    path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    if not path:
        return False
    try:
        with _lock:
            if _credentials is None or _path != path:
                _credentials = service_account.Credentials.from_service_account_file(
                    path, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
                _path = path
            if not _credentials.valid:
                transport = Request()
                _credentials.refresh(lambda **kwargs: transport(**{**kwargs, "timeout": 5}))
            access_token = _credentials.token
            project = _credentials.project_id
        response = httpx.post(
            f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
            headers={"Authorization": f"Bearer {access_token}"},
            json={"message": {"token": token, "data": data,
                  "android": {"priority": "HIGH" if data["type"] == "incoming" else "NORMAL",
                              "ttl": "60s"}}}, timeout=8)
        if response.is_success:
            return True
        log.warning("FCM_SEND_FAILED status=%s", response.status_code)
    except Exception:
        # Never log device tokens, private keys or upstream response bodies.
        log.warning("FCM_SEND_FAILED credentials_or_network=true")
    return False
