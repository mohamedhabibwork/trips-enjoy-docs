-- V6: accounting_periods — open / closed / locked monthly periods.
--
-- Periods support the platform's month-end close workflow. Each tenant
-- has a monthly accounting period per fiscal year. Posts are gated by
-- the period status:
--   * `open`     — accepts postings
--   * `closing`  — accepts postings with a warning (period close imminent)
--   * `closed`   — rejects new postings except via admin manual override
--   * `locked`   — fully immutable; only audit reads
--
-- The default state is `open` for the current month, `closing` for the
-- prior month if today is days 1-3 of the new month, and `closed` for
-- periods older than 90 days. A maintenance job in the application
-- layer (`PeriodCloseJob`) advances these states.
--
-- Retained is at least 10 years per regulatory; periods are kept
-- indefinitely (immutable once locked).

CREATE TABLE IF NOT EXISTS ledger.accounting_periods (
    id UUID NOT NULL,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    period_code TEXT NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    fiscal_year SMALLINT NOT NULL,
    fiscal_month SMALLINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    closed_at TIMESTAMPTZ,
    closed_by UUID,
    locked_at TIMESTAMPTZ,
    locked_by UUID,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT accounting_periods_uq UNIQUE (tenant_id, period_code),
    CONSTRAINT accounting_periods_status_chk CHECK (status IN
        ('open','closing','closed','locked')),
    CONSTRAINT accounting_periods_fiscal_year_chk CHECK (fiscal_year BETWEEN 2000 AND 2200),
    CONSTRAINT accounting_periods_fiscal_month_chk CHECK (fiscal_month BETWEEN 1 AND 12),
    CONSTRAINT accounting_periods_range_chk CHECK (period_end > period_start)
);

CREATE INDEX IF NOT EXISTS accounting_periods_status_ix
    ON ledger.accounting_periods (status, period_start);
CREATE INDEX IF NOT EXISTS accounting_periods_tenant_year_ix
    ON ledger.accounting_periods (tenant_id, fiscal_year);

-- ---------------------------------------------------------------------------
-- Seed: open 13 monthly periods (1 past + current + 11 future) for every
-- active tenant. Idempotent — re-running adds nothing.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    tenant_rec RECORD;
    i INT;
    v_period_code TEXT;
    v_start DATE;
    v_end DATE;
    v_fiscal_year SMALLINT;
    v_fiscal_month SMALLINT;
    v_status TEXT;
    v_id UUID;
BEGIN
    FOR tenant_rec IN SELECT tenant_id FROM ledger.tenants WHERE is_active = TRUE LOOP
        FOR i IN -1..11 LOOP
            v_start := make_date(
                EXTRACT(YEAR FROM (CURRENT_DATE + (i || ' month')::INTERVAL))::INT,
                EXTRACT(MONTH FROM (CURRENT_DATE + (i || ' month')::INTERVAL))::INT,
                1
            );
            v_end := (v_start + INTERVAL '1 month')::DATE;
            v_period_code := to_char(v_start, 'YYYY-MM');
            v_fiscal_year := EXTRACT(YEAR FROM v_start)::SMALLINT;
            v_fiscal_month := EXTRACT(MONTH FROM v_start)::SMALLINT;

            -- Status logic: current month = open, future = open, past within
            -- 30 days = open (close imminent), past 30-90 days = closing,
            -- past 90 days = closed.
            v_status := CASE
                WHEN v_start > CURRENT_DATE THEN 'open'
                WHEN v_start = date_trunc('month', CURRENT_DATE)::DATE THEN 'open'
                WHEN CURRENT_DATE - v_end < 30 THEN 'open'
                WHEN CURRENT_DATE - v_end < 90 THEN 'closing'
                ELSE 'closed'
            END;

            IF NOT EXISTS (SELECT 1 FROM ledger.accounting_periods
                            WHERE tenant_id = tenant_rec.tenant_id
                              AND period_code = v_period_code) THEN
                v_id := gen_random_uuid();
                INSERT INTO ledger.accounting_periods
                    (id, tenant_id, period_code, period_start, period_end,
                     fiscal_year, fiscal_month, status)
                VALUES
                    (v_id, tenant_rec.tenant_id, v_period_code, v_start, v_end,
                     v_fiscal_year, v_fiscal_month, v_status);
            END IF;
        END LOOP;
    END LOOP;
END $$;
