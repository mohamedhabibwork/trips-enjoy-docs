-- V5__gateway_selection_rules.sql
-- Per docs/services/payment-service/GATEWAYS.md §6 "Resolution precedence"
-- the gateway for a payment intent is resolved in this order:
--   1. gateway_pin           (explicit per-intent pin by admin)
--   2. tenant_override       (per-tenant default gateway)
--   3. region_default        (per-region default gateway)
--   4. currency_default      (per-currency default gateway)
--   5. method_default        (per-method default gateway)
--   6. env_default           (env-level default gateway, the `is_default` row)
--   7. auto                  (registry picks the lowest-priority enabled gateway
--                             whose regions / currencies / methods match)
--
-- This migration creates the configuration tables for the per-tenant,
-- per-region, per-currency, per-method overrides. Admin-service writes
-- here via POST /v1/admin/payments/gateway-overrides.

CREATE TABLE IF NOT EXISTS payment.gateway_overrides (
    id UUID PRIMARY KEY,
    scope TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    gateway_id TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT true,
    notes TEXT,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT gateway_overrides_scope_check
        CHECK (scope IN ('tenant','region','currency','method','tenant_region','tenant_currency'))
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_overrides_scope_key_uniq
    ON payment.gateway_overrides (scope, scope_key, gateway_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS gateway_overrides_scope_idx
    ON payment.gateway_overrides (scope)
    WHERE enabled = true AND deleted_at IS NULL;

-- gateway_overrides_history is the audit log for every override write,
-- append-only (V4 trigger pattern). Drives the admin-service audit emit.
CREATE TABLE IF NOT EXISTS payment.gateway_overrides_history (
    id UUID PRIMARY KEY,
    override_id UUID NOT NULL,
    scope TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    gateway_id TEXT NOT NULL,
    priority INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    action TEXT NOT NULL,
    actor_id UUID NOT NULL,
    actor_email TEXT,
    reason TEXT,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_overrides_history_action_check
        CHECK (action IN ('created','updated','deleted','enabled','disabled'))
);

CREATE INDEX IF NOT EXISTS gateway_overrides_history_override_idx
    ON payment.gateway_overrides_history (override_id, created_at DESC);
CREATE INDEX IF NOT EXISTS gateway_overrides_history_correlation_idx
    ON payment.gateway_overrides_history (correlation_id);

-- Append-only audit history
DROP TRIGGER IF EXISTS gateway_overrides_history_no_update ON payment.gateway_overrides_history;
CREATE TRIGGER gateway_overrides_history_no_update
    BEFORE UPDATE ON payment.gateway_overrides_history
    FOR EACH ROW EXECUTE FUNCTION payment.reject_update();
DROP TRIGGER IF EXISTS gateway_overrides_history_no_delete ON payment.gateway_overrides_history;
CREATE TRIGGER gateway_overrides_history_no_delete
    BEFORE DELETE ON payment.gateway_overrides_history
    FOR EACH ROW EXECUTE FUNCTION payment.reject_delete();