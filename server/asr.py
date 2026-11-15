"""Chunked Whisper adapter. It is not native streaming recognition."""
import os
import numpy as np


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
        segments, _ = self.model.transcribe(
            audio, language=language, beam_size=1, vad_filter=True,
            condition_on_previous_text=False,
        )
        return " ".join(s.text.strip() for s in segments if s.no_speech_prob < 0.7).strip()
