"""SQLAlchemy ORM models for fraud-risk-service.

Mirrors docs/services/fraud-risk-service/ERD.md §3. 7 entities:
  - Score (the headline risk score)
  - DeviceFingerprint
  - Blocklist
  - Model (ML model registry)
  - Evaluation
  - Action (time-partitioned audit)
  - VelocityCounter
Plus Outbox + Inbox + IdempotencyKey per the canonical platform pattern.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import INET, JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


SCHEMA = "fraud_risk"


class Base(DeclarativeBase):
    pass


def _uuid_pk() -> Mapped[uuid.UUID]:
    return mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)


def _now() -> Mapped[datetime]:
    return mapped_column(DateTime(timezone=True), nullable=False, server_default="now()")


class Score(Base):
    __tablename__ = "score"
    __table_args__ = (
        CheckConstraint(
            "subject_kind IN ('customer','driver','courier','merchant')",
            name="score_subject_kind_check",
        ),
        CheckConstraint(
            "decision IN ('allow','review','block')", name="score_decision_check"
        ),
        CheckConstraint("score >= 0 AND score <= 1", name="score_range_check"),
        {"schema": SCHEMA},
    )

    id: Mapped[uuid.UUID] = _uuid_pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    subject_kind: Mapped[str] = mapped_column(Text, nullable=False)
    score: Mapped[float] = mapped_column(Numeric(6, 4), nullable=False)
    decision: Mapped[str] = mapped_column(Text, nullable=False)
    model_id: Mapped[str] = mapped_column(Text, nullable=False)
    features: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    explanations: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    computed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    row_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")
    created_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    __mapper_args__ = {"eager_defaults": "auto"}

    def __repr__(self) -> str:
        return (
            f"<Score id={self.id} subject_kind={self.subject_kind} "
            f"decision={self.decision} score={self.score}>"
        )


class DeviceFingerprint(Base):
    __tablename__ = "device_fingerprint"
    __table_args__ = (
        CheckConstraint(
            "trust_score >= 0 AND trust_score <= 1",
            name="device_fingerprint_trust_score_check",
        ),
        {"schema": SCHEMA},
    )

    fingerprint: Mapped[str] = mapped_column(Text, primary_key=True)
    subject_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    user_agent: Mapped[str | None] = mapped_column(Text)
    ip_address: Mapped[str | None] = mapped_column(INET)
    device_class: Mapped[str | None] = mapped_column(Text)
    first_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    seen_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    trust_score: Mapped[float] = mapped_column(
        Numeric(3, 2), nullable=False, server_default="0.50"
    )
    row_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")
    created_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)


class Blocklist(Base):
    __tablename__ = "blocklist"
    __table_args__ = (
        CheckConstraint(
            "kind IN ('email','phone','ip','fingerprint','device','card_bin','country')",
            name="blocklist_kind_check",
        ),
        UniqueConstraint("kind", "value", name="blocklist_kind_value_uniq"),
        {"schema": SCHEMA},
    )

    id: Mapped[uuid.UUID] = _uuid_pk()
    kind: Mapped[str] = mapped_column(Text, nullable=False)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    added_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    row_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )


class Model(Base):
    """ML model registry. PK is (id, version) — composite."""

    __tablename__ = "model"
    __table_args__ = ({"schema": SCHEMA},)

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    version: Mapped[int] = mapped_column(Integer, primary_key=True)
    algorithm: Mapped[str] = mapped_column(Text, nullable=False)
    trained_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    deployed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    retired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    hyperparameters: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    metrics: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    row_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")
    created_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)


class Evaluation(Base):
    __tablename__ = "evaluation"
    __table_args__ = ({"schema": SCHEMA},)

    id: Mapped[uuid.UUID] = _uuid_pk()
    model_id: Mapped[str] = mapped_column(Text, ForeignKey("model.id"), nullable=False)
    evaluated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    dataset: Mapped[str] = mapped_column(Text, nullable=False)
    metrics: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    row_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")
    created_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)


class Action(Base):
    """Time-partitioned append-only audit. Composite PK on (id, created_at)."""

    __tablename__ = "action"
    __table_args__ = (
        CheckConstraint(
            "subject_kind IN ('customer','driver','courier','merchant','device')",
            name="action_subject_kind_check",
        ),
        CheckConstraint(
            "action IN ('allow','review','block','blocklist_add','blocklist_remove','model_deploy','model_retire')",
            name="action_action_check",
        ),
        CheckConstraint(
            "actor_kind IN ('admin','owner','system','model')",
            name="action_actor_kind_check",
        ),
        {"schema": SCHEMA},
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    subject_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    subject_kind: Mapped[str] = mapped_column(Text, nullable=False)
    action: Mapped[str] = mapped_column(Text, nullable=False)
    actor_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    actor_kind: Mapped[str] = mapped_column(Text, nullable=False)
    reason: Mapped[str | None] = mapped_column(Text)
    payload: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    correlation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()", primary_key=True
    )


class VelocityCounter(Base):
    __tablename__ = "velocity_counter"
    __table_args__ = (
        CheckConstraint(
            "window_kind IN ('hourly','daily','weekly')",
            name="velocity_counter_window_kind_check",
        ),
        CheckConstraint("count >= 0", name="velocity_counter_count_check"),
        UniqueConstraint(
            "subject_id", "window_kind", "window_start",
            name="velocity_counter_subject_window_uniq",
        ),
        {"schema": SCHEMA},
    )

    id: Mapped[uuid.UUID] = _uuid_pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    window_kind: Mapped[str] = mapped_column(Text, nullable=False)
    window_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    sum_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="0")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    row_version: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default="1")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )


class OutboxEvent(Base):
    __tablename__ = "outbox"
    __table_args__ = ({"schema": SCHEMA},)

    id: Mapped[uuid.UUID] = _uuid_pk()
    aggregate_type: Mapped[str] = mapped_column(Text, nullable=False)
    aggregate_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_type: Mapped[str] = mapped_column(Text, nullable=False)
    topic: Mapped[str] = mapped_column(Text, nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    headers: Mapped[dict[str, str] | None] = mapped_column(JSONB)
    correlation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_error: Mapped[str | None] = mapped_column(Text)
    next_attempt_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    created_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    def mark_published(self, at: datetime) -> None:
        self.published_at = at

    def mark_failed(self, error: str, next_attempt_at: datetime) -> None:
        self.attempts = (self.attempts or 0) + 1
        self.last_error = error
        self.next_attempt_at = next_attempt_at


class InboxEvent(Base):
    __tablename__ = "inbox"
    __table_args__ = (
        UniqueConstraint(
            "source_topic", "source_event_id", name="inbox_topic_event_uniq"
        ),
        {"schema": SCHEMA},
    )

    id: Mapped[uuid.UUID] = _uuid_pk()
    source_topic: Mapped[str] = mapped_column(Text, nullable=False)
    source_event_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    event_type: Mapped[str] = mapped_column(Text, nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    correlation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    consumed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_by: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    def mark_processed(self, at: datetime) -> None:
        self.processed_at = at


class IdempotencyKey(Base):
    __tablename__ = "idempotency_keys"
    __table_args__ = (
        CheckConstraint(
            "length(request_hash) = 64", name="idempotency_request_hash_length_check"
        ),
        {"schema": SCHEMA},
    )

    idempotency_key: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True
    )
    request_hash: Mapped[str] = mapped_column(Text, nullable=False)
    response_status: Mapped[int] = mapped_column(Integer, nullable=False)
    response_body: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    actor_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default="now()"
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    def __init__(self, *args, **kwargs) -> None:
        request_hash = kwargs.get("request_hash")
        if request_hash is not None and len(request_hash) != 64:
            raise AssertionError(
                f"request_hash must be SHA-256 hex (64 chars); got {len(request_hash)}"
            )
        super().__init__(*args, **kwargs)

    def is_expired(self, at: datetime | None = None) -> bool:
        from datetime import datetime, timezone
        now = at or datetime.now(timezone.utc)
        return not (self.expires_at > now)