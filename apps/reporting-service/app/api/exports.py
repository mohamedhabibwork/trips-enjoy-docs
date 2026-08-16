"""POST /v1/exports/{name}/run + GET /v1/exports/{name}/status.

Idempotency-Key required on POST. Per INTEGRATION.md §1.3 / §1.4 the
service enqueues an `ExportJob` and returns 202 with `job_id`. Status
polling returns the row.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Path, Query
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncConnection

from ..auth import Principal, require_scope
from ..domain.exports import ExportService
from ._deps import correlation_id, db_conn

router = APIRouter(prefix="/v1/exports", tags=["exports"])


# ----- schemas -------------------------------------------------------------

class ExportRunRequest(BaseModel):
    """POST body for /v1/exports/{name}/run."""

    format: str = Field(pattern="^(csv|parquet)$")
    from_: datetime | None = Field(default=None, alias="from")
    to: datetime | None = None
    tenant_id: str | None = None
    reason: str = Field(min_length=1, max_length=512)
    query: dict[str, Any] = Field(default_factory=dict)

    model_config = {"populate_by_name": True}


class ExportRunAccepted(BaseModel):
    job_id: str
    status: str
    correlation_id: str


class ExportJobResponse(BaseModel):
    """Subset of the `ExportJob` row returned to callers."""

    id: str
    name: str
    format: str
    status: str
    actor_id: str
    correlation_id: str
    s3_path: str | None
    row_count: int | None
    size_bytes: int | None
    error: str | None
    created_at: datetime
    completed_at: datetime | None


# ----- POST /v1/exports/{name}/run -----------------------------------------

@router.post(
    "/{name}/run",
    response_model=ExportRunAccepted,
    status_code=202,
)
async def run_export(
    name: str = Path(..., min_length=1, max_length=64),
    body: ExportRunRequest = ...,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    principal: Principal = Depends(require_scope("reporting.export.{name}")),
    conn: AsyncConnection = Depends(db_conn),
) -> ExportRunAccepted:
    # Defensive: scope template above is anchored by the APIRouter matcher
    # (`name`); the per-name check below enforces the actual scope.
    scope_name = f"reporting.export.{name}"
    if scope_name not in principal.scopes and "reporting.admin" not in principal.roles:
        raise HTTPException(
            status_code=403,
            detail={"type": "about:blank", "title": "Forbidden", "status": 403,
                    "code": "FORBIDDEN", "detail": f"required scope: {scope_name}"},
        )

    if idempotency_key is None:
        raise HTTPException(
            status_code=400,
            detail={"type": "about:blank", "title": "Bad Request", "status": 400,
                    "code": "IDEMPOTENCY_KEY_REQUIRED",
                    "detail": "Idempotency-Key header is required for export runs"},
        )

    cid = correlation_id() or str(uuid.uuid7())
    try:
        uuid.UUID(cid)
    except ValueError:
        cid = str(uuid.uuid7())

    idem_uuid = uuid.uuid5(
        uuid.NAMESPACE_URL, f"reporting-service:{name}:{idempotency_key}"
    )
    service = ExportService(conn=conn)
    query = dict(body.query)
    query.setdefault("from", body.from_.isoformat() if body.from_ else None)
    query.setdefault("to", body.to.isoformat() if body.to else None)
    query.setdefault("tenant_id", body.tenant_id)

    row = await service.create_job(
        name=name,
        format=body.format,
        query=query,
        actor_id=uuid.UUID(principal.actor_id),
        reason=body.reason,
        correlation_id=uuid.UUID(cid),
        idempotency_key=idem_uuid,
    )
    await conn.commit()
    return ExportRunAccepted(
        job_id=row["id"],
        status=row["status"],
        correlation_id=row["correlation_id"],
    )


# ----- GET /v1/exports/{name}/status ---------------------------------------

@router.get("/{name}/status", response_model=ExportJobResponse)
async def export_status(
    name: str = Path(..., min_length=1, max_length=64),
    job_id: str = Query(...),
    principal: Principal = Depends(require_scope("reporting.export.{name}")),
    conn: AsyncConnection = Depends(db_conn),
) -> ExportJobResponse:
    scope_name = f"reporting.export.{name}"
    if scope_name not in principal.scopes and "reporting.admin" not in principal.roles:
        raise HTTPException(
            status_code=403,
            detail={"type": "about:blank", "title": "Forbidden", "status": 403,
                    "code": "FORBIDDEN", "detail": f"required scope: {scope_name}"},
        )

    try:
        jid = uuid.UUID(job_id)
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail={"type": "about:blank", "title": "Bad Request", "status": 400,
                    "code": "VALIDATION_FAILED", "detail": "job_id must be a UUID"},
        ) from exc

    service = ExportService(conn=conn)
    row = await service.get_job(jid)
    if row is None or row["name"] != name:
        raise HTTPException(
            status_code=404,
            detail={"type": "about:blank", "title": "Not Found", "status": 404,
                    "code": "EXPORT_NOT_FOUND", "detail": job_id},
        )
    return ExportJobResponse(
        id=row["id"],
        name=row["name"],
        format=row["format"],
        status=row["status"],
        actor_id=row["actor_id"],
        correlation_id=row["correlation_id"],
        s3_path=row["s3_path"],
        row_count=row["row_count"],
        size_bytes=row["size_bytes"],
        error=row["error"],
        created_at=row["created_at"],
        completed_at=row["completed_at"],
    )


__all__ = ["router"]
