-- V4: dimensional lookup tables (read-mostly, application role has SELECT).
--
-- These tables back the platform's per-currency math, accounting-period
-- rules, and per-tenant isolation. Per
-- docs/services/ledger-service/ERD.md §3, the ledger keeps cross-service
-- references as plain UUID / TEXT columns — but the supporting dimensions
-- (currency metadata, account-type semantics, tenant config) live here.
--
-- All tables are append-only; corrections are new rows with effective
-- date ranges.

-- ---------------------------------------------------------------------------
-- 1. currencies — ISO 4217 reference data
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.currencies (
    code CHAR(3) NOT NULL,
    iso_number SMALLINT NOT NULL,
    name TEXT NOT NULL,
    symbol TEXT NOT NULL,
    decimal_places SMALLINT NOT NULL DEFAULT 2,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_base_currency BOOLEAN NOT NULL DEFAULT FALSE,
    rounding_mode TEXT NOT NULL DEFAULT 'HALF_EVEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (code),
    CONSTRAINT currencies_iso_chk CHECK (iso_number BETWEEN 1 AND 999),
    CONSTRAINT currencies_decimal_chk CHECK (decimal_places BETWEEN 0 AND 6),
    CONSTRAINT currencies_rounding_chk CHECK (rounding_mode IN
        ('HALF_EVEN','HALF_UP','HALF_DOWN','UP','DOWN','CEILING','FLOOR'))
);

INSERT INTO ledger.currencies (code, iso_number, name, symbol, decimal_places, is_active, is_base_currency)
VALUES
    ('EUR', 978, 'Euro', '€', 2, TRUE, TRUE),
    ('USD', 840, 'United States Dollar', '$', 2, TRUE, FALSE),
    ('GBP', 826, 'Pound Sterling', '£', 2, TRUE, FALSE),
    ('SAR', 682, 'Saudi Riyal', 'SAR', 2, TRUE, FALSE),
    ('AED', 784, 'UAE Dirham', 'AED', 2, TRUE, FALSE),
    ('EGP', 818, 'Egyptian Pound', 'EGP', 2, TRUE, FALSE),
    ('JPY', 392, 'Japanese Yen', '¥', 0, TRUE, FALSE),
    ('INR', 356, 'Indian Rupee', '₹', 2, TRUE, FALSE),
    ('PKR', 586, 'Pakistani Rupee', 'PKR', 2, TRUE, FALSE),
    ('TRY', 949, 'Turkish Lira', 'TRY', 2, TRUE, FALSE)
ON CONFLICT (code) DO NOTHING;

-- Exactly one currency must be the base.
DO $$
DECLARE
    v_count INT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM ledger.currencies WHERE is_base_currency = TRUE;
    IF v_count <> 1 THEN
        RAISE EXCEPTION 'expected exactly one base currency, found %', v_count;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. account_types — semantic metadata per accounting type
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.account_types (
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    normal_balance_side TEXT NOT NULL,
    statement_section TEXT NOT NULL,
    affects_trial_balance BOOLEAN NOT NULL DEFAULT TRUE,
    affects_pl BOOLEAN NOT NULL DEFAULT TRUE,
    affects_balance_sheet BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order SMALLINT NOT NULL,
    PRIMARY KEY (type),
    CONSTRAINT account_types_type_chk CHECK (type IN
        ('asset','liability','equity','revenue','expense')),
    CONSTRAINT account_types_normal_balance_chk CHECK (normal_balance_side IN ('debit','credit')),
    CONSTRAINT account_types_statement_section_chk CHECK (statement_section IN
        ('balance_sheet','income_statement','none'))
);

INSERT INTO ledger.account_types (type, name, normal_balance_side, statement_section,
                                    affects_trial_balance, affects_pl, affects_balance_sheet, sort_order)
VALUES
    ('asset',     'Asset',     'debit',  'balance_sheet',   TRUE, FALSE, TRUE,  1),
    ('liability', 'Liability', 'credit', 'balance_sheet',   TRUE, FALSE, TRUE,  2),
    ('equity',    'Equity',    'credit', 'balance_sheet',   TRUE, FALSE, TRUE,  3),
    ('revenue',   'Revenue',   'credit', 'income_statement',TRUE, TRUE,  FALSE, 4),
    ('expense',   'Expense',   'debit',  'income_statement',TRUE, TRUE,  FALSE, 5)
ON CONFLICT (type) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. tenants — multi-tenant isolation config
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.tenants (
    tenant_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    base_currency CHAR(3) NOT NULL,
    fiscal_year_start_month SMALLINT NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    contact_email TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id),
    CONSTRAINT tenants_fiscal_month_chk CHECK (fiscal_year_start_month BETWEEN 1 AND 12)
);

INSERT INTO ledger.tenants (tenant_id, display_name, base_currency, fiscal_year_start_month, contact_email)
VALUES
    ('global',  'Trips Enjoy — Global',  'EUR', 1, 'finance@trips-enjoy.com'),
    ('region-eu', 'Trips Enjoy — Europe', 'EUR', 1, 'eu-finance@trips-enjoy.com'),
    ('region-us', 'Trips Enjoy — Americas', 'USD', 1, 'us-finance@trips-enjoy.com'),
    ('region-uk', 'Trips Enjoy — UK', 'GBP', 4, 'uk-finance@trips-enjoy.com'),
    ('region-me', 'Trips Enjoy — Middle East', 'AED', 1, 'me-finance@trips-enjoy.com'),
    ('region-eg', 'Trips Enjoy — Egypt', 'EGP', 7, 'eg-finance@trips-enjoy.com')
ON CONFLICT (tenant_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. exchange_rates — daily reference rates for multi-currency conversions
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.exchange_rates (
    rate_date DATE NOT NULL,
    source_currency CHAR(3) NOT NULL,
    target_currency CHAR(3) NOT NULL,
    rate NUMERIC(20, 8) NOT NULL,
    provider TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rate_date, source_currency, target_currency),
    CONSTRAINT exchange_rates_currencies_chk CHECK (source_currency <> target_currency),
    CONSTRAINT exchange_rates_rate_chk CHECK (rate > 0)
);

CREATE INDEX IF NOT EXISTS exchange_rates_target_ix
    ON ledger.exchange_rates (target_currency, rate_date DESC);

-- Seed a snapshot of today with static rates so dev environments have a
-- coherent reference (production loads these from an upstream provider).
INSERT INTO ledger.exchange_rates (rate_date, source_currency, target_currency, rate, provider)
VALUES
    (CURRENT_DATE, 'USD', 'EUR', 0.92, 'seed'),
    (CURRENT_DATE, 'GBP', 'EUR', 1.17, 'seed'),
    (CURRENT_DATE, 'SAR', 'EUR', 0.25, 'seed'),
    (CURRENT_DATE, 'AED', 'EUR', 0.25, 'seed'),
    (CURRENT_DATE, 'EGP', 'EUR', 0.019, 'seed'),
    (CURRENT_DATE, 'EUR', 'USD', 1.087, 'seed'),
    (CURRENT_DATE, 'EUR', 'GBP', 0.854, 'seed'),
    (CURRENT_DATE, 'EUR', 'SAR', 4.05, 'seed'),
    (CURRENT_DATE, 'EUR', 'AED', 3.99, 'seed'),
    (CURRENT_DATE, 'EUR', 'EGP', 52.50, 'seed')
ON CONFLICT (rate_date, source_currency, target_currency) DO NOTHING;
