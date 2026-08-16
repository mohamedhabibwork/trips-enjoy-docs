"""Shared helpers for projector SQL UPSERTs."""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Any


def parse_rfc3339(value: str | None) -> datetime | None:
    """Parse an RFC3339 timestamp as UTC. Returns None for empty/None."""
    if not value:
        return None
    text = value.replace("Z", "+00:00") if value.endswith("Z") else value
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def extract_data(envelope: dict[str, Any]) -> dict[str, Any]:
    """Pull the `data` payload out of an event envelope."""
    data = envelope.get("data") or {}
    if not isinstance(data, dict):
        raise ValueError("event envelope `data` must be an object")
    return data


def envelope_correlation_id(envelope: dict[str, Any]) -> str | None:
    return envelope.get("correlation_id")
