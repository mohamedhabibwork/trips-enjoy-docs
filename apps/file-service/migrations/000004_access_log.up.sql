-- 000004_access_log.up.sql
--
-- file.access_log — append-mostly audit of every download / signed-url /
-- metadata read. RANGE-partitioned by occurred_at per DATABASE_ARCHITECTURE.md;
-- 1y retention (drop the partition once cold). Composite PK (id, occurred_at)
-- is mandatory on partitioned parents.

CREATE TABLE IF NOT EXISTS file.access_log (
    id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    file_id UUID NOT NULL,
    driver_id TEXT NOT NULL,
    actor_sub UUID,
    action TEXT NOT NULL,
    ip INET,
    user_agent TEXT,
    metadata JSONB,
    correlation_id UUID NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Pre-create the current monthly partition. The partition maintenance
-- job (follow-up) extends this forward.
CREATE TABLE IF NOT EXISTS file.access_log_default PARTITION OF file.access_log DEFAULT;

CREATE INDEX IF NOT EXISTS access_log_file_idx ON file.access_log (file_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS access_log_actor_idx ON file.access_log (actor_sub) WHERE actor_sub IS NOT NULL;
CREATE INDEX IF NOT EXISTS access_log_correlation_idx ON file.access_log (correlation_id);
CREATE INDEX IF NOT EXISTS access_log_driver_idx ON file.access_log (driver_id, occurred_at DESC);