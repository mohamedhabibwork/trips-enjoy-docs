# Refund Workflows

Refunds are critical financial operations. They MUST be idempotent,
auditable, and reconcilable. Reflects the **20-service architecture**
consolidated 2026-08-05 per
[ADR-0017](../architecture/adrs/0017-20-service-architecture.md):
refunds are coordinated by `payment-service` (which absorbed both
the ride and food payment sagas and the wallet).

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
    participant SAGA as payment-service (food saga)
    participant PAY as payment-service
    participant LD as ledger-service
    participant NOT as notification-service
    participant C as Customer

    FOR->>SAGA: order.cancelled.v1 (reason)
    SAGA->>PAY: refund(capture_id, amount, Idempotency-Key=request:{request_id}:refund:reason)
    PAY->>EXT: refund
    EXT-->>PAY: refund_id
    PAY->>LD: post(refund)
    LD-->>PAY: ledger.posted.v1
    PAY-->>SAGA: payment.refund.completed.v1
    SAGA->>PAY: debit wallet (if credited)
    SAGA->>NOT: notify customer
    NOT-->>C: push: "Refund processed"
```

## Workflow: Support-Initiated Refund

```mermaid
sequenceDiagram
    participant C as Customer
    participant ADM as admin-service (support module)
    participant SAGA as payment-service (saga)
    participant PAY as payment-service
    participant LD as ledger-service
    participant AUD as audit-service
    participant NOT as notification-service

    C->>ADM: open ticket (order_id, issue)
    ADM->>ADM: agent reviews
    ADM->>SAGA: POST /v1/refunds (order_id, amount, reason)
    SAGA->>PAY: refund (Idempotency-Key=ticket:T:refund:N)
    PAY-->>SAGA: payment.refund.completed.v1
    SAGA->>LD: post(refund)
    LD-->>SAGA: ledger.posted.v1
    SAGA->>AUD: refund.completed.v1 (audit)
    SAGA->>NOT: notify customer
    NOT-->>C: push: "Refund processed"
    SAGA-->>ADM: 200 OK
```

The agent's identity, ticket id, and refund reason are recorded in
the audit log.

## Workflow: Partial Refund (Quality)

```mermaid
sequenceDiagram
    participant C as Customer
    participant ADM as admin-service (support module)
    participant SAGA as payment-service (saga)
    participant PAY as payment-service (merchant settlement)
    participant AUD as audit-service

    C->>ADM: open ticket (order_id, missing items)
    ADM->>ADM: agent reviews photos
    ADM->>SAGA: POST /v1/refunds (order_id, amount=item_total)
    SAGA-->>ADM: ok
    SAGA->>PAY: reduce restaurant payable (per policy)
    PAY-->>SAGA: ok
    SAGA->>AUD: refund.partial.v1 (audit)
```

## Workflow: Refund to Wallet (instead of original method)

```mermaid
sequenceDiagram
    participant C as Customer
    participant ADM as admin-service (support module)
    participant SAGA as payment-service (saga)
    participant PAY as payment-service (wallet)
    participant LD as ledger-service

    C->>ADM: "refund to wallet, not card"
    ADM->>SAGA: POST /v1/refunds (target=wallet)
    SAGA->>PAY: credit
    PAY-->>SAGA: ok
    SAGA->>PAY: do not refund provider (closed-loop)
    SAGA->>LD: post(wallet_credit, refund_pending)
    LD-->>SAGA: ok
    SAGA-->>ADM: 200 OK
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
    participant ADM as admin-service (support module)
    participant AUD as audit-service

    EXT->>PAY: webhook (charge.dispute.created)
    PAY->>ADM: open ticket (P1) (via support.admin)
    PAY->>LD: post(disputed)
    ADM->>PAY: agent decision
    alt accept dispute
        PAY->>EXT: accept
        EXT-->>PAY: refund processed
        PAY->>LD: post(refund)
        PAY->>PAY: debit wallet (if credited)
        PAY->>AUD: refund.chargeback_accepted.v1
    else contest
        PAY->>EXT: submit evidence
        EXT->>PAY: decision later
    end
```

## Workflow: Refund Failure (Provider Timeout)

```mermaid
sequenceDiagram
    participant SAGA as payment-service (saga)
    participant PAY as payment-service
    participant ADM as admin-service (support module)
    participant LD as ledger-service
    participant NOT as notification-service
    participant C as Customer

    SAGA->>PAY: refund
    PAY->>EXT: refund (timeout)
    PAY->>PAY: retry with backoff (3 attempts)
    alt success
        PAY-->>SAGA: ok
    else persistent failure
        PAY-->>SAGA: refund.failed.v1
        SAGA->>ADM: open ticket (P1) (via support.admin)
        SAGA->>LD: post(refund_pending, manual)
        SAGA->>NOT: notify customer
        NOT-->>C: "Refund delayed, we're working on it"
    end
```

Manual refunds are processed by the finance team; they update the
ledger entry to `manual_refund` once the provider confirms.

## Idempotency in Refunds

Every refund call carries an `Idempotency-Key`:

| Pattern | Example |
|---------|---------|
| Auto refund on cancel | `request:{request_id}:refund:cancel` |
| Auto refund on reject | `request:{request_id}:refund:reject` |
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


## Conductor — Refund Orchestration

All refund flows run on Netflix Conductor per
[ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.3.

The 6 refund categories are encoded as 6 workflow definitions:

| Category | Workflow ID | Compensation steps |
|---|---|---|
| Standard refund | `wf.refund.standard.v1` | 5 (reverse) |
| Partial refund | `wf.refund.partial.v1` | 5 (reverse) |
| Food-order rejection | `wf.refund.food_reject.v1` | 6 (reverse) |
| Cancellation | `wf.refund.cancellation.v1` | 5 (reverse) |
| Dispute (chargeback) | `wf.refund.dispute.v1` | 7 (reverse, includes chargeback path) |
| COD failure | `wf.refund.cod_failed.v1` | 4 (reverse) |

The owner is `payment-service`. Workers run in `payment-service`,
`ledger-service`, `notification-service`, and `customer-service`. Each
worker's task list, idempotency-key namespace, and compensation is
documented in that service's `INTEGRATION.md` "Conductor Workers".
The canonical Kafka signal mapping lives in
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.3 and 6.

The legacy in-service saga pattern (per [ADR-0010](../architecture/adrs/0010-saga-pattern.md))
is **not** used for refunds; Conductor's `compensationSteps` is the
single source of truth for rollback ordering.

The compensation matrix in [`architecture/FAILURE_HANDLING.md`](../architecture/FAILURE_HANDLING.md)
remains authoritative for the forward step ↔ compensation action
pairing (e.g. `payment.captured` → `payment.refund`); Conductor simply
executes the matrix.

## Related docs

- [`../SERVICE_INTEGRATION_MATRIX.md`](../SERVICE_INTEGRATION_MATRIX.md) — service × event × dependency matrix
- [`../architecture/EVENT_ARCHITECTURE.md`](../architecture/EVENT_ARCHITECTURE.md) — event catalog and delivery semantics
- [`../architecture/SERVICE_ISOLATION.md`](../architecture/SERVICE_ISOLATION.md) — downstream failure handling per class
