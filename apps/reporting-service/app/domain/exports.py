"""Export-job service (WORKFLOWS.md §2).

The export workflow runs asynchronously:
1. POST /v1/exports/{name}/run inserts an `ExportJob` row (status=queued).
2. A background worker claims the job, runs the query, writes to S3.
3. The job is marked `succeeded` (with `s3_path`) or `failed` (with error).
4. On success an outbox event `reporting.export.completed.v1` is enqueued.

This module implements the row lifecycle + the S3 upload step. The actual
worker loop lives in `app/jobs/exports.py` (out of scope for this scaffold).
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ..config import get_settings
from ..events.outbox import OutboxWriter


@dataclass(slots=True)
class ExportService:
    conn: AsyncConnection
    s3_put: Any | None = None  # async callable(path, bytes) -> None; injected for tests

    async def create_job(
        self,
        *,
        name: str,
        format: str,
        query: dict[str, Any],
        actor_id: uuid.UUID,
        reason: str,
        correlation_id: uuid.UUID,
        idempotency_key: uuid.UUID | None,
    ) -> dict[str, Any]:
        """Insert an `ExportJob` row.

        Returns the inserted row as a dict (incl. `job_id`).
        """
        job_id = uuid.uuid7()
        now = datetime.now(tz=UTC)
        try:
            await self.conn.execute(
                sa.text(
                    """
                    INSERT INTO reporting.export_jobs
                        (id, name, format, query, status, actor_id,
                         reason, idempotency_key, correlation_id,
                         s3_path, row_count, size_bytes,
                         started_at, completed_at, error, created_at)
                    VALUES (:id, :name, :format, CAST(:query AS JSONB),
                            'queued', :actor_id, :reason,
                            :idempotency_key, :correlation_id,
                            NULL, NULL, NULL,
                            NULL, NULL, NULL, :now)
                    """
                ),
                {
                    "id": str(job_id),
                    "name": name,
                    "format": format,
                    "query": _json(query),
                    "actor_id": str(actor_id),
                    "reason": reason,
                    "idempotency_key": str(idempotency_key)
                    if idempotency_key
                    else None,
                    "correlation_id": str(correlation_id),
                    "now": now,
                },
            )
        except Exception:
            # Idempotency-Key collision: return the prior job.
            row = await self._existing_job(name, idempotency_key)
            if row is None:
                raise
            return row
        return await self._fetch_job(job_id)

    async def _existing_job(
        self, name: str, idempotency_key: uuid.UUID | None
    ) -> dict[str, Any] | None:
        if idempotency_key is None:
            return None
        result = await self.conn.execute(
            sa.text(
                "SELECT * FROM reporting.export_jobs "
                "WHERE name = :name AND idempotency_key = :idem"
            ),
            {"name": name, "idem": str(idempotency_key)},
        )
        row = result.first()
        if row is None:
            return None
        keys = result.keys()
        return dict(zip(keys, row, strict=True))

    async def _fetch_job(self, job_id: uuid.UUID) -> dict[str, Any]:
        result = await self.conn.execute(
            sa.text("SELECT * FROM reporting.export_jobs WHERE id = :id"),
            {"id": str(job_id)},
        )
        row = result.first()
        keys = result.keys()
        return dict(zip(keys, row, strict=True))

    async def get_job(self, job_id: uuid.UUID) -> dict[str, Any] | None:
        row = await self._fetch_job(job_id)
        return row or None

    async def list_recent(self, limit: int = 50) -> list[dict[str, Any]]:
        result = await self.conn.execute(
            sa.text(
                "SELECT * FROM reporting.export_jobs "
                "ORDER BY created_at DESC LIMIT :n"
            ),
            {"n": limit},
        )
        rows = result.fetchall()
        keys = result.keys()
        return [dict(zip(keys, row, strict=True)) for row in rows]

    async def mark_succeeded(
        self,
        *,
        job_id: uuid.UUID,
        s3_path: str,
        row_count: int,
        size_bytes: int,
        outbox: OutboxWriter,
    ) -> None:
        now = datetime.now(tz=UTC)
        await self.conn.execute(
            sa.text(
                "UPDATE reporting.export_jobs "
                "SET status = 'succeeded', s3_path = :s3, "
                "row_count = :rows, size_bytes = :size, "
                "completed_at = :now "
                "WHERE id = :id"
            ),
            {"id": str(job_id), "s3": s3_path, "rows": row_count, "size": size_bytes, "now": now},
        )
        await outbox.enqueue(
            topic="reporting.export.completed",
            event_id=uuid.uuid7(),
            payload={
                "event_name": "reporting.export.completed.v1",
                "aggregate_type": "ExportJob",
                "aggregate_id": str(job_id),
                "data": {
                    "job_id": str(job_id),
                    "s3_path": s3_path,
                    "row_count": row_count,
                    "size_bytes": size_bytes,
                },
            },
        )

    async def mark_failed(self, *, job_id: uuid.UUID, error: str) -> None:
        now = datetime.now(tz=UTC)
        await self.conn.execute(
            sa.text(
                "UPDATE reporting.export_jobs "
                "SET status = 'failed', error = :err, completed_at = :now "
                "WHERE id = :id"
            ),
            {"id": str(job_id), "err": error, "now": now},
        )

    async def mark_running(self, *, job_id: uuid.UUID) -> None:
        now = datetime.now(tz=UTC)
        await self.conn.execute(
            sa.text(
                "UPDATE reporting.export_jobs "
                "SET status = 'running', started_at = :now WHERE id = :id"
            ),
            {"id": str(job_id), "now": now},
        )

    def s3_target_path(self, job_id: uuid.UUID) -> str:
        """Compute the S3 key for an export (per TECH.md §5 / README §12)."""
        settings = get_settings()
        now = datetime.now(tz=UTC)
        return (
            f"s3://{settings.s3_bucket}/exports/"
            f"{now.year:04d}/{now.month:02d}/{now.day:02d}/"
            f"{job_id}.parquet"
        )


def _json(payload: dict[str, Any]) -> str:
    import json

    return json.dumps(payload, default=str)


__all__ = ["ExportService"]
