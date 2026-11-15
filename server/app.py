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

from server.asr import AudioAnalysis, AnalysisError, GeminiLive
from server.rtc import install_rtc
from server.diagnostics import log, error

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2
MAX_FRAME_BYTES = 16000


def level(pcm: bytes) -> float:
    samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768
    rms = float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0
    return round(20 * math.log10(max(rms, 0.000001)), 1)


def create_app(analysis_backend=None, token: str | None = None, *, load_environment: bool = False) -> FastAPI:
    """Build an app; the deployment entry point opts into local dotenv loading."""
    @asynccontextmanager
    async def lifespan(app):
        if load_environment:
            load_dotenv(override=False)
        app.state.token = token if token is not None else os.getenv("SAFETY_TOKEN", "")
        if len(app.state.token) < 16:
            raise RuntimeError("Set SAFETY_TOKEN to a random secret of at least 16 characters.")
        mode = os.getenv("ANALYSIS_MODE", "diagnostic")
        if mode not in {"diagnostic", "gemini"}:
            raise RuntimeError("ANALYSIS_MODE must be diagnostic or gemini")
        app.state.analysis = analysis_backend
        if analysis_backend is None and mode == "gemini":
            app.state.analysis = GeminiLive()
        app.state.mode = "gemini" if app.state.analysis else "diagnostic"
        app.state.busy = False
        log.info("ANALYSIS_SERVICE_READY mode=%s", app.state.mode)
        yield

    app = FastAPI(title="SafetyHack Voice", lifespan=lifespan)
    install_rtc(app)

    @app.get("/health")
    async def health():
        return {"status": "ok", "mode": app.state.mode, "busy": app.state.busy}

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
        analysis = None
        try:
            start = json.loads(await asyncio.wait_for(ws.receive_text(), timeout=10))
            if not isinstance(start, dict) or any(start.get(k) != v for k, v in {
                "type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"
            }.items()) or start.get("language", "ru") not in {"ru", "kk", "en"}:
                raise ValueError("Нужен start: PCM s16le, 16000 Hz, mono, язык ru/kk/en.")
            language = start.get("language", "ru")
            if app.state.analysis and (start.get("consent") is not True or start.get("analysis_provider") != "google"):
                raise AnalysisError("consent_required", "Explicit consent to send audio to Google is required: consent=true, analysis_provider=google.")
            send_lock = asyncio.Lock()

            async def emit(event):
                async with send_lock:
                    await ws.send_json(event)

            analysis = AudioAnalysis(app.state.analysis, emit, language)
            await analysis.start()
            await emit({"type": "ready", "mode": app.state.mode})
            total = 0
            last_meter = 0
            received_at = time.monotonic()
            last_received = received_at
            while True:
                analysis.check()
                try:
                    frame = await asyncio.wait_for(ws.receive(), timeout=1)
                except TimeoutError:
                    if time.monotonic() - last_received >= 20:
                        raise
                    continue
                last_received = time.monotonic()
                if frame["type"] == "websocket.disconnect":
                    break
                raw = frame.get("bytes")
                if raw is None:
                    event = json.loads(frame.get("text") or "{}")
                    if not isinstance(event, dict) or event.get("type") != "stop":
                        raise ValueError("Ожидается бинарное аудио или stop.")
                    await analysis.finish()
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
                analysis.feed(raw)
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            error("AUDIO_FAILED", exc)
            message = str(exc) if isinstance(exc, AnalysisError) else "Invalid audio or processing failure."
            with contextlib.suppress(Exception):
                await ws.send_json({"type": "error", "code": exc.code if isinstance(exc, AnalysisError) else "invalid_audio", "message": message})
                await ws.close(code=1011)
        finally:
            try:
                if analysis:
                    await analysis.close()
            finally:
                app.state.busy = False

    return app


app = create_app(load_environment=True)
