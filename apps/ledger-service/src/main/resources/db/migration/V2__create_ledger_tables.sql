-- V2: create the ledger tables per docs/services/ledger-service/ERD.md §3.
--
-- Tables (idempotent where practical):
--   accounts              — versioned chart of accounts (PK + (code, version))
--   postings              — RANGE partitioned by month on posted_at (composite PK)
--   posting_entries       — RANGE partitioned by month on posted_at (composite PK)
--   account_balances      — materialised per (account_code, currency) snapshot
--   journal_entries       — admin manual entries (audit-logged)
--   reconciliation_runs   — daily reconciliation summary
--   outbox                — transactional outbox for `ledger.posted.v1` and friends
--   inbox                 — consumer-side dedup for inbound money-movement events
--
-- Notes:
--   - Per DATABASE_ARCHITECTURE.md "Table Partitioning — Canonical Template",
--     RANGE-by-time partitions MUST be created with CREATE TABLE IF NOT EXISTS
--     … PARTITION OF … + an explicit bounds verification block (the IF NOT
--     EXISTS guard only protects the name, not the bounds).
--   - The accounts self-FK is intra-schema, not cross-service, per
--     DATA_OWNERSHIP.md.

-- ---------------------------------------------------------------------------
-- 1. accounts — chart of accounts, versioned insert-only
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.accounts (
    id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    parent_code TEXT,
    version INTEGER NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    CONSTRAINT accounts_pkey PRIMARY KEY (id),
    CONSTRAINT accounts_type_chk CHECK (type IN
        ('asset','liability','equity','revenue','expense')),
    CONSTRAINT accounts_version_chk CHECK (version > 0),
    CONSTRAINT accounts_valid_chk
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT accounts_parent_fk
        FOREIGN KEY (parent_code) REFERENCES ledger.accounts(code)
);

CREATE UNIQUE INDEX IF NOT EXISTS accounts_code_version_uq
    ON ledger.accounts (code, version);
CREATE UNIQUE INDEX IF NOT EXISTS accounts_code_current_uq
    ON ledger.accounts (code) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS accounts_parent_ix
    ON ledger.accounts (parent_code);
CREATE INDEX IF NOT EXISTS accounts_type_currency_ix
    ON ledger.accounts (type, currency);

-- ---------------------------------------------------------------------------
-- 2. postings — append-only, RANGE partitioned by month on posted_at
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.postings (
    id UUID NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    description TEXT NOT NULL,
    source_event_id UUID NOT NULL,
    source_event_name TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    idempotency_key TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id UUID,
    audit_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, posted_at)
) PARTITION BY RANGE (posted_at);

-- Pre-create partitions: previous month, current month, plus 12 future months.
-- Each is guarded by CREATE TABLE IF NOT EXISTS and verified via pg_inherits +
-- relpartbound so the bounds are correct (the IF NOT EXISTS name guard does not
-- validate bounds).
DO $$
DECLARE
    i INT;
    v_start DATE;
    v_end DATE;
    v_name TEXT;
    v_parent REGCLASS := 'ledger.postings'::REGCLASS;
    v_child REGCLASS;
    base_year INT;
    base_month INT;
BEGIN
    FOR i IN -1..12 LOOP
        base_year := EXTRACT(YEAR FROM (CURRENT_DATE + (i || ' month')::INTERVAL))::INT;
        base_month := EXTRACT(MONTH FROM (CURRENT_DATE + (i || ' month')::INTERVAL))::INT;
        v_start := make_date(base_year, base_month, 1);
        v_end := (v_start + INTERVAL '1 month')::DATE;
        v_name := format('ledger.postings_%s', to_char(v_start, 'YYYY_MM'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF ledger.postings FOR VALUES FROM (%L) TO (%L)',
            v_name, v_start, v_end);
        v_child := v_name::REGCLASS;
        IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
           IS DISTINCT FROM v_parent THEN
            RAISE EXCEPTION 'partition % is not attached to ledger.postings', v_name;
        END IF;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS postings_idem_uq
    ON ledger.postings (idempotency_key);
CREATE INDEX IF NOT EXISTS postings_source_event_ix
    ON ledger.postings (source_event_id);
CREATE INDEX IF NOT EXISTS postings_correlation_ix
    ON ledger.postings (correlation_id);
CREATE INDEX IF NOT EXISTS postings_posted_at_ix
    ON ledger.postings (posted_at);

-- ---------------------------------------------------------------------------
-- 3. posting_entries — append-only, RANGE partitioned by month on posted_at
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.posting_entries (
    id BIGSERIAL,
    posting_id UUID NOT NULL,
    account_code TEXT NOT NULL,
    account_version INTEGER NOT NULL,
    side TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, posted_at)
) PARTITION BY RANGE (posted_at);

DO $$
DECLARE
    i INT;
    v_start DATE;
    v_end DATE;
    v_name TEXT;
    base_year INT;
    base_month INT;
BEGIN
    FOR i IN -1..12 LOOP
        base_year := EXTRACT(YEAR FROM (CURRENT_DATE + (i || ' month')::INTERVAL))::INT;
        base_month := EXTRACT(MONTH FROM (CURRENT_DATE + (i || ' month')::INTERVAL))::INT;
        v_start := make_date(base_year, base_month, 1);
        v_end := (v_start + INTERVAL '1 month')::DATE;
        v_name := format('ledger.posting_entries_%s', to_char(v_start, 'YYYY_MM'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF ledger.posting_entries FOR VALUES FROM (%L) TO (%L)',
            v_name, v_start, v_end);
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS pe_posting_time_ix
    ON ledger.posting_entries (posting_id, posted_at);
CREATE INDEX IF NOT EXISTS pe_account_time_ix
    ON ledger.posting_entries (account_code, posted_at);
CREATE INDEX IF NOT EXISTS pe_currency_time_ix
    ON ledger.posting_entries (currency, posted_at);

-- ---------------------------------------------------------------------------
-- 4. account_balances — materialised per (account_code)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.account_balances (
    account_code TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    debit_total_minor BIGINT NOT NULL DEFAULT 0,
    credit_total_minor BIGINT NOT NULL DEFAULT 0,
    balance_minor BIGINT NOT NULL,
    last_posting_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_balances_pkey PRIMARY KEY (account_code),
    CONSTRAINT account_balances_nonneg_chk
        CHECK (debit_total_minor >= 0 AND credit_total_minor >= 0)
);

-- ---------------------------------------------------------------------------
-- 5. journal_entries — admin manual entries (audit-logged)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.journal_entries (
    id UUID NOT NULL,
    description TEXT NOT NULL,
    actor_id UUID NOT NULL,
    audit_note TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT je_audit_chk CHECK (length(audit_note) >= 10)
);

CREATE INDEX IF NOT EXISTS je_actor_ix ON ledger.journal_entries (actor_id);
CREATE INDEX IF NOT EXISTS je_created_ix ON ledger.journal_entries (created_at);

-- ---------------------------------------------------------------------------
-- 6. reconciliation_runs — daily reconciliation summary
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.reconciliation_runs (
    id UUID NOT NULL,
    run_date DATE NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    wallet_total BIGINT NOT NULL DEFAULT 0,
    earnings_total BIGINT NOT NULL DEFAULT 0,
    settlement_total BIGINT NOT NULL DEFAULT 0,
    ledger_total BIGINT NOT NULL DEFAULT 0,
    drift_minor BIGINT NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    details JSONB,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT reconciliation_runs_run_date_uq UNIQUE (run_date),
    CONSTRAINT reconciliation_runs_status_chk CHECK (status IN
        ('running','matched','drift','error'))
);

CREATE INDEX IF NOT EXISTS reconciliation_runs_status_ix
    ON ledger.reconciliation_runs (status);

-- ---------------------------------------------------------------------------
-- 7. outbox — transactional outbox (mirrors audit-service)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.outbox (
    id UUID NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID,
    topic TEXT NOT NULL,
    event_name TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS outbox_unpublished_ix
    ON ledger.outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_topic_aggregate_ix
    ON ledger.outbox (topic, aggregate_id);

-- ---------------------------------------------------------------------------
-- 8. inbox — consumer-side dedup
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.inbox (
    event_id UUID NOT NULL,
    topic TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error TEXT,
    PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS inbox_processed_ix
    ON ledger.inbox (received_at) WHERE processed_at IS NULL;

-- ---------------------------------------------------------------------------
-- 9. Append-only enforcement on postings / posting_entries via trigger.
-- The application role's UPDATE / DELETE privileges on these tables are
-- REVOKED at deployment time (SEC--002); this trigger is the belt-and-
-- suspenders guardrail in case privilege escalation occurs.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger.deny_posting_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'postings and posting_entries are append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS postings_append_only ON ledger.postings;
CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.deny_posting_mutation();

DROP TRIGGER IF EXISTS posting_entries_append_only ON ledger.posting_entries;
CREATE TRIGGER posting_entries_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger.posting_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.deny_posting_mutation();

-- ---------------------------------------------------------------------------
-- 10. Per-row posting validation trigger.
-- Rejects invalid `side` / `amount_minor` BEFORE INSERT. The application
-- enforces the per-posting balance invariant in PostingService.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger.check_posting_balance() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.amount_minor <= 0 THEN
        RAISE EXCEPTION 'amount_minor must be > 0';
    END IF;
    IF NEW.side NOT IN ('debit','credit') THEN
        RAISE EXCEPTION 'side must be debit or credit';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS posting_entries_validate_row ON ledger.posting_entries;
CREATE TRIGGER posting_entries_validate_row
    BEFORE INSERT ON ledger.posting_entries
    FOR EACH ROW EXECUTE FUNCTION ledger.check_posting_balance();
