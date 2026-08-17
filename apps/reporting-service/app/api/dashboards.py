"""GET /v1/dashboards/{name}

Returns the dashboard JSON envelope per INTEGRATION.md §1.1. Per-dashboard
scope check (e.g. `reporting.dashboard.operations`).
"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Path
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncConnection

from ..auth import Principal, decode_bearer
from ._deps import db_conn, read_access_log

router = APIRouter(prefix="/v1/dashboards", tags=["dashboards"])


# ----- response schema -----------------------------------------------------

class DashboardPanel(BaseModel):
    name: str
    data: list[dict[str, Any]]


class DashboardResponse(BaseModel):
    name: str
    as_of: str
    panels: list[DashboardPanel]


# ----- catalog -------------------------------------------------------------

# Per-dashboard scope map (TECH.md §10 + INTEGRATION.md §1.1).
_DASHBOARDS: dict[str, dict[str, Any]] = {
    "operations": {
        "scope": "reporting.dashboard.operations",
        "panels": ["trips_per_hour", "active_drivers", "active_couriers"],
    },
    "finance": {
        "scope": "reporting.dashboard.finance",
        "panels": ["revenue_today", "gmv_per_hour", "refunds_today"],
    },
    "growth": {
        "scope": "reporting.dashboard.growth",
        "panels": ["new_signups_per_day", "retention_d7"],
    },
}


# ----- handler -------------------------------------------------------------

@router.get("/{name}", response_model=DashboardResponse)
async def read_dashboard(
    name: str = Path(..., min_length=1, max_length=64),
    principal: Principal = Depends(decode_bearer),
    conn: AsyncConnection = Depends(db_conn),
) -> DashboardResponse:
    cfg = _DASHBOARDS.get(name)
    if cfg is None:
        raise HTTPException(
            status_code=404,
            detail={"type": "about:blank", "title": "Not Found", "status": 404,
                    "code": "DASHBOARD_NOT_FOUND", "detail": name},
        )
    if cfg["scope"] not in principal.scopes and "reporting.admin" not in principal.roles:
        raise HTTPException(
            status_code=403,
            detail={"type": "about:blank", "title": "Forbidden", "status": 403,
                    "code": "FORBIDDEN", "detail": f"required scope: {cfg['scope']}"},
        )

    # The actual panel data is rendered from the read models. For the
    # scaffold we return a stable envelope with empty panels so callers
    # can validate the contract; production wires the panels per
    # `_DASHBOARDS[name]['panels']`.
    panels = [
        DashboardPanel(name=p, data=[])
        for p in cfg["panels"]
    ]
    response = DashboardResponse(
        name=name,
        as_of=datetime.now(tz=UTC)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z"),
        panels=panels,
    )

    await read_access_log(
        conn,
        actor_id=__import__("uuid").UUID(principal.actor_id),
        view_name=f"dashboard:{name}",
        query={"dashboard": name},
        result_count=len(panels),
        reason="dashboard_read",
    )
    await conn.commit()
    return response


__all__ = ["router"]
