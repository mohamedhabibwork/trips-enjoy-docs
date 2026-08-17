"""Kafka consumer for fraud-risk-service.

Listens to identity.session.created.v1 (per INTEGRATION.md §4.1) and
upserts a DeviceFingerprint record. The actual Kafka client is mocked
in tests via a no-op producer; in production this is wired to the
canonical Kafka bootstrap URL.
"""
from __future__ import annotations

import json
import logging
import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .db import InboxEvent
from .services import DeviceFingerprintService

logger = logging.getLogger(__name__)


class FakeKafkaConsumer:
    """Minimal Kafka consumer used for tests + the in-process event bus.

    In production this is replaced by aiokafka or confluent-kafka.
    Here we expose `register_topic()` for unit tests and `drain()` for
    the integration tests.
    """

    def __init__(self) -> None:
        self._topics: dict[str, list[dict]] = {}

    def register_topic(self, topic: str) -> None:
        self._topics[topic] = []

    def push(self, topic: str, payload: dict) -> None:
        if topic not in self._topics:
            raise KeyError(f"unknown topic {topic}")
        self._topics[topic].append(payload)

    def drain(self, topic: str) -> list[dict]:
        msgs = list(self._topics.get(topic, []))
        self._topics[topic] = []
        return msgs


class KafkaConsumerRunner:
    """Drains inbox events from the FakeKafkaConsumer and applies them.

    For production: this is the same logic applied to aiokafka.
    """

    def __init__(
        self,
        consumer: FakeKafkaConsumer,
        fingerprints: DeviceFingerprintService,
        session_factory,
    ) -> None:
        self._consumer = consumer
        self._fingerprints = fingerprints
        self._session_factory = session_factory

    async def run_once(self) -> int:
        """Process one batch per topic. Returns total events handled."""
        handled = 0
        for topic in list(self._consumer._topics.keys()):
            payloads = self._consumer.drain(topic)
            for payload in payloads:
                await self._handle(topic, payload)
                handled += 1
        return handled

    async def _handle(self, topic: str, payload: dict) -> None:
        async with self._session_factory() as session:
            event_id = uuid.UUID(payload["event_id"])
            existing = await session.get(InboxEvent, event_id)
            if existing is not None:
                logger.info("replay dedup: %s/%s", topic, event_id)
                return
            session.add(
                InboxEvent(
                    id=event_id,
                    source_topic=topic,
                    source_event_id=event_id,
                    event_type=payload.get("event_type", topic),
                    payload=payload,
                    correlation_id=uuid.UUID(
                        payload.get("correlation_id", str(uuid.uuid4()))
                    ),
                    consumed_at=datetime.now(timezone.utc),
                    created_by=uuid.uuid4(),
                )
            )
            await session.commit()

        if topic == "identity.session.created.v1":
            await self._fingerprints.record(
                fingerprint=payload["fingerprint"],
                subject_id=uuid.UUID(payload["subject_id"]),
                user_agent=payload.get("user_agent"),
                ip_address=payload.get("ip_address"),
                device_class=payload.get("device_class"),
                actor_id=uuid.UUID(payload["actor_id"]),
                correlation_id=uuid.UUID(payload["correlation_id"]),
            )