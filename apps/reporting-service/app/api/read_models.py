"""GET /v1/read-models

Returns the list of registered read models with their lag and row counts
per INTEGRATION.md §1.6.
"""
from __future__ import annotations

import sqlalchemy as sa
from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncConnection

from ..auth import Principal, require_role
from ._deps import db_conn

router = APIRouter(prefix="/v1/read-models", tags=["read-models"])

_READ_MODELS = [
    ("reporting_trips.trips", "reporting_trips.trips"),
    ("reporting_orders.orders", "reporting_orders.orders"),
    ("reporting_payments.intents", "reporting_payments.intents"),
    ("reporting_payments.wallets", "reporting_payments.wallets"),
    ("reporting_ledger.postings", "reporting_ledger.postings"),
    ("reporting_promotions.redemptions", "reporting_promotions.redemptions"),
    ("reporting_loyalty.accounts", "reporting_loyalty.accounts"),
]


class ReadModelEntry(BaseModel):
    name: str
    lag_seconds: int
    row_count: int


class ReadModelsResponse(BaseModel):
    items: list[ReadModelEntry]


@router.get("", response_model=ReadModelsResponse)
async def list_read_models(
    principal: Principal = Depends(require_role("reporting.admin")),
    conn: AsyncConnection = Depends(db_conn),
) -> ReadModelsResponse:
    import re

    items: list[ReadModelEntry] = []
    for name, full_table in _READ_MODELS:
        schema, table = full_table.split(".", 1)
        # Guard against SQL injection via the lookup table.
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", schema):
            continue
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", table):
            continue
        try:
            lag_row = await conn.execute(
                sa.text(
                    'SELECT EXTRACT(EPOCH FROM (now() - MAX(last_event_at)))::int '
                    f'FROM "{schema}"."{table}"'
                )
            )
            lag = lag_row.scalar() or 0
            count_row = await conn.execute(
                sa.text(f'SELECT COUNT(*) FROM "{schema}"."{table}"')
            )
            count = count_row.scalar() or 0
        except Exception:
            lag = 0
            count = 0
        items.append(
            ReadModelEntry(
                name=name,
                lag_seconds=int(lag),
                row_count=int(count),
            )
        )
    return ReadModelsResponse(items=items)


__all__ = ["router"]
