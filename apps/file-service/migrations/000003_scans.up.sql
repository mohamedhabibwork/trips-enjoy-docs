-- 000003_scans.up.sql
--
-- file.scans — every virus-scan attempt for a file. Cascade-deletes with
-- the parent file.

CREATE TABLE IF NOT EXISTS file.scans (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES file.files(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending','running','clean','infected','error')),
    result TEXT CHECK (result IS NULL OR result IN ('clean','infected','error')),
    threat_name TEXT,
    raw_response JSONB,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    attempt INT NOT NULL DEFAULT 1,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS scans_file_idx ON file.scans (file_id, started_at DESC);
CREATE INDEX IF NOT EXISTS scans_status_idx ON file.scans (status) WHERE status IN ('pending','running');