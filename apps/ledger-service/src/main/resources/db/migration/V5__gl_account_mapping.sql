-- V5: gl-account-mapping — bridges the operational financial services'
-- internal account / event types to the ledger's chart-of-accounts codes.
--
-- Per docs/services/ledger-service/INTEGRATION.md §4, every money-movement
-- event is translated into a balanced ledger posting. This table is the
-- canonical mapping: it tells the consumer which ledger account to credit
-- / debit for a given source event + role.
--
-- The mapping is versioned (`effective_from` / `effective_to`) so that
-- renaming a ledger account never breaks a replay. Corrections are new
-- rows, never UPDATE.
--
-- Use the read pattern:
--   SELECT * FROM ledger.gl_account_mapping
--    WHERE source_service = ? AND source_event_type = ? AND role = ?
--      AND effective_from <= now() AND (effective_to IS NULL OR effective_to > now())

CREATE TABLE IF NOT EXISTS ledger.gl_account_mapping (
    id UUID NOT NULL,
    source_service TEXT NOT NULL,
    source_event_type TEXT NOT NULL,
    role TEXT NOT NULL,
    ledger_account_code TEXT NOT NULL,
    description TEXT NOT NULL,
    amount_minor BIGINT,
    currency CHAR(3),
    side TEXT NOT NULL,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT gl_account_mapping_side_chk CHECK (side IN ('debit','credit')),
    CONSTRAINT gl_account_mapping_fk
        FOREIGN KEY (ledger_account_code) REFERENCES ledger.accounts(code),
    CONSTRAINT gl_account_mapping_valid_chk
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX IF NOT EXISTS gl_account_mapping_active_uq
    ON ledger.gl_account_mapping (source_service, source_event_type, role, tenant_id)
    WHERE effective_to IS NULL;

CREATE INDEX IF NOT EXISTS gl_account_mapping_lookup_ix
    ON ledger.gl_account_mapping (source_service, source_event_type, role, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Seed: payment-service money-movement events
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_id UUID;
BEGIN
    -- payment.captured.v1
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service', 'payment.captured.v1', 'cash', '1100_cash_eur',
           'Cash leg of payment capture', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service'
                       AND source_event_type = 'payment.captured.v1' AND role = 'cash' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service', 'payment.captured.v1', 'receivable', '2100_customer_receivable',
           'Customer payable leg of payment capture', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service'
                       AND source_event_type = 'payment.captured.v1' AND role = 'receivable' AND effective_to IS NULL);

    -- payment.refund.completed.v1
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service', 'payment.refund.completed.v1', 'cash', '1100_cash_eur',
           'Cash leg of refund', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service'
                       AND source_event_type = 'payment.refund.completed.v1' AND role = 'cash' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service', 'payment.refund.completed.v1', 'receivable', '2100_customer_receivable',
           'Customer payable leg of refund', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service'
                       AND source_event_type = 'payment.refund.completed.v1' AND role = 'receivable' AND effective_to IS NULL);

    -- payment-service (wallet) — wallet.held / wallet.released
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.held.v1', 'holds', '1300_wallet_holds',
           'Wallet hold created', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.held.v1' AND role = 'holds' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.held.v1', 'customer_balance', '2500_wallet_customer_balance',
           'Wallet customer balance (held)', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.held.v1' AND role = 'customer_balance' AND effective_to IS NULL);

    -- payment-service (wallet) — wallet.credited
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.credited.v1', 'cash', '1100_cash_eur',
           'Cash leg of wallet credit', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.credited.v1' AND role = 'cash' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.credited.v1', 'customer_balance', '2500_wallet_customer_balance',
           'Wallet customer balance (credited)', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.credited.v1' AND role = 'customer_balance' AND effective_to IS NULL);

    -- payment-service (wallet) — wallet.debited
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.debited.v1', 'cash', '1100_cash_eur',
           'Cash leg of wallet debit', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.debited.v1' AND role = 'cash' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.debited.v1', 'customer_balance', '2500_wallet_customer_balance',
           'Wallet customer balance (debited)', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.debited.v1' AND role = 'customer_balance' AND effective_to IS NULL);

    -- payment-service-wallet — wallet.released
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.released.v1', 'holds', '1300_wallet_holds',
           'Wallet hold released', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.released.v1' AND role = 'holds' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-wallet', 'wallet.released.v1', 'customer_balance', '2500_wallet_customer_balance',
           'Wallet customer balance (released)', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-wallet'
                       AND source_event_type = 'wallet.released.v1' AND role = 'customer_balance' AND effective_to IS NULL);

    -- payment-service-merchant-settlement — merchant.settlement.accrued
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-merchant-settlement', 'merchant.settlement.accrued.v1', 'receivable', '1600_merchant_settlement_receivable',
           'Merchant settlement receivable', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-merchant-settlement'
                       AND source_event_type = 'merchant.settlement.accrued.v1' AND role = 'receivable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-merchant-settlement', 'merchant.settlement.accrued.v1', 'payable', '2300_merchant_payable',
           'Merchant payable', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-merchant-settlement'
                       AND source_event_type = 'merchant.settlement.accrued.v1' AND role = 'payable' AND effective_to IS NULL);

    -- payment-service-merchant-settlement — merchant.payout.completed
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-merchant-settlement', 'merchant.payout.completed.v1', 'payable', '2300_merchant_payable',
           'Merchant payable (paid)', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-merchant-settlement'
                       AND source_event_type = 'merchant.payout.completed.v1' AND role = 'payable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-merchant-settlement', 'merchant.payout.completed.v1', 'payout', '7300_merchant_payout',
           'Merchant settlement payout', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-merchant-settlement'
                       AND source_event_type = 'merchant.payout.completed.v1' AND role = 'payout' AND effective_to IS NULL);

    -- payment-service-driver-earnings — driver.earning.accrued
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-driver-earnings', 'driver.earning.accrued.v1', 'receivable', '1500_driver_earnings_receivable',
           'Driver earnings receivable', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-driver-earnings'
                       AND source_event_type = 'driver.earning.accrued.v1' AND role = 'receivable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-driver-earnings', 'driver.earning.accrued.v1', 'payable', '2200_driver_payable',
           'Driver payable', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-driver-earnings'
                       AND source_event_type = 'driver.earning.accrued.v1' AND role = 'payable' AND effective_to IS NULL);

    -- payment-service-driver-earnings — driver.withdrawal.completed
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-driver-earnings', 'driver.withdrawal.completed.v1', 'payable', '2200_driver_payable',
           'Driver payable (withdrawn)', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-driver-earnings'
                       AND source_event_type = 'driver.withdrawal.completed.v1' AND role = 'payable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-driver-earnings', 'driver.withdrawal.completed.v1', 'payout', '7100_driver_payout',
           'Driver payout', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-driver-earnings'
                       AND source_event_type = 'driver.withdrawal.completed.v1' AND role = 'payout' AND effective_to IS NULL);

    -- payment-service-courier-earnings — courier.earning.accrued
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-courier-earnings', 'courier.earning.accrued.v1', 'receivable', '1501_courier_earnings_receivable',
           'Courier earnings receivable', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-courier-earnings'
                       AND source_event_type = 'courier.earning.accrued.v1' AND role = 'receivable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-courier-earnings', 'courier.earning.accrued.v1', 'payable', '2201_courier_payable',
           'Courier payable', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-courier-earnings'
                       AND source_event_type = 'courier.earning.accrued.v1' AND role = 'payable' AND effective_to IS NULL);

    -- payment-service-courier-earnings — courier.withdrawal.completed
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-courier-earnings', 'courier.withdrawal.completed.v1', 'payable', '2201_courier_payable',
           'Courier payable (withdrawn)', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-courier-earnings'
                       AND source_event_type = 'courier.withdrawal.completed.v1' AND role = 'payable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'payment-service-courier-earnings', 'courier.withdrawal.completed.v1', 'payout', '7200_courier_payout',
           'Courier payout', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'payment-service-courier-earnings'
                       AND source_event_type = 'courier.withdrawal.completed.v1' AND role = 'payout' AND effective_to IS NULL);

    -- trip.reward.granted (informational: not balanced by ledger)
    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'trip-service', 'trip.reward.granted.v1', 'driver_topup', '6302_guaranteed_minimum',
           'Driver guaranteed-minimum top-up', 'debit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'trip-service'
                       AND source_event_type = 'trip.reward.granted.v1' AND role = 'driver_topup' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'trip-service', 'trip.reward.granted.v1', 'driver_payable', '2200_driver_payable',
           'Driver payable (reward)', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'trip-service'
                       AND source_event_type = 'trip.reward.granted.v1' AND role = 'driver_payable' AND effective_to IS NULL);

    INSERT INTO ledger.gl_account_mapping (id, source_service, source_event_type, role, ledger_account_code, description, side, tenant_id)
    SELECT gen_random_uuid(), 'trip-service', 'trip.reward.granted.v1', 'customer_credit', '2101_customer_credit_liability',
           'Customer credit (reward)', 'credit', 'global'
    WHERE NOT EXISTS (SELECT 1 FROM ledger.gl_account_mapping WHERE source_service = 'trip-service'
                       AND source_event_type = 'trip.reward.granted.v1' AND role = 'customer_credit' AND effective_to IS NULL);
END $$;
