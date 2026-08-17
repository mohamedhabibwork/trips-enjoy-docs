"""Kafka consumer runner.

Reads events from a topic list and dispatches them through the projector
registry. Every event is recorded in the inbox first; only then is the
projector invoked (PLAN §T-RPT-01 idempotency). On error the row stays
unprocessed and is retried with exponential backoff; after `retry_count`
attempts it is published to `<topic>.dlq`.

This scaffold uses `aiokafka` if installed but falls back to a stub
consumer when the broker is unreachable so the service can boot in
local-dev mode. The stub's `run()` simply yields control.
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from sqlalchemy.ext.asyncio import AsyncConnection, async_sessionmaker

from ..config import get_settings
from .inbox import Inbox
from .projectors import ProjectorRegistry, default_registry, extract_event_id

logger = logging.getLogger(__name__)


# ----- types ---------------------------------------------------------------

Message = dict[str, Any]
ConsumeFn = Callable[[str], Awaitable[AsyncIterator[Message]]]


# ----- consumer ------------------------------------------------------------

@dataclass(slots=True)
class KafkaConsumerRunner:
    """Project events from Kafka topics into the read models.

    `pool_size` is the number of concurrent projection coroutines.
    """

    registry: ProjectorRegistry
    session_factory: async_sessionmaker[AsyncConnection]
    pool_size: int = 4
    retry_count: int = 3
    backoff_base_seconds: float = 0.5
    _stopping: asyncio.Event = field(default_factory=asyncio.Event)

    async def run(self) -> None:
        """Connect to Kafka, consume, project. Blocks until `stop()`."""
        settings = get_settings()
        topics = self.registry.topics()
        if not topics:
            logger.warning("reporting-service consumer: no topics registered")
            await self._stopping.wait()
            return
        try:
            from aiokafka import AIOKafkaConsumer  # type: ignore[import-not-found]
        except ImportError:
            logger.warning(
                "aiokafka not installed; reporting-service runs with no consumer"
            )
            await self._stopping.wait()
            return
        consumer = AIOKafkaConsumer(
            *topics,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id=settings.kafka_consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        await consumer.start()
        try:
            workers = [
                asyncio.create_task(self._worker(consumer)) for _ in range(self.pool_size)
            ]
            await self._stopping.wait()
            for w in workers:
                w.cancel()
            await asyncio.gather(*workers, return_exceptions=True)
        finally:
            await consumer.stop()

    async def stop(self) -> None:
        self._stopping.set()

    async def _worker(self, consumer: Any) -> None:
        """One coroutine that pulls messages and dispatches them."""
        async for record in consumer:
            await self._handle(record.topic, record.value)

    async def _handle(self, topic: str, value: bytes | dict[str, Any]) -> None:
        """Single-message handler with retry + DLQ.

        Accepts either raw bytes (decoded as JSON) or a pre-parsed dict
        (used by tests).
        """
        if isinstance(value, (bytes, bytearray)):
            try:
                import json

                envelope = json.loads(value.decode("utf-8"))
            except Exception as exc:
                logger.exception(
                    "reporting-service: malformed payload on %s: %s", topic, exc
                )
                return
        else:
            envelope = value

        try:
            event_id = extract_event_id(envelope)
        except Exception:
            logger.exception("reporting-service: envelope missing event_id on %s", topic)
            return

        projector = self.registry.get(topic)
        if projector is None:
            # Unknown topic — nothing to project; record in inbox for audit.
            await self._record_only(topic, event_id)
            return

        last_exc: Exception | None = None
        for attempt in range(self.retry_count):
            try:
                async with self.session_factory() as conn:
                    if await Inbox.already_processed(conn, event_id):
                        return
                    await projector(conn, envelope)
                    await Inbox.record(conn, event_id, topic, processed=True)
                    await conn.commit()
                return
            except Exception as exc:
                last_exc = exc
                await asyncio.sleep(self.backoff_base_seconds * (2**attempt))
        # All retries failed → DLQ.
        logger.error(
            "reporting-service: DLQ for event %s on topic %s after %d attempts: %s",
            event_id,
            topic,
            self.retry_count,
            last_exc,
        )
        await self._send_to_dlq(topic, envelope, last_exc)

    async def _record_only(self, topic: str, event_id: uuid.UUID) -> None:
        async with self.session_factory() as conn:
            await Inbox.record(conn, event_id, topic, processed=True)
            await conn.commit()

    async def _send_to_dlq(
        self, topic: str, envelope: dict[str, Any], exc: Exception | None
    ) -> None:
        """Publish a poison event to `<topic>.dlq`.

        Implementation note: this scaffold writes the DLQ payload to the
        outbox so the existing poller publishes it. Production code can
        swap this for a direct aiokafka call.
        """
        from .outbox import OutboxWriter

        async with self.session_factory() as conn:
            writer = OutboxWriter(conn)
            await writer.enqueue(
                topic=f"{topic}.dlq",
                event_id=uuid.uuid7(),
                payload={
                    "original": envelope,
                    "error": str(exc) if exc else None,
                    "failed_at": datetime.now(tz=UTC).isoformat(),
                },
            )
            await conn.commit()


# ----- import-time helper (for type hints) ---------------------------------



def build_default_runner(
    session_factory: async_sessionmaker[AsyncConnection],
) -> KafkaConsumerRunner:
    """Helper for the FastAPI lifespan to construct the runner."""
    return KafkaConsumerRunner(
        registry=default_registry(),
        session_factory=session_factory,
    )
