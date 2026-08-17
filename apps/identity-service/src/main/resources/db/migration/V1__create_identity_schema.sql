CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.identities (
    id UUID PRIMARY KEY,
    kc_sub TEXT NOT NULL,
    realm TEXT NOT NULL,
    user_type TEXT NOT NULL,
    region TEXT,
    tenant_id UUID,
    name TEXT,
    email TEXT,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone TEXT,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    locale TEXT,
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL DEFAULT 'active',
    suspended_reason TEXT,
    suspended_at TIMESTAMPTZ,
    suspended_by UUID,
    disabled_at TIMESTAMPTZ,
    disabled_by UUID,
    erased_at TIMESTAMPTZ,
    erased_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT identities_status_check CHECK (status IN ('active', 'suspended', 'disabled', 'erased')),
    CONSTRAINT identities_realm_check CHECK (realm IN ('platform-customer', 'platform-driver', 'platform-courier', 'platform-staff', 'platform-internal', 'platform-services'))
);

CREATE UNIQUE INDEX identities_kc_sub_realm_active_uq
    ON identity.identities (kc_sub, realm) WHERE deleted_at IS NULL;
CREATE INDEX identities_status_idx ON identity.identities (status)
    WHERE status IN ('suspended', 'disabled');
