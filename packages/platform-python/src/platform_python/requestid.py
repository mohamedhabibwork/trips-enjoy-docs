"""Request-id middleware per ADR-0019.

The api-gateway is the canonical root generator; downstream services
inherit the value via this middleware. Inbound headers:

* ``X-Request-Id`` (primary, preferred)
* ``X-Correlation-Id`` (alias, always accepted)

When both are absent, a UUIDv7 is generated. The chosen value is
echoed in BOTH response headers, placed in the structured-log MDC
under ``requestId``, and made available via :func:`request_id_from_scope`.
"""
from __future__ import annotations

import uuid
from typing import Awaitable, Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

# Header names per ADR-0019.
HEADER_REQUEST_ID = "X-Request-Id"
HEADER_CORRELATION_ID = "X-Correlation-Id"

# MDC key in the structured logger.
MDC_KEY = "request_id"

# OpenTelemetry root-span attribute.
OTEL_ATTR_KEY = "platform.request_id"


def _new_request_id() -> str:
    """UUIDv7 since Python 3.14 — preserves rough time ordering."""
    return str(uuid.uuid7())


class RequestIDMiddleware(BaseHTTPMiddleware):
    """Starlette / FastAPI middleware that reads or generates the request id.

    On every request:

    1. Read ``X-Request-Id``; if absent, ``X-Correlation-Id``; if both
       absent, generate a UUIDv7.
    2. Set BOTH response headers to the chosen value.
    3. Bind the value to ``request.state.request_id`` so handlers can
       read it via :func:`request_id_from_scope`.
    """

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        request_id = request.headers.get(HEADER_REQUEST_ID) or request.headers.get(HEADER_CORRELATION_ID)
        if not request_id:
            request_id = _new_request_id()
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers[HEADER_REQUEST_ID] = request_id
        response.headers[HEADER_CORRELATION_ID] = request_id
        return response


def request_id_from_scope(request: Request) -> str:
    """Read the request id previously bound by the middleware."""
    return getattr(request.state, "request_id", "")


def new_request_id() -> str:
    """Public helper for tests and background jobs that need a fresh id."""
    return _new_request_id()
