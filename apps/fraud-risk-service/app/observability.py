"""Request-id middleware + structured logging setup (per ADR-0019).

Lifts the canonical pattern from platform-python + reporting-service.
"""
from __future__ import annotations

import logging
import sys
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response


class RequestIdMiddleware(BaseHTTPMiddleware):
    """Honour X-Request-Id / X-Correlation-Id on every request.

    If neither is supplied, generate a new UUIDv4. The chosen id is
    echoed in the response header for downstream tracing.
    """

    async def dispatch(self, request: Request, call_next) -> Response:
        inbound = (
            request.headers.get("X-Request-Id")
            or request.headers.get("X-Correlation-Id")
        )
        request_id = inbound or str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        response.headers["X-Correlation-Id"] = request_id
        return response


def configure_logging(level: str = "INFO") -> None:
    """JSON-line structured logging with request-id correlation."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            '{"timestamp":"%(asctime)s","level":"%(levelname)s",'
            '"logger":"%(name)s","message":"%(message)s"}'
        )
    )
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)