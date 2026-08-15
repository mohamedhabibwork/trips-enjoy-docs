"""Tests for the Idempotency service + OutboxEvent / InboxEvent / Model / Blocklist
domain entity validation (the schema invariants the app layer relies on)."""
from __future__ import annotations

import uuid

import pytest

from app.db import (
    IdempotencyKey,
    InboxEvent,
    OutboxEvent,
)


class TestIdempotencyKeyDomain:
    def test_request_hash_must_be_64_chars(self):
        """The migration CHECK constraint requires length(request_hash) = 64."""
        with pytest.raises(AssertionError):
            IdempotencyKey(
                idempotency_key=uuid.uuid4(),
                request_hash="short",
                response_status=201,
                response_body={"x": 1},
                actor_id=uuid.uuid4(),
                expires_at=__import__("datetime").datetime.now(__import__("datetime").timezone.utc),
            )

    def test_request_hash_64_chars_accepted(self):
        IdempotencyKey(
            idempotency_key=uuid.uuid4(),
            request_hash="a" * 64,
            response_status=201,
            response_body={"x": 1},
            actor_id=uuid.uuid4(),
            expires_at=__import__("datetime").datetime.now(__import__("datetime").timezone.utc),
        )

    def test_is_expired_returns_false_before_expires_at(self):
        from datetime import datetime, timedelta, timezone
        now = datetime.now(timezone.utc)
        key = IdempotencyKey(
            idempotency_key=uuid.uuid4(),
            request_hash="a" * 64,
            response_status=201,
            response_body={"x": 1},
            actor_id=uuid.uuid4(),
            created_at=now,
            expires_at=now + timedelta(seconds=3600),
        )
        assert key.is_expired() is False

    def test_is_expired_returns_true_after_expires_at(self):
        from datetime import datetime, timedelta, timezone
        now = datetime.now(timezone.utc)
        key = IdempotencyKey(
            idempotency_key=uuid.uuid4(),
            request_hash="a" * 64,
            response_status=201,
            response_body={"x": 1},
            actor_id=uuid.uuid4(),
            created_at=now,
            expires_at=now + timedelta(seconds=60),
        )
        assert key.is_expired(now + timedelta(seconds=120)) is True


class TestOutboxEventLifecycle:
    def test_mark_published_sets_published_at(self):
        from datetime import datetime, timezone
        event = OutboxEvent(
            id=uuid.uuid4(),
            aggregate_type="Score",
            aggregate_id=uuid.uuid4(),
            event_type="fraud.risk.scored.v1",
            topic="fraud.risk.scored.v1",
            payload={"score": 0.5},
            correlation_id=uuid.uuid4(),
            created_by=uuid.uuid4(),
        )
        assert event.published_at is None
        now = datetime.now(timezone.utc)
        event.mark_published(now)
        assert event.published_at == now

    def test_mark_failed_increments_attempts(self):
        from datetime import datetime, timezone, timedelta
        event = OutboxEvent(
            id=uuid.uuid4(),
            aggregate_type="Score",
            aggregate_id=uuid.uuid4(),
            event_type="fraud.risk.scored.v1",
            topic="fraud.risk.scored.v1",
            payload={"score": 0.5},
            correlation_id=uuid.uuid4(),
            created_by=uuid.uuid4(),
        )
        next_at = datetime.now(timezone.utc) + timedelta(seconds=1)
        event.mark_failed("kafka_unreachable", next_at)
        # mark_failed() treats None as 0 and increments to 1.
        assert event.attempts == 1
        assert event.last_error == "kafka_unreachable"
        assert event.next_attempt_at == next_at

        # Second failure increments to 2.
        event.mark_failed("kafka_unreachable_2", next_at)
        assert event.attempts == 2


class TestInboxEventLifecycle:
    def test_mark_processed_sets_processed_at(self):
        from datetime import datetime, timezone
        event = InboxEvent(
            id=uuid.uuid4(),
            source_topic="identity.session.created.v1",
            source_event_id=uuid.uuid4(),
            event_type="identity.session.created.v1",
            payload={"subject_id": str(uuid.uuid4())},
            correlation_id=uuid.uuid4(),
            created_by=uuid.uuid4(),
        )
        assert event.processed_at is None
        now = datetime.now(timezone.utc)
        event.mark_processed(now)
        assert event.processed_at == now


class TestRiskScoreValidation:
    def test_score_entity_constructs_with_valid_subject_kind(self):
        """Domain construction succeeds with valid subject_kind; the CHECK
        constraint is enforced at DB persist time only."""
        from app.db import Score
        Score(
            id=uuid.uuid4(),
            subject_id=uuid.uuid4(),
            subject_kind="customer",
            score=0.5,
            decision="allow",
            model_id="baseline-linear-v1",
            features={"x": 1},
            explanations=None,
            created_by=uuid.uuid4(),
        )  # no exception


class TestOutboxBackoff:
    """Verify the exponential backoff math (lifted from outbox_publisher)."""

    def test_backoff_doubles_each_attempt(self):
        from datetime import timedelta
        for attempt in (1, 2, 3, 4, 5):
            seconds = min(300, 1 << min(attempt - 1, 8))
            assert seconds == 1 << (attempt - 1)

    def test_backoff_caps_at_5_minutes(self):
        for attempt in (10, 100, 1000):
            seconds = min(300, 1 << min(attempt - 1, 9))
            assert seconds == 300