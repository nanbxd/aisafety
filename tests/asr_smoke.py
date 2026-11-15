"""Compatibility entry point for the consent-gated live server smoke test."""
import asyncio
from tests.live_smoke import main

if __name__ == "__main__":
    asyncio.run(main())
