-- V2__customers.sql
-- Per docs/services/customer-service/ERD.md §3:
--   customer.customers : the customer aggregate (one row per customer).
--   Not partitioned (one row per customer, low volume).
--   PII columns (name, email, phone) are flagged as column-level
--   encrypted in the docs; the schema does not enforce the cipher but
--   the application layer is responsible for the encryption-at-rest
--   contract (DEC-004 / TECH §6).
--
-- Schema-wide conventions:
--   * primary keys are UUIDv7 (UUID in PG, with a UUID PRIMARY KEY
--     constraint; the v7 ordering is enforced by the application via
--     kotlin.uuid.Uuid.generateV7()).
--   * cross-service references (identity_id, default_payment_method_id,
--     default_address_id, primary_city_id, kyc_verification_id) are
--     plain UUIDs WITHOUT database FKs (DATA--003).
--   * soft delete via deleted_at (DATA--006).
--   * audit columns (created_at, updated_at, created_by, updated_by)
--     on every mutable table (DATA--005).
--   * row_version (BIGINT) is the optimistic-lock counter (SRS §14).

CREATE TABLE IF NOT EXISTS customer.customers (
    id UUID PRIMARY KEY,
    identity_id UUID NOT NULL,
    name TEXT,
    email TEXT,
    phone TEXT,
    kyc_tier TEXT NOT NULL DEFAULT 'tier_0',
    kyc_verification_id UUID,
    kyc_verified_at TIMESTAMPTZ,
    kyc_document_file_ids UUID[] NOT NULL DEFAULT '{}',
    default_payment_method_id UUID,
    default_address_id UUID,
    primary_city_id UUID,
    ltv_minor BIGINT NOT NULL DEFAULT 0,
    ltv_currency CHAR(3) NOT NULL DEFAULT 'USD',
    ltv_updated_at TIMESTAMPTZ,
    segment TEXT NOT NULL DEFAULT 'standard',
    segment_updated_at TIMESTAMPTZ,
    rides_this_month INT NOT NULL DEFAULT 0,
    last_active_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'active',
    suspended_reason TEXT,
    suspended_at TIMESTAMPTZ,
    suspended_by UUID,
    disabled_at TIMESTAMPTZ,
    erased_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT customers_kyc_tier_check
        CHECK (kyc_tier IN ('tier_0', 'tier_1', 'tier_2', 'tier_3')),
    CONSTRAINT customers_segment_check
        CHECK (segment IN ('standard', 'frequent', 'vip', 'churned')),
    CONSTRAINT customers_status_check
        CHECK (status IN ('active', 'suspended', 'disabled', 'erased')),
    CONSTRAINT customers_ltv_minor_check
        CHECK (ltv_minor >= 0)
);

-- Unique partial index on (identity_id) for active rows. Drives the
-- identity.user.created.v1 back-channel lookup and the
-- customers.row uniqueness contract.
CREATE UNIQUE INDEX IF NOT EXISTS customers_identity_id_uniq
    ON customer.customers (identity_id)
    WHERE deleted_at IS NULL;

-- Partial indexes for the hot read paths (kyc / segment / status)
-- skipping the dominant 'tier_0' / 'active' / null-default rows.
CREATE INDEX IF NOT EXISTS customers_kyc_tier_idx
    ON customer.customers (kyc_tier)
    WHERE kyc_tier <> 'tier_0';

CREATE INDEX IF NOT EXISTS customers_segment_idx
    ON customer.customers (segment)
    WHERE status = 'active';

CREATE INDEX IF NOT EXISTS customers_status_idx
    ON customer.customers (status)
    WHERE status <> 'active';

CREATE INDEX IF NOT EXISTS customers_default_payment_method_id_idx
    ON customer.customers (default_payment_method_id)
    WHERE default_payment_method_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS customers_default_address_id_idx
    ON customer.customers (default_address_id)
    WHERE default_address_id IS NOT NULL;
