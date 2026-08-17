-- V3__wallets_and_earnings.sql
-- Per docs/services/payment-service/ERD.md §3:
--   payment.wallets            : the customer wallet aggregate (one row per customer).
--   payment.wallet_entries     : the wallet double-entry ledger (debits + credits).
--   payment.driver_earnings    : the driver period-earnings aggregate.
--   payment.driver_earnings_lines : per-period earnings line items.
--   payment.courier_earnings   : the courier period-earnings aggregate.
--   payment.courier_earnings_lines : per-period earnings line items.
--   payment.merchant_settlements : the merchant settlement aggregate.
--   payment.merchant_settlement_lines : per-merchant settlement line items.
--
-- The wallet follows the canonical double-entry pattern from ledger-service
-- (see docs/services/ledger-service/ERD.md): every wallet_entries row
-- debits one wallet and credits another (or the platform suspense account
-- 9999_001). Balance = SUM(amount_minor) WHERE wallet_id = ? AND deleted_at IS NULL.
-- The CHECK constraint enforces balance >= 0 (no overdraft on a customer wallet).
--
-- Earnings tables are NOT ledger entries — they are period-aggregated
-- balances. The actual money movement lives in ledger-service postings
-- (consumed via payment.completed.v1 events).

-- 1) payment.wallets
CREATE TABLE IF NOT EXISTS payment.wallets (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    wallet_kind TEXT NOT NULL DEFAULT 'customer',
    currency VARCHAR(3) NOT NULL,
    state TEXT NOT NULL DEFAULT 'active',
    balance_minor BIGINT NOT NULL DEFAULT 0,
    held_balance_minor BIGINT NOT NULL DEFAULT 0,
    last_entry_id UUID,
    last_activity_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT wallets_wallet_kind_check
        CHECK (wallet_kind IN ('customer','driver','courier','merchant','platform')),
    CONSTRAINT wallets_state_check
        CHECK (state IN ('active','frozen','closed')),
    CONSTRAINT wallets_balance_minor_check
        CHECK (balance_minor >= 0),
    CONSTRAINT wallets_held_balance_minor_check
        CHECK (held_balance_minor >= 0)
);

-- One wallet per (customer_id, wallet_kind, currency) tuple.
CREATE UNIQUE INDEX IF NOT EXISTS wallets_customer_kind_currency_uniq
    ON payment.wallets (customer_id, wallet_kind, currency)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS wallets_customer_id_idx
    ON payment.wallets (customer_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS wallets_state_idx
    ON payment.wallets (state)
    WHERE state <> 'active';

-- 2) payment.wallet_entries : double-entry ledger lines.
-- Every wallet_entry MUST belong to an event_id (idempotency) and reference
-- exactly one wallet. Customer wallets cannot go below 0 — enforced by
-- application logic (debit) plus a deferred trigger in V5.
CREATE TABLE IF NOT EXISTS payment.wallet_entries (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL,
    event_id UUID NOT NULL,
    direction TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    balance_after_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source TEXT NOT NULL,
    source_id UUID,
    description TEXT,
    correlation_id UUID NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    CONSTRAINT wallet_entries_direction_check
        CHECK (direction IN ('credit','debit')),
    CONSTRAINT wallet_entries_amount_minor_check
        CHECK (amount_minor > 0),
    CONSTRAINT wallet_entries_balance_after_minor_check
        CHECK (balance_after_minor >= 0),
    CONSTRAINT wallet_entries_source_check
        CHECK (source IN ('payment_capture','refund','reward_grant','reward_reversal','manual_adjustment','wallet_topup','wallet_transfer','merchant_payout','driver_payout','courier_payout','platform_commission'))
);

-- One entry per (event_id, source) — the canonical idempotency primitive.
CREATE UNIQUE INDEX IF NOT EXISTS wallet_entries_event_source_uniq
    ON payment.wallet_entries (event_id, source);
CREATE INDEX IF NOT EXISTS wallet_entries_wallet_id_idx
    ON payment.wallet_entries (wallet_id, posted_at DESC);
CREATE INDEX IF NOT EXISTS wallet_entries_correlation_id_idx
    ON payment.wallet_entries (correlation_id);

-- 3) payment.driver_earnings : period-aggregated earnings balance.
-- Period types: hourly / daily / weekly. Each row is the running total
-- for that driver + period.
CREATE TABLE IF NOT EXISTS payment.driver_earnings (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    period_kind TEXT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    currency VARCHAR(3) NOT NULL,
    rides_count INT NOT NULL DEFAULT 0,
    gross_fare_minor BIGINT NOT NULL DEFAULT 0,
    commission_minor BIGINT NOT NULL DEFAULT 0,
    tip_minor BIGINT NOT NULL DEFAULT 0,
    bonus_minor BIGINT NOT NULL DEFAULT 0,
    guaranteed_topup_minor BIGINT NOT NULL DEFAULT 0,
    correction_minor BIGINT NOT NULL DEFAULT 0,
    net_pay_minor BIGINT NOT NULL DEFAULT 0,
    paid_out_at TIMESTAMPTZ,
    state TEXT NOT NULL DEFAULT 'open',
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT driver_earnings_period_kind_check
        CHECK (period_kind IN ('hourly','daily','weekly','monthly')),
    CONSTRAINT driver_earnings_state_check
        CHECK (state IN ('open','finalized','paid_out','disputed')),
    CONSTRAINT driver_earnings_net_pay_minor_check
        CHECK (net_pay_minor >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS driver_earnings_driver_period_uniq
    ON payment.driver_earnings (driver_id, period_kind, period_start);
CREATE INDEX IF NOT EXISTS driver_earnings_driver_id_idx
    ON payment.driver_earnings (driver_id, period_end DESC);
CREATE INDEX IF NOT EXISTS driver_earnings_state_idx
    ON payment.driver_earnings (state)
    WHERE state = 'open';

-- 4) payment.driver_earnings_lines : per-ride earnings line items.
CREATE TABLE IF NOT EXISTS payment.driver_earnings_lines (
    id UUID PRIMARY KEY,
    driver_earnings_id UUID NOT NULL,
    request_id UUID NOT NULL,
    service TEXT NOT NULL,
    line_kind TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    trip_completed_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    CONSTRAINT driver_earnings_lines_line_kind_check
        CHECK (line_kind IN ('gross_fare','commission','tip','bonus','guaranteed_topup','correction'))
);

CREATE INDEX IF NOT EXISTS driver_earnings_lines_earnings_idx
    ON payment.driver_earnings_lines (driver_earnings_id, created_at DESC);
CREATE INDEX IF NOT EXISTS driver_earnings_lines_request_id_idx
    ON payment.driver_earnings_lines (request_id);

-- 5) payment.courier_earnings : same shape as driver_earnings.
CREATE TABLE IF NOT EXISTS payment.courier_earnings (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL,
    period_kind TEXT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    currency VARCHAR(3) NOT NULL,
    deliveries_count INT NOT NULL DEFAULT 0,
    gross_fee_minor BIGINT NOT NULL DEFAULT 0,
    commission_minor BIGINT NOT NULL DEFAULT 0,
    tip_minor BIGINT NOT NULL DEFAULT 0,
    bonus_minor BIGINT NOT NULL DEFAULT 0,
    correction_minor BIGINT NOT NULL DEFAULT 0,
    net_pay_minor BIGINT NOT NULL DEFAULT 0,
    paid_out_at TIMESTAMPTZ,
    state TEXT NOT NULL DEFAULT 'open',
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT courier_earnings_period_kind_check
        CHECK (period_kind IN ('hourly','daily','weekly','monthly')),
    CONSTRAINT courier_earnings_state_check
        CHECK (state IN ('open','finalized','paid_out','disputed')),
    CONSTRAINT courier_earnings_net_pay_minor_check
        CHECK (net_pay_minor >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS courier_earnings_courier_period_uniq
    ON payment.courier_earnings (courier_id, period_kind, period_start);
CREATE INDEX IF NOT EXISTS courier_earnings_courier_id_idx
    ON payment.courier_earnings (courier_id, period_end DESC);
CREATE INDEX IF NOT EXISTS courier_earnings_state_idx
    ON payment.courier_earnings (state)
    WHERE state = 'open';

-- 6) payment.courier_earnings_lines : per-delivery earnings line items.
CREATE TABLE IF NOT EXISTS payment.courier_earnings_lines (
    id UUID PRIMARY KEY,
    courier_earnings_id UUID NOT NULL,
    request_id UUID NOT NULL,
    service TEXT NOT NULL,
    line_kind TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    delivery_completed_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    CONSTRAINT courier_earnings_lines_line_kind_check
        CHECK (line_kind IN ('gross_fee','commission','tip','bonus','correction'))
);

CREATE INDEX IF NOT EXISTS courier_earnings_lines_earnings_idx
    ON payment.courier_earnings_lines (courier_earnings_id, created_at DESC);

-- 7) payment.merchant_settlements : merchant settlement aggregate.
CREATE TABLE IF NOT EXISTS payment.merchant_settlements (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    currency VARCHAR(3) NOT NULL,
    orders_count INT NOT NULL DEFAULT 0,
    gross_revenue_minor BIGINT NOT NULL DEFAULT 0,
    commission_minor BIGINT NOT NULL DEFAULT 0,
    adjustments_minor BIGINT NOT NULL DEFAULT 0,
    refund_reversal_minor BIGINT NOT NULL DEFAULT 0,
    net_payout_minor BIGINT NOT NULL DEFAULT 0,
    paid_out_at TIMESTAMPTZ,
    payout_reference TEXT,
    state TEXT NOT NULL DEFAULT 'open',
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT merchant_settlements_state_check
        CHECK (state IN ('open','finalized','paid_out','disputed'))
);

CREATE UNIQUE INDEX IF NOT EXISTS merchant_settlements_merchant_period_uniq
    ON payment.merchant_settlements (merchant_id, period_start);
CREATE INDEX IF NOT EXISTS merchant_settlements_merchant_id_idx
    ON payment.merchant_settlements (merchant_id, period_end DESC);
CREATE INDEX IF NOT EXISTS merchant_settlements_state_idx
    ON payment.merchant_settlements (state)
    WHERE state = 'open';

-- 8) payment.merchant_settlement_lines : per-order settlement line items.
CREATE TABLE IF NOT EXISTS payment.merchant_settlement_lines (
    id UUID PRIMARY KEY,
    merchant_settlement_id UUID NOT NULL,
    order_id UUID NOT NULL,
    service TEXT NOT NULL,
    line_kind TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    order_completed_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    CONSTRAINT merchant_settlement_lines_line_kind_check
        CHECK (line_kind IN ('gross_revenue','commission','adjustment','refund_reversal'))
);

CREATE INDEX IF NOT EXISTS merchant_settlement_lines_settlement_idx
    ON payment.merchant_settlement_lines (merchant_settlement_id, created_at DESC);
CREATE INDEX IF NOT EXISTS merchant_settlement_lines_order_id_idx
    ON payment.merchant_settlement_lines (order_id);