"""Outbox publisher — 200ms poll + exponential backoff up to 5min.

Mirrors the canonical pattern across all graduates (audit-service,
ledger-service, notification-service, configuration-service,
identity-service, customer-service, payment-service, driver-service,
courier-service, restaurant-service, pricing-service).
"""
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import OutboxEvent

logger = logging.getLogger(__name__)


class OutboxPublisher:
    """Polling outbox publisher.

    On startup, kicks off an asyncio task that polls outbox_events
    every 200ms, publishes pending events to Kafka, and updates
    `published_at`. On failure, increments `attempts` and schedules
    the next attempt with exponential backoff (1s → 5min cap).
    """

    def __init__(self, session_factory, kafka_producer) -> None:
        self._session_factory = session_factory
        self._producer = kafka_producer
        self._task: asyncio.Task | None = None
        self._running = False

    async def start(self) -> None:
        self._running = True
        self._task = asyncio.create_task(self._poll_loop())

    async def stop(self) -> None:
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _poll_loop(self) -> None:
        while self._running:
            try:
                await self._poll_once()
            except Exception as exc:  # pragma: no cover — defensive
                logger.exception("outbox poll failure: %s", exc)
            await asyncio.sleep(0.2)  # 200ms

    async def _poll_once(self) -> None:
        now = datetime.now(timezone.utc)
        async with self._session_factory() as session:
            stmt = (
                select(OutboxEvent)
                .where(OutboxEvent.published_at.is_(None))
                .where(OutboxEvent.next_attempt_at <= now)
                .order_by(OutboxEvent.next_attempt_at.asc())
                .limit(100)
            )
            result = await session.execute(stmt)
            pending = list(result.scalars().all())

            for event in pending:
                try:
                    payload = _serialize_payload(event.payload)
                    await self._producer.send(
                        topic=event.topic,
                        key=str(event.aggregate_id),
                        value=payload,
                    )
                    event.mark_published(datetime.now(timezone.utc))
                except Exception as exc:
                    backoff = _next_backoff(event.attempts + 1)
                    event.mark_failed(
                        str(exc), datetime.now(timezone.utc) + backoff
                    )
                    logger.warning(
                        "outbox publish failed (event=%s, attempt=%d): %s",
                        event.id, event.attempts + 1, exc,
                    )
            await session.commit()


def _serialize_payload(payload: dict) -> bytes:
    """Serialise a JSONB payload to bytes for the Kafka producer."""
    return json.dumps(payload, default=str).encode("utf-8")


def _next_backoff(attempt: int) -> timedelta:
    """Exponential backoff up to 5 minutes.

    The shift is clamped to 9 bits so that the maximum backoff (when
    attempt > 10) is exactly 300 seconds (5 minutes).
    """
    seconds = min(300, 1 << min(attempt - 1, 9))
    return timedelta(seconds=seconds)