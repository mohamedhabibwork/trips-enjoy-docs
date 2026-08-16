"""Projector: ledger.posted.v1 → reporting_ledger.postings.

This projector feeds the financial dashboards (trial balance, income
statement, balance sheet) per docs/services/reporting-service/README.md
§20 "Accounting impact" and the `ACCOUNTING_WORKFLOWS.md` cross-service
view.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ._helpers import extract_data, parse_rfc3339


async def project_posting(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    data = extract_data(envelope)
    posting_id = uuid.UUID(
        str(envelope.get("aggregate_id") or data.get("posting_id"))
    )
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_ledger.postings (
                id, account_code, tenant_id, currency, amount_minor,
                side, posted_at, last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :account_code, :tenant_id, :currency, :amount_minor,
                :side, :posted_at, :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(posting_id),
            "account_code": str(data.get("account_code") or ""),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "currency": _str(data.get("currency")),
            "amount_minor": int(data.get("amount_minor") or 0),
            "side": str(data.get("side") or "debit"),
            "posted_at": parse_rfc3339(data.get("posted_at"))
            or parse_rfc3339(envelope.get("occurred_at")),
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


def _str(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)
