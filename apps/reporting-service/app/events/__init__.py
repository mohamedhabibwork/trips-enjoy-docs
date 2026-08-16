"""Eventing package: Kafka consumer, inbox/outbox, projector dispatch."""
from __future__ import annotations

from .consumer import KafkaConsumerRunner
from .inbox import Inbox
from .outbox import OutboxWriter
from .projectors import ProjectorRegistry, default_registry

__all__ = [
    "Inbox",
    "KafkaConsumerRunner",
    "OutboxWriter",
    "ProjectorRegistry",
    "default_registry",
]
