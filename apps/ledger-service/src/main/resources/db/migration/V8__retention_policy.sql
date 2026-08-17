-- V8: retention_policy — declarative per-table retention bands.
--
-- Per docs/services/ledger-service/ERD.md §10 the ledger has different
-- retention rules per table:
--   * accounts / account_balances  — forever (versioned / rebuilt)
--   * postings / posting_entries   — 10 years (regulatory)
--   * journal_entries              — 10 years
--   * reconciliation_runs          — 10 years
--   * outbox                       — 24h after publish
--   * inbox                        — 30 days
--
-- The tables below back the platform's RetentionService. The application
-- reads `ledger.retention_policy` to drive the partition-drop + delete
-- jobs on the schedule; this is the single source of truth (replaces any
-- hard-coded constants in the application code).

CREATE TABLE IF NOT EXISTS ledger.retention_policy (
    id UUID NOT NULL,
    table_name TEXT NOT NULL,
    retention_class TEXT NOT NULL,
    retention_years INT,
    retention_days INT,
    retention_hours INT,
    purge_strategy TEXT NOT NULL,
    partition_strategy TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT retention_policy_table_uq UNIQUE (table_name),
    CONSTRAINT retention_policy_class_chk CHECK (retention_class IN
        ('forever','regulatory','operational','transient')),
    CONSTRAINT retention_policy_purge_chk CHECK (purge_strategy IN
        ('partition_drop','batch_delete','job_cleanup')),
    CONSTRAINT retention_policy_partition_chk CHECK (partition_strategy IS NULL
        OR partition_strategy IN ('monthly','quarterly','yearly')),
    CONSTRAINT retention_policy_valid_chk
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT retention_policy_at_least_one_chk
        CHECK (retention_years IS NOT NULL OR retention_days IS NOT NULL OR retention_hours IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- Seed: the canonical retention bands
-- ---------------------------------------------------------------------------
INSERT INTO ledger.retention_policy (table_name, retention_class, retention_years, retention_days, retention_hours, purge_strategy, partition_strategy, description)
VALUES
    ('ledger.accounts',           'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — versioned chart of accounts; old versions are kept.'),
    ('ledger.account_balances',   'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — rebuilt from postings; never purged.'),
    ('ledger.postings',           'regulatory',   10,   NULL, NULL, 'partition_drop', 'monthly', '10 years per regulatory; monthly partitions.'),
    ('ledger.posting_entries',    'regulatory',   10,   NULL, NULL, 'partition_drop', 'monthly', '10 years per regulatory; monthly partitions.'),
    ('ledger.journal_entries',    'regulatory',   10,   NULL, NULL, 'batch_delete',   NULL, '10 years per regulatory; nightly batch delete.'),
    ('ledger.reconciliation_runs', 'regulatory',  10,   NULL, NULL, 'batch_delete',   NULL, '10 years per regulatory; nightly batch delete.'),
    ('ledger.outbox',             'transient',    NULL, NULL, 24,   'job_cleanup',   NULL, '24h after published_at; OutboxPublisher poller purges.'),
    ('ledger.inbox',              'operational',  NULL, 30,  NULL, 'job_cleanup',   NULL, '30 days; InboxCleanup job purges (05:30 UTC).'),
    ('ledger.gl_account_mapping', 'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — mapping is versioned with effective_from/_to.'),
    ('ledger.accounting_periods', 'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — periods are immutable once locked.'),
    ('ledger.reconciliation_rules', 'forever',    NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — rules are versioned with effective_from/_to.'),
    ('ledger.currencies',         'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — ISO 4217 reference data, corrections are new rows.'),
    ('ledger.account_types',      'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — type semantics are versioned.'),
    ('ledger.tenants',            'forever',      NULL, NULL, NULL, 'partition_drop', NULL, 'Forever — tenant config is append-only.'),
    ('ledger.exchange_rates',     'operational',  NULL, 365, NULL, 'batch_delete',   NULL, '365 days of daily rates; older rates are deleted nightly.')
ON CONFLICT (table_name) DO NOTHING;
