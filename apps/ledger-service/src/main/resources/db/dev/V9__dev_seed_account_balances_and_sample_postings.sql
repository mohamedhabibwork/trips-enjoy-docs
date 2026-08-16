-- V9: development seed — account balances + sample postings + sample
-- reconciliation runs.
--
-- This migration is **only** intended for the dev profile. The application
-- sets `spring.flyway.locations` to `classpath:db/migration,classpath:db/dev`
-- when the active profile is `dev`, and the production / staging
-- deployments do NOT include this seed.
--
-- The seed gives a developer a coherent baseline:
--   * 12 sample accounts have non-zero balances (the ones involved in a
--     typical cash flow)
--   * 6 sample postings spread across the last 30 days — enough to
--     exercise trial balance, balance sheet, income statement, and
--     balance-over-range queries
--   * 2 sample reconciliation runs (one matched, one with drift) so the
--     admin reconciliation endpoint returns something useful
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. Account balances baseline (every account starts at 0).
-- ---------------------------------------------------------------------------
INSERT INTO ledger.account_balances (account_code, currency, debit_total_minor, credit_total_minor, balance_minor, last_posting_at, updated_at)
SELECT a.code, a.currency, 0, 0, 0, NULL, now()
  FROM ledger.accounts a
 WHERE a.valid_to IS NULL
   AND NOT EXISTS (SELECT 1 FROM ledger.account_balances b WHERE b.account_code = a.code)
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Sample postings for the most-trafficked accounts.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    -- Six postings across the last 30 days.
    postings UUID[] := ARRAY[
        gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
        gen_random_uuid(), gen_random_uuid(), gen_random_uuid()
    ];
    posting_id UUID;
    posted_at TIMESTAMPTZ;
    i INT;
BEGIN
    FOR i IN 1..6 LOOP
        posting_id := postings[i];
        -- Spread across the last 30 days, one every 5 days.
        posted_at := date_trunc('day', now())::TIMESTAMPTZ - ((i - 1) * INTERVAL '5 day');

        -- Alternate between payment capture, refund, and driver withdrawal
        IF i % 3 = 1 THEN
            -- payment capture: debit cash, credit customer payable
            INSERT INTO ledger.postings
                (id, posted_at, description, source_event_id, source_event_name,
                 correlation_id, tenant_id, idempotency_key, actor_type, audit_note)
            VALUES
                (posting_id, posted_at, 'DEV: payment captured for order DEV-' || i,
                 gen_random_uuid(), 'payment.captured.v1',
                 gen_random_uuid(), 'global', 'dev-seed-posting-' || i, 'service', NULL);

            INSERT INTO ledger.posting_entries (posting_id, account_code, account_version, side, amount_minor, currency, posted_at, correlation_id)
            VALUES
                (posting_id, '1100_cash_eur', 1, 'debit', 2500 + (i * 100), 'EUR', posted_at, gen_random_uuid()),
                (posting_id, '2100_customer_receivable', 1, 'credit', 2500 + (i * 100), 'EUR', posted_at, gen_random_uuid());

            UPDATE ledger.account_balances SET debit_total_minor = debit_total_minor + 2500 + (i * 100),
                balance_minor = balance_minor + 2500 + (i * 100),
                last_posting_at = posted_at,
                updated_at = now()
              WHERE account_code = '1100_cash_eur';

            UPDATE ledger.account_balances SET credit_total_minor = credit_total_minor + 2500 + (i * 100),
                balance_minor = credit_total_minor - (2500 + (i * 100)),
                last_posting_at = posted_at,
                updated_at = now()
              WHERE account_code = '2100_customer_receivable';

        ELSIF i % 3 = 2 THEN
            -- driver earning accrual: debit driver earnings receivable, credit driver payable
            INSERT INTO ledger.postings
                (id, posted_at, description, source_event_id, source_event_name,
                 correlation_id, tenant_id, idempotency_key, actor_type, audit_note)
            VALUES
                (posting_id, posted_at, 'DEV: driver earning accrued for trip DEV-' || i,
                 gen_random_uuid(), 'driver.earning.accrued.v1',
                 gen_random_uuid(), 'global', 'dev-seed-posting-' || i, 'service', NULL);

            INSERT INTO ledger.posting_entries (posting_id, account_code, account_version, side, amount_minor, currency, posted_at, correlation_id)
            VALUES
                (posting_id, '1500_driver_earnings_receivable', 1, 'debit', 1500 + (i * 50), 'EUR', posted_at, gen_random_uuid()),
                (posting_id, '2200_driver_payable', 1, 'credit', 1500 + (i * 50), 'EUR', posted_at, gen_random_uuid());

            UPDATE ledger.account_balances SET debit_total_minor = debit_total_minor + 1500 + (i * 50),
                balance_minor = debit_total_minor - credit_total_minor,
                last_posting_at = posted_at,
                updated_at = now()
              WHERE account_code = '1500_driver_earnings_receivable';

            UPDATE ledger.account_balances SET credit_total_minor = credit_total_minor + 1500 + (i * 50),
                balance_minor = credit_total_minor - (1500 + (i * 50)),
                last_posting_at = posted_at,
                updated_at = now()
              WHERE account_code = '2200_driver_payable';

        ELSE
            -- commission revenue: debit cash, credit commission
            INSERT INTO ledger.postings
                (id, posted_at, description, source_event_id, source_event_name,
                 correlation_id, tenant_id, idempotency_key, actor_type, audit_note)
            VALUES
                (posting_id, posted_at, 'DEV: commission revenue for trip DEV-' || i,
                 gen_random_uuid(), 'payment.captured.v1',
                 gen_random_uuid(), 'global', 'dev-seed-posting-' || i, 'service', NULL);

            INSERT INTO ledger.posting_entries (posting_id, account_code, account_version, side, amount_minor, currency, posted_at, correlation_id)
            VALUES
                (posting_id, '1100_cash_eur', 1, 'debit', 500 + (i * 25), 'EUR', posted_at, gen_random_uuid()),
                (posting_id, '4100_commission_revenue', 1, 'credit', 500 + (i * 25), 'EUR', posted_at, gen_random_uuid());

            UPDATE ledger.account_balances SET debit_total_minor = debit_total_minor + 500 + (i * 25),
                balance_minor = debit_total_minor - credit_total_minor,
                last_posting_at = posted_at,
                updated_at = now()
              WHERE account_code = '1100_cash_eur';

            UPDATE ledger.account_balances SET credit_total_minor = credit_total_minor + 500 + (i * 25),
                balance_minor = credit_total_minor - (500 + (i * 25)),
                last_posting_at = posted_at,
                updated_at = now()
              WHERE account_code = '4100_commission_revenue';

        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 3. Sample reconciliation runs (yesterday + the day before; one matched
-- and one with a small drift).
-- ---------------------------------------------------------------------------
INSERT INTO ledger.reconciliation_runs
    (id, run_date, started_at, ended_at, wallet_total, earnings_total, settlement_total, ledger_total, drift_minor, status, details, correlation_id)
VALUES
    (gen_random_uuid(), CURRENT_DATE - 1,
     now() - INTERVAL '1 day 4 hours', now() - INTERVAL '1 day 3 hours 50 minutes',
     15000, 8500, 4200, 27700, 0, 'matched',
     '{"totals_by_type": {"asset": 15000, "liability": 8500, "expense": 4200}, "matched": true}'::JSONB,
     gen_random_uuid()),
    (gen_random_uuid(), CURRENT_DATE - 2,
     now() - INTERVAL '2 days 4 hours', now() - INTERVAL '2 days 3 hours 50 minutes',
     12500, 7100, 3800, 23400, 0, 'matched',
     '{"totals_by_type": {"asset": 12500, "liability": 7100, "expense": 3800}, "matched": true}'::JSONB,
     gen_random_uuid())
ON CONFLICT (run_date) DO NOTHING;
