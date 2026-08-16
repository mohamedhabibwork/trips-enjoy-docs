-- 000002_files.up.sql
--
-- file.files — the canonical file-metadata table. Driver-agnostic: the
-- bytes live on whichever StorageDriver the file is pinned to; this
-- table holds the metadata + a driver-opaque JSONB locator.
--
-- DDL ports the column list from docs/services/file-service/ERD.md §5.
-- The correlation_id column is included (ERD.md indexes reference it
-- but the column was missing from the DDL sketch).

CREATE TABLE IF NOT EXISTS file.files (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 TEXT NOT NULL,
    owner_id UUID NOT NULL,
    owner_type TEXT NOT NULL CHECK (owner_type IN
        ('customer','driver','courier','merchant','restaurant','support_ticket','ride_safety','admin')),
    tenant_id UUID,
    retention_class TEXT NOT NULL CHECK (retention_class IN
        ('kyc','support_attachment','avatar','menu_photo','safety_recording','vehicle_photo','other')),
    status TEXT NOT NULL CHECK (status IN
        ('pending','scanning','available','quarantined','deleted')),
    driver_id TEXT NOT NULL,
    driver_locator JSONB NOT NULL CHECK (jsonb_typeof(driver_locator) = 'object'),
    driver_locale_version INT NOT NULL DEFAULT 1,
    kms_key_id TEXT,
    scan_id UUID,
    scan_result TEXT CHECK (scan_result IS NULL OR scan_result IN ('clean','infected','error')),
    uploaded_at TIMESTAMPTZ,
    scan_completed_at TIMESTAMPTZ,
    retention_until TIMESTAMPTZ NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS files_sha256_idx ON file.files (sha256);
CREATE INDEX IF NOT EXISTS files_owner_idx ON file.files (owner_id, owner_type) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS files_retention_idx ON file.files (retention_class, retention_until) WHERE status <> 'deleted';
CREATE INDEX IF NOT EXISTS files_status_pending_idx ON file.files (status) WHERE status IN ('pending','scanning');
CREATE INDEX IF NOT EXISTS files_correlation_idx ON file.files (correlation_id);
CREATE INDEX IF NOT EXISTS files_driver_idx ON file.files (driver_id);