-- 000010_driver_object_registry.up.sql
--
-- file.driver_object_registry — per-driver uniqueness on the logical
-- object address. Enforces "no two files share the same object key on
-- the same driver" without making the constraint driver-aware in
-- file.files (which is driver-agnostic).

CREATE TABLE IF NOT EXISTS file.driver_object_registry (
    id UUID PRIMARY KEY,
    driver_id TEXT NOT NULL,
    driver_kind TEXT NOT NULL,
    bucket TEXT,
    object_key TEXT,
    file_id UUID NOT NULL REFERENCES file.files(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS driver_object_registry_uk
    ON file.driver_object_registry (driver_id, bucket, object_key)
    WHERE object_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS driver_object_registry_file_idx ON file.driver_object_registry (file_id);