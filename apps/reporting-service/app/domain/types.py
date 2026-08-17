"""Domain types (Pydantic models) shared across the service.

Mirror the ERD.md §3 tables. Currency amounts are stored in minor units
(BIGINT) per docs/shared/PLATFORM_BASELINE.md "Money" — these models
carry that invariant forward into the API.
"""
from __future__ import annotations

import enum
import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

# ----- enums ---------------------------------------------------------------

class DriftType(enum.StrEnum):
    MISSING = "missing"
    EXTRA = "extra"
    MISMATCH = "mismatch"


class DriftSeverity(enum.StrEnum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class DriftStatus(enum.StrEnum):
    OPEN = "open"
    ACKNOWLEDGED = "acknowledged"
    RESOLVED = "resolved"


class ExportFormat(enum.StrEnum):
    CSV = "csv"
    PARQUET = "parquet"


class ExportStatus(enum.StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


# ----- helpers -------------------------------------------------------------

def _uuid(value: str | uuid.UUID) -> str:
    """Coerce a UUID-like string into a canonical hyphenated form."""
    return str(uuid.UUID(str(value)))


# ----- drift ---------------------------------------------------------------

class DriftFinding(BaseModel):
    """A drift finding from a reconciliation job (ERD.md §3)."""

    model_config = ConfigDict(extra="forbid")

    id: uuid.UUID
    view_name: str = Field(min_length=1, max_length=128)
    drift_type: DriftType
    entity_id: uuid.UUID
    details: dict[str, Any]
    severity: DriftSeverity
    status: DriftStatus = DriftStatus.OPEN
    ticket_id: uuid.UUID | None = None
    detected_at: datetime
    created_at: datetime


# ----- export --------------------------------------------------------------

class ExportJob(BaseModel):
    """An export job (ERD.md §3)."""

    model_config = ConfigDict(extra="forbid")

    id: uuid.UUID
    name: str = Field(min_length=1, max_length=128)
    format: ExportFormat
    query: dict[str, Any]
    status: ExportStatus
    actor_id: uuid.UUID
    reason: str = Field(min_length=1, max_length=512)
    idempotency_key: uuid.UUID | None = None
    correlation_id: uuid.UUID
    s3_path: str | None = None
    row_count: int | None = Field(default=None, ge=0)
    size_bytes: int | None = Field(default=None, ge=0)
    started_at: datetime | None = None
    completed_at: datetime | None = None
    error: str | None = None
    created_at: datetime


# ----- read access log -----------------------------------------------------

class ReadAccessLog(BaseModel):
    """Append-only log of every read access (ERD.md §3)."""

    model_config = ConfigDict(extra="forbid")

    id: uuid.UUID
    actor_id: uuid.UUID
    view_name: str
    query: dict[str, Any]
    result_count: int = Field(ge=0)
    reason: str = Field(min_length=1, max_length=512)
    correlation_id: uuid.UUID
    created_at: datetime


# ----- shared envelope helpers --------------------------------------------

class Page(BaseModel):
    """Pagination cursor + items (per INTEGRATION.md §1.2)."""

    items: list[Any]
    next_cursor: str | None = None
    total: int | None = None


class ErrorEnvelope(BaseModel):
    """RFC 7807 + downstream block (shared/CONVENTIONS.md §1)."""

    type: str
    title: str
    status: int
    code: str
    detail: str | None = None
    instance: str | None = None
    downstream: dict[str, Any] | None = None


__all__ = [
    "DriftFinding",
    "DriftSeverity",
    "DriftStatus",
    "DriftType",
    "ErrorEnvelope",
    "ExportFormat",
    "ExportJob",
    "ExportStatus",
    "Page",
    "ReadAccessLog",
]
