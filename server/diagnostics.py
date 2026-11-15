"""Operational metadata only: never log credentials, PCM, SDP or transcripts."""
import logging
import traceback

# Inherit Uvicorn's configured INFO handler, including in the Render container.
log = logging.getLogger("uvicorn.error.safety")


def error(event, exc, **fields):
    # Exception messages can contain remote input or credentials. Record safe
    # stack locations instead of exc_info, source lines, local values or str(exc).
    stack = ">".join(f"{frame.name}:{frame.lineno}" for frame in traceback.extract_tb(exc.__traceback__))
    log.error("%s error_type=%s stack=%s metadata=%s", event, type(exc).__name__, stack, fields)
