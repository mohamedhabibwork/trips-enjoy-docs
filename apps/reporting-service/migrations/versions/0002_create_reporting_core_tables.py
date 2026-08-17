"""create reporting core tables (drift_findings, export_jobs, read_access_log, inbox, outbox)

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-14

Implements docs/services/reporting-service/ERD.md §3 (the core non-read-model
tables). Read-model tables per entity (reporting_trips.trips, reporting_orders.orders,
reporting_payments.intents, …) are owned by the per-entity read model packages
under app/domain/ and are introduced by follow-up revisions 0003+.

Partitioning: per docs/services/reporting-service/ERD.md §9, drift_findings /
export_jobs / read_access_log are partitioned by month (created_at). Idempotent
pre-creation of the current month is included so a fresh database applies
cleanly without a separate maintenance job.
"""
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # ----- drift_findings -------------------------------------------------
    # Per ERD.md §3 "reporting.DriftFinding".
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS reporting.drift_findings (
            id UUID PRIMARY KEY,
            view_name TEXT NOT NULL,
            drift_type TEXT NOT NULL
                CHECK (drift_type IN ('missing','extra','mismatch')),
            entity_id UUID NOT NULL,
            details JSONB NOT NULL,
            severity TEXT NOT NULL
                CHECK (severity IN ('low','medium','high','critical')),
            status TEXT NOT NULL
                CHECK (status IN ('open','acknowledged','resolved')),
            ticket_id UUID,
            detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        ) PARTITION BY RANGE (created_at)
        """
    )
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_drift_view_status
            ON reporting.drift_findings (view_name, status, detected_at DESC)
        """
    )
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_drift_open_severity
            ON reporting.drift_findings (severity)
            WHERE status = 'open'
        """
    )

    # ----- export_jobs ---------------------------------------------------
    # Per ERD.md §3 "reporting.ExportJob".
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS reporting.export_jobs (
            id UUID PRIMARY KEY,
            name TEXT NOT NULL,
            format TEXT NOT NULL
                CHECK (format IN ('csv','parquet')),
            query JSONB NOT NULL,
            status TEXT NOT NULL
                CHECK (status IN ('queued','running','succeeded','failed')),
            actor_id UUID NOT NULL,
            reason TEXT NOT NULL,
            idempotency_key UUID,
            correlation_id UUID NOT NULL,
            s3_path TEXT,
            row_count BIGINT,
            size_bytes BIGINT,
            started_at TIMESTAMPTZ,
            completed_at TIMESTAMPTZ,
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        ) PARTITION BY RANGE (created_at)
        """
    )
    op.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_export_idem
            ON reporting.export_jobs (name, idempotency_key)
            WHERE idempotency_key IS NOT NULL
        """
    )
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_export_status
            ON reporting.export_jobs (status, created_at DESC)
        """
    )

    # ----- read_access_log -----------------------------------------------
    # Per ERD.md §3 "reporting.ReadAccessLog". Append-only (no UPDATE/DELETE
    # grants are revoked in a follow-up revision to keep this idempotent
    # in case the role does not yet exist).
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS reporting.read_access_log (
            id UUID PRIMARY KEY,
            actor_id UUID NOT NULL,
            view_name TEXT NOT NULL,
            query JSONB NOT NULL,
            result_count INT NOT NULL,
            reason TEXT NOT NULL,
            correlation_id UUID NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        ) PARTITION BY RANGE (created_at)
        """
    )

    # ----- inbox / outbox ------------------------------------------------
    # Per ERD.md §3 "reporting.Inbox" / "reporting.Outbox".
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS reporting.inbox (
            event_id UUID PRIMARY KEY,
            topic TEXT NOT NULL,
            received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            processed_at TIMESTAMPTZ,
            error TEXT
        )
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS reporting.outbox (
            id UUID PRIMARY KEY,
            topic TEXT NOT NULL,
            event_id UUID NOT NULL,
            payload JSONB NOT NULL,
            headers JSONB,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            claimed_at TIMESTAMPTZ,
            published_at TIMESTAMPTZ
        )
        """
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS reporting.outbox")
    op.execute("DROP TABLE IF EXISTS reporting.inbox")
    op.execute("DROP TABLE IF EXISTS reporting.read_access_log")
    op.execute("DROP TABLE IF EXISTS reporting.export_jobs")
    op.execute("DROP TABLE IF EXISTS reporting.drift_findings")
