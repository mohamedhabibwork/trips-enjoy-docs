# ledger-service — Software Requirements Specification

## 1. Introduction

This document specifies the software behaviour of
`ledger-service`. It is the engineering source of truth for the
double-entry ledger, the chart of accounts, the postings, and
the reconciliation.

## 2. Scope

- In scope: chart of accounts, postings, account types, posting
  immutability, balance reads, reconciliation jobs, financial
  reports, manual journal entries.
- Out of scope: payment provider integration, wallet runtime
  balance, settlement scheduling, earnings orchestration.

## 3. System Context

```mermaid
flowchart LR
    PS[payment-service] -- payment.captured.v1 --> LS[ledger-service]
    PS -- payment.refund.completed.v1 --> LS
    WS[`payment-service` (wallet)] -- wallet.*.v1 --> LS
    RSM[`payment-service` (merchant settlement)] -- merchant.*.v1 --> LS
    CE[`payment-service` (courier earnings)] -- courier.*.v1 --> LS
    DE[`payment-service` (driver earnings)] -- driver.*.v1 --> LS
    FPI[`payment-service` (food saga)] -- food.payment.*.v1 --> LS
    RPI[`payment-service` (ride saga)] -- ride.payment.*.v1 --> LS
    LS -- ledger.posted.v1 --> RP[reporting-service]
    LS -- ledger.audit.*.v1 --> AUD[audit-service]
    LS -- ledger.*.v1 --> RSM
    LS -- ledger.*.v1 --> CE
    LS -- ledger.*.v1 --> DE
    LS -- ledger.*.v1 --> WS
    LS -- ledger.*.v1 --> FPI
    LS -- ledger.*.v1 --> RPI
    ADM[admin-service] -- manual journal --> LS
```

## 4. Actors

- `payment-service` (system actor).
- ``payment-service` (wallet)` (system actor).
- ``payment-service` (merchant settlement)` (system actor).
- ``payment-service` (courier earnings)` (system actor).
- ``payment-service` (driver earnings)` (system actor).
- ``payment-service` (food saga)` (system actor).
- ``payment-service` (ride saga)` (system actor).
- `admin-service` / ``admin-service` (support module)` (Keycloak
  `platform-internal`).

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | Accept `POST /v1/postings` with `[{account_code, side, amount_minor, currency}, ...]` (≥ 2 entries; sum debits = sum credits). | MUST |
| FR--002 | Reject any posting that is not balanced (422 `UNBALANCED_POSTING`). | MUST |
| FR--003 | Reject any posting that references a non-existent account (422 `ACCOUNT_NOT_FOUND`). | MUST |
| FR--004 | Reject any posting where the entries have mixed currencies (422 `CURRENCY_MISMATCH`). | MUST |
| FR--005 | Insert the posting and its entries in a single transaction. | MUST |
| FR--006 | Update each account's balance (cached) in the same transaction. | MUST |
| FR--007 | Emit `ledger.posted.v1` for every posting. | MUST |
| FR--008 | Provide `GET /v1/accounts/{code}/balance` (current balance). | MUST |
| FR--009 | Provide `GET /v1/accounts/{code}/balance?from=…&to=…` (balance over a range). | MUST |
| FR--010 | Provide `GET /v1/postings/{id}` (full posting with entries). | MUST |
| FR--011 | Provide `GET /v1/postings?account=…&from=…&to=…` (list postings). | MUST |
| FR--012 | Maintain the chart of accounts with `code`, `name`, `type` (asset/liability/equity/revenue/expense), `currency`, `parent_code`, `version`. | MUST |
| FR--013 | Provide `POST /v1/journal-entries` (admin; manual entry with audit note). | MUST |
| FR--014 | Provide `GET /v1/reports/trial-balance?date=…`. | MUST |
| FR--015 | Provide `GET /v1/reports/balance-sheet?date=…`. | MUST |
| FR--016 | Provide `GET /v1/reports/income-statement?from=…&to=…`. | MUST |
| FR--017 | Run a daily reconciliation against the operational layers; emit `ledger.audit.reconciled.v1` or `reconciliation_drift.v1`. | MUST |
| FR--018 | Pre-create monthly partitions for `postings` for the next 12 months. | MUST |
| FR--019 | Drop partitions older than `retention_years` (default 10). | MUST |
| FR--020 | Reject any attempt to UPDATE or DELETE a posting (enforced by revoking privileges). | MUST |
| FR--021 | Reject any posting older than 5 minutes (clock-skew guard). | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | Posting P99 | ≤ 100ms |
| NFR--002 | performance | Balance read P99 | ≤ 50ms |
| NFR--003 | performance | Report P99 | ≤ 5s |
| NFR--004 | availability | Service uptime | 99.95% / 30d |
| NFR--005 | scalability | Posting throughput | 500 rps |
| NFR--006 | scalability | Concurrent active accounts | ≥ 100k |
| NFR--007 | maintainability | MTTR | ≤ 30 min |
| NFR--008 | observability | End-to-end trace per posting | 100% |
| NFR--009 | consistency | Trial balance diff = 0 at all times | enforced |
| NFR--010 | durability | Postings survive regional failover | MUST |

## 7. API Requirements

- All non-idempotent `POST` endpoints require `Idempotency-Key`.
- All responses use the standard error envelope.
- All endpoints validate input with JSON Schema.
- Full contracts: `INTEGRATION.md`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | The `postings` table is append-only; the application role has INSERT only. | |
| DATA--002 | The `postings` table is range-partitioned by month on `posted_at`. | |
| DATA--003 | The `accounts` table is versioned; a change is a new row. | |
| DATA--004 | Money values are `amount_minor BIGINT` + `currency CHAR(3)`. | |
| DATA--005 | No PII is stored. | |

## 9. Validation Rules

- A posting MUST have ≥ 2 entries.
- The sum of debits MUST equal the sum of credits.
- All entries in a posting MUST share the same currency.
- An account MUST exist.
- A manual journal entry MUST have an `audit_note` ≥ 10
  characters.
- A posting's `posted_at` MUST be within ±5 minutes of the
  server's wall clock.

## 10. State Transitions

`Account` versions:

```
v1 → v2 (a new version is added; the old version is kept)
```

`Posting` rows are terminal on insert.

## 11. Authorization Requirements

- Service-to-service callers require `ledger.write` or
  `ledger.read` in the `ledger-service` client.
- Admin endpoints require `ledger.admin`.
- Manual journal entries require `ledger.admin` AND a valid
  `audit_note`.

## 12. Configuration Requirements

- Reads `ledger.*` from `configuration-service` at startup and
  on `configuration.updated.v1`.
- All numeric config validated against min/max bounds.

## 13. Error Handling

| Error | Response |
|-------|----------|
| Unbalanced posting | 422 `UNBALANCED_POSTING` |
| Account not found | 422 `ACCOUNT_NOT_FOUND` |
| Currency mismatch | 422 `CURRENCY_MISMATCH` |
| Manual entry without note | 422 `AUDIT_NOTE_REQUIRED` |
| Idempotency-Key reused | 422 `IDEMPOTENCY_KEY_REUSED` |
| Clock skew | 422 `TIMESTAMP_OUT_OF_BOUNDS` |

## 14. Concurrency Requirements

- A row-level lock on the `account` row is acquired at every
  posting that touches it.
- Account balance updates are serialised by the per-account
  lock.
- The chart of accounts is read-mostly; account creation is
  rare and serialised by the chart-of-accounts version.

## 15. Idempotency Requirements

- `POST /v1/postings` requires `Idempotency-Key`. The key is
  unique per logical posting; replays return the original
  response.
- The consumer-side `inbox` table dedup's consumed events.

## 16. Performance

- Dominant path: receive `POST /v1/postings`, validate, lock the
  account rows, insert the posting, update the account balances,
  emit `ledger.posted.v1`.
- P50 / P95 / P99: see NFRs.
- Hot spot: account row lock. Mitigated by sharding by
  `account_code` (each account is its own hot row under normal
  load).

## 17. Scalability

- Horizontal: stateless; HPA on `kafka_consumer_lag` and
  `ledger_postings_total`.
- Vertical: bounded by PostgreSQL connection pool.

## 18. Availability

- SLO: 99.95% over 30 days.
- Maintenance: rolling deploys only.
- Degraded mode: the chart-of-accounts read is still served from
  cache; postings queue in the operational layers (which retry).

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require JWT bearer validated at the gateway. | |
| SEC--002 | Postings are append-only; UPDATE/DELETE privileges are revoked from the application role. | |
| SEC--003 | Manual journal entries are audit-logged. | `admin.action.performed.v1`. |
| SEC--004 | No PII is stored. | |
| SEC--005 | Rate limit per `Idempotency-Key`. | At the service. |

## 20. Privacy

- PII stored: none.
- Retention: 10 years (regulatory).
- Erasure: not applicable.

## 21. Auditability

- Every posting is in `postings` (append-only).
- Every manual journal entry is in `journal_entries` with
  `actor_id`, `audit_note`, `posted_at`.
- Reconciliation runs emit
  `ledger.audit.reconciled.v1` (or
  `reconciliation_drift.v1` on drift).

## 22. Observability

- Logs: JSON, fields include `correlation_id`, `posting_id`,
  `account_code`, `tenant_id`.
- Metrics: `ledger_postings_total{account_type,currency}`,
  `ledger_posting_seconds`,
  `ledger_balance_total{account_type,currency}`,
  `ledger_reconciliation_drift`.
- Traces: OpenTelemetry; root span per posting.
- Alerts: SLO burn-rate; unbalanced posting attempt; trial
  balance diff > 0.

## 23. Maintainability

- Code style: TypeScript strict, ESLint with platform rules.
- Test coverage: ≥ 80% line, ≥ 75% branch; 100% on the
  double-entry invariant.
- Documentation: this folder + `WORKFLOWS.md` diagrams.

## 24. Disaster Recovery

- RPO: 5 minutes (the postings table is replicated to standby
  region).
- RTO: 30 minutes (stateless service).

## 25. Acceptance Criteria

- All FR/NFR are met and verified by automated tests.
- All SEC are met and verified by a security / audit review.
- A load test sustains 500 postings / second with p99 ≤ 100ms.
- The trial balance reports zero drift over 7 days.
- The 10-year retention is enforced (verified by partition
  management).
- A manual journal entry is audit-logged.

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

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

