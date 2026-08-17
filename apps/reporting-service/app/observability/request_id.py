"""Request-id middleware (ADR-0019).

Per docs/architecture/adrs/0019-request-id-at-the-edge.md and the project
memory `uber-request-id-at-edge-adr-0019`, the api-gateway is the canonical
root generator. Downstream services — including this one — accept
`X-Request-Id` (or `X-Correlation-Id` as alias), propagate it into:
  - the response header,
  - the structured logger (via `correlation_id` + `request_id`),
  - Kafka headers on outgoing events (outbox payload `headers`),
  - OTel trace context where available.

If no header is present the service generates a UUIDv7-shaped id so the
field is never empty.
"""
from __future__ import annotations

import contextvars
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

_current_request_id: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "current_request_id", default=None
)


def current_request_id() -> str | None:
    return _current_request_id.get()


class RequestIdMiddleware(BaseHTTPMiddleware):
    """Read or mint a request id, set it on the response, and expose it."""

    HEADER = "X-Request-Id"
    LEGACY_ALIASES = ("X-Correlation-Id",)

    async def dispatch(self, request: Request, call_next):  # type: ignore[override]
        rid = _extract_or_mint(request)
        token = _current_request_id.set(rid)
        try:
            response: Response = await call_next(request)
        finally:
            _current_request_id.reset(token)
        response.headers[self.HEADER] = rid
        return response


def _extract_or_mint(request: Request) -> str:
    rid = request.headers.get(RequestIdMiddleware.HEADER)
    if rid:
        return rid.strip()
    for alias in RequestIdMiddleware.LEGACY_ALIASES:
        rid = request.headers.get(alias)
        if rid:
            return rid.strip()
    # UUIDv7-shaped id (timestamp prefix) for monotonic ordering.
    return _mint_uuidv7()


def _mint_uuidv7() -> str:
    """Mint a UUIDv7-shaped identifier per ADR-0015.

    Requires Python 3.14+ (the stdlib `uuid.uuid7()` was introduced in 3.14
    per PEP 798 / CPython commit history). The `requires-python = ">=3.15"`
    pin in pyproject.toml is the single source of truth — we do not fall
    back to v4 because v7's time-ordered prefix is contractually required
    by the platform's audit + correlation tooling.
    """
    return str(uuid.uuid7())
