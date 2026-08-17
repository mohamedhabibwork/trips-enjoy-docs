"""Inbox dedup (ERD.md §3 `reporting.Inbox`).

Every consumed Kafka event is recorded in `reporting.inbox` keyed by
`event_id`. If the same `event_id` arrives twice the second arrival is
a no-op, making projections idempotent (FR--015, SRS §15).
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection


@dataclass(slots=True)
class InboxRecord:
    """One row of the inbox table."""

    event_id: uuid.UUID
    topic: str
    received_at: datetime
    processed_at: datetime | None
    error: str | None


@dataclass(slots=True)
class Inbox:
    """Idempotent inbox writer + check."""

    @staticmethod
    async def already_processed(
        conn: AsyncConnection, event_id: uuid.UUID
    ) -> bool:
        """True if the inbox already has this event_id with `processed_at` set."""
        result = await conn.execute(
            sa.text(
                "SELECT processed_at FROM reporting.inbox WHERE event_id = :eid"
            ),
            {"eid": str(event_id)},
        )
        row = result.first()
        return bool(row and row[0] is not None)

    @staticmethod
    async def record(
        conn: AsyncConnection,
        event_id: uuid.UUID,
        topic: str,
        *,
        processed: bool,
        error: str | None = None,
    ) -> None:
        """Upsert the inbox row (ON CONFLICT DO NOTHING keeps the first error)."""
        now = datetime.now(tz=UTC)
        await conn.execute(
            sa.text(
                """
                INSERT INTO reporting.inbox
                    (event_id, topic, received_at, processed_at, error)
                VALUES (:eid, :topic, :now, :processed_at, :error)
                ON CONFLICT (event_id) DO NOTHING
                """
            ),
            {
                "eid": str(event_id),
                "topic": topic,
                "now": now,
                "processed_at": now if processed else None,
                "error": error,
            },
        )

    @staticmethod
    async def purge_older_than_days(conn: AsyncConnection, days: int) -> int:
        """Delete rows older than `days` (ERD.md §10 retention)."""
        result = await conn.execute(
            sa.text(
                "DELETE FROM reporting.inbox "
                "WHERE received_at < now() - (:days || ' days')::interval"
            ),
            {"days": days},
        )
        return result.rowcount or 0
