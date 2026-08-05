# Payment Workflows

The financial flows cut across ride-hailing and food delivery. This
document consolidates them.

> For the **accounting view** (tax recognition & remittance; gross-to-net
> driver / courier income; marketplace VAT; CIT & regulatory fees; expense
> recognition — incentives, refunds, opex, chargebacks; reconciliation
> & period close) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md).

## Actors and Services

| Actor | Services |
|-------|----------|
| Customer | `payment-service`, `wallet-service`, `ride-payment-integration-service` / `food-payment-integration-service` |
| Driver/Courier | `driver-earnings-service`, `courier-earnings-service`, `wallet-service` |
| Restaurant/Merchant | `restaurant-settlement-service`, `wallet-service` |
| System | `payment-service`, `ledger-service`, `fraud-risk-service` |

## Workflow: Authorize → Capture (Card)

```mermaid
sequenceDiagram
    participant ORCH as ride/food-payment-integration
    participant PAY as payment-service
    participant FR as fraud-risk-service
    participant EXT as Payment Provider
    participant LD as ledger-service
    participant WLT as wallet-service
    participant NOT as notification-service

    ORCH->>PAY: authorize(amount, payment_method_token, Idempotency-Key=order:O:auth)
    PAY->>FR: score(transaction)
    FR-->>PAY: risk_score
    alt risk high
        PAY-->>ORCH: payment.failed.v1 (code=RISK_DECLINED)
        ORCH->>NOT: notify customer
    else risk ok
        PAY->>EXT: authorize
        EXT-->>PAY: authorized (auth_id)
        PAY-->>ORCH: payment.authorized.v1
        Note over ORCH: later, on trip/delivery completion
        ORCH->>PAY: capture(auth_id, Idempotency-Key=order:O:cap)
        PAY->>EXT: capture
        EXT-->>PAY: captured
        PAY->>LD: post(authorization_hold, capture)
        LD-->>PAY: ledger.posted.v1
        PAY-->>ORCH: payment.captured.v1
        ORCH->>WLT: credit (e.g. for restaurant payable or driver earning)
    end
```

## Workflow: Refund

```mermaid
sequenceDiagram
    participant ORCH as ride/food-payment-integration
    participant PAY as payment-service
    participant EXT as Payment Provider
    participant WLT as wallet-service
    participant LD as ledger-service
    participant NOT as notification-service

    ORCH->>PAY: refund(capture_id, amount, reason, Idempotency-Key=order:O:refund)
    PAY->>EXT: refund
    EXT-->>PAY: refund_id
    PAY->>LD: post(refund)
    LD-->>PAY: ledger.posted.v1
    PAY-->>ORCH: payment.refund.completed.v1
    ORCH->>WLT: debit (if credited)
    ORCH->>NOT: notify customer
    NOT-->>C: push: "Refund processed"
```

## Workflow: Cash on Delivery (where configured)

```mermaid
sequenceDiagram
    participant CR as courier
    participant DLV as delivery-service
    participant FPI as food-payment-integration-service
    participant LD as ledger-service
    participant RSM as restaurant-settlement-service

    CR->>DLV: POST /v1/deliveries/{id}/cash-collected (amount)
    DLV->>FPI: cash.collected.v1
    FPI->>LD: post(cash_receivable, courier_cash)
    LD-->>FPI: ledger.posted.v1
    FPI->>RSM: schedule payable (less cash handling fee)
    FPI-->>DLV: cash.reconciled.v1
```

COD is **only** available when the merchant is configured to allow
it (`merchant.cod_enabled = true` in `configuration-service`). COD
amounts are reconciled daily.

## Workflow: Driver / Courier Earning Accrual

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant ORCH as ride/food-payment-integration
    participant DE as driver-earnings-service
    participant CE as courier-earnings-service
    participant WLT as wallet-service
    participant LD as ledger-service
    participant NOT as notification-service

    ORCH->>DE: accrue(trip_id, amount, Idempotency-Key=trip:T:earn)
    DE->>DE: insert earning
    DE->>LD: post(driver_payable, platform_payable)
    LD-->>DE: ledger.posted.v1
    DE-->>ORCH: driver.earning.accrued.v1

    Note over TR,DE: reward-grant contribution (independent of capture)
    TR-->>DE: trip.reward.granted.v1 (driver_topup_minor, snapshot)
    DE->>DE: apply top-up → earning = base + topup
    DE-->>LD: post(6302_guaranteed_minimum ↔ driver_payable)
    LD-->>DE: ledger.posted.v1

    Note over DE: optional, weekly transfer to driver's bank
    DE->>WLT: hold(amount)
    WLT-->>DE: held
    DE->>PAY: payout
    PAY-->>DE: payout.completed
    DE->>WLT: release
    WLT-->>DE: released
    DE-->>DR: driver.withdrawal.completed.v1
```

## Workflow: Restaurant Settlement

```mermaid
sequenceDiagram
    participant RSM as restaurant-settlement-service
    participant LD as ledger-service
    participant PAY as payment-service
    participant MR as merchant-service

    Note over RSM: daily / weekly cron
    RSM->>RSM: aggregate merchant payable
    RSM->>LD: post(merchant_payable, platform_commission)
    LD-->>RSM: ledger.posted.v1
    RSM->>PAY: payout to merchant bank
    PAY-->>RSM: payout.completed
    RSM->>MR: merchant.payout.completed.v1
    MR-->>MR: notify merchant
```

## Workflow: Tip Adjustment

```mermaid
sequenceDiagram
    participant C as Customer
    participant ORCH as ride/food-payment-integration
    participant WLT as wallet-service
    participant DE as driver-earnings-service
    participant CE as courier-earnings-service

    C->>ORCH: POST /v1/orders/{id}/tip (amount)
    ORCH->>WLT: charge tip
    WLT-->>ORCH: ok
    ORCH->>DE: accrue_tip
    DE-->>ORCH: ok
    ORCH-->>C: 200 OK
```

Tips are added to the next earning accrual; they do not modify the
already-captured base amount.

## Workflow: Wallet Top-up

The customer wallet has **two distinct credit paths** — both routed
through `wallet-service` and posted to `ledger-service` as
`2100_customer_credit_liability` ↔ `cash`, but triggered by different
events.

1. **Customer-initiated top-up** — the wallet holder funds their
   wallet from a payment method (see diagram below).
2. **Reward consumer path** — `trip-service` emits
   `trip.reward.granted.v1` on trip completion when the customer is
   eligible for a credit (loyalty tier, promo, or issue resolution);
   `wallet-service` consumes the event and credits the wallet
   idempotently on `grant_id` (no payment-method authorization is
   involved). Reversals on trip correction emit
   `trip.reward.reversed.v1` and produce a wallet **debit** (new
   posting, not UPDATE).

```mermaid
sequenceDiagram
    participant C as Customer
    participant WLT as wallet-service
    participant PAY as payment-service
    participant LD as ledger-service

    C->>WLT: POST /v1/wallets/{id}/topup (amount, payment_method)
    WLT->>PAY: authorize + capture
    PAY-->>WLT: payment.captured.v1
    WLT->>LD: post(wallet_credit, cash)
    LD-->>WLT: ledger.posted.v1
    WLT-->>C: 200 OK
```

## Workflow: Reward Reversal (Trip Correction)

When a previously-completed trip is cancelled after-the-fact, corrected
by a support agent, or invalidated by fraud-review,
`trip-service` emits `trip.reward.reversed.v1`. The reversal fans out
to the same consumers as the grant and produces **new postings** — a
ledger-service `UPDATE`/`DELETE` is rejected at the database trigger
level (per [[accounting-four-layer-truth-model]] §"How to apply"). For
the customer side, `wallet-service` debits the wallet (the original
credit posting remains, but a new negative posting offsets it). For the
driver side, `driver-earnings-service` issues an accrual correction
against the same `driver_payable` account.

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant DE as driver-earnings-service
    participant WLT as wallet-service
    participant LD as ledger-service
    participant NOT as notification-service

    TR->>TR: trip cancelled / corrected / fraud-invalid
    TR-->>DE: trip.reward.reversed.v1 (driver_topup_minor)
    TR-->>WLT: trip.reward.reversed.v1 (customer_credit_minor)
    TR-->>NOT: notify driver + customer

    Note over DE,WLT: ledger is append-only; reversals are NEW postings
    DE-->>LD: post NEW ROW: driver_payable ↔ 6302_guaranteed_minimum (negative)
    LD-->>DE: ledger.posted.v1
    WLT-->>LD: post NEW ROW: cash ↔ 2100_customer_credit_liability (negative)
    LD-->>WLT: ledger.posted.v1
    WLT->>WLT: debit customer wallet (idempotent on reversal_id)
    Note over LD: ledger-service blocks UPDATE/DELETE on ledger.postings
```

The reversal idempotency key is `trip:<trip_id>:reward:reversal`; a
duplicate reversal for the same trip is a no-op (the inbox table on
each consumer dedupes by `event_id`, and the consumer handler is
idempotent on reversal_id). For the accounting view see
[`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) §"Workflow:
Guaranteed Rewards — Driver Top-Up + Customer Credit".

## Workflow: Disputed Charge

```mermaid
sequenceDiagram
    participant EXT as Payment Provider
    participant PAY as payment-service
    participant SUP as support-service
    participant LD as ledger-service
    participant FPI as food-payment-integration
    participant ORCH as ride/food-payment-integration
    participant NOT as notification-service

    EXT->>PAY: webhook (charge.dispute.created)
    PAY->>SUP: open ticket
    PAY->>LD: post(disputed, pending)
    LD-->>PAY: ok
    SUP->>FPI/ORCH: pause settlement
    SUP->>SUP: agent investigates
    alt dispute won
        SUP->>PAY: accept dispute
        PAY->>LD: post(dispute_lost, refund)
    else dispute lost
        SUP->>PAY: contest (evidence)
        PAY->>EXT: submit evidence
    end
```

## Workflow: Idempotency in Practice

Every money-movement call carries an idempotency key. Examples:

| Action | Key pattern |
|--------|-------------|
| Authorize a ride payment | `ride:<ride_id>:auth` |
| Capture a ride payment | `ride:<ride_id>:cap` |
| Refund a ride payment | `ride:<ride_id>:refund:<reason>` |
| Accrue driver earning | `trip:<trip_id>:earn` |
| Accrue courier earning | `delivery:<delivery_id>:earn` |
| Settle merchant payable | `merchant:<merchant_id>:payout:<payout_id>` |
| Wallet top-up | `wallet:<wallet_id>:topup:<request_id>` |
| Trip reward grant (driver + customer) | `trip:<trip_id>:reward:grant` |
| Trip reward reversal | `trip:<trip_id>:reward:reversal` |
| Tip | `order:<order_id>:tip:<request_id>` |

The key is unique per logical operation. Replays of the same key
return the original result without re-executing.

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Authorization fails (insufficient funds, expired card) | Customer notified; alternative payment method requested |
| Capture fails (rare) | Refund of any held authorization; ticket opened |
| Provider timeout on authorize | Retry with backoff; on persistent failure, surface error |
| Refund fails | Manual intervention; ticket opened with P1 |
| Webhook lost | Reconciliation job compares ledger to provider reports; missing entries retried |
| Wallet balance drift | Reconciliation job opens a P1 ticket; manual review |
| Driver earning missing | Reconciliation job opens a P1 ticket |

## Acceptance Criteria

- All payment authorizations use idempotency keys (100% coverage).
- All captures happen within 5 minutes of the trigger event.
- All refunds happen within 1 hour of the trigger event.
- Wallet balance always matches the sum of ledger postings for that
  wallet (verified by reconciliation daily).
- 100% of money movement events have a matching `ledger.posted.v1`.
- Each eligible trip produces exactly one credit (one
  `trip.reward.granted.v1` → one wallet credit + one ledger posting);
  reversals are separate postings, not UPDATE.
- Wallet credit balance per customer always equals the sum of
  `2100_customer_credit_liability` postings for that customer
  (reconciled daily).
