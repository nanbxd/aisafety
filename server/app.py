import asyncio
import contextlib
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
from server.voximplant import install_voximplant
from server.diagnostics import log, error

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2
MAX_FRAME_BYTES = 16000
IDLE_TIMEOUT_S = 20
SESSION_LIMIT_S = 900


def level(pcm: bytes) -> float:
    samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768
    rms = float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0
    return round(20 * math.log10(max(rms, 0.000001)), 1)


def create_app(analysis_backend=None, *, load_environment: bool = False) -> FastAPI:
    """Build an app; the deployment entry point opts into local dotenv loading."""

    @asynccontextmanager
    async def lifespan(app):
        if load_environment:
            load_dotenv(override=False)
        mode = os.getenv("ANALYSIS_MODE", "diagnostic")
        if mode not in {"diagnostic", "gemini"}:
            raise RuntimeError("ANALYSIS_MODE must be diagnostic or gemini")
        app.state.analysis = analysis_backend
        if analysis_backend is None and mode == "gemini":
            app.state.analysis = GeminiLive()
        app.state.vox_analysis = analysis_backend
        if analysis_backend is None and mode == "gemini":
            app.state.vox_analysis = GeminiLive(fraud_alerts=True)
        app.state.mode = "gemini" if app.state.analysis else "diagnostic"
        app.state.busy = False
        log.info("ANALYSIS_SERVICE_READY mode=%s", app.state.mode)
        yield

    app = FastAPI(title="Skepticall Voice", lifespan=lifespan)
    install_rtc(app)
    install_voximplant(app)

    @app.get("/")
    @app.get("/health")
    async def health():
        return {"status": "ok", "mode": app.state.mode, "busy": app.state.busy}

    @app.websocket("/ws/audio")
    async def audio(ws: WebSocket):
        await ws.accept()
        if app.state.busy:
            with contextlib.suppress(Exception):
                await ws.send_json(
                    {"type": "error", "message": "Сервер занят другой сессией."}
                )
                await ws.close(code=1013)
            return

        app.state.busy = True
        analysis: AudioAnalysis | None = None
        closed = False

        async def safe_close(code: int = 1000) -> None:
            nonlocal closed
            if closed:
                return
            closed = True
            with contextlib.suppress(Exception):
                await ws.close(code=code)

        try:
            try:
                raw_start = await asyncio.wait_for(ws.receive_text(), timeout=10)
                start = json.loads(raw_start)
            except (TimeoutError, json.JSONDecodeError, TypeError) as exc:
                raise ValueError(
                    "Нужен start: PCM s16le, 16000 Hz, mono, язык ru/kk/en."
                ) from exc

            if (
                not isinstance(start, dict)
                or any(
                    start.get(k) != v
                    for k, v in {
                        "type": "start",
                        "sample_rate": 16000,
                        "channels": 1,
                        "format": "pcm_s16le",
                    }.items()
                )
                or start.get("language", "ru") not in {"ru", "kk", "en"}
            ):
                raise ValueError(
                    "Нужен start: PCM s16le, 16000 Hz, mono, язык ru/kk/en."
                )

            language = start.get("language", "ru")
            if app.state.analysis and (
                start.get("consent") is not True
                or start.get("analysis_provider") != "google"
            ):
                raise AnalysisError(
                    "consent_required",
                    "Explicit consent to send audio to Google is required: "
                    "consent=true, analysis_provider=google.",
                )

            send_lock = asyncio.Lock()

            async def emit(event: dict) -> None:
                if closed:
                    return
                async with send_lock:
                    try:
                        await ws.send_json(event)
                    except (WebSocketDisconnect, RuntimeError):
                        # Client already gone; swallow so analysis can shut down cleanly.
                        pass

            analysis = AudioAnalysis(app.state.analysis, emit, language)
            await analysis.start()
            await emit({"type": "ready", "mode": app.state.mode})

            total = 0
            last_meter = 0.0
            received_at = time.monotonic()
            last_received = received_at

            while True:
                # Propagate upstream failure as soon as the worker dies.
                analysis.check()

                try:
                    frame = await asyncio.wait_for(ws.receive(), timeout=1.0)
                except TimeoutError:
                    if time.monotonic() - last_received >= IDLE_TIMEOUT_S:
                        raise ValueError("Нет аудио дольше 20 секунд.")
                    continue

                last_received = time.monotonic()

                if frame["type"] == "websocket.disconnect":
                    break

                raw = frame.get("bytes")
                if raw is None:
                    try:
                        event = json.loads(frame.get("text") or "{}")
                    except json.JSONDecodeError as exc:
                        raise ValueError("Ожидается бинарное аудио или stop.") from exc
                    if not isinstance(event, dict) or event.get("type") != "stop":
                        raise ValueError("Ожидается бинарное аудио или stop.")
                    await analysis.finish()
                    await emit(
                        {
                            "type": "done",
                            "audio_seconds": round(total / BYTES_PER_SECOND, 2),
                        }
                    )
                    await safe_close(1000)
                    break

                if not raw or len(raw) % 2 or len(raw) > MAX_FRAME_BYTES:
                    raise ValueError(
                        "Некорректный PCM-пакет: нужен чётный размер 2–16000 байт."
                    )

                total += len(raw)
                elapsed = time.monotonic() - received_at
                if total > BYTES_PER_SECOND * SESSION_LIMIT_S or elapsed > SESSION_LIMIT_S:
                    raise ValueError("Лимит тестовой сессии: 15 минут.")
                if total > BYTES_PER_SECOND * (elapsed * 1.5 + 10):
                    raise ValueError("Аудио поступает быстрее реального времени.")

                now = time.monotonic()
                if now - last_meter >= 0.25:
                    db = level(raw)
                    await emit(
                        {
                            "type": "level",
                            "dbfs": db,
                            "silent": db < -55,
                            "audio_seconds": round(total / BYTES_PER_SECOND, 2),
                        }
                    )
                    last_meter = now

                analysis.feed(raw)

        except WebSocketDisconnect:
            pass
        except Exception as exc:
            error("AUDIO_FAILED", exc)
            code = "invalid_audio"
            message = "Invalid audio or processing failure."
            if isinstance(exc, AnalysisError):
                code = exc.code
                message = str(exc)
            with contextlib.suppress(Exception):
                if not closed:
                    await ws.send_json(
                        {"type": "error", "code": code, "message": message}
                    )
            await safe_close(1011)
        finally:
            try:
                if analysis is not None:
                    await analysis.close()
            finally:
                app.state.busy = False
                await safe_close()

    return app


app = create_app(load_environment=True)
