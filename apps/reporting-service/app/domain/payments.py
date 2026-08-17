"""Projector: payment + wallet events → reporting_payments.*."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ._helpers import extract_data, parse_rfc3339


async def project_intent(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    data = extract_data(envelope)
    intent_id = uuid.UUID(
        str(envelope.get("aggregate_id") or data.get("intent_id"))
    )
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()
    status = _intent_status_for_event(
        str(envelope.get("event_name") or ""), fallback=data.get("status")
    )

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_payments.intents (
                id, customer_id, tenant_id, amount_minor, currency, status,
                authorized_at, captured_at, failed_at,
                last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :customer_id, :tenant_id, :amount_minor, :currency, :status,
                :authorized_at, :captured_at, :failed_at,
                :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                status        = EXCLUDED.status,
                authorized_at = COALESCE(
                    EXCLUDED.authorized_at,
                    reporting_payments.intents.authorized_at,
                ),
                captured_at   = COALESCE(
                    EXCLUDED.captured_at,
                    reporting_payments.intents.captured_at,
                ),
                failed_at     = COALESCE(
                    EXCLUDED.failed_at,
                    reporting_payments.intents.failed_at,
                ),
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(intent_id),
            "customer_id": _uuid(data.get("customer_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "amount_minor": _int(data.get("amount_minor")),
            "currency": _str(data.get("currency")),
            "status": status,
            "authorized_at": parse_rfc3339(data.get("authorized_at")),
            "captured_at": parse_rfc3339(data.get("captured_at")),
            "failed_at": parse_rfc3339(data.get("failed_at")),
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


async def project_wallet(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    data = extract_data(envelope)
    account_id = uuid.UUID(
        str(envelope.get("aggregate_id") or data.get("account_id"))
    )
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()
    delta_minor = int(data.get("delta_minor") or data.get("amount_minor") or 0)

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_payments.wallets (
                id, account_id, customer_id, tenant_id, currency,
                delta_minor, last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :account_id, :customer_id, :tenant_id, :currency,
                :delta_minor, :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                delta_minor   = reporting_payments.wallets.delta_minor + EXCLUDED.delta_minor,
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(account_id),
            "account_id": str(account_id),
            "customer_id": _uuid(data.get("customer_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "currency": _str(data.get("currency")),
            "delta_minor": delta_minor,
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


def _intent_status_for_event(event_name: str, fallback: Any) -> str:
    table = {
        "payment.authorized.v1": "authorized",
        "payment.captured.v1": "captured",
        "payment.failed.v1": "failed",
        "payment.refunded.v1": "refunded",
    }
    return table.get(event_name, str(fallback or "unknown"))


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


def _uuid(value: Any) -> str:
    return str(uuid.UUID(str(value)))


def _int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    return int(value)


def _str(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)
