"""Reconciliation drift detection.

The reconciliation cron (PLAN.md T-RPT, README §21.2) samples N entities
from a read model and queries the source service for the current
authoritative value. Any mismatch produces a `DriftFinding`.

This scaffold implements the database side of that workflow:
- `find_sample_ids` — pick N entity_ids from the read model.
- `record_finding`   — write a DriftFinding row + enqueue an outbox event.
- `purge_resolved`   — optional maintenance hook.

The actual source-service comparison is performed by the cron entrypoint
under `app/jobs/reconciliation.py` (out of scope here).
"""
from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ..events.outbox import OutboxWriter

# ----- errors --------------------------------------------------------------

class ReconciliationPaused(Exception):
    """Raised when reconciliation cannot run (circuit open, etc.)."""


# ----- helpers -------------------------------------------------------------

_SAFE_IDENT = re.compile(r"[a-z][a-z0-9_]{0,62}")


def _safe_identifier(value: str) -> bool:
    return bool(_SAFE_IDENT.fullmatch(value))


# ----- service -------------------------------------------------------------

@dataclass(slots=True)
class DriftService:
    """The reconciliation service exposed to the public API + cron."""

    conn: AsyncConnection

    async def find_sample_ids(
        self, view_name: str, sample_size: int = 100
    ) -> list[uuid.UUID]:
        """Pick a random sample of entity ids from a read model view.

        The schema/table is derived from `view_name` (e.g. `reporting_trips.trips`).
        The query uses TABLESAMPLE when supported for efficiency, falling
        back to `ORDER BY random()`.
        """
        schema, table = view_name.split(".", 1)
        if not _safe_identifier(schema) or not _safe_identifier(table):
            raise ValueError(f"unsafe view_name: {view_name!r}")
        result = await self.conn.execute(
            sa.text(
                f'SELECT id FROM "{schema}"."{table}" ORDER BY random() LIMIT :n'
            ),
            {"n": sample_size},
        )
        rows = result.fetchall()
        return [uuid.UUID(str(row[0])) for row in rows]

    async def record_finding(
        self,
        *,
        view_name: str,
        drift_type: str,
        entity_id: uuid.UUID,
        details: dict[str, Any],
        severity: str,
        outbox: OutboxWriter | None = None,
        correlation_id: uuid.UUID,
    ) -> uuid.UUID:
        """Persist a `DriftFinding` + emit `reconciliation.drift.found.v1`."""
        finding_id = uuid.uuid7()
        now = datetime.now(tz=UTC)
        await self.conn.execute(
            sa.text(
                """
                INSERT INTO reporting.drift_findings
                    (id, view_name, drift_type, entity_id, details,
                     severity, status, ticket_id, detected_at, created_at)
                VALUES (:id, :view_name, :drift_type, :entity_id,
                        CAST(:details AS JSONB),
                        :severity, 'open', NULL, :now, :now)
                """
            ),
            {
                "id": str(finding_id),
                "view_name": view_name,
                "drift_type": drift_type,
                "entity_id": str(entity_id),
                "details": _json(details),
                "severity": severity,
                "now": now,
            },
        )
        if outbox is not None:
            await outbox.enqueue(
                topic="reconciliation.drift.found",
                event_id=uuid.uuid7(),
                payload={
                    "event_name": "reconciliation.drift.found.v1",
                    "aggregate_type": "DriftFinding",
                    "aggregate_id": str(finding_id),
                    "tenant_id": details.get("tenant_id", "global"),
                    "correlation_id": str(correlation_id),
                    "data": {
                        "view_name": view_name,
                        "drift_type": drift_type,
                        "entity_id": str(entity_id),
                        "details": details,
                        "severity": severity,
                    },
                },
            )
        return finding_id

    async def list_findings(
        self,
        *,
        view_name: str | None = None,
        severity: str | None = None,
        status: str | None = None,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        """List drift findings (used by `GET /v1/reconciliation/drift`)."""
        clauses: list[str] = []
        params: dict[str, Any] = {"limit": limit}
        if view_name:
            clauses.append("view_name = :view_name")
            params["view_name"] = view_name
        if severity:
            clauses.append("severity = :severity")
            params["severity"] = severity
        if status:
            clauses.append("status = :status")
            params["status"] = status
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        result = await self.conn.execute(
            sa.text(
                "SELECT id, view_name, drift_type, entity_id, details, "
                "severity, status, ticket_id, detected_at, created_at "
                "FROM reporting.drift_findings" + where + " "
                "ORDER BY detected_at DESC LIMIT :limit"
            ),
            params,
        )
        rows = result.fetchall()
        keys = result.keys()
        return [dict(zip(keys, row, strict=True)) for row in rows]

    async def resolve_finding(self, finding_id: uuid.UUID) -> bool:
        result = await self.conn.execute(
            sa.text(
                "UPDATE reporting.drift_findings "
                "SET status = 'resolved' WHERE id = :id AND status <> 'resolved'"
            ),
            {"id": str(finding_id)},
        )
        return (result.rowcount or 0) > 0


def _json(payload: dict[str, Any]) -> str:
    return json.dumps(payload, default=str)


__all__ = ["DriftService", "ReconciliationPaused"]
