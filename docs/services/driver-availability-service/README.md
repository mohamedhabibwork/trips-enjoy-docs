# driver-availability-service

## 1. Purpose

`driver-availability-service` owns the driver's **online state** —
whether a driver is currently available to receive ride offers, in
which zone, for which ride types, and whether they are currently
busy on a trip. It is the system's source of truth for "is this
driver dispatchable right now?"

## 2. Bounded Context

Bounded context: **Driver Online State**.

In scope:

- The driver's online/offline/busy state machine.
- The driver's current shift (start, planned end, breaks).
- The driver's accepted ride types (e.g. economy, premium, xl).
- The driver's current zone.
- The transition events that `dispatch-service` and
  `driver-location-service` react to.

Out of scope (explicitly):

- The driver profile (KYC, documents, rating) — `driver-service`.
- The driver's GPS location — `driver-location-service`.
- The trip aggregate — `trip-service`.
- Driver onboarding — `driver-service` (this service only handles
  approved drivers).

## 3. Responsibilities

- Accept `online` and `offline` requests from the driver app.
- Track which ride types and which zone the driver accepts.
- Cooperate with `dispatch-service` and `trip-service` to mark a
  driver `busy` when a match lands, and back to `available` when
  the trip completes.
- Refuse to take a driver offline if they have an active trip.
- Emit `driver.availability.online.v1`,
  `driver.availability.offline.v1`, and
  `driver.availability.busy.v1`.
- React to `driver.suspended.v1` and `driver.approved.v1` to set the
  driver offline or to allow them online.

## 4. Explicitly NOT Owned

- Driver KYC, document expiry, profile — `driver-service`.
- Driver location stream — `driver-location-service`.
- The ride request aggregate — `ride-request-service`.
- Trip state — `trip-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver app | system | read/write own state |
| `dispatch-service` | system | read (consume state events) |
| `driver-location-service` | system | read (consume state events to know whether to publish) |
| `trip-service` | system | notify on busy / available transitions |
| `driver-service` | system | emits `driver.approved.v1` and `driver.suspended.v1` |
| `admin-service` | system | force offline; force suspend |

## 6. Dependencies

### Synchronous (REST)

- `driver-service` — validate the driver is approved and not
  suspended; fetch accepted ride types — SLO 100ms — circuit
  breaker: yes.
- `zone-service` — validate the driver's chosen zone — SLO 100ms —
  circuit breaker: yes.

### Asynchronous (events consumed)

- `driver.approved.v1` from `driver-service` — allow the driver to
  go online — duplicate handling: inbox dedup.
- `driver.suspended.v1` from `driver-service` — force offline —
  duplicate handling: inbox dedup.
- `driver.document.expired.v1` from `driver-service` — force offline
  if state was `online` or `available` — duplicate handling: inbox
  dedup.
- `trip.started.v1` from `trip-service` — mark the driver `busy` —
  duplicate handling: inbox dedup, idempotent.
- `trip.completed.v1` from `trip-service` — mark the driver back to
  `available` — duplicate handling: inbox dedup, idempotent.
- `trip.cancelled.v1` from `trip-service` — same as completed when
  pre-pickup; ignore when mid-trip (the driver is already `busy`
  with no trip; the cancel means back to `available`) — duplicate
  handling: inbox dedup.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema
  `driver_availability`.
- Cache: Redis (per-service) for fast "is this driver dispatchable
  right now?" reads.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `driver_availability` (owned exclusively by this service).
- Migrations: `services/driver-availability-service/migrations/`.
- Soft delete: no (the driver's state is the truth; suspended is
  a hard state).
- Partitioning: no (volume is moderate; one row per driver).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/availability/online | bearer (driver) | go online with a zone and ride types |
| POST | /v1/availability/offline | bearer (driver) | go offline |
| GET | /v1/availability/{driver_id} | bearer (driver, support, admin) | read state |
| PATCH | /v1/availability/{driver_id}/zone | bearer (driver) | change zone |
| PATCH | /v1/availability/{driver_id}/ride-types | bearer (driver) | change accepted ride types |
| POST | /v1/availability/{driver_id}/break | bearer (driver) | start a break |
| POST | /v1/availability/{driver_id}/resume | bearer (driver) | end a break |
| POST | /v1/availability/zone/{zone_id}/online-drivers | system | list drivers in a zone (used by dispatch) |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `driver.availability.online.v1` | on successful `online` | `dispatch-service`, `driver-location-service` |
| `driver.availability.offline.v1` | on `offline` or forced offline | `dispatch-service`, `driver-location-service` |
| `driver.availability.busy.v1` | when the driver is assigned a trip | `dispatch-service` |
| `driver.availability.available.v1` | when the driver becomes available again | `dispatch-service` |
| `driver.availability.zone.changed.v1` | on zone change | `dispatch-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `driver.approved.v1` | `driver-service` | allow online | unlock; the driver can now go online |
| `driver.suspended.v1` | `driver-service` | force offline | if online, mark offline; deny future online |
| `driver.document.expired.v1` | `driver-service` | force offline | if online, mark offline |
| `trip.started.v1` | `trip-service` | mark busy | state=busy; emit `driver.availability.busy.v1` |
| `trip.completed.v1` | `trip-service` | mark available | state=available (if not on break) |
| `trip.cancelled.v1` | `trip-service` | mark available (pre-pickup only) | state=available (pre-pickup only) |

## 12. External Integrations

- `zone-service` (in-cluster) for zone validation.
- `driver-service` (in-cluster) for driver profile.
- No external map provider.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `driver_availability.break.max_minutes` | int | configuration-service | default 30 |
| `driver_availability.online.cooldown_seconds_after_offline` | int | configuration-service | default 30 |
| `driver_availability.zone.max_radius_km` | int | configuration-service | default 5 (for "near" zone changes) |
| `driver_availability.idle_timeout_minutes` | int | configuration-service | default 15 (online but no movement → flagged) |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: driver can read/write own state; admin can force offline
  with a reason.
- Secrets: Vault at `secret/driver_availability/{env}/*`.
- PII: none stored (we store driver_id and zone_id, both UUIDs).

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `driver_id`, `route`,
  `latency_ms`, `status`.
- Metrics: `driver_online_total{city}`, `driver_offline_total{city}`,
  `driver_busy_total{city}`, `driver_state_transitions_total{from,to}`,
  `driver_zone_changes_total{city, from_zone, to_zone}`,
  `driver_idle_seconds` (histogram).
- Traces: OpenTelemetry, root span per request.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 4 (default); HPA on CPU.
- Hot path: the per-zone "online drivers" query. We index
  `(zone_id, state)` and cache the result for 5 seconds.
- Read replicas: not needed at current volume; the table is small.

## 17. Local Development

```bash
docker compose up driver-availability-service postgres kafka redis
bun run --filter driver-availability-service dev
```

Seed data: a default driver and a default zone.

## 18. Deployment

- Image: `registry.uber.io/driver-availability-service:<sha>`.
- Replicas: 4 (HPA to 20).
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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`configuration-service`](../configuration-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md), [`zone-service`](../zone-service/README.md)
- **Depended on by**: [`dispatch-service`](../dispatch-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md), [`vehicle-service`](../vehicle-service/README.md)

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
