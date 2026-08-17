-- 000005_retention_policies.up.sql

CREATE TABLE IF NOT EXISTS file.retention_policies (
    id UUID PRIMARY KEY,
    retention_class TEXT NOT NULL UNIQUE
        CHECK (retention_class IN
            ('kyc','support_attachment','avatar','menu_photo','safety_recording','vehicle_photo','other')),
    display_name TEXT NOT NULL,
    duration INTERVAL NOT NULL,
    grace_period INTERVAL NOT NULL DEFAULT interval '7 days',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ
);