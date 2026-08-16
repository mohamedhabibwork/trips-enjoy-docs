-- 000011_provider_circuit_state.up.sql
--
-- geolocation.provider_circuit_state — last-known circuit-breaker state
-- per docs/services/geolocation-service/ERD.md §3.10. Mirrored from
-- the in-memory gobreaker set on every transition (so a restart can
-- restore state). Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.provider_circuit_state (
    vendor_id                   TEXT PRIMARY KEY,
    state                       TEXT NOT NULL CHECK (state IN ('closed','open','half_open')),
    consecutive_failures        INT NOT NULL DEFAULT 0,
    opened_at                   TIMESTAMPTZ,
    half_open_probes_remaining  INT NOT NULL DEFAULT 0,
    last_failure_at             TIMESTAMPTZ,
    last_success_at             TIMESTAMPTZ,
    last_transition_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     INT NOT NULL DEFAULT 1
);