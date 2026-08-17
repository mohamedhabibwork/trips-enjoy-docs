-- 000006_retention_overrides.up.sql

CREATE TABLE IF NOT EXISTS file.retention_overrides (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES file.files(id) ON DELETE CASCADE,
    new_retention_until TIMESTAMPTZ,
    reason TEXT NOT NULL,
    actor_sub UUID NOT NULL,
    signature TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS retention_overrides_file_idx ON file.retention_overrides (file_id);