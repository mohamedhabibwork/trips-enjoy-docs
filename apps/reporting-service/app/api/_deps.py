"""Shared request dependencies for the public API.

Provides:
  - `db_conn` — yields an `AsyncConnection` scoped to the request.
  - `read_access_log` — convenience coroutine that appends to
    `reporting.read_access_log` (SRS FR--011).
  - `correlation_id` — extracted from the request-id middleware.
"""
from __future__ import annotations

import uuid
from collections.abc import AsyncIterator

import sqlalchemy as sa
from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncConnection, async_sessionmaker

from ..observability.request_id import current_request_id


async def db_conn(request: Request) -> AsyncIterator[AsyncConnection]:
    """Yield one connection from the app-scoped session factory."""
    factory: async_sessionmaker[AsyncConnection] = request.app.state.session_factory
    async with factory() as conn:
        yield conn


def correlation_id() -> str | None:
    """Resolve the request id from the middleware context."""
    return current_request_id()


async def read_access_log(
    conn: AsyncConnection,
    *,
    actor_id: uuid.UUID,
    view_name: str,
    query: dict,
    result_count: int,
    reason: str,
) -> None:
    """Append a row to `reporting.read_access_log` (SRS FR--011)."""
    cid = correlation_id()
    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting.read_access_log
                (id, actor_id, view_name, query, result_count,
                 reason, correlation_id, created_at)
            VALUES (:id, :actor_id, :view_name, CAST(:query AS JSONB),
                    :count, :reason, :cid, now())
            """
        ),
        {
            "id": str(uuid.uuid7()),
            "actor_id": str(actor_id),
            "view_name": view_name,
            "query": _json(query),
            "count": int(result_count),
            "reason": reason,
            "cid": str(uuid.UUID(str(cid))) if cid else str(uuid.uuid7()),
        },
    )


def _json(payload: dict) -> str:
    import json

    return json.dumps(payload, default=str)
