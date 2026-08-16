-- V7: reconciliation_rules — declarative rules for the daily reconciliation
-- job. Each rule expresses an expected equality between a platform
-- operational layer and the ledger, with a tolerance and severity.
--
-- Per docs/services/ledger-service/WORKFLOWS.md §4 the daily reconciliation
-- cross-checks: cash = wallet + earnings + settlement, revenue = sum of
-- revenue accounts, etc. The rules below encode those checks as data so
-- adding a new layer doesn't require a code change.
--
-- Drift sources (operational layers):
--   wallet_total                  — payment-service (wallet)
--   earnings_total                — payment-service (driver + courier earnings)
--   settlement_total              — payment-service (merchant settlement)
--   tax_total                     — tax service
--   commission_total              — pricing-service (commission)
--   promo_discount_total          — promotion-service
--   loyalty_discount_total        — loyalty-service
--
-- Fields:
--   rule_id            — stable id (e.g. "cash_equality")
--   rule_name          — short human-readable name
--   rule_type          — `equality` (a == b) | `sum_equality` (sum of a == b)
--   left_side          — name of the left-hand sum (or one side of equality)
--   right_side         — name of the right-hand sum (or the other side)
--   operator           — `==`, `!=`, `<=`, `>=`, `<`, `>`
--   tolerance_minor    — acceptable absolute diff (minor units); 0 = exact
--   severity           — `info` | `warning` | `critical` | `p1_ticket`
--   enabled            — boolean
--   effective_from     — period the rule applies from
--   effective_to       — period the rule applies to (NULL = current)

CREATE TABLE IF NOT EXISTS ledger.reconciliation_rules (
    id UUID NOT NULL,
    rule_id TEXT NOT NULL,
    rule_name TEXT NOT NULL,
    rule_type TEXT NOT NULL,
    left_side TEXT NOT NULL,
    right_side TEXT NOT NULL,
    operator TEXT NOT NULL DEFAULT '==',
    tolerance_minor BIGINT NOT NULL DEFAULT 0,
    severity TEXT NOT NULL DEFAULT 'warning',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT reconciliation_rules_uq UNIQUE (rule_id),
    CONSTRAINT reconciliation_rules_type_chk CHECK (rule_type IN
        ('equality','sum_equality','ratio')),
    CONSTRAINT reconciliation_rules_operator_chk CHECK (operator IN
        ('==','!=','<=','>=','<','>')),
    CONSTRAINT reconciliation_rules_severity_chk CHECK (severity IN
        ('info','warning','critical','p1_ticket')),
    CONSTRAINT reconciliation_rules_tolerance_chk CHECK (tolerance_minor >= 0),
    CONSTRAINT reconciliation_rules_valid_chk
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS reconciliation_rules_enabled_ix
    ON ledger.reconciliation_rules (enabled, effective_from);

-- ---------------------------------------------------------------------------
-- Seed: the canonical rule set
-- ---------------------------------------------------------------------------
INSERT INTO ledger.reconciliation_rules
    (id, rule_id, rule_name, rule_type, left_side, right_side, operator, tolerance_minor, severity, description)
VALUES
    -- Rule 1: cash equality — cash movement = wallet + earnings + settlement
    (gen_random_uuid(), 'cash_equality',
     'Cash leg = wallet + earnings + settlement',
     'sum_equality', 'cash_total', 'wallet_total,earnings_total,settlement_total',
     '==', 0, 'p1_ticket',
     'Sum of cash debits must equal the sum of operational layer totals (wallet, earnings, settlement). Drift opens a P1.'),

    -- Rule 2: trial balance — sum of all debits must equal sum of all credits
    (gen_random_uuid(), 'trial_balance_drift_zero',
     'Trial balance drift = 0',
     'equality', 'total_debits', 'total_credits',
     '==', 0, 'p1_ticket',
     'Sum of all debit entries must equal sum of all credit entries across the entire ledger. This is the double-entry invariant and must never be > 0.'),

    -- Rule 3: revenue net — revenue accounts must net to non-negative
    (gen_random_uuid(), 'revenue_non_negative',
     'Revenue accounts net non-negative',
     'equality', 'revenue_net', '0',
     '>=', 0, 'warning',
     'Net revenue (sum of credits - sum of debits on revenue accounts) must be non-negative.'),

    -- Rule 4: cash non-negative — operational cash balance must be non-negative
    (gen_random_uuid(), 'cash_non_negative',
     'Cash balance non-negative',
     'equality', 'cash_balance', '0',
     '>=', 0, 'critical',
     'Cash balance (cash accounts) must be non-negative. Negative cash indicates missing postings.'),

    -- Rule 5: posting imbalance — no single posting may have a non-zero balance
    (gen_random_uuid(), 'posting_imbalance_zero',
     'Every posting balances',
     'equality', 'posting_debit_sum', 'posting_credit_sum',
     '==', 0, 'p1_ticket',
     'Sum of debits must equal sum of credits for every posting. Enforced in application code; this rule is a belt-and-suspenders guardrail.'),

    -- Rule 6: provider receivable drift
    (gen_random_uuid(), 'provider_receivable_drift',
     'Provider receivable drift bounded',
     'equality', 'provider_receivable_ledger', 'provider_receivable_provider',
     '<=', 100, 'warning',
     'Difference between ledger provider_receivable and provider-reported receivable must be ≤ 100 minor units (i.e. < 1 cent).'),

    -- Rule 7: tip pass-through should zero out
    (gen_random_uuid(), 'tip_passthrough_zero',
     'Tip pass-through ledger balances to zero',
     'equality', 'tip_revenue', 'tip_passthrough',
     '==', 0, 'warning',
     'Tips must be recorded symmetrically (revenue + pass-through); the net contribution to the platform is zero.'),

    -- Rule 8: dormant balance bounded
    (gen_random_uuid(), 'dormant_balance_cap',
     'Dormant balance cap',
     'equality', 'dormant_balance', 'dormant_balance_cap',
     '<=', 1000000, 'warning',
     'Dormant customer balance (unclaimed refunds older than 3 years) must be ≤ 10,000 minor units (€100).')
ON CONFLICT (rule_id) DO NOTHING;
