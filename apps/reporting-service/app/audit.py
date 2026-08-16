"""audit.admin.reporting.v1 emitter.

Per TECH.md §10.2 every admin call on this service emits one event to
audit-service via the standard `audit.admin.reporting.v1` key. The
emitter writes to the local `reporting.outbox` so the projection is
atomic with the admin action and the eventual Kafka publish is owned
by the outbox poller (see events/outbox.py).
"""
from __future__ import annotations

import time
import uuid
from typing import Any

from .auth.tokens import Principal
from .events.outbox import OutboxWriter

AUDIT_KEY = "audit.admin.reporting.v1"


def _now_ms() -> int:
    return int(time.time() * 1000)


def build_admin_audit_record(
    *,
    principal: Principal,
    endpoint: str,
    target_resource: str,
    action: str,
    reason_code: str | None,
    request_id: str,
    trace_id: str | None,
    result: str,
    duration_ms: int,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build the audit envelope that lands in `reporting.outbox`."""
    payload: dict[str, Any] = {
        "audit_key": AUDIT_KEY,
        "actor_id": principal.actor_id,
        "actor_username": principal.username,
        "roles": sorted(principal.roles),
        "endpoint": endpoint,
        "target_resource": target_resource,
        "action": action,
        "reason_code": reason_code,
        "request_id": request_id,
        "trace_id": trace_id,
        "result": result,
        "duration_ms": duration_ms,
        "occurred_at_ms": _now_ms(),
    }
    if extra:
        payload.update(extra)
    return payload


async def emit_admin_audit(
    *,
    outbox: OutboxWriter,
    principal: Principal,
    endpoint: str,
    target_resource: str,
    action: str,
    reason_code: str | None,
    request_id: str,
    trace_id: str | None,
    result: str,
    duration_ms: int,
    extra: dict[str, Any] | None = None,
) -> uuid.UUID:
    """Enqueue the audit record for the outbox poller to publish."""
    payload = build_admin_audit_record(
        principal=principal,
        endpoint=endpoint,
        target_resource=target_resource,
        action=action,
        reason_code=reason_code,
        request_id=request_id,
        trace_id=trace_id,
        result=result,
        duration_ms=duration_ms,
        extra=extra,
    )
    return await outbox.enqueue(
        topic=AUDIT_KEY,
        event_id=uuid.uuid7(),
        payload=payload,
    )
