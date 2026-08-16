"""GET /v1/reconciliation/drift

Returns paginated drift findings per INTEGRATION.md §1.5.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncConnection

from ..auth import Principal, require_role
from ..domain.drift import DriftService
from ..domain.types import Page
from ._deps import db_conn

router = APIRouter(prefix="/v1/reconciliation", tags=["reconciliation"])


@router.get("/drift", response_model=Page)
async def list_drift(
    view_name: str | None = Query(default=None),
    severity: str | None = Query(default=None),
    status: str | None = Query(default=None, pattern="^(open|acknowledged|resolved)$"),
    limit: int = Query(default=100, ge=1, le=500),
    principal: Principal = Depends(require_role("reporting.admin")),
    conn: AsyncConnection = Depends(db_conn),
) -> Page:
    service = DriftService(conn=conn)
    rows = await service.list_findings(
        view_name=view_name, severity=severity, status=status, limit=limit
    )
    return Page(items=rows, next_cursor=None, total=len(rows))


__all__ = ["router"]
