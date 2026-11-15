"""Manual real-model smoke test; input must be a consented or synthetic WAV.

Run: python -m tests.asr_smoke .tools/synthetic-ru.wav
Not part of pytest: downloading/running a model is intentionally explicit.
"""
import os
import sys
import time
import wave

os.environ.setdefault("ASR_MODEL", "tiny")

from server.asr import WhisperTranscriber
from server.risk import RiskEngine


def main():
    with wave.open(sys.argv[1], "rb") as source:
        assert (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, 16000)
        pcm = source.readframes(source.getnframes())
    transcribe = WhisperTranscriber()
    began = time.monotonic()
    text = transcribe(pcm, "ru")
    print({"model": os.environ["ASR_MODEL"], "audio_seconds": len(pcm) / 32000,
           "inference_seconds": round(time.monotonic() - began, 2), "text": text})
    print(RiskEngine().assess(text))
    if not text:
        raise SystemExit("FAIL: model produced no transcript")


if __name__ == "__main__":
    main()
