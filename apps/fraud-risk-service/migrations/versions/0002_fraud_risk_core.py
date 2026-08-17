"""fraud-risk-service core schema (V2).

Adds:
  - score
  - device_fingerprint
  - blocklist
  - model
  - evaluation
  - action (audit; time-partitioned)
  - velocity_counter
  - outbox
  - inbox
  - idempotency_keys

Per docs/services/fraud-risk-service/ERD.md §3 and docs/shared/PLATFORM_BASELINE.md §2.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "0002_fraud_risk_core"
down_revision = "0001_create_fraud_risk_schema"


def upgrade() -> None:
    # 1) fraud_risk.score : the headline risk score aggregate.
    op.create_table(
        "score",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("subject_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("subject_kind", sa.Text, nullable=False),
        sa.Column("score", sa.dialects.postgresql.NUMERIC(6, 4), nullable=False),
        sa.Column("decision", sa.Text, nullable=False),
        sa.Column("model_id", sa.Text, nullable=False),
        sa.Column("features", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("explanations", sa.dialects.postgresql.JSONB),
        sa.Column("computed_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("row_version", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint("subject_kind IN ('customer','driver','courier','merchant')", name="score_subject_kind_check"),
        sa.CheckConstraint("decision IN ('allow','review','block')", name="score_decision_check"),
        sa.CheckConstraint("score >= 0 AND score <= 1", name="score_range_check"),
        schema="fraud_risk",
    )
    op.create_index("score_subject_id_idx", "score", ["subject_id"], schema="fraud_risk")
    op.create_index("score_decision_idx", "score", ["decision"], schema="fraud_risk")
    op.create_index("score_computed_at_idx", "score", ["computed_at"], schema="fraud_risk")

    # 2) fraud_risk.device_fingerprint : the device fingerprinting cache.
    op.create_table(
        "device_fingerprint",
        sa.Column("fingerprint", sa.Text, primary_key=True),
        sa.Column("subject_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_agent", sa.Text),
        sa.Column("ip_address", sa.dialects.postgresql.INET),
        sa.Column("device_class", sa.Text),
        sa.Column("first_seen_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("last_seen_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("seen_count", sa.Integer, nullable=False, server_default="1"),
        sa.Column("trust_score", sa.dialects.postgresql.NUMERIC(3, 2), nullable=False, server_default="0.50"),
        sa.Column("row_version", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint("trust_score >= 0 AND trust_score <= 1", name="device_fingerprint_trust_score_check"),
        schema="fraud_risk",
    )
    op.create_index("device_fingerprint_subject_id_idx", "device_fingerprint", ["subject_id"], schema="fraud_risk")
    op.create_index("device_fingerprint_trust_score_idx", "device_fingerprint", ["trust_score"], schema="fraud_risk",
                    postgresql_where=sa.text("trust_score < 0.30"))

    # 3) fraud_risk.blocklist : the global blocklist.
    op.create_table(
        "blocklist",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("kind", sa.Text, nullable=False),
        sa.Column("value", sa.Text, nullable=False),
        sa.Column("reason", sa.Text, nullable=False),
        sa.Column("added_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("expires_at", sa.TIMESTAMP(timezone=True)),
        sa.Column("row_version", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.CheckConstraint("kind IN ('email','phone','ip','fingerprint','device','card_bin','country')", name="blocklist_kind_check"),
        schema="fraud_risk",
    )
    op.create_index("blocklist_kind_value_uniq", "blocklist", ["kind", "value"], unique=True, schema="fraud_risk")

    # 4) fraud_risk.model : the ML model registry.
    op.create_table(
        "model",
        sa.Column("id", sa.Text, primary_key=True),
        sa.Column("version", sa.Integer, nullable=False),
        sa.Column("algorithm", sa.Text, nullable=False),
        sa.Column("trained_at", sa.TIMESTAMP(timezone=True), nullable=False),
        sa.Column("deployed_at", sa.TIMESTAMP(timezone=True)),
        sa.Column("retired_at", sa.TIMESTAMP(timezone=True)),
        sa.Column("hyperparameters", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("metrics", sa.dialects.postgresql.JSONB),
        sa.Column("row_version", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        schema="fraud_risk",
    )

    # 5) fraud_risk.evaluation : per-model evaluation runs.
    op.create_table(
        "evaluation",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("model_id", sa.Text, nullable=False),
        sa.Column("evaluated_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("dataset", sa.Text, nullable=False),
        sa.Column("metrics", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("row_version", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.ForeignKeyConstraint(["model_id"], ["model.id"], name="evaluation_model_id_fkey"),
        schema="fraud_risk",
    )
    op.create_index("evaluation_model_id_idx", "evaluation", ["model_id"], schema="fraud_risk")

    # 6) fraud_risk.action : time-partitioned append-only audit.
    op.execute("""
        CREATE TABLE fraud_risk.action (
            id UUID NOT NULL,
            subject_id UUID NOT NULL,
            subject_kind TEXT NOT NULL,
            action TEXT NOT NULL,
            actor_id UUID,
            actor_kind TEXT NOT NULL,
            reason TEXT,
            payload JSONB,
            correlation_id UUID NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (id, created_at),
            CONSTRAINT action_subject_kind_check CHECK (subject_kind IN ('customer','driver','courier','merchant','device')),
            CONSTRAINT action_action_check CHECK (action IN ('allow','review','block','blocklist_add','blocklist_remove','model_deploy','model_retire')),
            CONSTRAINT action_actor_kind_check CHECK (actor_kind IN ('admin','owner','system','model'))
        ) PARTITION BY RANGE (created_at)
    """)
    op.create_index("action_subject_id_idx", "action", ["subject_id", "created_at"], schema="fraud_risk")
    op.create_index("action_correlation_id_idx", "action", ["correlation_id"], schema="fraud_risk")

    # 7) fraud_risk.velocity_counter : rolling-window counters.
    op.create_table(
        "velocity_counter",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("subject_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("window_kind", sa.Text, nullable=False),
        sa.Column("window_start", sa.TIMESTAMP(timezone=True), nullable=False),
        sa.Column("count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("sum_minor", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("updated_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("row_version", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.CheckConstraint("window_kind IN ('hourly','daily','weekly')", name="velocity_counter_window_kind_check"),
        sa.CheckConstraint("count >= 0", name="velocity_counter_count_check"),
        sa.UniqueConstraint("subject_id", "window_kind", "window_start", name="velocity_counter_subject_window_uniq"),
        schema="fraud_risk",
    )

    # 8) fraud_risk.outbox + 9) inbox (standard platform pattern).
    op.create_table(
        "outbox",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("aggregate_type", sa.Text, nullable=False),
        sa.Column("aggregate_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("event_type", sa.Text, nullable=False),
        sa.Column("topic", sa.Text, nullable=False),
        sa.Column("payload", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("headers", sa.dialects.postgresql.JSONB),
        sa.Column("correlation_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("last_error", sa.Text),
        sa.Column("next_attempt_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("published_at", sa.TIMESTAMP(timezone=True)),
        sa.Column("created_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        schema="fraud_risk",
    )
    op.create_index("outbox_pending_idx", "outbox", ["next_attempt_at"], schema="fraud_risk",
                    postgresql_where=sa.text("published_at IS NULL"))

    op.create_table(
        "inbox",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("source_topic", sa.Text, nullable=False),
        sa.Column("source_event_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("event_type", sa.Text, nullable=False),
        sa.Column("payload", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("correlation_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("consumed_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("processed_at", sa.TIMESTAMP(timezone=True)),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.UniqueConstraint("source_topic", "source_event_id", name="inbox_topic_event_uniq"),
        schema="fraud_risk",
    )

    # 10) fraud_risk.idempotency_keys (newer PK pattern, like pricing-service).
    op.create_table(
        "idempotency_keys",
        sa.Column("idempotency_key", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("request_hash", sa.Text, nullable=False),
        sa.Column("response_status", sa.Integer, nullable=False),
        sa.Column("response_body", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("actor_id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("expires_at", sa.TIMESTAMP(timezone=True), nullable=False),
        sa.CheckConstraint("length(request_hash) = 64", name="idempotency_request_hash_length_check"),
        schema="fraud_risk",
    )

    # Create a few initial monthly partitions for the `action` table.
    op.execute("""
        CREATE TABLE fraud_risk.action_default PARTITION OF fraud_risk.action DEFAULT
    """)


def downgrade() -> None:
    op.drop_table("idempotency_keys", schema="fraud_risk")
    op.drop_table("inbox", schema="fraud_risk")
    op.drop_table("outbox", schema="fraud_risk")
    op.drop_table("velocity_counter", schema="fraud_risk")
    op.execute("DROP TABLE IF EXISTS fraud_risk.action_default")
    op.drop_table("action", schema="fraud_risk")
    op.drop_table("evaluation", schema="fraud_risk")
    op.drop_table("model", schema="fraud_risk")
    op.drop_table("blocklist", schema="fraud_risk")
    op.drop_table("device_fingerprint", schema="fraud_risk")
    op.drop_table("score", schema="fraud_risk")