"""Bounded single-process P2P signaling and receiver-owned remote PCM analysis."""
import asyncio
import contextlib
import hmac
import json
import os
import re
import secrets
import time
from collections import deque
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from server.asr import AudioAnalysis, AnalysisError
from server.diagnostics import log, error
from server import push

RING_SECONDS = 60
JOIN_SECONDS = 10
ENDED_CALL_LIMIT = 16
ENDED_CALL_TTL = 120
MAX_JSON = 70000
ROOM = re.compile(r"[A-Za-z0-9_-]{8,64}\Z")
BPS = 32000


def page_headers(request):
    # Explicit WebSocket origin supports browsers where connect-src 'self'
    # does not cover ws/wss. Never interpolate unchecked Host header contents.
    connect = "'self'"
    configured = os.getenv("PUBLIC_BASE_URL", "")
    try:
        origin = urlsplit(configured or str(request.url))
        host = origin.hostname or ""
        if (origin.scheme in {"http", "https"} and not origin.username
                and not origin.password and re.fullmatch(r"[A-Za-z0-9.:-]+", host)
                and (configured or host in {"localhost", "127.0.0.1", "::1"})):
            authority = f"[{host}]" if ":" in host else host
            if origin.port is not None:
                authority += f":{origin.port}"
            connect += f" {'wss' if origin.scheme == 'https' else 'ws'}://{authority}"
    except ValueError:
        pass
    return {
        "Content-Security-Policy": (
            "default-src 'self'; script-src 'self'; style-src 'self'; "
            f"worker-src 'self'; connect-src {connect}; media-src 'self' blob:; "
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        ),
        "Permissions-Policy": "microphone=(self), camera=()",
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
    }


def ice_servers():
    raw = os.getenv("RTC_ICE_SERVERS_JSON", "")
    servers = json.loads(raw) if raw else [{"urls": "stun:stun.l.google.com:19302"}]
    if not isinstance(servers, list) or len(servers) > 8:
        raise ValueError("Invalid ICE configuration")
    for server in servers:
        if not isinstance(server, dict) or set(server) - {"urls", "username", "credential"}:
            raise ValueError("Invalid ICE configuration")
        urls = server.get("urls")
        urls = [urls] if isinstance(urls, str) else urls
        if not isinstance(urls, list) or not urls or len(urls) > 8:
            raise ValueError("Invalid ICE configuration")
        if any(not isinstance(u, str) or len(u) > 2048 or not u.startswith(("stun:", "stuns:", "turn:", "turns:")) for u in urls):
            raise ValueError("Invalid ICE configuration")
        if any(k in server and (not isinstance(server[k], str) or len(server[k]) > 2048) for k in ("username", "credential")):
            raise ValueError("Invalid ICE configuration")
    return servers


def allowed_origin(ws):
    origin = ws.headers.get("origin", "")
    try:
        parsed = urlsplit(origin)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
            return False
        base = urlsplit(os.getenv("PUBLIC_BASE_URL", ""))
        def identity(url):
            return url.scheme, url.hostname, url.port or (443 if url.scheme == "https" else 80)
        if base.hostname:
            return identity(parsed) == identity(base)
        return parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    except ValueError:
        return False


async def read_json(ws, timeout=JOIN_SECONDS):
    frame = await asyncio.wait_for(ws.receive(), timeout)
    if frame["type"] == "websocket.disconnect":
        raise WebSocketDisconnect()
    raw = frame.get("text")
    if raw is None or len(raw.encode("utf-8")) > MAX_JSON:
        raise ValueError("Invalid message size or format")
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object")
    return value


async def fail(ws, message="Invalid request", code=1008, *, error_code=None):
    with contextlib.suppress(Exception):
        event = {"type": "error", "message": message}
        if error_code is not None:
            event["code"] = error_code
        await ws.send_json(event)
        await ws.close(code=code)


class Hub:
    def __init__(self, room=None):
        self.lock = asyncio.Lock()
        self.room = room
        self.peers = {}
        self.call_id = None
        self.accepted = False
        self.offered = False
        self.answered = False
        self.ticket = None
        self.timer = None
        self.audio_end = asyncio.Event()
        self.ended_calls = deque(maxlen=ENDED_CALL_LIMIT)
        self.devices = {}
        self.push_tasks = set()
        self.push_target = None
        self.expires_at = 0

    def device(self, room):
        self.devices = {r: entry for r, entry in self.devices.items() if entry[1] > time.time()}
        return self.devices.get(room, (None, 0))[0] if push.configured() else None

    def deliver(self, token, data):
        async def deliver():
            sent = await asyncio.to_thread(push.send, token, data)
            if not sent and data["type"] == "incoming":
                async with self.lock:
                    if self.call_id == data["call_id"] and not self.accepted and "receiver" not in self.peers:
                        call_id = self.call_id
                        self.reset()
                        with contextlib.suppress(Exception):
                            await self.broadcast({"type": "rejected", "call_id": call_id, "reason": "push_unavailable"})
        task = asyncio.create_task(deliver())
        self.push_tasks.add(task)
        task.add_done_callback(self.push_tasks.discard)

    def is_ended_call(self, call_id, ws):
        now = time.monotonic()
        while self.ended_calls and self.ended_calls[0][0] <= now:
            self.ended_calls.popleft()
        return any(room == self.room and ended_id == call_id and ws in peers
                   for _, room, ended_id, peers in self.ended_calls)

    async def send(self, role, event):
        peer = self.peers.get(role)
        if peer:
            await asyncio.wait_for(peer.send_json(event), 5)

    async def broadcast(self, event):
        for role in tuple(self.peers):
            await self.send(role, event)

    def reset(self):
        if self.call_id and self.push_target:
            self.deliver(self.push_target, {"type": "cancel", "room": self.room, "call_id": self.call_id})
        self.push_target = None
        if self.call_id is not None:
            self.ended_calls.append((time.monotonic() + ENDED_CALL_TTL, self.room,
                                     self.call_id, tuple(self.peers.values())))
        self.audio_end.set()
        if self.timer and self.timer is not asyncio.current_task():
            self.timer.cancel()
        self.timer = None
        self.call_id = self.ticket = None
        self.accepted = self.offered = self.answered = False

    async def expire(self, call_id):
        await asyncio.sleep(RING_SECONDS)
        async with self.lock:
            if self.call_id == call_id and not self.accepted:
                self.reset()
                with contextlib.suppress(Exception):
                    await self.broadcast({"type": "rejected", "call_id": call_id, "reason": "timeout"})


class HubDirectory:
    """Independent signaling hubs keyed by the user's internal phone address."""
    def __init__(self):
        self.hubs = {}

    def for_room(self, room):
        hub = self.hubs.get(room)
        if hub is None:
            hub = Hub(room)
            self.hubs[room] = hub
        return hub

    def find(self, room):
        return self.hubs.get(room) if isinstance(room, str) else None

    def registered_devices(self):
        return sum(bool(hub.device(room)) for room, hub in tuple(self.hubs.items()))

    def prune(self, room, hub):
        if self.hubs.get(room) is hub and not hub.peers and not hub.call_id and not hub.device(room):
            self.hubs.pop(room, None)


def install_rtc(app):
    directory = HubDirectory()
    app.state.rtc = directory
    static = Path(__file__).parent / "static" / "rtc"
    app.mount("/rtc-assets", StaticFiles(directory=str(static), check_dir=False), name="rtc-assets")

    @app.get("/rtc")
    async def rtc_page(request: Request):
        headers = page_headers(request)
        path = static / "index.html"
        if not path.is_file():
            raise HTTPException(503, "RTC frontend is not installed", headers=headers)
        return FileResponse(path, headers=headers)

    @app.get("/api/rtc/status")
    async def status():
        try:
            servers = ice_servers()
            turn = any(u.startswith(("turn:", "turns:")) for s in servers for u in ([s["urls"]] if isinstance(s["urls"], str) else s["urls"]))
        except (ValueError, TypeError):
            turn = False
        return {"asr_configured": app.state.analysis is not None, "turn_configured": turn}

    @app.post("/api/rtc/push")
    async def push_action(request: Request):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 8192:
                raise HTTPException(413)
        try:
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError()
        except (ValueError, UnicodeError):
            raise HTTPException(400, "Invalid request")
        room = data.get("room")
        hub = directory.find(room)
        if hub is None:
            raise HTTPException(404, "Call unavailable")
        async with hub.lock:
            token = data.get("token")
            valid = (isinstance(token, str) and hub.push_target is not None
                     and hmac.compare_digest(token.encode(), hub.push_target.encode())
                     and data.get("room") == hub.room and data.get("call_id") == hub.call_id
                     and not hub.accepted and hub.expires_at > time.time())
            if not valid:
                raise HTTPException(404, "Call unavailable")
            if data.get("action") == "reject":
                call_id = hub.call_id
                hub.reset()
                await hub.broadcast({"type": "rejected", "call_id": call_id})
            elif data.get("action") != "check":
                raise HTTPException(400, "Invalid action")
            return {"ok": True}

    @app.websocket("/ws/rtc")
    async def signaling(ws: WebSocket):
        if not allowed_origin(ws) or ws.query_params:
            await ws.close(code=1008)
            return
        await ws.accept()
        role = None
        room = None
        hub = None
        joined = False
        try:
            start = await read_json(ws)
            role, room = start.get("role"), start.get("room")
            if start.get("type") != "join" or start.get("consent") is not True or role not in {"caller", "receiver"} or not isinstance(room, str) or not ROOM.fullmatch(room):
                raise ValueError("Invalid join")
            servers = ice_servers()
            hub = directory.for_room(room)
            async with hub.lock:
                resume = start.get("resume_call")
                if resume is not None and (role != "receiver" or resume != hub.call_id or hub.accepted or hub.room != room):
                    raise ValueError("Call expired")
                if role in hub.peers:
                    raise ValueError("Room or role is busy")
                hub.peers[role] = ws
                joined = True
                other = "receiver" if role == "caller" else "caller"
                await hub.send(role, {"type": "ready", "role": role, "room": room,
                                      "ice_servers": servers, "peer_present": other in hub.peers or (role == "caller" and bool(hub.device(room)))})
                await hub.send(other, {"type": "peer-joined", "role": role})
                if role == "receiver" and hub.call_id and not hub.accepted:
                    await hub.send(role, {"type": "incoming", "call_id": hub.call_id})
            rates = deque()
            while True:
                event = await read_json(ws, 900)
                now = time.monotonic()
                while rates and rates[0] < now - 10:
                    rates.popleft()
                rates.append(now)
                if len(rates) > 120:
                    raise ValueError("Signaling rate exceeded")
                kind = event.get("type")
                async with hub.lock:
                    if kind == "push-register":
                        token = event.get("token")
                        if role != "receiver" or not isinstance(token, str) or not 20 <= len(token) <= 4096:
                            raise ValueError("Invalid push registration")
                        hub.device(room)  # Expire old registrations before enforcing bound.
                        if room not in hub.devices and directory.registered_devices() >= 128:
                            raise ValueError("Push registration capacity reached")
                        hub.devices[room] = (token, time.time() + 86400)
                        await hub.send(role, {"type": "push-ready", "enabled": push.configured()})
                        continue
                    if kind == "push-unregister":
                        if role != "receiver":
                            raise ValueError("Invalid push role")
                        hub.devices.pop(room, None)
                        continue
                    if kind == "invite":
                        if role != "caller" or (len(hub.peers) != 2 and not hub.device(room)) or hub.call_id:
                            raise ValueError("Cannot invite")
                        hub.call_id = secrets.token_urlsafe(18)
                        hub.audio_end = asyncio.Event()
                        hub.timer = asyncio.create_task(hub.expire(hub.call_id))
                        hub.expires_at = int(time.time() + RING_SECONDS)
                        if "receiver" not in hub.peers:
                            hub.push_target = hub.device(room)
                            hub.deliver(hub.push_target, {"type": "incoming", "room": room,
                                        "call_id": hub.call_id, "expires_at": str(hub.expires_at)})
                        await hub.broadcast({"type": "incoming", "call_id": hub.call_id})
                        continue
                    if not hub.call_id or event.get("call_id") != hub.call_id:
                        if kind in {"ice", "offer", "answer", "hangup", "reject", "accept"} and hub.is_ended_call(event.get("call_id"), ws):
                            continue
                        raise ValueError("No matching live call")
                    call_id = hub.call_id
                    if kind in {"accept", "reject"}:
                        if role != "receiver" or hub.accepted:
                            raise ValueError("Invalid call decision")
                        if kind == "reject":
                            hub.reset()
                            await hub.broadcast({"type": "rejected", "call_id": call_id})
                        else:
                            hub.accepted = True
                            if hub.push_target:
                                hub.deliver(hub.push_target, {"type": "cancel", "room": room, "call_id": call_id})
                            hub.ticket = secrets.token_urlsafe(32)
                            if hub.timer:
                                hub.timer.cancel()
                            await hub.send("caller", {"type": "accepted", "call_id": call_id})
                            await hub.send("receiver", {"type": "accepted", "call_id": call_id, "audio_ticket": hub.ticket})
                    elif kind == "hangup":
                        hub.reset()
                        await hub.broadcast({"type": "ended", "call_id": call_id})
                    elif kind in {"offer", "answer", "ice"}:
                        if not hub.accepted:
                            raise ValueError("Call not accepted")
                        relay = {"type": kind, "call_id": call_id}
                        if kind in {"offer", "answer"}:
                            if (kind == "offer" and (role != "caller" or hub.offered)) or (kind == "answer" and (role != "receiver" or not hub.offered or hub.answered)):
                                raise ValueError("Invalid SDP direction or state")
                            sdp = event.get("sdp")
                            if not isinstance(sdp, str) or not sdp or len(sdp.encode()) > 65536:
                                raise ValueError("Invalid SDP")
                            relay["sdp"] = sdp
                            if kind == "offer":
                                hub.offered = True
                            else:
                                hub.answered = True
                        else:
                            candidate = event.get("candidate")
                            if candidate is not None and (not isinstance(candidate, dict) or len(json.dumps(candidate)) > 4096):
                                raise ValueError("Invalid ICE candidate")
                            relay["candidate"] = candidate
                        await hub.send(other, relay)
                    else:
                        raise ValueError("Unknown signaling message")
        except WebSocketDisconnect:
            pass
        except (ValueError, TypeError, TimeoutError):
            await fail(ws)
        except Exception:
            await fail(ws, "Signaling unavailable", 1011)
        finally:
            if hub is not None:
                if joined:
                    async with hub.lock:
                        if hub.peers.get(role) is ws:
                            del hub.peers[role]
                            call_id = hub.call_id
                            hub.reset()
                            with contextlib.suppress(Exception):
                                await hub.broadcast({"type": "peer-left", "role": role, "call_id": call_id,
                                                     "push_available": role == "receiver" and bool(hub.device(hub.room))})
                            if not hub.peers:
                                hub.ended_calls.clear()
                directory.prune(room, hub)

    @app.websocket("/ws/rtc-audio")
    async def audio(ws: WebSocket):
        from server.app import level
        trace = secrets.token_hex(4)
        if not allowed_origin(ws) or ws.query_params:
            log.warning("RTC_AUDIO_REJECT trace=%s reason=origin_or_query", trace)
            await ws.close(code=1008)
            return
        await ws.accept()
        log.info("RTC_AUDIO_SOCKET trace=%s waiting_for_start=true", trace)
        analysis = None
        acquired = False
        disconnected = False
        send_lock = asyncio.Lock()
        phase = "waiting_for_start"
        hub = None
        total = 0
        packet_count = 0
        last_progress = time.monotonic()

        async def emit(event):
            if not disconnected:
                async with send_lock:
                    await asyncio.wait_for(ws.send_json(event), 5)

        try:
            start = await read_json(ws)
            phase = "validating_format"
            if any(start.get(k) != v for k, v in {"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"}.items()) or start.get("consent") is not True or start.get("language", "ru") not in {"ru", "kk", "en"}:
                raise ValueError("Expected consent and PCM s16le, 16000 Hz, mono")
            if app.state.analysis and start.get("analysis_provider") != "google":
                raise AnalysisError("consent_required", "Explicit consent to send audio to Google is required: analysis_provider=google.")
            hub = directory.find(start.get("room"))
            if hub is None:
                raise ValueError("Unknown address")
            async with hub.lock:
                phase = "validating_ticket"
                ticket = start.get("audio_ticket")
                if not hub.accepted or "receiver" not in hub.peers or start.get("room") != hub.room or start.get("call_id") != hub.call_id or not isinstance(ticket, str) or not hub.ticket or not hmac.compare_digest(ticket.encode(), hub.ticket.encode()):
                    raise ValueError("Invalid receiver audio ticket")
                if app.state.busy:
                    log.warning("RTC_AUDIO_BUSY trace=%s ticket_consumed=false", trace)
                    await fail(ws, "Analysis is busy", error_code="busy")
                    return
                hub.ticket = None  # Single use, never reconnect active audio implicitly.
                app.state.busy = True
                acquired = True
                ended = hub.audio_end
            language = start.get("language", "ru")
            phase = "connecting"
            log.info("RTC_ANALYSIS_CONNECTING trace=%s", trace)
            async def guarded_emit(event):
                if not ended.is_set():
                    await emit(event)
            analysis = AudioAnalysis(app.state.analysis, guarded_emit, language)
            if not await analysis.start(ended):
                await emit({"type": "done", "reason": "call-ended", "audio_seconds": 0})
                await ws.close()
                return
            phase = "streaming"
            log.info("RTC_AUDIO_READY trace=%s mode=%s", trace, app.state.mode)
            await emit({"type": "ready", "mode": app.state.mode,
                        "message": "Gemini Live ready" if app.state.analysis else "Analysis is not configured; no transcription or risk analysis"})
            began = time.monotonic()
            frames = deque()
            while True:
                now = time.monotonic()
                if now - last_progress >= 5:
                    log.info("RTC_AUDIO_PROGRESS trace=%s frames=%d audio_seconds=%.2f queue=%d", trace, packet_count, total / BPS, analysis.queue.qsize())
                    last_progress = now
                if ended.is_set():
                    break
                analysis.check()
                if time.monotonic() - began > 900:
                    raise ValueError("Audio session limit reached")
                try:
                    frame = await asyncio.wait_for(ws.receive(), 1)
                except TimeoutError:
                    continue
                if frame["type"] == "websocket.disconnect":
                    disconnected = True
                    break
                raw = frame.get("bytes")
                if raw is None:
                    value = frame.get("text") or ""
                    if len(value) > 256 or json.loads(value) != {"type": "stop"}:
                        raise ValueError("Expected binary PCM or stop")
                    await analysis.finish()
                    await emit({"type": "done", "audio_seconds": round(total / BPS, 2)})
                    await ws.close()
                    return
                if not raw or len(raw) % 2 or len(raw) > 8000:
                    raise ValueError("PCM frame must contain 2 to 8000 even bytes")
                now = time.monotonic()
                while frames and frames[0] < now - 10:
                    frames.popleft()
                frames.append(now)
                if len(frames) > 400:
                    raise ValueError("Audio frame rate exceeded")
                total += len(raw)
                packet_count += 1
                if packet_count == 1:
                    log.info("RTC_PCM_FIRST trace=%s bytes=%d dbfs=%.1f", trace, len(raw), level(raw))
                if total > BPS * 900 or total > BPS * ((time.monotonic() - began) * 1.5 + 4):
                    raise ValueError("Audio duration or rate exceeded")
                analysis.feed(raw)
            if not disconnected:
                await emit({"type": "done", "reason": "call-ended", "audio_seconds": round(total / BPS, 2)})
                await ws.close()
        except WebSocketDisconnect:
            disconnected = True
        except AnalysisError as exc:
            error("RTC_AUDIO_FAILED", exc, trace=trace, phase=phase)
            await fail(ws, str(exc), 1011, error_code=exc.code)
        except (ValueError, TypeError, TimeoutError, asyncio.QueueFull) as exc:
            error("RTC_AUDIO_FAILED", exc, trace=trace, phase=phase)
            await fail(ws, "Invalid audio or processing limit exceeded")
        except Exception as exc:
            error("RTC_AUDIO_FAILED", exc, trace=trace, phase=phase)
            await fail(ws, "Audio processing failed", 1011)
        finally:
            log.info("RTC_AUDIO_CLOSING trace=%s phase=%s frames=%d audio_seconds=%.2f", trace, phase, packet_count, total / BPS)
            disconnected = True
            try:
                if analysis:
                    await analysis.close()
            finally:
                if acquired:
                    app.state.busy = False
                    log.info("ANALYSIS_SLOT_RELEASED trace=%s", trace)
