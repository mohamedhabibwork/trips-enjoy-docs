"""Projector: promotion + loyalty events → reporting_promotions / loyalty."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ._helpers import extract_data, parse_rfc3339


async def project_redemption(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    data = extract_data(envelope)
    redemption_id = uuid.UUID(
        str(envelope.get("aggregate_id") or data.get("redemption_id"))
    )
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_promotions.redemptions (
                id, promotion_id, customer_id, tenant_id, code,
                discount_minor, currency, redeemed_at,
                last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :promotion_id, :customer_id, :tenant_id, :code,
                :discount_minor, :currency, :redeemed_at,
                :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(redemption_id),
            "promotion_id": _uuid(data.get("promotion_id")),
            "customer_id": _uuid(data.get("customer_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "code": _str(data.get("code")),
            "discount_minor": int(data.get("discount_minor") or 0),
            "currency": _str(data.get("currency")),
            "redeemed_at": parse_rfc3339(data.get("redeemed_at"))
            or parse_rfc3339(envelope.get("occurred_at")),
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


async def project_loyalty(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    data = extract_data(envelope)
    account_id = uuid.UUID(
        str(envelope.get("aggregate_id") or data.get("account_id"))
    )
    delta = int(data.get("points_delta") or 0)
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_loyalty.accounts (
                id, customer_id, tenant_id, points, last_event_at,
                last_event_id, created_at
            )
            VALUES (
                :id, :customer_id, :tenant_id, :points, :last_event_at,
                :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                points        = reporting_loyalty.accounts.points + EXCLUDED.points,
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(account_id),
            "customer_id": _uuid(data.get("customer_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "points": delta,
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


def _uuid(value: Any) -> str:
    return str(uuid.UUID(str(value)))


def _str(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)
