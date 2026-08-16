-- V3__preferences_and_suppressions.sql
-- Per docs/services/notification-service/ERD.md:
--   - notification.preferences  : per-(user, category, channel) consent + quiet hours
--   - notification.suppressions : admin-managed global suppressions
--
-- Both tables soft-delete (`deleted_at`). Right-to-erasure anonymises
-- preferences; it never touches template_history.

-- =========================================================================
-- 1. notification.preferences
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.preferences (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL,
    category            TEXT NOT NULL,
    channel             TEXT NOT NULL,
    opt_in              BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_hours_start   INT,
    quiet_hours_end     INT,
    timezone            TEXT NOT NULL DEFAULT 'UTC',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT preferences_channel_check
        CHECK (channel IN ('push', 'sms', 'email', 'in_app', 'whatsapp')),
    CONSTRAINT preferences_category_check
        CHECK (category IN ('trip', 'food', 'payment', 'safety', 'marketing', 'chat', 'onboarding', 'deal', 'refund', 'reward', 'admin', 'system')),
    CONSTRAINT preferences_quiet_hours_check CHECK (
        (quiet_hours_start IS NULL AND quiet_hours_end IS NULL) OR
        (quiet_hours_start BETWEEN 0 AND 23 AND quiet_hours_end BETWEEN 0 AND 23 AND quiet_hours_start <> quiet_hours_end)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_preferences_user_category_channel_active
    ON notification.preferences (user_id, category, channel)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_preferences_user
    ON notification.preferences (user_id)
    WHERE deleted_at IS NULL;

-- =========================================================================
-- 2. notification.suppressions
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.suppressions (
    id           UUID PRIMARY KEY,
    category     TEXT NOT NULL,
    reason       TEXT NOT NULL,
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID NOT NULL,
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT suppressions_category_check
        CHECK (category IN ('trip', 'food', 'payment', 'safety', 'marketing', 'chat', 'onboarding', 'deal', 'refund', 'reward', 'admin', 'system'))
);

CREATE INDEX IF NOT EXISTS idx_suppressions_category_active
    ON notification.suppressions (category)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_suppressions_expires
    ON notification.suppressions (expires_at)
    WHERE deleted_at IS NULL AND expires_at IS NOT NULL;