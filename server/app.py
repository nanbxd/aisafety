import asyncio
import contextlib
import hmac
import json
import math
import os
import time
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from dotenv import load_dotenv

from server.risk import RiskEngine
from server.rtc import install_rtc

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2
WINDOW_BYTES = BYTES_PER_SECOND * 4
OVERLAP_BYTES = BYTES_PER_SECOND
MAX_FRAME_BYTES = 16000


def level(pcm: bytes) -> float:
    samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768
    rms = float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0
    return round(20 * math.log10(max(rms, 0.000001)), 1)


def create_app(transcriber=None, token: str | None = None, *, load_environment: bool = False) -> FastAPI:
    """Build an app; the deployment entry point opts into local dotenv loading."""
    @asynccontextmanager
    async def lifespan(app):
        if load_environment:
            load_dotenv(override=False)
        app.state.token = token if token is not None else os.getenv("SAFETY_TOKEN", "")
        if len(app.state.token) < 16:
            raise RuntimeError("Set SAFETY_TOKEN to a random secret of at least 16 characters.")
        app.state.transcribe = transcriber
        mode = os.getenv("ASR_MODE", "diagnostic")
        if mode not in {"diagnostic", "whisper"}:
            raise RuntimeError("ASR_MODE must be diagnostic or whisper")
        if transcriber is None and mode == "whisper":
            from server.asr import WhisperTranscriber
            app.state.transcribe = await asyncio.to_thread(WhisperTranscriber)
        app.state.busy = False
        yield

    app = FastAPI(title="SafetyHack Voice", lifespan=lifespan)
    # The P2P mode must boot without the optional Twilio SDK installed.
    try:
        from server.telephony import install_telephony
    except ModuleNotFoundError as exc:
        if exc.name != "twilio" and not (exc.name or "").startswith("twilio."):
            raise
    else:
        install_telephony(app)
    install_rtc(app)

    @app.get("/health")
    async def health():
        return {"status": "ok", "mode": "whisper" if app.state.transcribe else "diagnostic", "busy": app.state.busy}

    @app.websocket("/ws/audio")
    async def audio(ws: WebSocket):
        supplied = ws.headers.get("authorization", "")
        if not hmac.compare_digest(supplied, "Bearer " + app.state.token):
            await ws.close(code=1008)
            return
        await ws.accept()
        if app.state.busy:
            await ws.send_json({"type": "error", "message": "Сервер занят другой сессией."})
            await ws.close(code=1013)
            return
        app.state.busy = True
        worker = None
        try:
            start = json.loads(await asyncio.wait_for(ws.receive_text(), timeout=10))
            if not isinstance(start, dict) or any(start.get(k) != v for k, v in {
                "type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"
            }.items()) or start.get("language", "ru") not in {"ru", "kk", "en"}:
                raise ValueError("Нужен start: PCM s16le, 16000 Hz, mono, язык ru/kk/en.")
            language = start.get("language", "ru")
            await ws.send_json({"type": "ready", "mode": "whisper" if app.state.transcribe else "diagnostic"})
            queue = asyncio.Queue(maxsize=3)
            send_lock = asyncio.Lock()
            risk = RiskEngine()

            async def emit(event):
                async with send_lock:
                    await ws.send_json(event)

            async def process():
                while True:
                    item = await queue.get()
                    if item is None:
                        return
                    pcm, offset = item
                    if app.state.transcribe is None or level(pcm) < -55:
                        continue
                    began = time.monotonic()
                    text = await asyncio.to_thread(app.state.transcribe, pcm, language)
                    if text:
                        await emit({"type": "transcript", "text": text, "offset_seconds": offset,
                                    "window_seconds": len(pcm) / BYTES_PER_SECOND,
                                    "processing_ms": round((time.monotonic() - began) * 1000)})
                        await emit(risk.assess(text))

            worker = asyncio.create_task(process())
            buffer = bytearray()
            total = 0
            offset = 0
            last_meter = 0
            received_at = time.monotonic()
            new_bytes = 0
            while True:
                if worker.done():
                    await worker
                frame = await asyncio.wait_for(ws.receive(), timeout=20)
                if frame["type"] == "websocket.disconnect":
                    break
                raw = frame.get("bytes")
                if raw is None:
                    event = json.loads(frame.get("text") or "{}")
                    if not isinstance(event, dict) or event.get("type") != "stop":
                        raise ValueError("Ожидается бинарное аудио или stop.")
                    if new_bytes and len(buffer) >= BYTES_PER_SECOND // 2:
                        queue.put_nowait((bytes(buffer), offset))
                    await asyncio.wait_for(queue.put(None), timeout=30)
                    await asyncio.wait_for(asyncio.shield(worker), timeout=120)
                    await emit({"type": "done", "audio_seconds": round(total / BYTES_PER_SECOND, 2)})
                    await ws.close(code=1000)
                    break
                if not raw or len(raw) % 2 or len(raw) > MAX_FRAME_BYTES:
                    raise ValueError("Некорректный PCM-пакет: нужен чётный размер 2–16000 байт.")
                total += len(raw)
                elapsed = time.monotonic() - received_at
                if total > BYTES_PER_SECOND * 900 or elapsed > 900:
                    raise ValueError("Лимит тестовой сессии: 15 минут.")
                if total > BYTES_PER_SECOND * (elapsed * 1.5 + 10):
                    raise ValueError("Аудио поступает быстрее реального времени.")
                if time.monotonic() - last_meter >= 0.25:
                    db = level(raw)
                    await emit({"type": "level", "dbfs": db, "silent": db < -55,
                                "audio_seconds": round(total / BYTES_PER_SECOND, 2)})
                    last_meter = time.monotonic()
                buffer.extend(raw)
                new_bytes += len(raw)
                while len(buffer) >= WINDOW_BYTES:
                    queue.put_nowait((bytes(buffer[:WINDOW_BYTES]), offset))
                    del buffer[:WINDOW_BYTES - OVERLAP_BYTES]
                    offset += 3
                    new_bytes = len(buffer) - OVERLAP_BYTES
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            if isinstance(exc, asyncio.QueueFull):
                message = "Распознавание не успевает. Выберите меньшую модель или GPU; сессия остановлена."
            elif isinstance(exc, (ValueError, TimeoutError)):
                message = str(exc) or "Истекло время ожидания аудио или распознавания."
            else:
                message = "Ошибка обработки аудио на сервере. Проверьте настройки модели."
            with contextlib.suppress(Exception):
                await ws.send_json({"type": "error", "message": message})
                await ws.close(code=1011)
        finally:
            # Wait for a running inference before admitting the next session:
            # cancelling to_thread does not stop the underlying model invocation.
            if worker is not None and not worker.done():
                with contextlib.suppress(asyncio.QueueFull):
                    queue.put_nowait(None)
                # Pending queue entries must not prolong a disconnected session.
                while not queue.empty():
                    queue.get_nowait()
                queue.put_nowait(None)
                with contextlib.suppress(Exception):
                    await worker
            elif worker is not None:
                with contextlib.suppress(Exception):
                    worker.result()
            app.state.busy = False

    return app


app = create_app(load_environment=True)
