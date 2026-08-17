-- V3: seed the default chart of accounts per docs/services/ledger-service/ERD.md §5.
--
-- The chart of accounts is the platform's authoritative list of balance-sheet,
-- revenue, and expense accounts. Each row is an instance of `ledger.accounts`
-- with `version = 1`, `valid_from = now()`, `valid_to = NULL`.
--
-- The accounts follow the standard 4-digit + suffix convention:
--   1000_*  assets
--   2000_*  liabilities
--   3000_*  equity
--   4000_*  revenue
--   5000_*  expense (commissions / discounts)
--   6000_*  expense (operational costs)
--   7000_*  expense (driver / courier payouts)
--
-- A separate migration may `INSERT` a new version (version = 2) to supersede
-- an account row; the previous version gets `valid_to = now()`.

DO $$
DECLARE
    accounts TEXT[][] := ARRAY[
        -- [code, name, type, currency, parent_code]
        ['1000_assets', 'Assets (root)', 'asset', 'EUR', NULL],
        ['1100_cash_eur', 'Cash (EUR)', 'asset', 'EUR', '1000_assets'],
        ['1101_cash_usd', 'Cash (USD)', 'asset', 'USD', '1000_assets'],
        ['1102_cash_gbp', 'Cash (GBP)', 'asset', 'GBP', '1000_assets'],
        ['1103_cash_sar', 'Cash (SAR)', 'asset', 'SAR', '1000_assets'],
        ['1104_cash_aed', 'Cash (AED)', 'asset', 'AED', '1000_assets'],
        ['1105_cash_egp', 'Cash (EGP)', 'asset', 'EGP', '1000_assets'],
        ['1200_bank_eur', 'Bank — provider settlement (EUR)', 'asset', 'EUR', '1000_assets'],
        ['1201_bank_usd', 'Bank — provider settlement (USD)', 'asset', 'USD', '1000_assets'],
        ['1202_bank_gbp', 'Bank — provider settlement (GBP)', 'asset', 'GBP', '1000_assets'],
        ['1203_bank_sar', 'Bank — provider settlement (SAR)', 'asset', 'SAR', '1000_assets'],
        ['1204_bank_aed', 'Bank — provider settlement (AED)', 'asset', 'AED', '1000_assets'],
        ['1300_wallet_holds', 'Wallet holds (in-flight)', 'asset', 'EUR', '1000_assets'],
        ['1400_provider_receivable', 'Provider receivable', 'asset', 'EUR', '1000_assets'],
        ['1401_provider_receivable_usd', 'Provider receivable (USD)', 'asset', 'USD', '1000_assets'],
        ['1402_provider_receivable_gbp', 'Provider receivable (GBP)', 'asset', 'GBP', '1000_assets'],
        ['1500_driver_earnings_receivable', 'Driver earnings receivable', 'asset', 'EUR', '1000_assets'],
        ['1501_courier_earnings_receivable', 'Courier earnings receivable', 'asset', 'EUR', '1000_assets'],
        ['1600_merchant_settlement_receivable', 'Merchant settlement receivable', 'asset', 'EUR', '1000_assets'],
        ['1700_tax_receivable', 'Tax receivable (VAT refund)', 'asset', 'EUR', '1000_assets'],
        ['1800_fx_receivable', 'FX receivable', 'asset', 'EUR', '1000_assets'],
        ['1900_intercompany_receivable', 'Intercompany receivable', 'asset', 'EUR', '1000_assets'],

        ['2000_liabilities', 'Liabilities (root)', 'liability', 'EUR', NULL],
        ['2100_customer_receivable', 'Customer payable (refunds)', 'liability', 'EUR', '2000_liabilities'],
        ['2101_customer_credit_liability', 'Customer credit (loyalty / reward)', 'liability', 'EUR', '2000_liabilities'],
        ['2102_customer_credit_liability_usd', 'Customer credit (USD)', 'liability', 'USD', '2000_liabilities'],
        ['2200_driver_payable', 'Driver payable', 'liability', 'EUR', '2000_liabilities'],
        ['2201_courier_payable', 'Courier payable', 'liability', 'EUR', '2000_liabilities'],
        ['2300_merchant_payable', 'Merchant payable', 'liability', 'EUR', '2000_liabilities'],
        ['2400_tax_payable', 'Tax payable', 'liability', 'EUR', '2000_liabilities'],
        ['2401_tax_payable_usd', 'Tax payable (USD)', 'liability', 'USD', '2000_liabilities'],
        ['2500_wallet_customer_balance', 'Wallet customer balance', 'liability', 'EUR', '2000_liabilities'],
        ['2501_wallet_customer_balance_usd', 'Wallet customer balance (USD)', 'liability', 'USD', '2000_liabilities'],
        ['2502_wallet_customer_balance_gbp', 'Wallet customer balance (GBP)', 'liability', 'GBP', '2000_liabilities'],
        ['2503_wallet_customer_balance_sar', 'Wallet customer balance (SAR)', 'liability', 'SAR', '2000_liabilities'],
        ['2504_wallet_customer_balance_aed', 'Wallet customer balance (AED)', 'liability', 'AED', '2000_liabilities'],
        ['2600_provider_payable', 'Provider payable (settlement)', 'liability', 'EUR', '2000_liabilities'],
        ['2700_dormant_balance', 'Dormant customer balance', 'liability', 'EUR', '2000_liabilities'],
        ['2800_intercompany_payable', 'Intercompany payable', 'liability', 'EUR', '2000_liabilities'],

        ['3000_equity', 'Equity (root)', 'equity', 'EUR', NULL],
        ['3100_platform_equity', 'Platform equity', 'equity', 'EUR', '3000_equity'],
        ['3200_retained_earnings', 'Retained earnings', 'equity', 'EUR', '3000_equity'],
        ['3300_other_comprehensive_income', 'Other comprehensive income (FX)', 'equity', 'EUR', '3000_equity'],

        ['4000_revenue', 'Revenue (root)', 'revenue', 'EUR', NULL],
        ['4100_commission_revenue', 'Commission revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4101_service_fee_revenue', 'Service fee revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4102_surge_revenue', 'Surge revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4103_minimum_fare_revenue', 'Minimum fare revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4200_cancellation_fee_revenue', 'Cancellation fee revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4201_wait_time_revenue', 'Wait time revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4300_delivery_fee_revenue', 'Delivery fee revenue (food)', 'revenue', 'EUR', '4000_revenue'],
        ['4400_merchant_commission_revenue', 'Merchant commission revenue (food)', 'revenue', 'EUR', '4000_revenue'],
        ['4401_small_order_fee_revenue', 'Small order fee revenue', 'revenue', 'EUR', '4000_revenue'],
        ['4500_subscription_revenue', 'Subscription revenue (loyalty)', 'revenue', 'EUR', '4000_revenue'],
        ['4600_tip_revenue', 'Tip revenue (driver / courier)', 'revenue', 'EUR', '4000_revenue'],
        ['4601_tip_passthrough', 'Tip pass-through (driver / courier)', 'revenue', 'EUR', '4000_revenue'],

        ['5000_expense', 'Expense (root)', 'expense', 'EUR', NULL],
        ['5100_promotion_discount', 'Promotion discount (platform-borne)', 'expense', 'EUR', '5000_expense'],
        ['5101_loyalty_discount', 'Loyalty discount (platform-borne)', 'expense', 'EUR', '5000_expense'],
        ['5102_rating_density_discount', 'Rating-density discount (platform-borne)', 'expense', 'EUR', '5000_expense'],
        ['5103_geo_config_discount', 'Geo-config discount (platform-borne)', 'expense', 'EUR', '5000_expense'],
        ['5104_first_ride_discount', 'First ride discount (platform-borne)', 'expense', 'EUR', '5000_expense'],
        ['5105_referral_discount', 'Referral discount (platform-borne)', 'expense', 'EUR', '5000_expense'],

        ['6000_operational_expense', 'Operational expense (root)', 'expense', 'EUR', NULL],
        ['6100_payment_processing_fees', 'Payment processing fees', 'expense', 'EUR', '6000_operational_expense'],
        ['6101_payment_processing_fees_usd', 'Payment processing fees (USD)', 'expense', 'USD', '6000_operational_expense'],
        ['6200_refunds_goodwill', 'Refunds — goodwill', 'expense', 'EUR', '6000_operational_expense'],
        ['6201_refunds_dispute', 'Refunds — dispute', 'expense', 'EUR', '6000_operational_expense'],
        ['6302_guaranteed_minimum', 'Driver guaranteed-minimum top-up', 'expense', 'EUR', '6000_operational_expense'],
        ['6303_courier_guaranteed_minimum', 'Courier guaranteed-minimum top-up', 'expense', 'EUR', '6000_operational_expense'],
        ['6400_platform_infra_cost', 'Platform infrastructure cost', 'expense', 'EUR', '6000_operational_expense'],
        ['6401_cloud_cost', 'Cloud cost (AWS / GCP)', 'expense', 'EUR', '6000_operational_expense'],
        ['6500_fraud_loss', 'Fraud loss', 'expense', 'EUR', '6000_operational_expense'],
        ['6501_chargeback_loss', 'Chargeback loss', 'expense', 'EUR', '6000_operational_expense'],
        ['6600_insurance', 'Insurance', 'expense', 'EUR', '6000_operational_expense'],
        ['6700_support_ops', 'Support operations', 'expense', 'EUR', '6000_operational_expense'],
        ['6800_marketing', 'Marketing', 'expense', 'EUR', '6000_operational_expense'],
        ['6900_taxes_local', 'Local taxes (non-VAT)', 'expense', 'EUR', '6000_operational_expense'],

        ['7000_payouts', 'Payouts (root)', 'expense', 'EUR', NULL],
        ['7100_driver_payout', 'Driver payout (gross)', 'expense', 'EUR', '7000_payouts'],
        ['7101_driver_withholding', 'Driver withholding (tax / fees)', 'expense', 'EUR', '7000_payouts'],
        ['7200_courier_payout', 'Courier payout (gross)', 'expense', 'EUR', '7000_payouts'],
        ['7201_courier_withholding', 'Courier withholding (tax / fees)', 'expense', 'EUR', '7000_payouts'],
        ['7300_merchant_payout', 'Merchant settlement payout', 'expense', 'EUR', '7000_payouts']
    ];
    row TEXT[];
    v_id UUID;
    v_now TIMESTAMPTZ := now();
    v_admin UUID := '00000000-0000-0000-0000-000000000000'::UUID;
BEGIN
    -- Ensure parent codes exist before child codes (parent-first ordering).
    FOREACH row SLICE 1 IN ARRAY accounts LOOP
        IF row[5] IS NOT NULL THEN
            PERFORM 1 FROM ledger.accounts WHERE code = row[5];
            IF NOT FOUND THEN
                RAISE EXCEPTION 'parent account % missing for child %', row[5], row[1];
            END IF;
        END IF;
    END LOOP;

    FOREACH row SLICE 1 IN ARRAY accounts LOOP
        PERFORM 1 FROM ledger.accounts WHERE code = row[1] AND valid_to IS NULL;
        IF FOUND THEN
            CONTINUE;
        END IF;
        v_id := gen_random_uuid();
        INSERT INTO ledger.accounts
            (id, code, name, type, currency, parent_code, version, valid_from, valid_to, created_by)
        VALUES
            (v_id, row[1], row[2], row[3], row[4],
             NULLIF(row[5], ''),
             1, v_now, NULL, v_admin)
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;
