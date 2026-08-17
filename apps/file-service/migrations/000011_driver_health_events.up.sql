-- 000011_driver_health_events.up.sql
--
-- file.driver_health_events — synthetic probe results per driver.
-- RANGE-partitioned by occurred_at; 90-day retention.

CREATE TABLE IF NOT EXISTS file.driver_health_events (
    id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    driver_id TEXT NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('pass','warn','fail')),
    latency_ms INT,
    error_class TEXT,
    correlation_id UUID NOT NULL,
    metadata JSONB,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE IF NOT EXISTS file.driver_health_events_default PARTITION OF file.driver_health_events DEFAULT;

CREATE INDEX IF NOT EXISTS driver_health_events_driver_idx ON file.driver_health_events (driver_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS driver_health_events_correlation_idx ON file.driver_health_events (correlation_id);