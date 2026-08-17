-- V2__configuration_documents_schemas_channel_subsets.sql
-- Per docs/services/configuration-service/ERD.md:
--   - configuration.documents       : current "head" of every configuration key
--   - configuration.schemas         : declared JSON Schema per key (versioned)
--   - configuration.channel_subsets : per-channel view declarations
--
-- documents is NOT partitioned — the dominant access is by key.
-- Both versions and audit_log are partitioned by month on created_at and
-- land in V3 / V4.

-- =========================================================================
-- 1. configuration.documents
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.documents (
    id UUID PRIMARY KEY,
    key TEXT NOT NULL UNIQUE
        CHECK (key ~ '^[a-z][a-z0-9_.\-]{1,127}$'),
    tenant_id TEXT NOT NULL DEFAULT 'global',
    current_version BIGINT NOT NULL DEFAULT 0,
    schema_id UUID NOT NULL,
    value JSONB,
    value_type TEXT NOT NULL
        CHECK (value_type IN ('string','number','boolean','object','array','null')),
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT documents_deactivated_value_check
        CHECK ((deactivated_at IS NULL) OR (value IS NULL))
);

-- Partial unique index on the dominant read path (tenant + key for active rows).
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_active
    ON configuration.documents (tenant_id, key)
    WHERE deactivated_at IS NULL;

-- Index for schema lookups by id.
CREATE INDEX IF NOT EXISTS idx_documents_schema_id
    ON configuration.documents (schema_id);

-- GIN index for tenant searches on the JSON value body.
CREATE INDEX IF NOT EXISTS idx_documents_value_gin
    ON configuration.documents USING gin (value jsonb_path_ops);

-- =========================================================================
-- 2. configuration.schemas — declared JSON Schema per key, versioned
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.schemas (
    id UUID PRIMARY KEY,
    key TEXT NOT NULL,
    version INT NOT NULL CHECK (version >= 1),
    json_schema JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    UNIQUE (key, version)
);
CREATE INDEX IF NOT EXISTS idx_schemas_key ON configuration.schemas (key);

-- =========================================================================
-- 3. configuration.channel_subsets — per-channel view declarations
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.channel_subsets (
    id UUID PRIMARY KEY,
    channel TEXT NOT NULL,
    key TEXT NOT NULL,
    json_pointer TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (channel, key, json_pointer)
);
CREATE INDEX IF NOT EXISTS idx_channel_subsets_channel
    ON configuration.channel_subsets (channel);
