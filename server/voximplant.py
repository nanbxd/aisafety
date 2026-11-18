"""Voximplant audio ingress and call-scoped Android events (single-process MVP)."""
import asyncio
import base64
import contextlib
import json
import re
import time
import uuid

from fastapi import WebSocket, WebSocketDisconnect
from server.asr import AudioAnalysis, AnalysisError
from server.diagnostics import error

CALL_ID = re.compile(r"[A-Za-z0-9_-]{8,128}\Z")
MAX_TEXT = 24000


def user_id(event):
    value = event.get("user_id")
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_+@.\-]{1,128}", value):
        raise ValueError("Invalid user_id")
    if "call_id" in event:
        raise ValueError("Specify user_id or call_id, not both")
    return value


async def read_event(ws, timeout=10):
    frame = await asyncio.wait_for(ws.receive(), timeout)
    if frame["type"] == "websocket.disconnect":
        raise WebSocketDisconnect()
    raw = frame.get("text")
    if raw is None or len(raw) > MAX_TEXT:
        raise ValueError("Expected bounded JSON message")
    event = json.loads(raw)
    if not isinstance(event, dict):
        raise ValueError("Expected JSON object")
    return event


def call_id(event):
    value = event.get("call_id")
    if not isinstance(value, str) or not CALL_ID.fullmatch(value):
        raise ValueError("call_id must contain 8-128 ASCII letters, digits, _ or -")
    return value


async def send(ws, event):
    await asyncio.wait_for(ws.send_json(event), 3)


async def reject(ws, code, message):
    with contextlib.suppress(Exception):
        await send(ws, {"type": "ERROR", "code": code, "message": message})
        await ws.close(code=1008)


def install_voximplant(app):
    # Per-app state, never a module-global socket. A second subscriber cannot steal a call.
    subscribers = {}
    users = {}
    routes = {}
    streams = set()

    async def publish(cid, event):
        route = routes.get(cid)
        peer = subscribers.get(cid) if route is None else users.get(route[0])
        if route is not None and peer is not route[1]:
            peer = None
        if peer is None:
            raise AnalysisError("android_disconnected", "Android is disconnected; analysis stopped.")
        ws, lock = peer
        async with lock:
            await send(ws, {**event, "call_id": cid})

    @app.websocket("/ws/android")
    async def android(ws: WebSocket):
        await ws.accept()
        cid = None
        owned = False
        registry = subscribers
        identity_field = "call_id"
        try:
            event = await read_event(ws)
            if "user_id" in event:
                cid = user_id(event)
                registry = users
                identity_field = "user_id"
            else:
                cid = call_id(event)
            if event.get("type") != "subscribe" or event.get("consent") is not True:
                raise ValueError("subscribe and explicit consent are required")
            if (cid in registry or (registry is subscribers and cid in streams)
                    or len(subscribers) + len(users) >= 16):
                raise AnalysisError("busy", "This call already has a subscriber or the server is full.")
            registry[cid] = (ws, asyncio.Lock())
            owned = True
            async def reply(kind):
                async with registry[cid][1]:
                    await send(ws, {"type": kind, identity_field: cid})
            await reply("SUBSCRIBED")
            while True:
                event = await read_event(ws, timeout=90)
                if event.get("type") != "ping":
                    raise ValueError("Only ping is accepted after subscribe")
                await reply("pong")
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            await reject(ws, exc.code if isinstance(exc, AnalysisError) else "invalid_subscription",
                         str(exc) if isinstance(exc, AnalysisError) else "Invalid subscription or heartbeat timeout.")
        finally:
            if owned and registry.get(cid, (None,))[0] is ws:
                del registry[cid]

    @app.websocket("/media-stream")
    async def media(ws: WebSocket):
        await ws.accept()
        cid = None
        owned = False
        analysis = None
        terminal = {"type": "CALL_ENDED", "complete": False}
        try:
            start = await read_event(ws)
            if start.get("event") == "init":
                start = await read_event(ws)
            uid = user_id(start) if "user_id" in start else None
            cid = uuid.uuid4().hex if uid is not None else call_id(start)
            if (start.get("type") != "start" or start.get("consent") is not True
                    or start.get("analysis_provider") != "google"
                    or start.get("format") != "pcm_s16le" or start.get("sample_rate") != 16000
                    or start.get("channels") != 1 or start.get("language", "ru") not in {"ru", "kk"}):
                raise ValueError("Expected consented PCM s16le, 16000 Hz, mono start")
            peer = users.get(uid) if uid is not None else subscribers.get(cid)
            if peer is None:
                raise AnalysisError("android_required", "Subscribe Android to this call before streaming.")
            if app.state.vox_analysis is None:
                raise AnalysisError("analysis_unavailable", "Set ANALYSIS_MODE=gemini to enable analysis.")
            if app.state.busy:
                raise AnalysisError("busy", "Another audio analysis session is active.")
            # No await between admission and reservation: shares the existing single ASR slot.
            app.state.busy = True
            streams.add(cid)
            owned = True
            if uid is not None:
                routes[cid] = (uid, peer)
                await publish(cid, {"type": "CALL_ASSIGNED", "user_id": uid})

            async def emit(event):
                if event.get("type") == "fraud_report":
                    await publish(cid, {"type": "FRAUD_ALERT", "data": event["data"]})
                elif event.get("type") == "transcript":
                    await publish(cid, {"type": "TRANSCRIPT", "text": event["text"]})

            analysis = AudioAnalysis(app.state.vox_analysis, emit, start.get("language", "ru"))
            await analysis.start()
            await publish(cid, {"type": "ANALYSIS_STARTED"})
            await send(ws, {"customEvent": "safety-status", "type": "ready", "call_id": cid, "sample_rate": 16000})
            started = last_audio = time.monotonic()
            total = 0
            metadata_count = 0
            while True:
                analysis.check()
                if ((uid is None and cid not in subscribers)
                        or (uid is not None and users.get(uid) is not peer)):
                    raise AnalysisError("android_disconnected", "Android disconnected; analysis stopped.")
                now = time.monotonic()
                if now - started > 900 or now - last_audio > 20:
                    raise AnalysisError("stream_timeout", "Audio idle timeout or 15-minute session limit.")
                try:
                    frame = await asyncio.wait_for(ws.receive(), 1)
                except TimeoutError:
                    continue
                if frame["type"] == "websocket.disconnect":
                    break
                pcm = frame.get("bytes")
                if pcm is None:
                    text = frame.get("text", "")
                    if len(text) > MAX_TEXT:
                        raise ValueError("Metadata too large")
                    event = json.loads(text)
                    if not isinstance(event, dict):
                        raise ValueError("Invalid metadata")
                    kind = event.get("event", event.get("type"))
                    if kind == "stop":
                        await analysis.finish()
                        terminal = {"type": "CALL_ENDED", "complete": True}
                        await send(ws, {"customEvent": "safety-status", "type": "done", "call_id": cid})
                        break
                    if kind == "media":
                        payload = event.get("media", {}).get("payload")
                        if not isinstance(payload, str):
                            raise ValueError("Missing base64 media payload")
                        pcm = base64.b64decode(payload, validate=True)
                    elif kind in {"start", "init", "connected"}:
                        metadata_count += 1
                        if metadata_count > 2:
                            raise ValueError("Repeated stream metadata")
                        if kind == "start":
                            fmt = event.get("start", {}).get("mediaFormat", {})
                            if (fmt.get("sampleRate") != 16000 or fmt.get("channels") != 1
                                    or str(fmt.get("encoding", "")).lower() not in
                                    {"pcm16", "audio/pcm", "audio/x-l16", "audio/l16"}):
                                raise ValueError("Voximplant must send PCM16 at 16000 Hz, mono")
                        continue
                    else:
                        raise ValueError("Unsupported media event")
                if not pcm or len(pcm) % 2 or len(pcm) > 16000 or pcm.startswith(b"RIFF"):
                    raise ValueError("Expected raw PCM16 frame, 2-16000 bytes, without WAV header")
                total += len(pcm)
                last_audio = time.monotonic()
                if total > 32000 * 900 or total > 32000 * ((last_audio - started) * 1.5 + 10):
                    raise ValueError("Audio exceeds realtime or session limit")
                analysis.feed(pcm)
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            error("VOX_STREAM_FAILED", exc)
            code = exc.code if isinstance(exc, AnalysisError) else "invalid_audio"
            message = str(exc) if isinstance(exc, AnalysisError) else "Invalid audio stream or processing failure."
            terminal = {"type": "ANALYSIS_ERROR", "code": code, "message": message, "complete": False}
            await reject(ws, code, message)
        finally:
            try:
                if analysis:
                    await analysis.close()
                if owned:
                    with contextlib.suppress(Exception):
                        await publish(cid, terminal)
            finally:
                if owned:
                    streams.discard(cid)
                    routes.pop(cid, None)
                    app.state.busy = False
                with contextlib.suppress(Exception):
                    await ws.close()
