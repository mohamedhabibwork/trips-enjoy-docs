"""GET /v1/views/{view_name}

Per-INTEGRATION.md §1.2 the endpoint returns a paginated list of rows
from a read-model view with per-view scope check.
"""
from __future__ import annotations

import uuid
from typing import Any

import sqlalchemy as sa
from fastapi import APIRouter, Depends, HTTPException, Path, Query
from sqlalchemy.ext.asyncio import AsyncConnection

from ..auth import Principal, decode_bearer
from ..domain.types import Page
from ._deps import db_conn, read_access_log

router = APIRouter(prefix="/v1/views", tags=["views"])

# view_name → scope. Per INTEGRATION.md §4.1, the canonical views match
# the `reporting_*.*` schema/table names.
_VIEWS: dict[str, str] = {
    "reporting_trips.trips": "reporting.view.trips",
    "reporting_orders.orders": "reporting.view.orders",
    "reporting_payments.intents": "reporting.view.payments",
    "reporting_ledger.postings": "reporting.view.ledger",
    "reporting_promotions.redemptions": "reporting.view.promotions",
    "reporting_loyalty.accounts": "reporting.view.loyalty",
}


def _safe_identifier(value: str) -> bool:
    """Schema/table names must be lowercase alnum + underscore."""
    import re

    return bool(re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value))


@router.get("/{view_name:path}", response_model=Page)
async def read_view(
    view_name: str = Path(..., min_length=1, max_length=128),
    cursor: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=500),
    tenant_id: str | None = Query(default=None),
    principal: Principal = Depends(decode_bearer),
    conn: AsyncConnection = Depends(db_conn),
) -> Page:
    scope = _VIEWS.get(view_name)
    if scope is None:
        raise HTTPException(
            status_code=404,
            detail={"type": "about:blank", "title": "Not Found", "status": 404,
                    "code": "VIEW_NOT_FOUND", "detail": view_name},
        )
    if scope not in principal.scopes and "reporting.admin" not in principal.roles:
        raise HTTPException(
            status_code=403,
            detail={"type": "about:blank", "title": "Forbidden", "status": 403,
                    "code": "FORBIDDEN", "detail": f"required scope: {scope}"},
        )

    schema, table = view_name.split(".", 1)
    if not _safe_identifier(schema) or not _safe_identifier(table):
        raise HTTPException(
            status_code=400,
            detail={"type": "about:blank", "title": "Bad Request", "status": 400,
                    "code": "VALIDATION_FAILED", "detail": "view_name"},
        )
    filters: list[str] = []
    params: dict[str, Any] = {"limit": limit}
    effective_tenant = tenant_id or principal.tenant_id
    if effective_tenant:
        filters.append("tenant_id = :tenant_id")
        params["tenant_id"] = effective_tenant
    where = (" WHERE " + " AND ".join(filters)) if filters else ""

    result = await conn.execute(
        sa.text(
            f'SELECT id, tenant_id, last_event_at FROM "{schema}"."{table}"{where} '
            "ORDER BY last_event_at DESC LIMIT :limit"
        ),
        params,
    )
    rows = result.fetchall()
    keys = result.keys()
    items = [dict(zip(keys, row, strict=True)) for row in rows]

    await read_access_log(
        conn,
        actor_id=uuid.UUID(principal.actor_id),
        view_name=view_name,
        query={"cursor": cursor, "limit": limit, "tenant_id": effective_tenant},
        result_count=len(items),
        reason="view_read",
    )
    await conn.commit()

    next_cursor = items[-1]["last_event_at"].isoformat() if items else None
    return Page(items=items, next_cursor=next_cursor, total=len(items))


__all__ = ["router"]
