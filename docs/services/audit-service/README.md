# Audit Service

## 1. Purpose

`audit-service` is the platform's **immutable audit log**. It
consumes every audit-relevant event from every service, persists
the events in an append-only store, and exposes a strict-RBAC
search API. The service is a **consumer** of the event stream —
it never produces business events and never mutates business
state.

## 2. Bounded Context

**Bounded context**: Audit log persistence. In scope:

- Subscription to all `*.audit.*` topics (and other audit-relevant
  events).
- Append-only persistence with cryptographic immutability.
- Strict-RBAC search API (admin, security, compliance).
- Retention policy enforcement.
- Partitioning by month / year.

Out of scope:

- Business event production (every other service).
- Action dispatch (owned by `admin-service`; this service just
  records `admin.action.performed.v1`).
- Right-to-erasure (handled per-service; this service retains the
  log per legal requirements).

## 3. Responsibilities

- Consume every `*.audit.*` topic.
- Persist every event in an immutable, append-only table.
- Apply a cryptographic hash chain (each row references the
  previous row's hash).
- Expose a strict-RBAC search API.
- Enforce retention (7 years for financial, 1 year for the rest).
- Reject UPDATE / DELETE on the audit schema at the database grant
  level.
- Export the audit log to S3 for offline analysis.

## 4. Explicitly NOT Owned

- **Business events** — every other service.
- **Action dispatch** — `admin-service`.
- **Right-to-erasure** — per-service; this service retains the log
  per legal minimums.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Admin (compliance / security) | human | read (strict RBAC) |
| External auditor | human | read (offline, via S3) |
| Every service (event) | system | producer of consumed events |
| Reconciliation job | system | read |

## 6. Dependencies

### Synchronous (REST)

- n/a (the service is consumer-only).

### Asynchronous (events consumed)

- `*.audit.*` — every audit-relevant event.
- Plus the platform's high-value events:
  `admin.action.performed.v1`, `payment.*.v1`, `wallet.*.v1`,
  `ledger.posted.v1`, `trip.*.v1`, `food.order.*.v1`, etc.

## 7. Technology Assumptions

- Runtime: **Kotlin 2.2.x + Spring Boot 4.x** (per `TECH.md` and `RECOMMENDATIONS.md`); high-throughput Kafka consumer (Spring Kafka). The platform's backend apps are Go, Kotlin, or Python only — no Node.js / TypeScript on the backend.
- Database: PostgreSQL 19 (per-service schema `audit`).
- Cache: none (read path is direct from DB).
- Event broker: Kafka.
- Object storage: AWS S3 (export).

## 8. Database Ownership

- Schema: `audit`.
- Migrations: `services/audit-service/migrations/`.
- Soft delete: no (append-only).
- Partitioning: `audit.events` partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/audit/search` | bearer (admin) | search events |
| GET | `/v1/audit/events/{id}` | bearer (admin) | read event |
| GET | `/v1/audit/verify/{id}` | bearer (admin) | verify hash chain |

## 10. Events Produced

The service does not produce business events. It MAY emit
operational events:

- `audit.consumer.lag.v1` — periodic metric event (every minute).
- `audit.export.completed.v1` — nightly export.

## 11. Events Consumed

The service subscribes to:

- `admin.action.performed.v1`
- `payment.attempted.v1`, `payment.authorized.v1`,
  `payment.captured.v1`, `payment.failed.v1`,
  `payment.refund.initiated.v1`, `payment.refund.completed.v1`
- `wallet.credited.v1`, `wallet.debited.v1`, `wallet.held.v1`,
  `wallet.released.v1`
- `ledger.posted.v1`
- `trip.started.v1`, `trip.arrived.v1`, `trip.completed.v1`,
  `trip.cancelled.v1`
- `ride.request.created.v1`, `ride.request.matched.v1`,
  `ride.request.cancelled.v1`, `ride.request.expired.v1`
- `dispatch.matched.v1`, `dispatch.no_driver.v1`
- `food.order.*.v1`
- `delivery.*.v1`
- `identity.user.*.v1`
- `customer.*.v1`
- `driver.*.v1`, `courier.*.v1`
- `merchant.*.v1`, `restaurant.*.v1`
- `configuration.updated.v1`, `feature_flag.updated.v1`
- `promotion.*.v1`
- `loyalty.*.v1`
- `review.*.v1`
- `tax.*.v1`
- `pricing.quote.created.v1`
- `notification.sent.v1`, `notification.failed.v1`
- `comms.*.sent.v1`
- `support.ticket.*.v1`
- `fraud.*.v1`
- `file.*.v1`
- `search.*.v1` (n/a; search-service has no events)
- `zone.*.v1`
- `trip.reward.granted.v1`, `trip.reward.reversed.v1` (per-trip
  guaranteed reward; 7-year retention)
- `pricing.rating_density.applied.v1`,
  `pricing.loyalty_discount.applied.v1`
- `pricing.geo_config.updated.v1` (operator audit)

## 12. External Integrations

- **HashiCorp Vault** — DB credentials.
- **AWS S3** — daily export at
  `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/`.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `S3_BUCKET` | string | env | |

Runtime configuration keys read from `configuration-service`:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `audit.retention.financial_years` | int | configuration-service | default 7 |
| `audit.retention.default_years` | int | configuration-service | default 1 |
| `audit.export.s3.path_template` | string | configuration-service | default `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/` |
| `audit.export.cron` | string | configuration-service | default `0 4 * * *` (04:00 UTC) |
| `audit.hash.algo` | string | configuration-service | default `sha256` |
| `audit.api.request.body_redaction.regex_patterns` | string[] | configuration-service | PII redaction patterns (defaults from `platform-spring-boot-starter`) |

> **Canonical key index.** See
> [`../configuration-service/INTEGRATION.md` 10.3](../configuration-service/INTEGRATION.md#103-audit-service)
> for the full `audit.*` key family.

## 14. Security

- AuthN: JWT bearer (admin realm `platform-internal`).
- AuthZ: strict RBAC — `audit.read` for compliance, `audit.admin`
  for security.
- Secrets: Vault.
- PII: stored as in the source event; column-level encryption for
  sensitive fields.
- Read access is logged at the service level.

## 15. Observability

- Logs: JSON to stdout; standard fields.
- Metrics: RED per route + `audit_events_ingested_total{topic}`,
  `audit_consumer_lag{topic, partition}`,
  `audit_export_seconds`, `audit_hash_chain_status`.
- Traces: OpenTelemetry; one root span per event.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 6; HPA on consumer lag.
- Hot path: Kafka consumer (high throughput).

## 17. Local Development

```bash
docker compose -f deploy/compose/audit-service.yml up -d db kafka
make -C services/audit-service migrate-up
go run services/audit-service/cmd/server
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/audit-service:<sha>`.
- Replicas: 6 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes (the audit log is the source of truth; loss is
  catastrophic).
- RTO: 30 minutes.
- Backups: nightly logical + continuous WAL; 7-year retention.

## 20. Accounting impact

`audit-service` is the **immutable audit log** for every accounting
event on the platform. It consumes `ledger.posted.v1` and the
money-movement event streams and persists them with **7-year
retention** (regulatory requirement for financial events).

- **What money facts it owns:** the immutable, append-only audit
  log of every state transition on a financial aggregate
  (`payment.*.v1`, `wallet.*.v1`, `driver.earning.*.v1`,
  `courier.earning.*.v1`, `merchant.*.v1`, `ledger.posted.v1`,
  `tax.*.v1`, `promotion.redeemed.v1`, `driver.incentive.earned.v1`,
  plus the new `trip.reward.granted.v1` and `trip.reward.reversed.v1`
  events which carry the per-trip `6302_guaranteed_minimum` and
  `2100_customer_credit_liability` chart-of-account postings).
- **Auto-emitted request audit:** every API call to a financial
  service emits `audit.api.request.v1`; every admin action emits
  `audit.admin.<service>.v1`. Auto-emitted via
  `platform-spring-boot-starter`.
- **Reconciliation:** indirect — `audit-service` does not run
  reconciliation jobs (those live in `reporting-service`); it
  records the drift events that the reconciliation jobs emit.
- **Retention:** 7 years for financial events (regulatory);
  every new event below also lands in this 7-year bucket:
  - `trip.reward.granted.v1` — per-trip guaranteed reward; the
    operational postings flow through ``payment-service` (driver earnings)`
    (driver top-up) and ``payment-service` (wallet)` (customer credit).
  - `trip.reward.reversed.v1` — per-trip reversal; the
    downstream services post the reversing rows.
  - `pricing.rating_density.applied.v1` — quote composition
    audit (configurable rating-density surcharge).
  - `pricing.loyalty_discount.applied.v1` — quote composition
    audit (loyalty discount applied).
  - `pricing.geo_config.updated.v1` — operator audit; the
    authoritative geo-config CRUD is in `admin-service`.
- **Human operator path:** read-only; queries via audit console;
  exports for regulator requests.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.


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
- [`PLAN.md`](./PLAN.md) — implementation tasks, phases, dependencies

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`search-service`](../search-service/README.md)
- **Depended on by**: [``customer-service` (addresses)`](../customer-service/README.md), [`admin-service`](../admin-service/README.md), [``reporting-service` (data lake)`](../reporting-service/README.md), [`api-gateway`](../api-gateway/README.md), [``restaurant-service` (branch)`](../restaurant-service/README.md), [``food-order-service` (cart)`](../food-order-service/README.md), [``food-order-service` (checkout)`](../food-order-service/README.md), [``notification-service` (provider ACL)`](../notification-service/README.md), [`configuration-service`](../configuration-service/README.md), [``courier-service` (dispatch)`](../courier-service/README.md), [``payment-service` (courier earnings)`](../payment-service/README.md), [`courier-service`](../courier-service/README.md), [``courier-service` (tracking)`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [``courier-service` (delivery)`](../courier-service/README.md), [``payment-service` (driver earnings)`](../payment-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [``payment-service` (food saga)`](../payment-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (audit log of every accounting event; 7-year retention)
