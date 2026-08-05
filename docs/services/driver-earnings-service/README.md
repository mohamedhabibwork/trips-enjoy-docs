# driver-earnings-service

## 1. Purpose

`driver-earnings-service` owns the **driver earnings ledger** and
the **withdrawal** flow. It records every earning the driver
earns, maintains a withdrawable balance, and orchestrates
withdrawals to the driver's bank account.

## 2. Bounded Context

Bounded context: **Driver Earnings**.

In scope:

- The driver earnings ledger.
- The withdrawable balance.
- Withdrawal requests (to bank, to wallet).
- Statements.
- Bank details management.
- Tips (added to the next earning).

Out of scope (explicitly):

- The trip aggregate — `trip-service`.
- Card capture — `payment-service`.
- Wallet balance — `wallet-service`.
- Driver profile — `driver-service`.

## 3. Responsibilities

- Accrue an earning on `ride.payment.completed.v1` or
  `trip.completed.v1` (the latter for tips or bonuses).
- Maintain a running balance per driver.
- Allow the driver to request a withdrawal to a bank account.
- Cooperate with `payment-service` for the payout and
  `wallet-service` for the hold/release.
- Cooperate with `ledger-service` for the double-entry posting.
- Emit `driver.earning.accrued.v1`,
  `driver.withdrawal.requested.v1`, and
  `driver.withdrawal.completed.v1`.
- Cooperate with `ride-payment-integration-service` for penalty
  postings.
- **Consume `trip.reward.granted.v1`** from `trip-service`; accrue
  the driver-side guaranteed top-up (`per_trip_topup`,
  `hourly_topup`, `daily_topup`) as a new earning `type =
  guaranteed_topup`. The grant's id+version are recorded in the
  earning row for audit; the idempotency key is
  `trip:{trip_id}:reward:driver:grant`.
- **Consume `trip.reward.reversed.v1`** from `trip-service`; post a
  `type=correction` earning row that pays back the original grant
  (no UPDATE/DELETE — the new row is the authoritative record, per
  the reversal rule from the accounting four-layer truth model).
- Expose `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`
  to `trip-service` so the period-floor evaluation reads the same
  window math (rolling 60-min and 24-h windows).

## 4. Explicitly NOT Owned

- The trip aggregate.
- Card capture.
- Wallet balance.
- Driver profile.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver app | system | read balance, request withdrawal, manage bank details |
| `ride-payment-integration-service` | system | accrue earning, post penalty |
| `payment-service` | system | payout to bank |
| `wallet-service` | system | hold / release |
| `ledger-service` | system | post |
| `notification-service` | system | notify driver on accrual / withdrawal |
| `admin-service` | system | read; force-adjust with reason |

## 6. Dependencies

### Synchronous (REST)

- `payment-service` — payout to bank — SLO 1s — circuit breaker:
  yes.
- `wallet-service` — hold / release — SLO 300ms — circuit breaker:
  yes.
- `ledger-service` — post — SLO 300ms — circuit breaker: yes.
- `driver-service` — validate driver — SLO 100ms — circuit breaker:
  yes.

### Asynchronous (events consumed)

- `ride.payment.completed.v1` from `ride-payment-integration-service`
  — accrue the driver earning — duplicate handling: inbox dedup.
- `trip.completed.v1` from `trip-service` — accrue tips / bonuses
  that are attached to the trip — duplicate handling: inbox dedup.
- `payment.failed.v1` from `ride-payment-integration-service` — no
  accrual (the saga failed; the driver is not paid) — duplicate
  handling: inbox dedup.
- `configuration.updated.v1` from `configuration-service` — reload
  config.
- `trip.reward.granted.v1` from `trip-service` — accrue the driver
  top-up as `type=guaranteed_topup` (idempotency-key
  `trip:{trip_id}:reward:driver:grant`).
- `trip.reward.reversed.v1` from `trip-service` — post a
  `type=correction` earning that negates the original grant (new row;
  no UPDATE/DELETE).

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `driver_earnings`.
- Cache: Redis (per-service) for the hot balance read.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `driver_earnings` (owned exclusively by this service).
- Migrations: `services/driver-earnings-service/migrations/`.
- Soft delete: no (the ledger is append-only).
- Partitioning: yes — `driver_earnings.earnings` is
  range-partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/earnings/balance | bearer (driver) | current withdrawable balance |
| GET | /v1/earnings/today | bearer (driver) | today's earnings |
| GET | /v1/earnings/week | bearer (driver) | this week's earnings |
| GET | /v1/earnings/statement?from=…&to=… | bearer (driver) | statement |
| POST | /v1/earnings/withdrawals | bearer (driver) | request a withdrawal |
| GET | /v1/earnings/withdrawals/{id} | bearer (driver) | read a withdrawal |
| GET | /v1/earnings/bank | bearer (driver) | read bank details |
| PATCH | /v1/earnings/bank | bearer (driver) | update bank details |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `driver.earning.accrued.v1` | on accrual | `ride-history-service`, `reporting-service` |
| `driver.withdrawal.requested.v1` | on withdrawal request | `payment-service` (payout), `audit-service` |
| `driver.withdrawal.completed.v1` | on payout success | `audit-service`, `notification-service` |
| `driver.withdrawal.failed.v1` | on payout failure | `support-service`, `notification-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `ride.payment.completed.v1` | `ride-payment-integration-service` | accrue | insert earning row |
| `trip.completed.v1` | `trip-service` | accrue tips / bonuses | insert earning row |
| `payment.failed.v1` | `ride-payment-integration-service` | no accrual | (informational; no earning) |
| `configuration.updated.v1` | `configuration-service` | reload | cache invalidation |
| `trip.reward.granted.v1` | `trip-service` | accrue driver top-up as `type=guaranteed_topup` | insert earning row keyed on `trip:{trip_id}:reward:driver:grant`; inbox dedup on `grant_event_id` |
| `trip.reward.reversed.v1` | `trip-service` | post `type=correction` for the reversal | insert reversal earning row (new row in `driver_earnings.earnings`; never UPDATE/DELETE the original grant earning) |

## 12. External Integrations

- `payment-service` (in-cluster) for payouts.
- No external bank integration (the payment-service abstracts it).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `driver_earnings.commission_pct` | int | configuration-service | default 20% |
| `driver_earnings.withdrawal.min_minor.{currency}` | money | configuration-service | default 5000 minor |
| `driver_earnings.withdrawal.cooldown_hours` | int | configuration-service | default 24 |
| `driver_earnings.bank.max_per_driver` | int | configuration-service | default 3 |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: driver can read/write own earnings; admin can read and
  force-adjust with a reason.
- Secrets: Vault at `secret/driver_earnings/{env}/*`. Bank details
  are encrypted at rest (per-column encryption with a per-tenant
  KEK).
- PII: bank details, earning amounts, trip refs.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `driver_id`,
  `earning_id`, `route`, `latency_ms`, `status`.
- Metrics: `driver_earnings_accrued_total{city, ride_type}`,
  `driver_earnings_accrual_seconds` (histogram),
  `driver_withdrawals_total{city, status}`,
  `driver_withdrawal_seconds` (histogram),
  `driver_withdrawal_failure_total{reason}`,
  `driver_earnings_balance` (gauge, sampled).
- Traces: OpenTelemetry, root span per request.
- Health: `/health`, `/ready` (DB + Kafka + Redis), `/started`.

## 16. Scalability

- Replicas: 6 (default); HPA on CPU and on
  `driver_earnings_accrual_seconds_p99`.
- Hot path: accrual on `ride.payment.completed.v1`. The earnings
  table is partitioned by month; queries are efficient.
- The balance read is cached in Redis for 5s per driver.

## 17. Local Development

```bash
docker compose up driver-earnings-service postgres kafka redis
bun run --filter driver-earnings-service dev
```

Seed data: a default driver, a default earning, a default bank
detail.

## 18. Deployment

- Image: `registry.uber.io/driver-earnings-service:<sha>`.
- Replicas: 6 (HPA to 30).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.
- Partition maintenance: nightly.

## 20. Accounting impact

`driver-earnings-service` is the **operational owner of driver payable
accounting on the rides side**. It computes gross-to-net (gross fare
minus commission minus withholding tax, plus tips and incentives),
posts the corresponding payable / commission / withholding entries to
the ledger via `driver.earning.accrued.v1`, and orchestrates
withdrawals to the driver's bank on `driver.withdrawal.completed.v1`.

- **What money facts it owns:** driver earnings rows (`type = fare
  | tip | incentive | penalty | correction`), driver balances,
  withdrawals, bank details (encrypted).
- **Postings:** `driver_payable` (liability) ↔ `commission_revenue`
  + `tax_withheld_payable` + `tip_payable` (where applicable); at
  payout, `driver_payable` ↔ `cash`.
- **Tips:** commission-free by default (per-city overrides).
- **Penalties:** posted as negative `type=penalty` rows, not as
  reversals of the original fare.
- **Reconciliation:** daily against `ledger-service` (driver_payable
  account); drift opens a P1 ticket.
- **Human operator path:** admin adjustments via `support-service` /
  `admin-service`; force-payout requires `driver-earnings.admin` role.
- **Guaranteed reward top-ups (driver-side):** on
  `trip.reward.granted.v1`, this service posts a new
  `type=guaranteed_topup` earning row. **Chart of accounts:**
  debit `6302_guaranteed_minimum` (existing sub-account under
  `6300_incentive_payments`, see
  [`driver-incentive-service/README.md`](../driver-incentive-service/README.md#20-accounting-impact)),
  credit `driver_payable`. The reversal posts a `type=correction`
  row that pays back the liability — never UPDATE/DELETE on the
  original. See
  [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
  §"Workflow: Guaranteed Rewards — Driver Top-Up + Customer Credit".

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.

## 19. Cross-Service Coordination Notes

This service participates in the platform's
cross-service choreography. The following notes summarize
how it fits with the broader event-driven architecture
(see `architecture/EVENT_ARCHITECTURE.md`):

- **Idempotency**: every non-idempotent write is
  protected by an `Idempotency-Key` header and the
  platform-standard idempotency store. A retried
  request with the same key and body returns the
  stored response.
- **Outbox**: every state change that needs to be
  published to Kafka is written to the local outbox
  table in the same database transaction as the
  state change. A separate poller publishes to Kafka
  with `acks=all` and retries on failure. Outbox rows
  are purged 24 h after a successful publish.
- **Inbox**: every consumed event is recorded in the
  local inbox table keyed by `event_id` with a 24 h
  TTL, so re-deliveries are de-duplicated.
- **Cross-service references**: every cross-service
  reference (e.g. `identity_id`, `customer_id`,
  `driver_id`, `courier_id`, `vehicle_id`,
  `address_id`, `payment_method_id`) is stored as a
  UUID column WITHOUT database FK. The owning
  service is the source of truth; this service
  validates the reference exists and is current
  before persisting.
- **Distributed tracing**: OpenTelemetry
  `traceparent` is propagated to every downstream
  call. The platform's `correlation_id` is enriched on
  every span and emitted in every event's envelope.
- **Graceful degradation**: when a non-critical
  dependency is unavailable, the service degrades
  to a safe fallback (e.g. cached read, degraded
  write). The fallback is documented in the
  relevant workflow's `WORKFLOWS.md`.


---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`driver-service`](../driver-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md), [`wallet-service`](../wallet-service/README.md)
- **Depended on by**: [`driver-incentive-service`](../driver-incentive-service/README.md), [`driver-service`](../driver-service/README.md), [`ledger-service`](../ledger-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/DRIVER_WORKFLOWS.md`](../../workflows/DRIVER_WORKFLOWS.md) — onboarding, shifts, earnings
- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (gross-to-net, withholding, payable, payout)
