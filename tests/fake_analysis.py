"""Mocked streaming backend used by HTTP/WebSocket tests; never connects externally."""
import asyncio
from server.asr import AnalysisError


class FakeAnalysis:
    def __init__(self, failure=None, cleanup_failure=False):
        self.frames = []
        self.closed = False
        self.failure = failure
        self.cleanup_failure = cleanup_failure

    async def run(self, queue, emit, ready, language):
        ready.set_result(None)
        try:
            while True:
                pcm = await queue.get()
                if pcm is None:
                    return
                self.frames.append((pcm, language))
                if self.failure:
                    raise self.failure
                await emit({"type": "transcript", "text": "PRIVATE_TRANSCRIPT_MARKER"})
                await emit({"type": "risk", "level": "high", "reasons": ["Code requested"],
                            "message": "Do not share codes."})
        finally:
            self.closed = True
            if self.cleanup_failure:
                raise RuntimeError("PRIVATE_CLEANUP_MARKER")
