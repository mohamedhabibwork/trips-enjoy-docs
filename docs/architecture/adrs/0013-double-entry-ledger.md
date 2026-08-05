# ADR-0013: Double-Entry Ledger for Financial State

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: finance, ledger, double-entry, audit, money, compliance

## Context and Problem Statement

Money moves on this platform every second: a customer pays for a
ride; a driver earns; a merchant gets a settlement; a courier gets
a delivery fee; a wallet is credited and debited; a refund is
issued. The platform must (a) keep the books correctly under all
conditions (no money created, no money lost, every penny traceable
to a business event), (b) prove correctness to auditors and
regulators (PCI-DSS, SOC 2, regional regulators), (c) reconstruct
any balance at any point in time (forensics, customer disputes,
legal hold), and (d) reconcile with the payment provider, the
bank, and the merchant settlement system.

The naive approach — a `balance` column on a wallet table, updated
in place — fails every one of these. We need a data model that
makes the invariant "the books balance" a structural property of
the data, not a runtime check. The choice is between a
**double-entry ledger** (every money movement is a balanced pair of
postings to accounts; the books balance by construction), a
**single-entry ledger with a balance column** (one row per
movement, current balance maintained on the row), or **no ledger**
(the payment service maintains its own state; we trust the
provider).

## Decision Drivers

- **Invariant**: for every money movement, debits = credits across
  all accounts. This must hold under all conditions (crash,
  partial failure, replay).
- **Auditability**: every posting is a row in an append-only table
  with a reference to the business event that caused it. Auditors
  can reconstruct any balance at any point in time.
- **Reconciliation**: the ledger is the source of truth for the
  platform's money. The reconciliation job in `reporting-service`
  compares the ledger to the payment provider's records, the
  bank, and the merchant settlement system; drift opens a ticket.
- **Multi-currency**: every monetary row carries a `currency`
  column (ISO 4217); conversion is an explicit operation, not
  implicit.
- **7-year retention** (financial regulation; in some jurisdictions
  10 years). The ledger is append-only; rows are never updated
  or deleted.
- **Idempotency**: a replay of the same business event produces
  the same posting. The posting is keyed on
  `(account_id, posting_id)` so a re-run is a no-op.
- **Regulatory compliance**: PCI-DSS, SOC 2, regional regulators
  (PDPL, NDMO, DPA). The ledger's append-only nature is the
  foundation of the audit trail.

## Considered Options

- **Double-entry ledger** — every money movement is a balanced
  pair of postings; the books balance by construction.
- **Single-entry ledger with a balance column** — one row per
  movement; current balance maintained on a row.
- **No ledger (trust the payment provider)** — the payment
  service is the source of truth; we mirror the provider's
  state.
- **Event-sourced money** — money is an event log; balance is
  derived.

## Decision Outcome

Chosen option: "**Double-entry ledger**", because (a) the
invariant "debits = credits" is a structural property of the data
— every posting row has a `debit_minor` and a `credit_minor` that
sum to zero across the postings for one business event, and the
books balance by construction, (b) the append-only nature gives
us 7-year (in some jurisdictions 10-year) retention with no
in-place updates, (c) the audit trail is the ledger itself — every
posting is a row with a reference to the business event, the
account, the amount, the currency, and the timestamp, (d)
reconciliation is a comparison between the ledger's postings and
the provider's records, and (e) the regulator-facing question "can
you reconstruct any balance at any point in time?" is answered by
"sum the postings up to that timestamp."

`ledger-service` is the only service authorized to post to the
ledger. It is a pure persistence service: it consumes money-movement
events (`payment.captured.v1`, `wallet.credited.v1`,
`restaurant.settlement.accrued.v1`, etc.) and writes balanced
postings; it exposes a `GET /v1/accounts/{account_id}/balance` and
`GET /v1/accounts/{account_id}/postings` API. Every other service
that touches money (the saga orchestrators
`ride-payment-integration-service` and
`food-payment-integration-service`, `payment-service`,
`wallet-service`, `driver-earnings-service`, `courier-earnings-service`,
`restaurant-settlement-service`) eventually writes to the ledger
via the saga's final step.

### Consequences

- Good: The invariant "debits = credits" is structural. Every
  posting pair sums to zero; the books balance by construction.
- Good: Append-only. Rows are never updated or deleted. Retention
  is 7-10 years per regulation.
- Good: Audit trail is the ledger itself. Every posting is a row
  with a reference to the business event, the account, the amount,
  the currency, and the timestamp.
- Good: Reconstruction. "What was the driver's wallet balance on
  March 15 at 10:42 UTC?" is a `SUM(debit_minor - credit_minor)
  WHERE account_id = ? AND posted_at <= ?` query.
- Good: Reconciliation. The reconciliation job in
  `reporting-service` compares the ledger to the payment provider,
  the bank, and the merchant settlement system; drift opens a
  ticket.
- Good: Multi-currency. Every monetary row carries a `currency`
  column; conversion is an explicit operation, not implicit.
- Good: Idempotency. The posting is keyed on
  `(account_id, posting_id)`; a replay is a no-op.
- Good: Regulator-facing. PCI-DSS, SOC 2, and regional regulators
  (PDPL, NDMO, DPA) all require an append-only financial record;
  the ledger is that record.
- Bad: Double-entry is more complex than a single-entry table. We
  must teach the team the conventions (debit/credit, accounts,
  postings) and enforce them in code review. (Mitigation: a
  per-service guide; a CI lint that asserts every money-movement
  event has a corresponding balanced pair of postings.)
- Bad: The ledger is a hot path. Every `payment.captured.v1`
  triggers a ledger write. We mitigate with a dedicated
  `ledger` Postgres schema (and physical isolation for the
  noisiest services), with the inbox pattern for at-least-once
  consumption, and with the idempotency key on
  `(account_id, posting_id)`.
- Bad: Every new money flow requires a new account type and a
  documented posting pair. (Mitigation: a chart of accounts in
  `ledger-service`'s `README.md`; a per-flow saga spec.)
- Neutral: We accept that the ledger is the only source of truth
  for the platform's money; the `wallet.balance` column is a
  cache that is reconciled against the ledger.

### Confirmation

- 100% of money-movement events have a corresponding balanced
  pair of postings in the ledger; verified by a reconciliation
  job in `reporting-service` that runs hourly.
- The invariant `SUM(debit_minor) = SUM(credit_minor)` holds
  for every business event in the last 7 years; verified by a
  nightly integrity check.
- Reconstruction: "what was the merchant's payable balance on
  date X at time T" is answerable in < 1 second from the
  ledger; verified by a quarterly drill.
- Append-only: 0 UPDATEs and 0 DELETEs on the `ledger.postings`
  table in the last 365 days; enforced by a Postgres trigger
  and a CI lint.
- Reconciliation: the hourly reconciliation job compares the
  ledger to the payment provider, the bank, and the merchant
  settlement system; drift opens a ticket within 1 hour.

## Pros and Cons of the Options

### Double-entry ledger

The chosen option. Every money movement is a balanced pair of
postings to accounts. The books balance by construction.

- Good: The invariant is structural.
- Good: Append-only; 7-10 year retention with no in-place
  updates.
- Good: Audit trail is the ledger.
- Good: Reconstruction at any point in time.
- Good: Reconciliation is a comparison.
- Good: Multi-currency; explicit conversion.
- Good: Idempotency via `(account_id, posting_id)`.
- Good: Regulator-facing.
- Bad: More complex than a single-entry table; the team must
  learn the conventions.
- Bad: Hot path; every money event triggers a write.
- Bad: Every new flow requires a new account type and a
  documented posting pair.

### Single-entry ledger with a balance column

One row per movement; current balance on a row.

- Good: Simpler schema.
- Good: Balance is a single column read.
- Bad: The invariant "debits = credits" is a runtime check,
  not a structural property; a bug can desynchronize the
  balance from the movements.
- Bad: In-place updates of the balance column break the
  audit trail; forensics requires a separate history table.
- Bad: Reconstruction is "what was the balance at time T?"
  — answerable only if we have a history table or a CDC log.
- Bad: Reconciliation is harder; we cannot sum postings to
  verify the balance.

### No ledger (trust the payment provider)

The payment service is the source of truth; we mirror the
provider's state.

- Good: No ledger to build.
- Good: The provider is the regulator-facing party.
- Bad: We do not own the provider's data model; we cannot
  reconstruct balances across providers (we have multiple
  payment providers per region).
- Bad: The provider's data is not under our control; we cannot
  audit it.
- Bad: The provider's data is not a multi-currency, multi-account
  ledger; it is a payment-intent log.
- Bad: Forensics ("where did this money go?") requires calling
  the provider, which is slow and rate-limited.

### Event-sourced money

Money is an event log; balance is derived.

- Good: All state is in the events; the event log is the
  source of truth.
- Good: Reconstruction is "fold the events."
- Bad: Folded balance is not the same as a ledger; we still
  need a way to assert that the events balance.
- Bad: We already have the events (Kafka); we still need a
  ledger to make the invariant structural.
- Bad: Event-sourced money is harder to reconcile with the
  provider; the provider's data is not event-sourced.

## References

- [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md) —
  the "money is conserved" invariant and how the ledger
  enforces it.
- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — the
  money-movement events that the ledger consumes.
- [`DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md) —
  the `ledger` schema; money conventions
  (`amount_minor BIGINT`, `currency CHAR(3)`); retention
  (10 years); append-only.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) —
  `ledger-service` and its consumers/producers.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) —
  PCI-DSS, SOC 2, audit.
- ADR-0009 — outbox pattern, which the saga uses to commit
  the saga state and the `ledger.posted.v1` event
  atomically.
- ADR-0010 — saga pattern, which orchestrates the trip
  completion → payment capture → driver earning → ledger
  posting flow.
- Luca Pacioli, *Summa de Arithmetica* (1494) — the original
  double-entry bookkeeping treatise.
