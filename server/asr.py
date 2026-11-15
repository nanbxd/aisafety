"""Chunked Whisper adapter. It is not native streaming recognition."""
import os
import numpy as np
from server.diagnostics import log


class WhisperTranscriber:
    def __init__(self):
        from faster_whisper import WhisperModel
        self.model = WhisperModel(
            os.getenv("ASR_MODEL", "small"),
            device=os.getenv("ASR_DEVICE", "cpu"),
            compute_type=os.getenv("ASR_COMPUTE_TYPE", "int8"),
            download_root=os.getenv("ASR_CACHE", ".models"),
        )

    def __call__(self, pcm: bytes, language: str) -> str:
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        segments, info = self.model.transcribe(
            audio, language=language, beam_size=1, vad_filter=True,
            condition_on_previous_text=False,
        )
        parts = []
        count = rejected = 0
        for segment in segments:
            count += 1
            if segment.no_speech_prob < 0.7:
                parts.append(segment.text.strip())
            else:
                rejected += 1
        log.info("ASR_SEGMENTS total=%d filtered_no_speech=%d vad_seconds=%.2f", count, rejected, getattr(info, "duration_after_vad", 0) or 0)
        return " ".join(parts).strip()
