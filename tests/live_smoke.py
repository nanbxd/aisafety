"""Exercise a real running diagnostic server with silence, never real user audio."""
import asyncio
import json
import os
import sys
import wave

from websockets.asyncio.client import connect


async def main():
    if len(sys.argv) > 1 and os.getenv("ALLOW_LIVE_GEMINI") != "1":
        raise SystemExit("Set ALLOW_LIVE_GEMINI=1 only after consent to send this audio to Gemini.")
    async with connect(os.getenv("SAFETY_ENDPOINT", "ws://127.0.0.1:8000/ws/audio"),
                       additional_headers={"Authorization": "Bearer " + os.environ["SAFETY_TOKEN"]}) as ws:
        await ws.send(json.dumps({"type": "start", "sample_rate": 16000, "channels": 1,
                                  "format": "pcm_s16le", "language": "ru",
                                  "consent": len(sys.argv) > 1, "analysis_provider": "google"}))
        ready = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        assert ready["type"] == "ready"
        if len(sys.argv) > 1:
            assert ready.get("mode") == "gemini"
            with wave.open(sys.argv[1], "rb") as audio:
                assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 16000)
                pcm = audio.readframes(audio.getnframes())

            async def receive_events():
                events = []
                while True:
                    event = json.loads(await asyncio.wait_for(ws.recv(), timeout=120))
                    assert event["type"] != "error", event
                    events.append(event)
                    if event["type"] == "done":
                        return events

            receiver = asyncio.create_task(receive_events())
            for offset in range(0, len(pcm), 8000):
                await ws.send(pcm[offset:offset + 8000])
                await asyncio.sleep(0.25)
            await ws.send('{"type":"stop"}')
            events = await receiver
            assert any(e["type"] == "transcript" for e in events)
            print({"events": len(events), "completed": True})
            return
        assert ready.get("mode") == "diagnostic"
        await ws.send(bytes(8000))
        meter = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        assert meter["type"] == "level" and meter["silent"]
        await ws.send('{"type":"stop"}')
        done = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        assert done["type"] == "done"
        print({"ready": ready, "meter": meter, "done": done})


if __name__ == "__main__":
    asyncio.run(main())
