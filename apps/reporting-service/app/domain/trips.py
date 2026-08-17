"""Projector: trip events → reporting_trips.trips read model.

Per docs/services/reporting-service/ERD.md §3 (representative table):
the UPSERT keys on `id` and refreshes `last_event_at` + `last_event_id`
on every event for view-lag tracking (WORKFLOWS.md §1.5, SRS §14).
"""
from __future__ import annotations

import uuid
from datetime import UTC
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ._helpers import extract_data, parse_rfc3339


async def project_trip(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    """Project one trip event into `reporting_trips.trips`.

    The handler is idempotent: re-projecting the same `event_id` produces
    the same row state (SRS §14, §15).
    """
    data = extract_data(envelope)
    aggregate_id = envelope.get("aggregate_id") or data.get("trip_id")
    if not aggregate_id:
        raise ValueError("trip event missing aggregate_id / data.trip_id")
    trip_id = uuid.UUID(str(aggregate_id))

    # Determine status from event type (mapped by the registry caller).
    event_name = envelope.get("event_name") or ""
    status = _status_for_event(event_name, fallback=data.get("status"))

    started_at = parse_rfc3339(data.get("started_at"))
    completed_at = parse_rfc3339(data.get("completed_at"))
    cancelled_at = parse_rfc3339(data.get("cancelled_at"))
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_trips.trips (
                id, customer_id, driver_id, tenant_id, city_id,
                ride_type, status, total_minor, currency,
                started_at, completed_at, cancelled_at,
                last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :customer_id, :driver_id, :tenant_id, :city_id,
                :ride_type, :status, :total_minor, :currency,
                :started_at, :completed_at, :cancelled_at,
                :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                driver_id     = COALESCE(EXCLUDED.driver_id, reporting_trips.trips.driver_id),
                status        = EXCLUDED.status,
                total_minor   = COALESCE(EXCLUDED.total_minor, reporting_trips.trips.total_minor),
                currency      = COALESCE(EXCLUDED.currency, reporting_trips.trips.currency),
                started_at    = COALESCE(EXCLUDED.started_at, reporting_trips.trips.started_at),
                completed_at  = COALESCE(EXCLUDED.completed_at, reporting_trips.trips.completed_at),
                cancelled_at  = COALESCE(EXCLUDED.cancelled_at, reporting_trips.trips.cancelled_at),
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(trip_id),
            "customer_id": _uuid_or(data.get("customer_id")),
            "driver_id": _uuid_or(data.get("driver_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "city_id": str(data.get("city_id") or ""),
            "ride_type": str(data.get("ride_type") or ""),
            "status": status,
            "total_minor": _int_or(data.get("total_minor")),
            "currency": _str_or(data.get("currency")),
            "started_at": started_at,
            "completed_at": completed_at,
            "cancelled_at": cancelled_at,
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


def _status_for_event(event_name: str, fallback: str | None) -> str:
    """Map an event name to a status string."""
    table = {
        "trip.requested.v1": "requested",
        "trip.matched.v1": "matched",
        "trip.completed.v1": "completed",
        "trip.cancelled.v1": "cancelled",
    }
    if event_name in table:
        return table[event_name]
    return str(fallback or "unknown")


def _utcnow():
    from datetime import datetime

    return datetime.now(tz=UTC)


def _uuid_or(value: Any) -> str | None:
    if value is None or value == "":
        return None
    return str(uuid.UUID(str(value)))


def _int_or(value: Any) -> int | None:
    if value is None or value == "":
        return None
    return int(value)


def _str_or(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)
