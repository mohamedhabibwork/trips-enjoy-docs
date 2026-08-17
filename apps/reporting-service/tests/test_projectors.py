"""Tests for the projector registry + dispatcher."""
from __future__ import annotations

import uuid
from unittest.mock import AsyncMock

import pytest

from app.events.projectors import (
    ProjectorRegistry,
    default_registry,
    extract_event_id,
    extract_partition_key,
)


@pytest.mark.asyncio
async def test_default_registry_covers_documented_topics() -> None:
    reg = default_registry()
    expected = {
        "trip.completed.v1",
        "food.order.placed.v1",
        "payment.captured.v1",
        "ledger.posted.v1",
        "promotion.redeemed.v1",
    }
    topics = set(reg.topics())
    assert expected.issubset(topics)


def test_register_and_dispatch() -> None:
    reg = ProjectorRegistry.empty()
    handler = AsyncMock()
    reg.register("foo.bar.v1", handler)
    assert reg.get("foo.bar.v1") is handler
    assert reg.get("missing") is None


def test_extract_event_id_requires_field() -> None:
    with pytest.raises(ValueError):
        extract_event_id({})
    eid = uuid.uuid4()
    assert extract_event_id({"event_id": str(eid)}) == eid


def test_extract_partition_key_prefers_data_override() -> None:
    assert extract_partition_key({"aggregate_id": "a", "data": {"partition_key": "b"}}) == "b"


def test_extract_partition_key_falls_back_to_aggregate_id() -> None:
    assert extract_partition_key({"aggregate_id": "a"}) == "a"
