-- 000012_provider_usage_daily.up.sql
--
-- geolocation.provider_usage_daily — daily cost / usage roll-up per
-- docs/services/geolocation-service/ERD.md §3.11. RANGE partitioned
-- monthly on usage_date; 3y retention. Pre-created _2026_07 partition
-- follows the canonical template.
CREATE TABLE IF NOT EXISTS geolocation.provider_usage_daily (
    vendor_id            TEXT NOT NULL,
    usage_date           DATE NOT NULL,
    capability           TEXT NOT NULL,
    region               TEXT NOT NULL,
    invocations          BIGINT NOT NULL DEFAULT 0,
    cache_hits           BIGINT NOT NULL DEFAULT 0,
    failures             BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd   NUMERIC(18,4) NOT NULL DEFAULT 0,
    PRIMARY KEY (vendor_id, usage_date, capability, region)
) PARTITION BY RANGE (usage_date);

CREATE TABLE IF NOT EXISTS geolocation.provider_usage_daily_2026_07
    PARTITION OF geolocation.provider_usage_daily
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

DO $$
DECLARE
    v_parent REGCLASS := 'geolocation.provider_usage_daily'::REGCLASS;
    v_child  REGCLASS := 'geolocation.provider_usage_daily_2026_07'::REGCLASS;
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS provider_usage_daily_date_vendor_idx
    ON geolocation.provider_usage_daily (usage_date DESC, vendor_id);