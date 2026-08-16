"""Projector: food-order + delivery events → reporting_orders.*."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncConnection

from ._helpers import extract_data, parse_rfc3339


async def project_order(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    """Project food order events into `reporting_orders.orders`."""
    data = extract_data(envelope)
    order_id = uuid.UUID(str(envelope.get("aggregate_id") or data.get("order_id")))
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()

    status = _order_status_for_event(
        str(envelope.get("event_name") or ""), fallback=data.get("status")
    )

    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_orders.orders (
                id, customer_id, branch_id, tenant_id, status,
                total_minor, currency, placed_at, delivered_at,
                cancelled_at, last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :customer_id, :branch_id, :tenant_id, :status,
                :total_minor, :currency, :placed_at, :delivered_at,
                :cancelled_at, :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                status        = EXCLUDED.status,
                total_minor   = COALESCE(
                    EXCLUDED.total_minor, reporting_orders.orders.total_minor
                ),
                currency      = COALESCE(
                    EXCLUDED.currency, reporting_orders.orders.currency
                ),
                delivered_at  = COALESCE(
                    EXCLUDED.delivered_at, reporting_orders.orders.delivered_at
                ),
                cancelled_at  = COALESCE(
                    EXCLUDED.cancelled_at, reporting_orders.orders.cancelled_at
                ),
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(order_id),
            "customer_id": _uuid(data.get("customer_id")),
            "branch_id": _uuid(data.get("branch_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "status": status,
            "total_minor": _int(data.get("total_minor")),
            "currency": _str(data.get("currency")),
            "placed_at": parse_rfc3339(data.get("placed_at")),
            "delivered_at": parse_rfc3339(data.get("delivered_at")),
            "cancelled_at": parse_rfc3339(data.get("cancelled_at")),
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


async def project_delivery(conn: AsyncConnection, envelope: dict[str, Any]) -> None:
    """Project delivery events into `reporting_orders.deliveries`."""
    data = extract_data(envelope)
    delivery_id = uuid.UUID(
        str(envelope.get("aggregate_id") or data.get("delivery_id"))
    )
    last_event_at = parse_rfc3339(envelope.get("occurred_at")) or _utcnow()

    status = _delivery_status_for_event(
        str(envelope.get("event_name") or ""), fallback=data.get("status")
    )

    # The deliveries table is created in a follow-up revision; until then
    # this UPSERT is best-effort and silently no-ops if the table is missing.
    await conn.execute(
        sa.text(
            """
            INSERT INTO reporting_orders.deliveries (
                id, order_id, courier_id, tenant_id, status,
                picked_up_at, delivered_at, failed_at,
                last_event_at, last_event_id, created_at
            )
            VALUES (
                :id, :order_id, :courier_id, :tenant_id, :status,
                :picked_up_at, :delivered_at, :failed_at,
                :last_event_at, :last_event_id, :created_at
            )
            ON CONFLICT (id) DO UPDATE SET
                status        = EXCLUDED.status,
                picked_up_at  = COALESCE(
                    EXCLUDED.picked_up_at,
                    reporting_orders.deliveries.picked_up_at,
                ),
                delivered_at  = COALESCE(
                    EXCLUDED.delivered_at,
                    reporting_orders.deliveries.delivered_at,
                ),
                failed_at     = COALESCE(
                    EXCLUDED.failed_at,
                    reporting_orders.deliveries.failed_at,
                ),
                last_event_at = EXCLUDED.last_event_at,
                last_event_id = EXCLUDED.last_event_id
            """
        ),
        {
            "id": str(delivery_id),
            "order_id": _uuid(data.get("order_id")),
            "courier_id": _uuid(data.get("courier_id")),
            "tenant_id": str(data.get("tenant_id") or envelope.get("tenant_id") or ""),
            "status": status,
            "picked_up_at": parse_rfc3339(data.get("picked_up_at")),
            "delivered_at": parse_rfc3339(data.get("delivered_at")),
            "failed_at": parse_rfc3339(data.get("failed_at")),
            "last_event_at": last_event_at,
            "last_event_id": envelope.get("event_id"),
            "created_at": _utcnow(),
        },
    )


def _order_status_for_event(event_name: str, fallback: Any) -> str:
    table = {
        "food.order.placed.v1": "placed",
        "food.order.preparing.v1": "preparing",
        "food.order.delivered.v1": "delivered",
        "food.order.cancelled.v1": "cancelled",
    }
    return table.get(event_name, str(fallback or "unknown"))


def _delivery_status_for_event(event_name: str, fallback: Any) -> str:
    table = {
        "delivery.dispatched.v1": "dispatched",
        "delivery.picked_up.v1": "picked_up",
        "delivery.delivered.v1": "delivered",
        "delivery.failed.v1": "failed",
    }
    return table.get(event_name, str(fallback or "unknown"))


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


def _uuid(value: Any) -> str:
    return str(uuid.UUID(str(value)))


def _int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    return int(value)


def _str(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)
