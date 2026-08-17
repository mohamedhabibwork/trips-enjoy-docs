"""Admin router (/admin/v1/*) per TECH.md §10.

Mounted on the admin port (TECH.md §10.5). Endpoints:
  - POST /admin/v1/reports/{id}/materialize  (force-rebuild a read model)
  - GET  /admin/v1/exports                   (list recent export jobs)

All endpoints emit `audit.admin.reporting.v1` per TECH.md §10.2.
"""
from __future__ import annotations

import time
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Path
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncConnection

from ..audit import emit_admin_audit
from ..auth import Principal, require_role
from ..events.outbox import OutboxWriter
from ._deps import correlation_id, db_conn

router = APIRouter(prefix="/admin/v1", tags=["admin"])


# ----- schemas -------------------------------------------------------------

class MaterializeRequest(BaseModel):
    reason_code: str
    from_event_id: str | None = None  # optional replay window


class MaterializeAccepted(BaseModel):
    job_id: str
    read_model: str
    status: str


class ExportSummary(BaseModel):
    id: str
    name: str
    format: str
    status: str
    actor_id: str
    s3_path: str | None
    row_count: int | None
    size_bytes: int | None
    created_at: str
    completed_at: str | None


class ExportsList(BaseModel):
    items: list[ExportSummary]


# ----- POST /admin/v1/reports/{id}/materialize -----------------------------

@router.post(
    "/reports/{read_model:path}/materialize",
    response_model=MaterializeAccepted,
    status_code=202,
)
async def materialize_read_model(
    read_model: str = Path(..., min_length=1, max_length=128),
    body: MaterializeRequest = ...,
    request_id: str | None = Header(default=None, alias="X-Request-Id"),
    trace_id: str | None = Header(default=None, alias="X-Trace-Id"),
    principal: Principal = Depends(require_role("reporting.admin")),
    conn: AsyncConnection = Depends(db_conn),
) -> MaterializeAccepted:
    start = time.monotonic()
    job_id = str(uuid.uuid7())
    outbox = OutboxWriter(conn=conn)
    try:
        await outbox.enqueue(
            topic="reporting.materialize.requested",
            event_id=uuid.uuid7(),
            payload={
                "job_id": job_id,
                "read_model": read_model,
                "from_event_id": body.from_event_id,
                "actor_id": principal.actor_id,
                "reason_code": body.reason_code,
            },
            headers={"X-Request-Id": request_id or correlation_id() or ""},
        )
        duration_ms = int((time.monotonic() - start) * 1000)
        await emit_admin_audit(
            outbox=outbox,
            principal=principal,
            endpoint=f"POST /admin/v1/reports/{read_model}/materialize",
            target_resource=read_model,
            action="materialize",
            reason_code=body.reason_code,
            request_id=request_id or correlation_id() or "",
            trace_id=trace_id,
            result="accepted",
            duration_ms=duration_ms,
            extra={"job_id": job_id},
        )
        await conn.commit()
    except Exception as exc:
        await conn.rollback()
        raise HTTPException(
            status_code=500,
            detail={"type": "about:blank", "title": "Internal Server Error",
                    "status": 500, "code": "INTERNAL_ERROR", "detail": str(exc)},
        ) from exc

    return MaterializeAccepted(
        job_id=job_id,
        read_model=read_model,
        status="queued",
    )


# ----- GET /admin/v1/exports -----------------------------------------------

@router.get("/exports", response_model=ExportsList)
async def list_exports(
    principal: Principal = Depends(require_role("reporting.admin")),
    conn: AsyncConnection = Depends(db_conn),
) -> ExportsList:
    from ..domain.exports import ExportService

    service = ExportService(conn=conn)
    rows = await service.list_recent(limit=50)
    return ExportsList(
        items=[
            ExportSummary(
                id=row["id"],
                name=row["name"],
                format=row["format"],
                status=row["status"],
                actor_id=row["actor_id"],
                s3_path=row["s3_path"],
                row_count=row["row_count"],
                size_bytes=row["size_bytes"],
                created_at=row["created_at"].isoformat(),
                completed_at=row["completed_at"].isoformat()
                if row["completed_at"]
                else None,
            )
            for row in rows
        ]
    )


__all__ = ["router"]
