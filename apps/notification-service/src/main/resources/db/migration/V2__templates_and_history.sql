-- V2__templates_and_history.sql
-- Per docs/services/notification-service/ERD.md (Phase 1 migration):
--   - notification.templates         : logical mutable template (per (name, channel, locale))
--   - notification.template_history  : immutable audit snapshot (append-only)
--
-- Discriminator CHECK enforces mutual exclusivity between `body` (plain
-- Handlebars) and `body_structured` (WhatsApp Business API JSONB).
-- Right-to-erasure (TECH.md) preserves template_history rows because they
-- carry no PII (only admin sub UUIDs).

-- =========================================================================
-- 1. notification.templates
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.templates (
    id                              UUID PRIMARY KEY,
    name                            TEXT NOT NULL,
    category                        TEXT NOT NULL,
    channel                         TEXT NOT NULL,
    locale                          TEXT NOT NULL,
    subject                         TEXT,
    body                            TEXT,
    template_type                   TEXT NOT NULL,
    body_structured                 JSONB,
    provider_template_id            TEXT,
    provider_template_language      TEXT,
    provider_template_status        TEXT NOT NULL DEFAULT 'draft',
    provider_template_approved_at   TIMESTAMPTZ,
    provider_template_reject_reason TEXT,
    required_variables              TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    metadata                        JSONB NOT NULL DEFAULT '{}'::JSONB,
    status                          TEXT NOT NULL DEFAULT 'active',
    version                         INT NOT NULL DEFAULT 1,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                      UUID,
    updated_by                      UUID,
    deleted_at                      TIMESTAMPTZ,
    CONSTRAINT templates_channel_check
        CHECK (channel IN ('push', 'sms', 'email', 'in_app', 'whatsapp')),
    CONSTRAINT templates_category_check
        CHECK (category IN ('trip', 'food', 'payment', 'safety', 'marketing', 'chat', 'onboarding', 'deal', 'refund', 'reward', 'admin', 'system')),
    CONSTRAINT templates_type_check
        CHECK (template_type IN ('plain', 'whatsapp_structured')),
    CONSTRAINT templates_status_check
        CHECK (status IN ('active', 'disabled')),
    CONSTRAINT templates_provider_status_check
        CHECK (provider_template_status IN ('draft', 'submitted', 'approved', 'rejected', 'paused', 'retired')),
    CONSTRAINT templates_body_discriminator_chk CHECK (
        (template_type = 'plain'             AND body IS NOT NULL        AND body_structured IS NULL) OR
        (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)
    ),
    CONSTRAINT templates_provider_approved_at_chk CHECK (
        (provider_template_status = 'approved' AND provider_template_approved_at IS NOT NULL) OR
        (provider_template_status <> 'approved')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_templates_name_channel_locale_version
    ON notification.templates (name, channel, locale, version);

CREATE INDEX IF NOT EXISTS idx_templates_active
    ON notification.templates (name, channel, locale)
    WHERE status = 'active' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_templates_category
    ON notification.templates (category);

CREATE INDEX IF NOT EXISTS idx_templates_provider_template
    ON notification.templates (provider_template_id, provider_template_language)
    WHERE provider_template_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_templates_type_status
    ON notification.templates (template_type, status);

-- =========================================================================
-- 2. notification.template_history  (append-only immutable snapshot)
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.template_history (
    id                              UUID PRIMARY KEY,
    template_id                     UUID NOT NULL,
    revision_no                     INT  NOT NULL,
    version                         INT  NOT NULL,
    name                            TEXT NOT NULL,
    category                        TEXT NOT NULL,
    channel                         TEXT NOT NULL,
    locale                          TEXT NOT NULL,
    subject                         TEXT,
    body                            TEXT,
    template_type                   TEXT NOT NULL,
    body_structured                 JSONB,
    provider_template_id            TEXT,
    provider_template_language      TEXT,
    provider_template_status        TEXT NOT NULL,
    provider_template_approved_at   TIMESTAMPTZ,
    required_variables              TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    metadata                        JSONB NOT NULL DEFAULT '{}'::JSONB,
    diff_summary                    JSONB NOT NULL,
    published_by                    UUID NOT NULL,
    approved_by                     UUID,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT template_history_channel_check
        CHECK (channel IN ('push', 'sms', 'email', 'in_app', 'whatsapp')),
    CONSTRAINT template_history_type_check
        CHECK (template_type IN ('plain', 'whatsapp_structured')),
    CONSTRAINT template_history_provider_status_check
        CHECK (provider_template_status IN ('draft', 'submitted', 'approved', 'rejected', 'paused', 'retired')),
    CONSTRAINT template_history_body_discriminator_chk CHECK (
        (template_type = 'plain'             AND body IS NOT NULL        AND body_structured IS NULL) OR
        (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)
    ),
    CONSTRAINT template_history_whatsapp_approved_chk CHECK (
        (channel = 'whatsapp' AND approved_by IS NOT NULL) OR
        (channel <> 'whatsapp')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_template_history_template_revision
    ON notification.template_history (template_id, revision_no);

CREATE UNIQUE INDEX IF NOT EXISTS uq_template_history_template_version
    ON notification.template_history (template_id, version);

CREATE INDEX IF NOT EXISTS idx_template_history_template_created
    ON notification.template_history (template_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_template_history_channel_name_created
    ON notification.template_history (channel, name, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_template_history_publisher
    ON notification.template_history (published_by, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_template_history_provider_template
    ON notification.template_history (provider_template_id)
    WHERE provider_template_id IS NOT NULL;

-- Append-only trigger — reject UPDATE/DELETE (TECH.md + TEMPLATE_HISTORY.md)
CREATE OR REPLACE FUNCTION notification.prevent_template_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'notification.template_history is append-only (op=%)', TG_OP;
END;
$$;

DROP TRIGGER IF EXISTS template_history_immutable ON notification.template_history;
CREATE TRIGGER template_history_immutable
    BEFORE UPDATE OR DELETE ON notification.template_history
    FOR EACH ROW EXECUTE FUNCTION notification.prevent_template_history_mutation();