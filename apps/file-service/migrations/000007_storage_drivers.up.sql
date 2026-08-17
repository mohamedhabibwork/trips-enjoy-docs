-- 000007_storage_drivers.up.sql
--
-- file.storage_drivers — the in-DB mirror of the Storage Driver catalog
-- (per ERD.md §5). Updates land via configuration.updated.v1 → hot
-- reload. Exactly one row may have is_default=true (partial unique
-- index).

CREATE TABLE IF NOT EXISTS file.storage_drivers (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN
        ('s3','azure_blob','oracle_object_storage','gcs','local_fs')),
    display_name TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('enabled','draining','disabled')),
    priority INT NOT NULL DEFAULT 100,
    region TEXT,
    container TEXT,
    endpoint TEXT,
    path_style BOOLEAN NOT NULL DEFAULT false,
    kms_key_id TEXT,
    signed_url_ttl_seconds INT NOT NULL DEFAULT 900
        CHECK (signed_url_ttl_seconds > 0 AND signed_url_ttl_seconds <= 86400),
    max_object_size_bytes BIGINT NOT NULL CHECK (max_object_size_bytes > 0),
    multipart_threshold_bytes BIGINT NOT NULL CHECK (multipart_threshold_bytes >= 0),
    is_default BOOLEAN NOT NULL DEFAULT false,
    config_hash TEXT NOT NULL,
    health TEXT NOT NULL CHECK (health IN ('healthy','degraded','unreachable')),
    health_last_checked_at TIMESTAMPTZ,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    version INT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS storage_drivers_kind_idx ON file.storage_drivers (kind);
CREATE INDEX IF NOT EXISTS storage_drivers_state_idx ON file.storage_drivers (state) WHERE state <> 'disabled';
CREATE INDEX IF NOT EXISTS storage_drivers_priority_idx ON file.storage_drivers (priority);
CREATE UNIQUE INDEX IF NOT EXISTS storage_drivers_is_default_uk ON file.storage_drivers (is_default) WHERE is_default;