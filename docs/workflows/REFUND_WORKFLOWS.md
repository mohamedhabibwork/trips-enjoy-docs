# Refund Workflows

Refunds are critical financial operations. They MUST be idempotent,
auditable, and reconcilable.

> For the **accounting view** of refunds (`6200_refunds` expense
> recognition; revenue reversal; closed-loop wallet debit;
> reconciliation drift) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Expense Recognition".

## Refund Categories

| Category | Trigger | Compensation |
|----------|---------|--------------|
| Full refund (cancellation) | Customer cancels before restaurant accept; restaurant rejects; trip cancellation before pickup | 100% of authorized amount |
| Partial refund (cancellation fee) | Customer cancels after accept but before courier en route | Authorized amount minus cancellation fee |
| Quality refund | Wrong / missing items, food quality | Configurable % or amount per policy |
| Goodwill refund | Customer support decision | Up to a per-agent limit |
| Provider-initiated refund | Chargeback won by customer | Full amount |
| Settlement reversal | Restaurant found in violation | Up to the entire settlement |

## Workflow: Auto Refund on Cancellation

```mermaid
sequenceDiagram
    participant FOR as food-order-service
    participant FPI as food-payment-integration-service
    participant PAY as payment-service
    participant LD as ledger-service
    participant WLT as wallet-service
    participant NOT as notification-service
    participant C as Customer

    FOR->>FPI: order.cancelled.v1 (reason)
    FPI->>PAY: refund(capture_id, amount, Idempotency-Key=order:O:refund:reason)
    PAY->>EXT: refund
    EXT-->>PAY: refund_id
    PAY->>LD: post(refund)
    LD-->>PAY: ledger.posted.v1
    PAY-->>FPI: payment.refund.completed.v1
    FPI->>WLT: debit (if credited)
    FPI->>NOT: notify customer
    NOT-->>C: push: "Refund processed"
```

## Workflow: Support-Initiated Refund

```mermaid
sequenceDiagram
    participant C as Customer
    participant SUP as support-service
    participant FPI as food-payment-integration-service
    participant PAY as payment-service
    participant LD as ledger-service
    participant AUD as audit-service
    participant NOT as notification-service

    C->>SUP: open ticket (order_id, issue)
    SUP->>SUP: agent reviews
    SUP->>FPI: POST /v1/refunds (order_id, amount, reason)
    FPI->>PAY: refund (Idempotency-Key=ticket:T:refund:N)
    PAY-->>FPI: payment.refund.completed.v1
    FPI->>LD: post(refund)
    LD-->>FPI: ledger.posted.v1
    FPI->>AUD: refund.completed.v1 (audit)
    FPI->>NOT: notify customer
    NOT-->>C: push: "Refund processed"
    FPI-->>SUP: 200 OK
```

The agent's identity, ticket id, and refund reason are recorded in
the audit log.

## Workflow: Partial Refund (Quality)

```mermaid
sequenceDiagram
    participant C as Customer
    participant SUP as support-service
    participant FPI as food-payment-integration-service
    participant RSM as restaurant-settlement-service
    participant AUD as audit-service

    C->>SUP: open ticket (order_id, missing items)
    SUP->>SUP: agent reviews photos
    SUP->>FPI: POST /v1/refunds (order_id, amount=item_total)
    FPI-->>SUP: ok
    FPI->>RSM: reduce restaurant payable (per policy)
    RSM-->>FPI: ok
    FPI->>AUD: refund.partial.v1 (audit)
```

## Workflow: Refund to Wallet (instead of original method)

```mermaid
sequenceDiagram
    participant C as Customer
    participant SUP as support-service
    participant FPI as food-payment-integration-service
    participant WLT as wallet-service
    participant PAY as payment-service
    participant LD as ledger-service

    C->>SUP: "refund to wallet, not card"
    SUP->>FPI: POST /v1/refunds (target=wallet)
    FPI->>WLT: credit
    WLT-->>FPI: ok
    FPI->>PAY: do not refund provider (closed-loop)
    FPI->>LD: post(wallet_credit, refund_pending)
    LD-->>FPI: ok
    FPI-->>SUP: 200 OK
```

Closed-loop wallet refunds are allowed when:

- The original payment was authorized < 90 days ago.
- The original method is no longer valid (expired card).
- Customer explicitly requests it.

## Workflow: Provider-Initiated Refund (Chargeback Won)

```mermaid
sequenceDiagram
    participant EXT as Payment Provider
    participant PAY as payment-service
    participant LD as ledger-service
    participant WLT as wallet-service
    participant SUP as support-service
    participant AUD as audit-service

    EXT->>PAY: webhook (charge.dispute.created)
    PAY->>SUP: open ticket (P1)
    PAY->>LD: post(disputed)
    SUP->>PAY: agent decision
    alt accept dispute
        PAY->>EXT: accept
        EXT-->>PAY: refund processed
        PAY->>LD: post(refund)
        PAY->>WLT: debit (if credited)
        PAY->>AUD: refund.chargeback_accepted.v1
    else contest
        PAY->>EXT: submit evidence
        EXT->>PAY: decision later
    end
```

## Workflow: Refund Failure (Provider Timeout)

```mermaid
sequenceDiagram
    participant FPI as food-payment-integration-service
    participant PAY as payment-service
    participant SUP as support-service
    participant LD as ledger-service
    participant NOT as notification-service
    participant C as Customer

    FPI->>PAY: refund
    PAY->>EXT: refund (timeout)
    PAY->>PAY: retry with backoff (3 attempts)
    alt success
        PAY-->>FPI: ok
    else persistent failure
        PAY-->>FPI: refund.failed.v1
        FPI->>SUP: open ticket (P1)
        FPI->>LD: post(refund_pending, manual)
        FPI->>NOT: notify customer
        NOT-->>C: "Refund delayed, we're working on it"
    end
```

Manual refunds are processed by the finance team; they update the
ledger entry to `manual_refund` once the provider confirms.

## Idempotency in Refunds

Every refund call carries an `Idempotency-Key`:

| Pattern | Example |
|---------|---------|
| Auto refund on cancel | `order:<order_id>:refund:cancel` |
| Auto refund on reject | `order:<order_id>:refund:reject` |
| Support refund | `ticket:<ticket_id>:refund:<N>` |
| Chargeback | `chargeback:<chargeback_id>:refund` |

The `(payment_id, idempotency_key)` pair is unique in the
`payment.refunds` table; replays return the original result.

## Compensation / Rollback

Refunds don't have compensations in the strict sense — they ARE the
compensation. But there are edge cases:

- **Refund issued by mistake**: a new "clawback" transaction debits
  the wallet and re-captures. Allowed only within 24h of the refund
  and requires admin approval.
- **Refund fails after wallet was debited**: the wallet is
  re-credited (compensation).

## Audit

Every refund emits `payment.refund.initiated.v1` and
`payment.refund.completed.v1` (or `.failed.v1`). These are persisted
in `audit-service` with:

- Actor (system / support agent id)
- Reason
- Original payment id
- Refund amount
- Idempotency key
- Provider reference

## Acceptance Criteria

- 100% of refunds are idempotent.
- 100% of refunds are recorded in the audit log.
- 99% of refunds are processed by the provider within 5 minutes.
- 100% of failed refunds open a P1 ticket within 1 minute.
- 100% of refunds have a corresponding `ledger.posted.v1` (or
  `manual_refund` flag for pending cases).
