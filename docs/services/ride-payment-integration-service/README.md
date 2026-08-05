# ride-payment-integration-service

## 1. Purpose

`ride-payment-integration-service` is the **ride payment saga
orchestrator**. It owns the multi-step financial flow that turns a
`trip.completed.v1` event into a captured payment, an accrued driver
earning, and a ledger posting. It is the system that ensures money
moves exactly once and in the right order.

## 2. Bounded Context

Bounded context: **Ride Payment Saga**.

In scope:

- The ride payment saga state machine.
- Calling `payment-service` to capture (and, on failure, void or
  refund).
- Calling `driver-earnings-service` to accrue the driver earning.
- Calling `ledger-service` to post the double-entry postings.
- Emitting `ride.payment.completed.v1` and
  `ride.payment.failed.v1`.
- Idempotency for every step.
- Compensation on failure.

Out of scope (explicitly):

- The trip aggregate — `trip-service`.
- The actual card capture mechanics — `payment-service`.
- The driver earnings ledger — `driver-earnings-service`.
- The general ledger — `ledger-service`.
- Wallet, settlement, refunds (these are owned by their respective
  services).

## 3. Responsibilities

- Consume `trip.completed.v1` and start a ride payment saga.
- Capture the customer's payment method (via `payment-service`)
  with an idempotency key derived from the trip id.
- Accrue the driver's earning (via `driver-earnings-service`) with
  an idempotency key derived from the trip id.
- Post the double-entry posting (via `ledger-service`).
- Emit `ride.payment.completed.v1` on success.
- On failure, compensate: void the authorization, refund any
  capture, release the earning, open a support ticket.
- Persist the saga state in `ride_payment_integration.sagas`
  keyed by `trip_id` (idempotent re-runs are no-ops).

## 4. Explicitly NOT Owned

- The trip aggregate.
- Card capture mechanics.
- Driver earnings ledger.
- General ledger.
- Wallet / settlement / refunds.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `trip-service` | system | emits `trip.completed.v1` |
| `payment-service` | system | capture / void / refund |
| `driver-earnings-service` | system | accrue |
| `ledger-service` | system | post |
| `notification-service` | consumer | notify customer on success / failure |
| `support-service` | consumer | open ticket on failure |
| `admin-service` | system | read saga state; force-retry with reason |

## 6. Dependencies

### Synchronous (REST)

- `payment-service` — capture / void / refund — SLO 500ms — circuit
  breaker: yes.
- `driver-earnings-service` — accrue — SLO 300ms — circuit breaker:
  yes.
- `ledger-service` — post — SLO 300ms — circuit breaker: yes.

### Asynchronous (events consumed)

- `trip.completed.v1` from `trip-service` — start the saga —
  duplicate handling: inbox dedup.
- `payment.captured.v1` from `payment-service` — advance the saga —
  duplicate handling: inbox dedup.
- `payment.failed.v1` from `payment-service` — fail the saga —
  duplicate handling: inbox dedup.
- `payment.authorized.v1` from `payment-service` — advance if
  pre-auth was used — duplicate handling: inbox dedup.
- `configuration.updated.v1` from `configuration-service` — reload
  config.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema
  `ride_payment_integration`.
- Cache: Redis (per-service) for hot sagas.
- Event broker: Kafka.
- The saga state is durable in the DB; replays are safe.

## 8. Database Ownership

- Schema: `ride_payment_integration` (owned exclusively by this
  service).
- Migrations: `services/ride-payment-integration-service/migrations/`.
- Soft delete: no.
- Partitioning: no.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/ride-payment-sagas/{trip_id} | bearer (admin / support) | read saga |
| POST | /v1/ride-payment-sagas/{trip_id}/retry | bearer (admin) | force retry |
| GET | /v1/ride-payment-sagas | bearer (admin) | list sagas (paginated) |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `ride.payment.completed.v1` | on saga success | `driver-earnings-service` (ack), `ride-history-service`, `audit-service`, `customer-service` (history) |
| `ride.payment.failed.v1` | on saga failure | `support-service`, `notification-service`, `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.completed.v1` | `trip-service` | start saga | create saga, begin capture |
| `payment.captured.v1` | `payment-service` | advance | mark capture done, accrue earning |
| `payment.authorized.v1` | `payment-service` | advance (if pre-auth) | mark auth done, schedule capture |
| `payment.failed.v1` | `payment-service` | fail | compensate, emit `ride.payment.failed.v1` |
| `configuration.updated.v1` | `configuration-service` | reload | cache invalidation |

## 12. External Integrations

- `payment-service`, `driver-earnings-service`, `ledger-service`
  (in-cluster).
- No external provider.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `ride_payment.saga.max_attempts` | int | configuration-service | default 3 |
| `ride_payment.saga.retry_backoff_ms` | int | configuration-service | default 1000 |
| `ride_payment.saga.timeout_seconds` | int | configuration-service | default 300 (5 min) |
| `ride_payment.compensation.notify_customer` | bool | configuration-service | default true |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: admin / support can read; admin can force-retry with
  `X-Audit-Reason`.
- Secrets: Vault at `secret/ride_payment_integration/{env}/*`.
- PII: trip, customer, driver refs (cross-service). No PAN.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `trip_id`, `saga_id`,
  `route`, `latency_ms`, `status`.
- Metrics: `ride_payment_sagas_total{state}`,
  `ride_payment_saga_duration_seconds` (histogram),
  `ride_payment_saga_failures_total{step, reason}`,
  `ride_payment_saga_compensations_total{step}`.
- Traces: OpenTelemetry, root span per saga; child spans per step.
- Health: `/health`, `/ready` (DB + Kafka + Redis), `/started`.

## 16. Scalability

- Replicas: 6 (default); HPA on
  `ride_payment_saga_duration_seconds_p99` and CPU.
- Hot path: the saga flow; the state is durable in the DB so
  restart is safe.
- The capture / accrue / post steps are I/O-bound; the bottleneck
  is the downstream services.

## 17. Local Development

```bash
docker compose up ride-payment-integration-service postgres kafka redis
bun run --filter ride-payment-integration-service dev
```

Seed data: a fake `trip.completed.v1`; a fake
`payment-service` returning `captured`.

## 18. Deployment

- Image: `registry.uber.io/ride-payment-integration-service:<sha>`.
- Replicas: 6 (HPA to 30).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.

## 20. Accounting impact

`ride-payment-integration-service` is the **ride-payment saga
orchestrator**. It does not post to the ledger directly but emits the
events that `payment-service` and `driver-earnings-service` consume
to derive their postings.

- **Trigger:** `trip.completed.v1` from `trip-service`.
- **Saga steps:** authorize → capture (via `payment-service`) →
  accrue driver earning (via `driver-earnings-service`) → emit
  `ride.payment.completed.v1`.
- **Idempotency keys:**
  `ride:<ride_id>:auth`, `ride:<ride_id>:cap`,
  `trip:<trip_id>:earn`.
- **Resulting ledger postings:** `cash` ↔ `revenue` +
  `tax_payable` + `driver_payable` (recorded by `ledger-service` on
  `payment.captured.v1` and `driver.earning.accrued.v1`).
- **Cancellation / refund:** `trip.cancelled.v1` triggers the
  `payment-service` cancellation / refund flow (compensation per
  [`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md)).

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`customer-service`](../customer-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`ledger-service`](../ledger-service/README.md), [`payment-service`](../payment-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md), [`wallet-service`](../wallet-service/README.md)

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

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (ride-payment saga postings)
