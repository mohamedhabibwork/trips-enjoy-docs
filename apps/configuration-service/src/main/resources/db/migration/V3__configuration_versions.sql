-- V3__configuration_versions.sql
-- Per docs/services/configuration-service/ERD.md §3 (Version):
--   configuration.versions — immutable per-document history, monthly
--   partitioned on created_at.
--
-- Partitioning follows the canonical template at
-- docs/architecture/DATABASE_ARCHITECTURE.md "Table Partitioning".

-- =========================================================================
-- 1. Parent table — partitioned by month on created_at
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.versions (
    id UUID NOT NULL,
    document_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    value JSONB,
    scope_type TEXT NOT NULL
        CHECK (scope_type IN ('user','restaurant','branch','merchant',
                              'ride_type','zone','city','country',
                              'segment','tenant','global')),
    scope_id TEXT,
    cohort JSONB,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 8 AND 512),
    correlation_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    client_ip TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_at TIMESTAMPTZ,
    CONSTRAINT versions_effective_window_check
        CHECK ((effective_from IS NULL) = (effective_to IS NULL)),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Composite UNIQUE on (document_id, version) — note this is enforced
-- implicitly by the partition key + a unique index on the parent.
-- PostgreSQL allows UNIQUE indexes on partitioned tables only when the
-- index includes the partition key, so we include created_at here.
CREATE UNIQUE INDEX IF NOT EXISTS idx_versions_doc_version_created
    ON configuration.versions (document_id, version, created_at);

-- Secondary indexes (local to each partition).
CREATE INDEX IF NOT EXISTS idx_versions_doc_created
    ON configuration.versions (document_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_versions_actor
    ON configuration.versions (actor_id);
CREATE INDEX IF NOT EXISTS idx_versions_correlation
    ON configuration.versions (correlation_id);
CREATE INDEX IF NOT EXISTS idx_versions_created_at
    ON configuration.versions (created_at);

-- =========================================================================
-- 2. Default partition — safety net so inserts never fail
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.versions_default
    PARTITION OF configuration.versions DEFAULT;

-- =========================================================================
-- 3. Pre-create monthly partitions for previous, current, and next 12 months
--    (matches ERD §9 and PartitionMaintenanceJob's contract).
-- =========================================================================
DO $$
DECLARE
    i INT;
    start_month DATE;
    end_month DATE;
    part_name TEXT;
BEGIN
    FOR i IN -1..12 LOOP
        start_month := date_trunc('month', now() + (i || ' month')::interval)::date;
        end_month := (date_trunc('month', now() + ((i + 1) || ' month')::interval))::date;
        part_name := 'configuration.versions_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF configuration.versions
             FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;
