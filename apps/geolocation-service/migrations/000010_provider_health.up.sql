-- 000010_provider_health.up.sql
--
-- geolocation.provider_health — append-only health probe log per
-- docs/services/geolocation-service/ERD.md §3.9. RANGE partitioned
-- monthly on probed_at; 30d retention. Pre-created _2026_07
-- partition follows the canonical template.
CREATE TABLE IF NOT EXISTS geolocation.provider_health (
    id             UUID NOT NULL,
    vendor_id      TEXT NOT NULL,
    probed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    result         TEXT NOT NULL CHECK (result IN ('ok','timeout','http_4xx','http_5xx','dns','tls')),
    latency_ms     INT,
    capability     TEXT NOT NULL,
    endpoint       TEXT NOT NULL,
    error_code     TEXT,
    correlation_id UUID NOT NULL,
    PRIMARY KEY (id, probed_at)
) PARTITION BY RANGE (probed_at);

CREATE TABLE IF NOT EXISTS geolocation.provider_health_2026_07
    PARTITION OF geolocation.provider_health
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

DO $$
DECLARE
    v_parent REGCLASS := 'geolocation.provider_health'::REGCLASS;
    v_child  REGCLASS := 'geolocation.provider_health_2026_07'::REGCLASS;
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS provider_health_vendor_time_idx
    ON geolocation.provider_health (vendor_id, probed_at DESC);
CREATE INDEX IF NOT EXISTS provider_health_result_time_idx
    ON geolocation.provider_health (result, probed_at DESC);