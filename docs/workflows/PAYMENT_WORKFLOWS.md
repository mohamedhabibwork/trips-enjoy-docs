# Payment Workflows

The financial flows cut across ride-hailing and food delivery. This
document consolidates them. Reflects the **20-service architecture**
consolidated 2026-08-05 per
[ADR-0017](../architecture/adrs/0017-20-service-architecture.md):
ride / food sagas, wallet, driver / courier earnings, restaurant
settlement, and COD are all owned by `payment-service`.

> For the **accounting view** (tax recognition & remittance; gross-to-net
> driver / courier income; marketplace VAT; CIT & regulatory fees; expense
> recognition — incentives, refunds, opex, chargebacks; reconciliation
> & period close) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md).

## Actors and Services

| Actor | Services |
|-------|----------|
| Customer | `payment-service` (intents, wallet, saga, refunds) |
| Driver/Courier | `payment-service` (earnings, withdrawals) |
| Restaurant/Merchant | `payment-service` (merchant payable, payouts, disputes, COD) |
| System | `payment-service`, `ledger-service`, `fraud-risk-service` |

## Workflow: Authorize → Capture (Card)

```mermaid
sequenceDiagram
    participant SAGA as payment-service (saga)
    participant PAY as payment-service
    participant FR as fraud-risk-service
    participant EXT as Payment Provider
    participant LD as ledger-service
    participant NOT as notification-service

    SAGA->>PAY: authorize(amount, payment_method_token, Idempotency-Key=order:O:auth)
    PAY->>FR: score(transaction)
    FR-->>PAY: risk_score
    alt risk high
        PAY-->>SAGA: payment.failed.v1 (code=RISK_DECLINED)
        SAGA->>NOT: notify customer
    else risk ok
        PAY->>EXT: authorize
        EXT-->>PAY: authorized (auth_id)
        PAY-->>SAGA: payment.authorized.v1
        Note over SAGA: later, on trip/delivery completion
        SAGA->>PAY: capture(auth_id, Idempotency-Key=order:O:cap)
        PAY->>EXT: capture
        EXT-->>PAY: captured
        PAY->>LD: post(authorization_hold, capture)
        LD-->>PAY: ledger.posted.v1
        PAY-->>SAGA: payment.captured.v1
        SAGA->>PAY: credit (e.g. for restaurant payable or driver earning)
    end
```

## Workflow: Refund

```mermaid
sequenceDiagram
    participant SAGA as payment-service (saga)
    participant PAY as payment-service
    participant EXT as Payment Provider
    participant LD as ledger-service
    participant NOT as notification-service

    SAGA->>PAY: refund(capture_id, amount, reason, Idempotency-Key=order:O:refund)
    PAY->>EXT: refund
    EXT-->>PAY: refund_id
    PAY->>LD: post(refund)
    LD-->>PAY: ledger.posted.v1
    PAY-->>SAGA: payment.refund.completed.v1
    SAGA->>PAY: debit (if credited)
    SAGA->>NOT: notify customer
    NOT-->>C: push: "Refund processed"
```

## Workflow: Cash on Delivery (COD)

```mermaid
sequenceDiagram
    participant CR as courier
    participant COS as courier-service (delivery)
    participant PAY as payment-service
    participant LD as ledger-service

    CR->>COS: POST /v1/orders/{id}/cod/mark-collected (amount)
    COS->>PAY: payment.cod.collected.v1
    PAY->>LD: post(cash_receivable, courier_cash)
    LD-->>PAY: ledger.posted.v1
    PAY->>PAY: schedule payable (less cash handling fee)
    PAY-->>COS: cash.reconciled.v1
```

COD is **only** available when the merchant is configured to allow
it (`restaurant.cod_enabled = true` in `configuration-service`). COD
amounts are reconciled daily.

## Workflow: Driver / Courier Earning Accrual

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant PAY as payment-service
    participant LD as ledger-service
    participant NOT as notification-service

    PAY->>PAY: accrue(request_id, amount, Idempotency-Key=request:{request_id}:earn)
    PAY->>PAY: insert earning
    PAY->>LD: post(driver_payable, platform_payable)
    LD-->>PAY: ledger.posted.v1
    PAY-->>TR: driver.earning.accrued.v1

    Note over TR,PAY: reward-grant contribution (independent of capture)
    TR-->>PAY: trip.reward.granted.v1 (driver_topup_minor, snapshot)
    PAY->>PAY: apply top-up → earning = base + topup
    PAY-->>LD: post(6302_guaranteed_minimum ↔ driver_payable)
    LD-->>PAY: ledger.posted.v1

    Note over PAY: optional, weekly transfer to driver's bank
    PAY->>PAY: hold(amount)
    PAY-->>PAY: held
    PAY->>PAY: payout
    PAY-->>PAY: payout.completed
    PAY->>PAY: release
    PAY-->>PAY: released
    PAY-->>DR: driver.withdrawal.completed.v1
```

## Workflow: Restaurant Settlement

```mermaid
sequenceDiagram
    participant PAY as payment-service
    participant LD as ledger-service
    participant RES as restaurant-service

    Note over PAY: daily / weekly cron
    PAY->>PAY: aggregate merchant payable
    PAY->>LD: post(merchant_payable, platform_commission)
    LD-->>PAY: ledger.posted.v1
    PAY->>PAY: payout to merchant bank
    PAY-->>PAY: payout.completed
    PAY->>RES: merchant.payout.completed.v1
    RES-->>RES: notify merchant
```

## Workflow: Tip Adjustment

```mermaid
sequenceDiagram
    participant C as Customer
    participant SAGA as payment-service (saga)
    participant PAY as payment-service

    C->>SAGA: POST /v1/orders/{id}/tip (amount)
    SAGA->>PAY: charge tip
    PAY-->>SAGA: ok
    SAGA->>PAY: accrue_tip
    PAY-->>SAGA: ok
    SAGA-->>C: 200 OK
```

Tips are added to the next earning accrual; they do not modify the
already-captured base amount.

## Workflow: Wallet Top-up

The customer wallet has **two distinct credit paths** — both routed
through `payment-service` (which absorbed `wallet-service`) and
posted to `ledger-service` as
`2100_customer_credit_liability` ↔ `cash`, but triggered by different
events.

1. **Customer-initiated top-up** — the wallet holder funds their
   wallet from a payment method (see diagram below).
2. **Reward consumer path** — `trip-service` emits
   `trip.reward.granted.v1` on trip completion when the customer is
   eligible for a credit (loyalty tier, promo, or issue resolution);
   `payment-service` consumes the event and credits the wallet
   idempotently on `grant_id` (no payment-method authorization is
   involved). Reversals on trip correction emit
   `trip.reward.reversed.v1` and produce a wallet **debit** (new
   posting, not UPDATE).

```mermaid
sequenceDiagram
    participant C as Customer
    participant PAY as payment-service (wallet)
    participant LD as ledger-service

    C->>PAY: POST /v1/wallets/me/topup (amount, payment_method)
    PAY->>PAY: authorize + capture
    PAY-->>PAY: payment.captured.v1
    PAY->>LD: post(wallet_credit, cash)
    LD-->>PAY: ledger.posted.v1
    PAY-->>C: 200 OK
```

## Workflow: Reward Reversal (Trip Correction)

When a previously-completed trip is cancelled after-the-fact, corrected
by a support agent, or invalidated by fraud-review,
`trip-service` emits `trip.reward.reversed.v1`. The reversal fans out
to the same consumers as the grant and produces **new postings** — a
ledger-service `UPDATE`/`DELETE` is rejected at the database trigger
level. For the customer side, `payment-service` debits the wallet
(the original credit posting remains, but a new negative posting
offsets it). For the driver side, the embedded earnings ledger issues
an accrual correction against the same `driver_payable` account.

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant PAY as payment-service
    participant LD as ledger-service
    participant NOT as notification-service

    TR->>TR: trip cancelled / corrected / fraud-invalid
    TR-->>PAY: trip.reward.reversed.v1 (driver_topup_minor)
    TR-->>PAY: trip.reward.reversed.v1 (customer_credit_minor)
    TR-->>NOT: notify driver + customer

    Note over PAY: ledger is append-only; reversals are NEW postings
    PAY-->>LD: post NEW ROW: driver_payable ↔ 6302_guaranteed_minimum (negative)
    LD-->>PAY: ledger.posted.v1
    PAY-->>LD: post NEW ROW: cash ↔ 2100_customer_credit_liability (negative)
    LD-->>PAY: ledger.posted.v1
    PAY->>PAY: debit customer wallet (idempotent on reversal_id)
    Note over LD: ledger-service blocks UPDATE/DELETE on ledger.postings
```

The reversal idempotency key is `request:{request_id}:reward:reversal`; a
duplicate reversal for the same trip is a no-op (the inbox table on
each consumer dedupes by `event_id`, and the consumer handler is
idempotent on reversal_id). For the accounting view see
[`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) "Workflow:
Guaranteed Rewards — Driver Top-Up + Customer Credit".

## Workflow: Disputed Charge

```mermaid
sequenceDiagram
    participant EXT as Payment Provider
    participant PAY as payment-service
    participant ADM as admin-service (support module)
    participant LD as ledger-service
    participant NOT as notification-service

    EXT->>PAY: webhook (charge.dispute.created)
    PAY->>ADM: open ticket (via support.admin scope)
    PAY->>LD: post(disputed, pending)
    LD-->>PAY: ok
    ADM->>PAY: pause settlement
    ADM->>ADM: agent investigates
    alt dispute won
        ADM->>PAY: accept dispute
        PAY->>LD: post(dispute_lost, refund)
    else dispute lost
        ADM->>PAY: contest (evidence)
        PAY->>EXT: submit evidence
    end
```

## Workflow: Idempotency in Practice

Every money-movement call carries an idempotency key. Examples:

| Action | Key pattern |
|--------|-------------|
| Authorize a ride payment | `request:{request_id}:payment:auth` |
| Capture a ride payment | `request:{request_id}:payment:cap` |
| Refund a ride payment | `request:{request_id}:payment:refund:<reason>` |
| Accrue driver earning | `request:{request_id}:earn` |
| Accrue courier earning | `request:{request_id}:earn` |
| Settle merchant payable | `merchant:<merchant_id>:payout:<payout_id>` |
| Wallet top-up | `request:{request_id}:wallet:topup` |
| Trip reward grant (driver + customer) | `request:{request_id}:reward:grant` |
| Trip reward reversal | `request:{request_id}:reward:reversal` |
| Tip | `request:{request_id}:tip:<request_id>` |
| COD collected | `request:{request_id}:cod` |

The key is unique per logical operation. Replays of the same key
return the original result without re-executing.

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Authorization fails (insufficient funds, expired card) | Customer notified; alternative payment method requested |
| Capture fails (rare) | Refund of any held authorization; ticket opened (via admin-service support module) |
| Provider timeout on authorize | Retry with backoff; on persistent failure, surface error |
| Refund fails | Manual intervention; ticket opened with P1 |
| Webhook lost | Reconciliation job compares ledger to provider reports; missing entries retried |
| Wallet balance drift | Reconciliation job opens a P1 ticket; manual review |
| Driver earning missing | Reconciliation job opens a P1 ticket |
| COD amount not posted | Reconciliation job (daily) flags the delivery as `cod_unreconciled` |

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
- Every COD order produces exactly one `payment.cod.collected.v1`
  within 60 seconds of `delivery.completed.v1`.