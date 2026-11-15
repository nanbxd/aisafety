"""Single-user, single-process MVP. All authenticated subscribers share one identity.

Deploy one worker: call correlation and bounded event queues are process-local.
Webhooks are signed against PUBLIC_BASE_URL, never proxy or Host headers.
"""
import asyncio
import base64
import contextlib
import hmac
import json
import os
import re
import time
from dataclasses import dataclass
from urllib.parse import urlsplit

import numpy as np
from fastapi import HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from twilio.jwt.access_token import AccessToken
from twilio.jwt.access_token.grants import VoiceGrant
from twilio.request_validator import RequestValidator
from twilio.twiml.voice_response import VoiceResponse, Start, Dial

from server.risk import RiskEngine

EVENT_BUFFER = 64
MEDIA_QUEUE = 3
MAX_CALLS = 128
CALL_TTL = 3600
SID = re.compile(r"^CA[0-9a-fA-F]{32}$")
STREAM_SID = re.compile(r"^MZ[0-9a-fA-F]{32}$")


@dataclass
class Config:
    account: str
    key: str
    secret: str
    auth: str
    push: str
    identity: str
    base: str

    @classmethod
    def load(cls):
        return cls(*(os.getenv("TWILIO_" + key, "").strip() for key in
                     ("ACCOUNT_SID", "API_KEY_SID", "API_KEY_SECRET", "AUTH_TOKEN", "PUSH_CREDENTIAL_SID")),
                   os.getenv("TWILIO_CLIENT_ID", "senior").strip(),
                   os.getenv("PUBLIC_BASE_URL", "").rstrip("/"))

    def missing(self):
        missing = [name for name, value in (("TWILIO_ACCOUNT_SID", self.account),
                   ("TWILIO_API_KEY_SID", self.key), ("TWILIO_API_KEY_SECRET", self.secret),
                   ("TWILIO_AUTH_TOKEN", self.auth)) if not value]
        for name, value, prefix in (("TWILIO_ACCOUNT_SID", self.account, "AC"),
                                     ("TWILIO_API_KEY_SID", self.key, "SK"),
                                     ("TWILIO_PUSH_CREDENTIAL_SID", self.push, "CR")):
            if value and not re.fullmatch(prefix + r"[0-9a-fA-F]{32}", value):
                missing.append(name + " (invalid SID)")
        try:
            url = urlsplit(self.base)
            port = url.port
        except ValueError:
            missing.append("PUBLIC_BASE_URL (invalid URL)")
            return missing
        if (url.scheme != "https" or not url.netloc or url.username or url.query or url.fragment
                or not url.hostname or port not in (None, 443) or url.path
                or any(c.isspace() for c in self.base)):
            missing.append("PUBLIC_BASE_URL (HTTPS origin required, no path/query/credentials)")
        if not re.fullmatch(r"[A-Za-z0-9_]{1,121}", self.identity):
            missing.append("TWILIO_CLIENT_ID (letters, digits and underscore only)")
        return missing

    def require(self):
        if self.missing():
            raise HTTPException(503, "Twilio configuration missing/invalid: " + ", ".join(self.missing()))


class MulawDecoder:
    """G.711 mu-law to PCM16 with stateful linear 2x interpolation (8 -> 16 kHz)."""
    def __init__(self):
        self.previous = None

    def decode(self, raw):
        values = np.bitwise_xor(np.frombuffer(raw, dtype=np.uint8), 255).astype(np.int32)
        magnitude = ((values & 15) * 8 + 132) << ((values >> 4) & 7)
        samples = np.where(values & 128, 132 - magnitude, magnitude - 132)
        if not len(samples):
            return b""
        previous = np.concatenate(([samples[0] if self.previous is None else self.previous], samples[:-1]))
        output = np.empty(len(samples) * 2, dtype="<i2")
        output[0::2] = (previous + samples) // 2
        output[1::2] = samples
        self.previous = int(samples[-1])
        return output.tobytes()


def install_telephony(app):
    calls = {}
    subscribers = set()

    def emit(event):
        call = calls.get(event.get("call_sid"))
        if call is not None:
            if event["type"] == "error":
                call.update(analysis_status="error", message=event["message"])
            elif event["type"] == "call":
                call["status"] = event["status"]
                if event["status"] == "stream-started" and call["analysis_status"] != "error":
                    call["analysis_status"] = "active"
                elif event["status"] == "stream-stopped" and call["analysis_status"] != "error":
                    call["analysis_status"] = "stopped"
        for queue in tuple(subscribers):
            if queue.full():
                while not queue.empty():
                    queue.get_nowait()
                queue.put_nowait({"type": "error", "call_sid": event.get("call_sid"),
                                  "message": "Event subscriber overflow; reconnect.", "fatal": True})
                subscribers.discard(queue)
            else:
                queue.put_nowait(event)

    def authorize(headers):
        if not hmac.compare_digest(headers.get("authorization", ""), "Bearer " + app.state.token):
            raise HTTPException(401, "Bearer SAFETY_TOKEN required", headers={"WWW-Authenticate": "Bearer"})

    def prune():
        for sid, call in tuple(calls.items()):
            if time.monotonic() - call["created"] > CALL_TTL and not call.get("active"):
                del calls[sid]

    async def signed(request):
        cfg = Config.load()
        cfg.require()
        if request.url.query:
            raise HTTPException(400, "Webhook query parameters are not supported")
        if request.headers.get("content-type", "").split(";")[0] != "application/x-www-form-urlencoded":
            raise HTTPException(415, "Expected form-urlencoded webhook")
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 16384:
                raise HTTPException(413, "Webhook too large")
        # Retain duplicate fields for the official signature validator.
        from starlette.datastructures import FormData
        from urllib.parse import parse_qsl
        try:
            form = FormData(parse_qsl(body.decode("utf-8"), keep_blank_values=True, max_num_fields=100))
        except ValueError:
            raise HTTPException(400, "Invalid webhook form") from None
        if not RequestValidator(cfg.auth).validate(cfg.base + request.scope["path"], form,
                                                   request.headers.get("x-twilio-signature", "")):
            raise HTTPException(403, "Invalid Twilio signature")
        if form.get("AccountSid") != cfg.account:
            raise HTTPException(403, "Unexpected Twilio account")
        return cfg, form

    @app.get("/api/voice/status")
    async def status(request: Request):
        authorize(request.headers)
        cfg = Config.load()
        return {"configured": not cfg.missing(), "push_configured": bool(cfg.push)}

    @app.get("/api/voice/token")
    async def token(request: Request, response: Response):
        authorize(request.headers)
        cfg = Config.load()
        cfg.require()
        jwt = AccessToken(cfg.account, cfg.key, cfg.secret, identity=cfg.identity, ttl=3600)
        jwt.add_grant(VoiceGrant(incoming_allow=True, push_credential_sid=cfg.push or None))
        response.headers["Cache-Control"] = "no-store"
        return {"token": jwt.to_jwt(), "identity": cfg.identity, "expires_in": 3600}

    @app.websocket("/ws/events")
    async def events(ws: WebSocket):
        try:
            authorize(ws.headers)
        except HTTPException:
            await ws.close(code=1008)
            return
        await ws.accept()
        if len(subscribers) >= 8:
            await ws.send_json({"type": "error", "call_sid": None, "message": "Subscriber limit reached"})
            await ws.close(code=1013)
            return
        queue = asyncio.Queue(maxsize=EVENT_BUFFER)
        subscribers.add(queue)
        receiver = asyncio.create_task(ws.receive())
        pending = None
        try:
            prune()
            await ws.send_json({"type": "ready", "call_sid": None, "identity": Config.load().identity,
                                "single_user": True, "asr_configured": app.state.transcribe is not None,
                                "calls": [{"call_sid": sid, "stream_sid": call["stream"],
                                           "status": call["status"], "analysis_status": call["analysis_status"],
                                           "message": call.get("message", "")} for sid, call in calls.items()]})
            while True:
                pending = asyncio.create_task(queue.get())
                done, _ = await asyncio.wait({pending, receiver}, return_when=asyncio.FIRST_COMPLETED)
                if receiver in done:
                    break
                event = pending.result()
                await asyncio.wait_for(ws.send_json(event), 10)
                if event.get("fatal"):
                    await ws.close(code=1013)
                    break
        except (WebSocketDisconnect, TimeoutError):
            pass
        finally:
            subscribers.discard(queue)
            for task in (receiver, pending):
                if task:
                    task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await task

    @app.post("/twilio/voice")
    async def voice(request: Request):
        cfg, form = await signed(request)
        sid = form.get("CallSid", "")
        if not SID.fullmatch(sid):
            raise HTTPException(400, "Valid CallSid required")
        prune()
        if sid not in calls:
            if len(calls) >= MAX_CALLS:
                raise HTTPException(503, "Call tracking capacity reached")
            calls[sid] = {"created": time.monotonic(), "stream": None, "active": False,
                          "status": "incoming", "analysis_status": "pending"}
            emit({"type": "call", "call_sid": sid, "status": "incoming"})
        response = VoiceResponse()
        response.say("Этот разговор будет анализироваться автоматической системой для выявления признаков мошенничества.",
                     language="ru-RU")
        start = Start()
        start.stream(url="wss://" + cfg.base.removeprefix("https://") + "/twilio/media",
                     track="inbound_track", status_callback=cfg.base + "/twilio/stream-status",
                     status_callback_method="POST")
        response.append(start)
        dial = Dial(action=cfg.base + "/twilio/status", method="POST")
        client = dial.client(status_callback=cfg.base + "/twilio/status",
                             status_callback_method="POST", status_callback_event="initiated ringing answered completed")
        client.identity(cfg.identity)
        client.parameter(name="parentCallSid", value=sid)
        response.append(dial)
        return Response(str(response), media_type="application/xml")

    @app.post("/twilio/status")
    @app.post("/twilio/stream-status")
    async def callback(request: Request):
        _, form = await signed(request)
        sid = form.get("ParentCallSid") or form.get("CallSid")
        prune()
        if sid not in calls:
            raise HTTPException(404, "Unknown CallSid")
        stream = form.get("StreamSid")
        call = calls[sid]
        if stream:
            if not STREAM_SID.fullmatch(stream) or call["stream"] not in (None, stream):
                raise HTTPException(409, "StreamSid does not match call")
            call["stream"] = stream
        state = form.get("StreamEvent") or form.get("DialCallStatus") or form.get("CallStatus")
        if not state:
            raise HTTPException(400, "Status required")
        emit({"type": "error" if state == "stream-error" else "call", "call_sid": sid,
              "stream_sid": stream, "status": state,
              **({"message": "Twilio reported a media stream error"} if state == "stream-error" else {})})
        return Response("<Response/>", media_type="application/xml")

    @app.websocket("/twilio/media")
    async def media(ws: WebSocket):
        cfg = Config.load()
        # Only configured external URLs; never use Host or forwarded headers.
        # Support the WSS URL and HTTPS upgrade form, including Twilio's documented
        # trailing-slash handshake normalization.
        urls = (cfg.base + "/twilio/media", "wss://" + cfg.base.removeprefix("https://") + "/twilio/media")
        if cfg.missing() or ws.url.query or not any(RequestValidator(cfg.auth).validate(
                url + suffix, {}, ws.headers.get("x-twilio-signature", ""))
                for url in urls for suffix in ("", "/")):
            await ws.close(code=1008)
            return
        await ws.accept()
        sid = stream = None
        worker = None
        owns_slot = False
        queue = asyncio.Queue(maxsize=MEDIA_QUEUE)
        def publish(event):
            emit({**event, "call_sid": sid, "stream_sid": stream})
        try:
            decoder = MulawDecoder()
            risk = RiskEngine()
            buffer = bytearray()
            total = new_bytes = offset = 0
            began = time.monotonic()
            async def process():
                while True:
                    item = await queue.get()
                    if item is None:
                        return
                    pcm, position = item
                    samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768
                    if not len(samples) or float(np.sqrt(np.mean(samples * samples))) < 10 ** (-55 / 20):
                        continue
                    try:
                        text = await asyncio.to_thread(app.state.transcribe, pcm, "ru")
                    except Exception:
                        raise RuntimeError("ASR processing failed") from None
                    if text:
                        publish({"type": "transcript", "text": text, "offset_seconds": position,
                                 "window_seconds": len(pcm) / 32000})
                        publish(risk.assess(text))
            while True:
                if worker and worker.done():
                    await worker
                raw = await asyncio.wait_for(ws.receive_text(), 20)
                if len(raw) > 16384:
                    raise ValueError("Media message too large")
                event = json.loads(raw)
                kind = event.get("event")
                if time.monotonic() - began > 900:
                    raise ValueError("Media session exceeds 15 minutes")
                if kind == "connected" and sid is None:
                    continue
                if kind == "start" and sid is None:
                    start = event["start"]
                    candidate = start.get("callSid")
                    candidate_stream = start.get("streamSid")
                    if start.get("accountSid") != cfg.account or candidate not in calls:
                        raise ValueError("Unknown call or account")
                    sid = candidate
                    if not isinstance(candidate_stream, str) or not STREAM_SID.fullmatch(candidate_stream):
                        raise ValueError("Invalid StreamSid")
                    stream = candidate_stream
                    call = calls[sid]
                    if call["stream"] not in (None, stream) or call["active"] or call.get("consumed"):
                        raise ValueError("Call already has a media stream")
                    if start.get("mediaFormat") != {"encoding": "audio/x-mulaw", "sampleRate": 8000, "channels": 1}:
                        raise ValueError("Expected mono mu-law 8000 Hz")
                    if app.state.busy:
                        raise ValueError("ASR is busy; single-user MVP supports one audio session")
                    if app.state.transcribe is None:
                        raise ValueError("ASR is not configured; enable whisper mode")
                    app.state.busy = owns_slot = True
                    call.update(stream=stream, active=True, consumed=True)
                    worker = asyncio.create_task(process())
                    publish({"type": "call", "status": "stream-started"})
                    continue
                if sid is None or worker is None or event.get("streamSid") != stream:
                    raise ValueError("Media must follow start and match StreamSid")
                if kind == "media":
                    if event["media"].get("track") != "inbound":
                        raise ValueError("Expected inbound audio track")
                    audio = base64.b64decode(event["media"]["payload"], validate=True)
                    if not audio or len(audio) > 4000:
                        raise ValueError("Invalid media payload size")
                    total += len(audio)
                    if total > 8000 * 900 or total > 8000 * ((time.monotonic() - began) * 1.5 + 10):
                        raise ValueError("Media duration or realtime rate limit exceeded")
                    pcm = decoder.decode(audio)
                    buffer.extend(pcm)
                    new_bytes += len(pcm)
                    while len(buffer) >= 128000:
                        queue.put_nowait((bytes(buffer[:128000]), offset))
                        del buffer[:96000]
                        offset += 3
                        new_bytes = len(buffer) - 32000
                elif kind == "stop":
                    if event.get("stop", {}).get("callSid", sid) != sid:
                        raise ValueError("Stop CallSid mismatch")
                    if new_bytes and len(buffer) >= 16000:
                        queue.put_nowait((bytes(buffer), offset))
                    await asyncio.wait_for(queue.put(None), 30)
                    await asyncio.wait_for(asyncio.shield(worker), 120)
                    publish({"type": "call", "status": "stream-stopped"})
                    await ws.close(code=1000)
                    return
                else:
                    raise ValueError("Unexpected media event")
        except WebSocketDisconnect:
            publish({"type": "error", "message": "Media disconnected before stop"})
        except Exception as exc:
            message = ("ASR queue overflow; media stopped" if isinstance(exc, asyncio.QueueFull) else
                       str(exc) if isinstance(exc, ValueError) else "Media processing failed or timed out")
            publish({"type": "error", "message": message})
            with contextlib.suppress(Exception):
                await ws.close(code=1011)
        finally:
            if worker:
                while not queue.empty():
                    queue.get_nowait()
                queue.put_nowait(None)
                # Do not cancel to_thread inference: keep the shared model slot until it finishes.
                with contextlib.suppress(Exception):
                    await worker
            if owns_slot:
                calls[sid]["active"] = False
                app.state.busy = False
