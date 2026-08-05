# driver-incentive-service

## 1. Purpose

`driver-incentive-service` owns the **driver incentives** programme:
quests, bonuses, surge guarantees, and the eligibility rules that
decide which drivers qualify for what. It is the system that
motivates drivers to be online during high-demand windows.

## 2. Bounded Context

Bounded context: **Driver Incentives / Quests**.

In scope:

- Quests (e.g. "complete 20 trips in this zone by Friday").
- Bonuses (e.g. "earn an extra 50 AED if you complete 5 trips
  between 5pm and 7pm").
- Surge guarantees (e.g. "earn at least 100 AED/hour between
  5pm and 7pm").
- Eligibility rules (e.g. "driver must have rating ≥ 4.5").
- Calculation of the earned amount on `trip.completed.v1`.
- Posting the earned amount to `driver-earnings-service`.

Out of scope (explicitly):

- The trip aggregate — `trip-service`.
- The driver earnings ledger — `driver-earnings-service`.
- The ride request aggregate — `ride-request-service`.
- Surge pricing itself — `pricing-service` (we only consume the
  resulting surge).

## 3. Responsibilities

- Define quests / bonuses / guarantees (admin-configured).
- Evaluate eligibility for each driver on each completed trip.
- Calculate the earned amount.
- Post the earned amount to `driver-earnings-service` with an
  idempotency key.
- Emit `driver.incentive.earned.v1` for the driver app and
  reporting.
- Surface quest progress to the driver.

## 4. Explicitly NOT Owned

- The trip aggregate.
- The driver earnings ledger.
- The ride request aggregate.
- Surge pricing.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `trip-service` | system | emits `trip.completed.v1` (we evaluate) |
| `driver-earnings-service` | system | receives the earned amount |
| Driver app | system | read quest progress; opt in / out |
| `configuration-service` | system | read incentive config |
| `admin-service` | system | CRUD on quests / bonuses |
| `notification-service` | system | notify driver on earning |

## 6. Dependencies

### Synchronous (REST)

- `driver-service` — read driver rating, KYC — SLO 100ms — circuit
  breaker: yes.
- `driver-earnings-service` — post the earned amount — SLO 300ms —
  circuit breaker: yes.

### Asynchronous (events consumed)

- `trip.completed.v1` from `trip-service` — evaluate and earn —
  duplicate handling: inbox dedup.
- `configuration.updated.v1` from `configuration-service` — reload
  config.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `driver_incentive`.
- Cache: Redis (per-service) for the active quest cache.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `driver_incentive` (owned exclusively by this service).
- Migrations: `services/driver-incentive-service/migrations/`.
- Soft delete: yes for quests / bonuses (admin can disable).
- Partitioning: no (volume is moderate).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/incentives/quests | bearer (driver) | list active quests for the driver |
| GET | /v1/incentives/quests/{id}/progress | bearer (driver) | quest progress |
| POST | /v1/incentives/quests/{id}/opt-in | bearer (driver) | opt in to a quest |
| POST | /v1/incentives/quests/{id}/opt-out | bearer (driver) | opt out |
| GET | /v1/incentives/bonuses | bearer (driver) | list available bonuses |
| GET | /v1/incentives/guarantees | bearer (driver) | list active guarantees |
| POST | /v1/incentives (admin) | bearer (admin) | create a quest / bonus |
| PATCH | /v1/incentives/{id} (admin) | bearer (admin) | update a quest / bonus |
| POST | /v1/incentives/{id}/disable (admin) | bearer (admin) | disable |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `driver.incentive.earned.v1` | on incentive earned | `driver-earnings-service` (post), `reporting-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.completed.v1` | `trip-service` | evaluate and earn | evaluate eligibility; post to earnings |
| `configuration.updated.v1` | `configuration-service` | reload | cache invalidation |

## 12. External Integrations

None (all in-cluster).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `driver_incentive.quests.cache_ttl_seconds` | int | configuration-service | default 300 |
| `driver_incentive.evaluation.timeout_ms` | int | configuration-service | default 500 |
| `driver_incentive.eligibility.min_rating` | float | configuration-service | default 4.0 |
| `driver_incentive.eligibility.min_trip_count` | int | configuration-service | default 10 |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: driver can read own quests; admin can CRUD.
- Secrets: Vault at `secret/driver_incentive/{env}/*`.
- PII: none beyond the driver_id (cross-service ref).

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `driver_id`,
  `incentive_id`, `route`, `latency_ms`, `status`.
- Metrics: `driver_incentive_evaluations_total{city, result}`,
  `driver_incentive_earned_total{city, type}`,
  `driver_incentive_earned_minor_total` (gauge, sampled),
  `driver_incentive_evaluation_seconds` (histogram).
- Traces: OpenTelemetry, root span per evaluation.
- Health: `/health`, `/ready` (DB + Kafka + Redis), `/started`.

## 16. Scalability

- Replicas: 4 (default); HPA on CPU and on
  `driver_incentive_evaluation_seconds_p99`.
- Hot path: evaluation on `trip.completed.v1`. The active quests
  are cached in Redis for 5 minutes.

## 17. Local Development

```bash
docker compose up driver-incentive-service postgres kafka redis
bun run --filter driver-incentive-service dev
```

Seed data: a default quest ("complete 10 trips in zone X by
Friday for 100 AED bonus").

## 18. Deployment

- Image: `registry.uber.io/driver-incentive-service:<sha>`.
- Replicas: 4 (HPA to 20).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.

## 20. Accounting impact

`driver-incentive-service` produces **expense recognition** for
driver-side incentive programs (guaranteed hours, surge bonuses,
quest rewards). Every earned incentive becomes an
`expense` ledger posting rather than a revenue offset.

- **What money facts it owns:** incentive programs, eligibility
  rules, accrual periods, per-program accounting code.
- **Postings:** on `driver.incentive.earned.v1`, the ledger records
  `6300_incentive_payments` (expense) ↔ `driver_payable` (liability);
  `driver-earnings-service` consumes the event and credits the
  driver's balance so the eventual payout offsets the same payable.
- **Program accounting codes:** each incentive program has its own
  expense sub-account under `6300_incentive_payments` (e.g.
  `6301_quest_rewards`, `6302_guaranteed_minimum`,
  `6303_surge_bonus`) for granular reporting. Codes are managed
  via `admin-service` and seeded by `configuration-service`.
- **Reconciliation:** indirect — `driver-earnings-service` reconciles
  `driver_payable` against `ledger-service` daily; incentive-side
  drift surfaces there.
- **Human operator path:** admin CRUD on programs via
  `driver-incentive.admin` role; program changes emit
  `configuration.updated.v1`.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`configuration-service`](../configuration-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-service`](../driver-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md), [`reporting-service`](../reporting-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`driver-service`](../driver-service/README.md), [`trip-service`](../trip-service/README.md)

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
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (incentive expense recognition)
