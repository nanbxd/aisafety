"""Optional FCM HTTP v1 delivery. Credentials stay on the server."""
import hashlib
import json
import os
import threading

import httpx
from google.auth.transport.requests import Request
from google.oauth2 import service_account

from server.diagnostics import log

_lock = threading.Lock()
_credentials = None
_source = None


def configured():
    return bool(os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON") or os.getenv("GOOGLE_APPLICATION_CREDENTIALS"))


def send(token, data):
    global _credentials, _source
    raw = os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")
    path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    if not raw and not path:
        return False
    try:
        with _lock:
            source = ("json", hashlib.sha256(raw.encode()).hexdigest()) if raw else ("file", path)
            if _credentials is None or _source != source:
                if raw:
                    info = json.loads(raw)
                    if not isinstance(info, dict):
                        raise ValueError("Firebase credentials must be a JSON object")
                    _credentials = service_account.Credentials.from_service_account_info(
                        info, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
                else:
                    _credentials = service_account.Credentials.from_service_account_file(
                        path, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
                _source = source
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
