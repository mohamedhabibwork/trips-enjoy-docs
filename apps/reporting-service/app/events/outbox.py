"""Transactional outbox writer + poller (ERD.md §3 `reporting.Outbox`).

The producer side writes one row per outgoing event into `reporting.outbox`
in the same transaction as the domain change. A background poller reads
the unpublished rows and publishes them to Kafka, then marks them
`published_at`. DLQ is paired with each topic (INTEGRATION.md §5).
"""
from __future__ import annotations

import asyncio
import json
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection


@dataclass(slots=True)
class OutboxRow:
    """One row of the outbox table."""

    id: uuid.UUID
    topic: str
    event_id: uuid.UUID
    payload: dict[str, Any]
    headers: dict[str, Any] | None
    created_at: datetime
    claimed_at: datetime | None
    published_at: datetime | None


@dataclass(slots=True)
class OutboxWriter:
    """Outbox write-side helper.

    Tests construct a writer directly; the FastAPI app builds one per
    request and passes it to handlers via dependency injection so a
    single transaction covers the business write + outbox enqueue.
    """

    conn: AsyncConnection

    async def enqueue(
        self,
        *,
        topic: str,
        event_id: uuid.UUID,
        payload: dict[str, Any],
        headers: dict[str, Any] | None = None,
        row_id: uuid.UUID | None = None,
    ) -> uuid.UUID:
        """Insert one outbox row; returns the row id."""
        rid = row_id or uuid.uuid7()
        await self.conn.execute(
            sa.text(
                """
                INSERT INTO reporting.outbox
                    (id, topic, event_id, payload, headers, created_at)
                VALUES (:id, :topic, :event_id,
                        CAST(:payload AS JSONB),
                        CAST(:headers AS JSONB),
                        :created_at)
                """
            ),
            {
                "id": str(rid),
                "topic": topic,
                "event_id": str(event_id),
                "payload": json.dumps(payload),
                "headers": json.dumps(headers or {}),
                "created_at": datetime.now(tz=UTC),
            },
        )
        return rid

    async def purge_published_older_than_hours(self, hours: int = 24) -> int:
        """Purge rows whose `published_at` is older than `hours` (ERD.md §10)."""
        result = await self.conn.execute(
            sa.text(
                "DELETE FROM reporting.outbox "
                "WHERE published_at IS NOT NULL "
                "AND published_at < now() - (:hours || ' hours')::interval"
            ),
            {"hours": hours},
        )
        return result.rowcount or 0


@dataclass(slots=True)
class OutboxPoller:
    """Background worker that drains the outbox into Kafka.

    In production this connects via `aiokafka`; in this scaffold the
    publish step is a callback that the app provides so the unit tests
    can assert on enqueued messages without spinning up a broker.
    """

    conn_factory: Any  # async callable returning AsyncConnection
    publish: Any  # async callable(topic, payload, headers) -> None
    batch_size: int = 100
    interval_seconds: float = 0.2  # PLAN §T-RPT-01: 200 ms
    _stopping: asyncio.Event = field(default_factory=asyncio.Event)

    async def run(self) -> None:
        """Run until `stop()` is called."""
        while not self._stopping.is_set():
            try:
                drained = await self._drain_once()
            except Exception:
                # A transient failure must not kill the poller.
                await asyncio.sleep(self.interval_seconds * 5)
                continue
            if drained == 0:
                await asyncio.sleep(self.interval_seconds)
            else:
                # Backoff is unnecessary when the batch was non-empty;
                # immediately look for more.
                continue

    async def stop(self) -> None:
        self._stopping.set()

    async def _drain_once(self) -> int:
        conn = await self.conn_factory()
        try:
            result = await conn.execute(
                sa.text(
                    """
                    SELECT id, topic, event_id, payload, headers
                    FROM reporting.outbox
                    WHERE published_at IS NULL
                    ORDER BY created_at
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED
                    """
                ),
                {"batch": self.batch_size},
            )
            rows = result.fetchall()
            if not rows:
                await conn.rollback()
                return 0
            now = datetime.now(tz=UTC)
            for row in rows:
                payload = row.payload
                headers = row.headers or {}
                await self.publish(row.topic, payload, headers)
                await conn.execute(
                    sa.text(
                        "UPDATE reporting.outbox "
                        "SET published_at = :now WHERE id = :id"
                    ),
                    {"now": now, "id": str(row.id)},
                )
            await conn.commit()
            return len(rows)
        except Exception:
            await conn.rollback()
            raise
        finally:
            await conn.close()
