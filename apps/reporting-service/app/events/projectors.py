"""Projector registry + dispatch.

Each domain event (`trip.completed.v1`, `food.order.placed.v1`, ...) is
mapped to a projector function that UPSERTs the relevant read model row
in a single SQL statement per event (SRS §14). Projectors are
idempotent on `event_id` via the inbox (handled by `consumer.py`).

Topics → projector mapping lives in `INTEGRATION.md` §4.1. We keep the
list short here and rely on `app/domain/` for the actual projections.
"""
from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from sqlalchemy.ext.asyncio import AsyncConnection

ProjectorFn = Callable[
    [AsyncConnection, dict[str, Any]], Awaitable[None]
]


@dataclass(slots=True)
class ProjectorRegistry:
    """A simple `topic → projector` lookup.

    Projectors receive the parsed event envelope. The envelope shape is
    documented in docs/services/reporting-service/INTEGRATION.md §3.
    """

    _handlers: dict[str, ProjectorFn]

    @classmethod
    def empty(cls) -> ProjectorRegistry:
        return cls(_handlers={})

    def register(self, topic: str, fn: ProjectorFn) -> None:
        """Idempotent registration; last-write wins on duplicate topics."""
        self._handlers[topic] = fn

    def get(self, topic: str) -> ProjectorFn | None:
        return self._handlers.get(topic)

    def topics(self) -> list[str]:
        return sorted(self._handlers)


def default_registry() -> ProjectorRegistry:
    """Build the registry that ships with the service.

    Imports are deferred so test environments can mock individual
    projectors without pulling the whole DAG.
    """
    from ..domain import ledger, orders, payments, promotions, trips

    reg = ProjectorRegistry.empty()
    # trip.* → reporting_trips.trips
    for topic in (
        "trip.requested.v1",
        "trip.matched.v1",
        "trip.completed.v1",
        "trip.cancelled.v1",
    ):
        reg.register(topic, trips.project_trip)
    # food.order.* → reporting_orders.orders
    for topic in (
        "food.order.placed.v1",
        "food.order.preparing.v1",
        "food.order.delivered.v1",
        "food.order.cancelled.v1",
    ):
        reg.register(topic, orders.project_order)
    # delivery.* → reporting_orders.deliveries
    for topic in (
        "delivery.dispatched.v1",
        "delivery.picked_up.v1",
        "delivery.delivered.v1",
        "delivery.failed.v1",
    ):
        reg.register(topic, orders.project_delivery)
    # payment.* → reporting_payments.intents
    for topic in (
        "payment.authorized.v1",
        "payment.captured.v1",
        "payment.failed.v1",
        "payment.refunded.v1",
    ):
        reg.register(topic, payments.project_intent)
    # wallet.* → reporting_payments.wallets
    for topic in (
        "wallet.credited.v1",
        "wallet.debited.v1",
    ):
        reg.register(topic, payments.project_wallet)
    # ledger.posted.v1 → reporting_ledger.postings (financial dashboards)
    reg.register("ledger.posted.v1", ledger.project_posting)
    # promotion.redeemed.v1 → reporting_promotions.redemptions
    reg.register("promotion.redeemed.v1", promotions.project_redemption)
    # loyalty.* → reporting_loyalty.accounts
    for topic in (
        "loyalty.points_accrued.v1",
        "loyalty.points_redeemed.v1",
    ):
        reg.register(topic, promotions.project_loyalty)
    return reg


__all__ = ["ProjectorFn", "ProjectorRegistry", "default_registry"]


def extract_event_id(envelope: dict[str, Any]) -> uuid.UUID:
    """Pull the canonical `event_id` from an event envelope."""
    raw = envelope.get("event_id") or envelope.get("id")
    if not raw:
        raise ValueError("event envelope missing event_id")
    return uuid.UUID(str(raw))


def extract_partition_key(envelope: dict[str, Any]) -> str:
    """Pull the partition key per INTEGRATION.md §3 conventions.

    Most events use `aggregate_id`; some reporting-specific events
    override via `data.partition_key`. Falls back to aggregate_id.
    """
    data = envelope.get("data") or {}
    if isinstance(data, dict) and "partition_key" in data:
        return str(data["partition_key"])
    return str(envelope.get("aggregate_id") or envelope.get("event_id") or "")
