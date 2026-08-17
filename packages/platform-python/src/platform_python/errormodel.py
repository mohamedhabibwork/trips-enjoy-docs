"""RFC 7807 error envelope shared by every Python service.

Mirrors `platform-spring-boot-error` (Kotlin) and `platform-go/errormodel`
(Go). The body shape is normative:

.. code-block:: json

    {
      "type":       "https://platform.trips-enjoy.com/errors/<code-kebab>",
      "title":      "<Human title>",
      "status":     503,
      "detail":     "<service-specific detail>",
      "instance":   "<request path>",
      "code":       "DEPENDENCY_UNAVAILABLE",
      "traceId":    "<otel trace_id>",
      "spanId":     "<otel span_id>",
      "timestamp":  "RFC3339",
      "errors":     [{"field": "amount", "message": "must be > 0", "code": "MIN_VALUE"}],
      "downstream": {"service": "payment-service", "code": "CIRCUIT_OPEN", "status": 503,
                    "traceId": "...", "latency_ms": 17, "attempt": 1}
    }
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ErrorCode(str, Enum):
    """SCREAMING_SNAKE_CASE machine identifier per the platform catalog."""

    VALIDATION_FAILED = "VALIDATION_FAILED"
    UNAUTHENTICATED = "UNAUTHENTICATED"
    FORBIDDEN = "FORBIDDEN"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    RATE_LIMITED = "RATE_LIMITED"
    BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION"
    STATE_INVALID = "STATE_INVALID"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
    DEPENDENCY_TIMEOUT = "DEPENDENCY_TIMEOUT"
    BAD_GATEWAY = "BAD_GATEWAY"
    CIRCUIT_OPEN = "CIRCUIT_OPEN"
    BULKHEAD_FULL = "BULKHEAD_FULL"


# Canonical HTTP status per ErrorCode. Mirrors the Kotlin and Go maps.
_HTTP_STATUS: dict[ErrorCode, int] = {
    ErrorCode.VALIDATION_FAILED: 400,
    ErrorCode.UNAUTHENTICATED: 401,
    ErrorCode.FORBIDDEN: 403,
    ErrorCode.NOT_FOUND: 404,
    ErrorCode.CONFLICT: 409,
    ErrorCode.IDEMPOTENCY_KEY_REUSED: 422,
    ErrorCode.BUSINESS_RULE_VIOLATION: 422,
    ErrorCode.RATE_LIMITED: 429,
    ErrorCode.BAD_GATEWAY: 502,
    ErrorCode.CIRCUIT_OPEN: 503,
    ErrorCode.DEPENDENCY_UNAVAILABLE: 503,
    ErrorCode.BULKHEAD_FULL: 503,
    ErrorCode.DEPENDENCY_TIMEOUT: 504,
    ErrorCode.INTERNAL_ERROR: 500,
    ErrorCode.STATE_INVALID: 409,
}


def http_status_for(code: ErrorCode) -> int:
    return _HTTP_STATUS.get(code, 500)


class FieldError(BaseModel):
    """One row in the errors[] array (validation failures)."""

    field: str
    message: str
    code: str | None = None


class Downstream(BaseModel):
    """Identifies the originating service for an error that crossed a boundary."""

    service: str
    code: str | None = None
    status: int
    traceId: str | None = None
    latency_ms: int | None = None
    attempt: int | None = None


class ErrorEnvelope(BaseModel):
    """RFC 7807 + platform-extension response body."""

    model_config = ConfigDict(populate_by_name=True)

    type: str
    title: str
    status: int
    detail: str
    instance: str
    code: ErrorCode
    traceId: str | None = None
    spanId: str | None = None
    timestamp: datetime
    errors: list[FieldError] | None = Field(default=None)
    downstream: Downstream | None = Field(default=None)

    @classmethod
    def build(
        cls,
        code: ErrorCode,
        detail: str,
        instance: str,
        trace_id: str | None = None,
        span_id: str | None = None,
        errors: list[FieldError] | None = None,
        downstream: Downstream | None = None,
    ) -> "ErrorEnvelope":
        return cls(
            type=f"https://platform.trips-enjoy.com/errors/{code.value.lower().replace('_', '-')}",
            title=_titleize(code),
            status=http_status_for(code),
            detail=detail,
            instance=instance,
            code=code,
            traceId=trace_id,
            spanId=span_id,
            timestamp=datetime.now(timezone.utc),
            errors=errors,
            downstream=downstream,
        )


def _titleize(code: ErrorCode) -> str:
    return " ".join(p.title() for p in code.value.split("_"))


def to_fastapi_response(envelope: ErrorEnvelope) -> dict[str, Any]:
    """Render the envelope as a JSON-serialisable dict for FastAPI."""
    return envelope.model_dump(mode="json", exclude_none=True)
