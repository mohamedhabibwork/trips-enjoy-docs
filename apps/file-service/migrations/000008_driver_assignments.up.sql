-- 000008_driver_assignments.up.sql
--
-- file.driver_assignments — append-only log of which rule resolved a
-- file to which driver. source ∈ {file_pin, tenant_override,
-- owner_type_override, retention_class_override, default}.

CREATE TABLE IF NOT EXISTS file.driver_assignments (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES file.files(id) ON DELETE CASCADE,
    driver_id TEXT NOT NULL,
    source TEXT NOT NULL CHECK (source IN
        ('file_pin','tenant_override','owner_type_override','retention_class_override','default')),
    rule_id TEXT,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS driver_assignments_file_idx ON file.driver_assignments (file_id, effective_at DESC);
CREATE INDEX IF NOT EXISTS driver_assignments_source_idx ON file.driver_assignments (source);