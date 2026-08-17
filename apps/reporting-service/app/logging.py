"""Structured JSON logger + secret-redaction helpers.

Per docs/services/reporting-service/TECH.md §7 the service emits structured
JSON to stdout with standard fields (correlation_id, request_id, user_id,
tenant_id). All log records flow through `redact()` before emission so
credentials, tokens, and connection-string passwords never leak into logs.
"""
from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

from .config import get_settings

# Standard fields per TECH.md §7 / docs/shared/CONVENTIONS.md.
STANDARD_FIELDS = {
    "timestamp",
    "level",
    "service",
    "version",
    "env",
    "correlation_id",
    "request_id",
    "user_id",
    "tenant_id",
    "message",
}

# Substrings that mark a value as sensitive and trigger redaction.
_SECRET_KEYS = {"password", "secret", "token", "authorization", "api_key"}


def _redact_value(key: str | None, value: Any) -> Any:
    if value is None:
        return None
    if key and any(s in key.lower() for s in _SECRET_KEYS):
        return "***"
    if isinstance(value, str) and "://" in value and "@" in value:
        return _redact_url(value)
    return value


def _redact_url(url: str) -> str:
    """Strip the password segment from `scheme://user:pass@host/path`."""
    if "@" not in url:
        return url
    try:
        scheme, rest = url.split("://", 1)
        creds, host = rest.split("@", 1)
        if ":" in creds:
            user, _ = creds.split(":", 1)
            return f"{scheme}://{user}:***@{host}"
        return url
    except ValueError:
        return url


def redact(payload: dict[str, Any]) -> dict[str, Any]:
    """Return a deep-copied dict with sensitive fields redacted."""
    out: dict[str, Any] = {}
    for k, v in payload.items():
        if isinstance(v, dict):
            out[k] = redact(v)
        else:
            out[k] = _redact_value(k, v)
    return out


class JsonFormatter(logging.Formatter):
    """Emit one JSON object per record, with the standard fields first."""

    def __init__(self, service: str, version: str, env: str) -> None:
        super().__init__()
        self._service = service
        self._version = version
        self._env = env

    def format(self, record: logging.LogRecord) -> str:  # type: ignore[override]
        # Standard fields first; everything else appended under `extra`.
        standard: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname,
            "service": self._service,
            "version": self._version,
            "env": self._env,
            "message": record.getMessage(),
        }
        # Promote well-known structured fields if present on the record.
        for key in ("correlation_id", "request_id", "user_id", "tenant_id"):
            value = getattr(record, key, None)
            if value is not None:
                standard[key] = value

        extras: dict[str, Any] = {
            k: v
            for k, v in record.__dict__.items()
            if k not in STANDARD_FIELDS
            and k
            not in {
                "args",
                "msg",
                "levelname",
                "levelno",
                "pathname",
                "filename",
                "module",
                "exc_info",
                "exc_text",
                "stack_info",
                "lineno",
                "funcName",
                "created",
                "msecs",
                "relativeCreated",
                "thread",
                "threadName",
                "processName",
                "process",
                "name",
                "taskName",
            }
        }
        if extras:
            standard["extra"] = redact(extras)

        if record.exc_info:
            standard["exception"] = self.formatException(record.exc_info)
        return json.dumps(standard, default=str)


def configure_logging() -> None:
    """Idempotently install the JSON formatter on the root logger."""
    settings = get_settings()
    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(
        JsonFormatter(
            service=settings.service_name,
            version=settings.service_version,
            env=settings.platform_env,
        )
    )
    root = logging.getLogger()
    # Replace existing handlers to avoid duplicate JSON lines under reload.
    root.handlers = [handler]
    root.setLevel(logging.INFO)


def get_logger(name: str) -> logging.LoggerAdapter:
    """Convenience adapter that injects the service name into every record."""
    logger = logging.getLogger(name)
    return logging.LoggerAdapter(logger, {"service": get_settings().service_name})
