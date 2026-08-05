# dispatch-service

## 1. Purpose

`dispatch-service` owns the **match attempt** between a ride request
and a driver. It is the system that decides "which driver gets this
ride offer, in what order, with what expiration, with what fairness."
The result of a match is a single `dispatch.matched.v1` (or
`dispatch.no_driver.v1` / `dispatch.offer.expired.v1`).

## 2. Bounded Context

Bounded context: **Matching**.

In scope:

- The match attempt aggregate (a search through candidate drivers).
- The driver offer/accept/expire flow.
- The fairness policy (who gets the next offer).
- The expiration timer (driver has 15s to accept).
- The no-driver fallback (re-try with different parameters, or
  give up).
- Emitting `dispatch.matched.v1`, `dispatch.no_driver.v1`,
  `dispatch.offer.expired.v1`.

Out of scope (explicitly):

- The ride request itself — `ride-request-service`.
- Driver online state — `driver-availability-service`.
- Driver location — `driver-location-service`.
- The trip aggregate — `trip-service`.
- Pricing — `pricing-service`.

## 3. Responsibilities

- Consume `ride.request.created.v1` and begin a match attempt.
- Query `driver-availability-service` for online drivers in the
  pickup zone with the requested ride type.
- Sort candidates by ETA, fairness score, and recent activity.
- Send a ride offer to the top candidate via push (the driver app
  is the source of truth for accept/reject).
- Hold a 15s offer timer; on expiration, emit
  `dispatch.offer.expired.v1` and try the next candidate.
- On accept, emit `dispatch.matched.v1` and stop the search.
- After N attempts with no driver, emit `dispatch.no_driver.v1`.
- Persist the match attempt for audit and fairness analysis.

## 4. Explicitly NOT Owned

- The ride request aggregate.
- The driver online state.
- The driver's GPS location.
- The trip aggregate.
- Pricing.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `ride-request-service` | system | emits `ride.request.created.v1` |
| `driver-availability-service` | system | read; emits `driver.availability.*.v1` |
| `driver-location-service` | system | read; emits `driver.location.updated.v1` |
| Driver app | system | accept / reject offer (via push) |
| `admin-service` | system | read; force-cancel a match attempt |
| `eta-routing-service` | system | compute ETA to pickup |

## 6. Dependencies

### Synchronous (REST)

- `driver-availability-service` — list online drivers in zone — SLO
  100ms — circuit breaker: yes.
- `driver-location-service` — read last known positions — SLO 50ms
  — circuit breaker: yes.
- `eta-routing-service` — compute ETA to pickup for each candidate
  — SLO 300ms — circuit breaker: yes.
- `driver-service` — read driver rating (for fairness) — SLO 100ms
  — circuit breaker: yes.

### Asynchronous (events consumed)

- `ride.request.created.v1` from `ride-request-service` — start a
  match attempt — duplicate handling: inbox dedup.
- `ride.request.cancelled.v1` from `ride-request-service` — abandon
  the match — duplicate handling: inbox dedup.
- `driver.availability.offline.v1` from `driver-availability-service`
  — remove from candidate list — duplicate handling: inbox dedup.
- `driver.location.updated.v1` (curated) from
  `driver-location-service` — update candidate positions — duplicate
  handling: inbox dedup.
- `configuration.updated.v1` from `configuration-service` — reload
  fairness, offer TTL, max attempts.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `dispatch`.
- Cache: Redis (per-service) for the in-flight match attempts and
  the per-zone candidate set.
- Event broker: Kafka.
- The 15s offer timer is implemented with a Redis-backed
  sorted-set of expirations (cheap) plus a small sweeper.

## 8. Database Ownership

- Schema: `dispatch` (owned exclusively by this service).
- Migrations: `services/dispatch-service/migrations/`.
- Soft delete: no.
- Partitioning: no (the match attempts table is moderate; the
  assignment ledger is small).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/dispatch/requests | system | create a match attempt (called by `ride-request-service`) |
| GET | /v1/dispatch/attempts/{id} | bearer (admin / support) | read an attempt |
| POST | /v1/dispatch/attempts/{id}/cancel | system | cancel an attempt |
| GET | /v1/dispatch/drivers/{driver_id}/offers | bearer (driver) | list the driver's pending offers |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `dispatch.matched.v1` | driver accepts the offer | `ride-request-service`, `trip-service`, `notification-service` |
| `dispatch.no_driver.v1` | no driver accepted within N attempts | `ride-request-service`, `notification-service` |
| `dispatch.offer.expired.v1` | driver does not respond in 15s | `ride-request-service` (re-attempt), `dispatch-service` (next candidate) |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `ride.request.created.v1` | `ride-request-service` | start a match | create attempt; begin search |
| `ride.request.cancelled.v1` | `ride-request-service` | abandon | mark attempt cancelled |
| `driver.availability.offline.v1` | `driver-availability-service` | drop candidate | remove from candidate list |
| `driver.location.updated.v1` (curated) | `driver-location-service` | refresh candidate | update position in the in-flight list |
| `configuration.updated.v1` | `configuration-service` | reload config | cache invalidation |

## 12. External Integrations

- `eta-routing-service` (in-cluster) for ETA to pickup.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `dispatch.offer.ttl_seconds` | int | configuration-service | default 15 |
| `dispatch.attempt.max_attempts` | int | configuration-service | default 5 |
| `dispatch.fairness.recent_offers_window_minutes` | int | configuration-service | default 5 |
| `dispatch.candidate.radius_meters` | int | configuration-service | default 5000 |
| `dispatch.candidate.min_radius_meters` | int | configuration-service | default 1000 |
| `dispatch.candidate.expansion_factor` | float | configuration-service | default 1.5 (search radius expands each attempt) |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: the `POST /v1/dispatch/requests` endpoint is system-only.
  Driver offers are read by the driver app via push; the driver's
  accept/reject is via push.
- Secrets: Vault at `secret/dispatch/{env}/*`.
- PII: pickup/dropoff (held in the attempt row); encrypted at rest
  (disk-level KMS).

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `match_attempt_id`,
  `ride_request_id`, `driver_id`, `route`, `latency_ms`, `status`.
- Metrics: `dispatch_attempts_total{city, ride_type}`,
  `dispatch_match_seconds` (histogram),
  `dispatch_offer_expirations_total`,
  `dispatch_no_driver_total{city, ride_type}`,
  `dispatch_candidates_considered` (histogram),
  `dispatch_fairness_skips_total`.
- Traces: OpenTelemetry, root span per match attempt; child spans
  per candidate evaluation.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 8 (default); HPA on `dispatch_match_seconds_p99` and
  CPU.
- Hot path: the match attempt; we cache the per-zone candidate set
  for 2s to absorb bursts.
- The 15s offer timer is implemented with a Redis sorted-set of
  expiration timestamps; a sweeper wakes every 1s to expire
  candidates.

## 17. Local Development

```bash
docker compose up dispatch-service postgres kafka redis
bun run --filter dispatch-service dev
```

Seed data: a fake `eta-routing-service` returning predictable
ETAs; a default `driver-availability-service` with three online
drivers.

## 18. Deployment

- Image: `registry.uber.io/dispatch-service:<sha>`.
- Replicas: 8 (HPA to 40).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`configuration-service`](../configuration-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`branch-service`](../branch-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-service`](../courier-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`scheduled-ride-service`](../scheduled-ride-service/README.md), [`trip-service`](../trip-service/README.md), [`vehicle-service`](../vehicle-service/README.md), [`zone-service`](../zone-service/README.md)

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
