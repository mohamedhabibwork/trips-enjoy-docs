"""Tests for the logging redaction helpers."""
from __future__ import annotations

import json
import logging

from app.logging import JsonFormatter, redact

_SECRET_VALUE = "Hunter2"


def test_redact_strips_password_from_url() -> None:
    payload = {"db_url": f"postgresql://user:{_SECRET_VALUE}@host/db"}
    out = redact(payload)
    assert _SECRET_VALUE not in out["db_url"]
    assert "***" in out["db_url"]


def test_redact_masks_known_secret_keys() -> None:
    payload = {"password": _SECRET_VALUE, "username": "alice", "token": "abc"}
    out = redact(payload)
    assert out["password"] == "***"
    assert out["token"] == "***"
    assert out["username"] == "alice"


def test_redact_handles_nested_dict() -> None:
    payload = {"outer": {"password": _SECRET_VALUE, "value": 1}}
    out = redact(payload)
    assert out["outer"]["password"] == "***"
    assert out["outer"]["value"] == 1


def test_json_formatter_emits_standard_fields() -> None:
    fmt = JsonFormatter(service="reporting-service", version="1.0.0", env="test")
    record = logging.LogRecord(
        name="test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="hello",
        args=(),
        exc_info=None,
    )
    record.correlation_id = "abc-123"
    rendered = fmt.format(record)
    parsed = json.loads(rendered)
    assert parsed["service"] == "reporting-service"
    assert parsed["level"] == "INFO"
    assert parsed["message"] == "hello"
    assert parsed["correlation_id"] == "abc-123"
