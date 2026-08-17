-- 000008_provider_config.up.sql
--
-- geolocation.provider_config — canonical registry of every map provider
-- this service can route to per docs/services/geolocation-service/ERD.md
-- §3.7. Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.provider_config (
    id                    UUID PRIMARY KEY,
    vendor_id             TEXT NOT NULL UNIQUE,
    display_name          TEXT NOT NULL,
    adapter_type          TEXT NOT NULL CHECK (adapter_type IN ('commercial_rest','self_host_rest','in_process')),
    capabilities          TEXT[] NOT NULL CHECK (cardinality(capabilities) > 0),
    is_self_host          BOOL NOT NULL DEFAULT false,
    is_static_only        BOOL NOT NULL DEFAULT false,
    enabled               BOOL NOT NULL DEFAULT true,
    priority              INT NOT NULL DEFAULT 100,
    base_url              TEXT,
    auth_type             TEXT CHECK (auth_type IS NULL OR auth_type IN ('api_key','oauth2_client_credentials','mtls','none')),
    vault_secret_path     TEXT NOT NULL,
    qps_limit             INT NOT NULL DEFAULT 100 CHECK (qps_limit > 0),
    burst_limit           INT NOT NULL DEFAULT 100 CHECK (burst_limit > 0),
    timeout_ms            INT NOT NULL DEFAULT 1500 CHECK (timeout_ms > 0),
    failure_threshold     INT NOT NULL DEFAULT 5,
    cooldown_seconds      INT NOT NULL DEFAULT 30,
    half_open_probe_count INT NOT NULL DEFAULT 3,
    cost_per_1k_usd       NUMERIC(10,4),
    jurisdictions         TEXT[] NOT NULL DEFAULT ARRAY['global'],
    metadata              JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID NOT NULL,
    updated_by            UUID NOT NULL,
    version               INT NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS provider_config_enabled_idx
    ON geolocation.provider_config (enabled) WHERE enabled;
CREATE INDEX IF NOT EXISTS provider_config_capabilities_gin
    ON geolocation.provider_config USING GIN (capabilities);
CREATE INDEX IF NOT EXISTS provider_config_jurisdictions_gin
    ON geolocation.provider_config USING GIN (jurisdictions);