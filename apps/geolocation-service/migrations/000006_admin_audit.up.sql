-- 000006_admin_audit.up.sql
--
-- geolocation.admin_audit — append-only audit log per
-- docs/services/geolocation-service/ERD.md §3.5. RANGE partitioned
-- monthly on occurred_at; 1y retention (drop partitions older than
-- 12 months). The pre-created _2026_07 partition follows the
-- canonical template from DATABASE_ARCHITECTURE.md "Table Partitioning".
CREATE TABLE IF NOT EXISTS geolocation.admin_audit (
    id                       UUID NOT NULL,
    occurred_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    action                   TEXT NOT NULL CHECK (action IN ('cache_purge','provider_rotate','fallback_activate','fallback_deactivate')),
    actor_sub                UUID NOT NULL,
    actor_role               TEXT NOT NULL CHECK (actor_role IN ('admin','platform_engineer')),
    tenant_id                UUID,
    request_body             JSONB NOT NULL,
    request_idempotency_key  TEXT,
    signature                TEXT NOT NULL,
    result                   TEXT NOT NULL CHECK (result IN ('success','failure')),
    error_code               TEXT,
    correlation_id           UUID NOT NULL,
    outbox_event_id          UUID,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE IF NOT EXISTS geolocation.admin_audit_2026_07
    PARTITION OF geolocation.admin_audit
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

DO $$
DECLARE
    v_parent REGCLASS := 'geolocation.admin_audit'::REGCLASS;
    v_child  REGCLASS := 'geolocation.admin_audit_2026_07'::REGCLASS;
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS admin_audit_actor_idx
    ON geolocation.admin_audit (occurred_at DESC, actor_sub);
CREATE INDEX IF NOT EXISTS admin_audit_correlation_idx
    ON geolocation.admin_audit (correlation_id);