"""Tests for the domain Pydantic models."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.domain.types import (
    DriftFinding,
    DriftSeverity,
    DriftStatus,
    DriftType,
    ExportFormat,
    ExportJob,
    ExportStatus,
    ReadAccessLog,
)


def _now() -> datetime:
    return datetime.now(tz=UTC)


def test_drift_finding_round_trip() -> None:
    finding = DriftFinding(
        id=uuid.uuid4(),
        view_name="reporting_trips.trips",
        drift_type=DriftType.MISSING,
        entity_id=uuid.uuid4(),
        details={"source": "trip-service"},
        severity=DriftSeverity.HIGH,
        status=DriftStatus.OPEN,
        ticket_id=None,
        detected_at=_now(),
        created_at=_now(),
    )
    dumped = finding.model_dump()
    restored = DriftFinding.model_validate(dumped)
    assert restored.view_name == finding.view_name


def test_export_job_enforces_format_enum() -> None:
    with pytest.raises(ValidationError):
        ExportJob(
            id=uuid.uuid4(),
            name="revenue",
            format="xlsx",  # type: ignore[arg-type]
            query={"from": "2026-07-01T00:00:00Z"},
            status=ExportStatus.QUEUED,
            actor_id=uuid.uuid4(),
            reason="test",
            correlation_id=uuid.uuid4(),
            s3_path=None,
            row_count=None,
            size_bytes=None,
            started_at=None,
            completed_at=None,
            error=None,
            created_at=_now(),
        )


def test_export_job_format_accepts_known_values() -> None:
    for fmt in (ExportFormat.CSV, ExportFormat.PARQUET):
        ExportJob(
            id=uuid.uuid4(),
            name="revenue",
            format=fmt,
            query={},
            status=ExportStatus.QUEUED,
            actor_id=uuid.uuid4(),
            reason="test",
            correlation_id=uuid.uuid4(),
            s3_path=None,
            row_count=None,
            size_bytes=None,
            started_at=None,
            completed_at=None,
            error=None,
            created_at=_now(),
        )


def test_read_access_log_requires_reason() -> None:
    with pytest.raises(ValidationError):
        ReadAccessLog(
            id=uuid.uuid4(),
            actor_id=uuid.uuid4(),
            view_name="reporting_trips.trips",
            query={},
            result_count=1,
            reason="",  # empty -> min_length=1 fails
            correlation_id=uuid.uuid4(),
            created_at=_now(),
        )
