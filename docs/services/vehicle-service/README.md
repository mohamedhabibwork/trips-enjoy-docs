# vehicle-service

## 1. Purpose

The `vehicle-service` is the platform's source of truth
for **vehicles** — cars, motorcycles, scooters — owned
by drivers and couriers. It tracks the vehicle's
registration (plate, registration certificate),
insurance, inspection, and ownership. It is the only
writer of the `vehicle` schema and the canonical source
of `vehicle_id` for the platform.

## 2. Bounded Context

**Vehicle registry.** In scope: vehicle registration,
plate, registration certificate, insurance policy,
inspection certificate, multi-owner support (a vehicle
can be associated with one driver and one courier at
the same time, e.g. a family car), document expiry
warnings, auto-removal. Out of scope: driver / courier
profiles (only references), location, availability,
earnings.

## 3. Responsibilities

- Create and maintain the `vehicle.vehicles` row for
  every vehicle registered on the platform.
- Track vehicle ownership: a vehicle can be owned by
  one driver and one courier (multi-owner).
- Track insurance policies with expiry dates.
- Track inspection certificates with expiry dates.
- Send document expiry warnings (30, 7, 1 day).
- Emit `vehicle.registered.v1`, `vehicle.approved.v1`,
- `vehicle.insurance.expired.v1`,
  `vehicle.inspection.expired.v1`,
  `vehicle.erased.v1`.
- Provide vehicle metadata for `driver-service` and
  `courier-service`.

## 4. Explicitly NOT Owned

- **Driver / courier profiles.** `driver-service`,
  `courier-service` (this service only stores
  references).
- **Location.** `driver-location-service`,
  `courier-tracking-service`.
- **Availability.** `driver-availability-service`,
  `courier-dispatch-service`.
- **Trip / delivery assignments.**
  `dispatch-service`, `courier-dispatch-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver / courier (vehicle owner) | human | read/write on their own vehicles |
| `identity-service` | service (producer) | emits `identity.*.v1` (none consumed directly) |
| `driver-service` | service (consumer) | reads `vehicle.registered.v1` |
| `courier-service` | service (consumer) | reads `vehicle.registered.v1` |
| `admin-service` | service | admin actions (approve, archive, GDPR erasure) |
| `notification-service` | service (consumer) | reads `vehicle.*.v1` for expiry warnings |
| `audit-service` | consumer | reads `vehicle.*.v1` |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — read claims on vehicle owner
  validation — SLO 99.95% — circuit breaker: yes.

### Asynchronous (events consumed)

- `configuration.updated.v1` from
  `configuration-service` — reload KYC rules,
  document expiry windows. Duplicate handling:
  configuration version stamp.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript).
- Database: PostgreSQL 18 (per-service schema
  `vehicle`).
- Cache: Redis (per-service logical DB).
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `vehicle`.
- Migrations: `services/vehicle-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (`vehicles` use `deleted_at`).
- Partitioning: no. The `vehicles` table is one row
  per vehicle; `vehicle_insurances` and
  `vehicle_inspections` are small per vehicle.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/vehicles/{vehicle_id}` | bearer (owner or service) | get a vehicle |
| POST | `/v1/vehicles` | bearer (driver or courier) | register a vehicle |
| PATCH | `/v1/vehicles/{vehicle_id}` | bearer (owner or admin) | update vehicle |
| GET | `/v1/vehicles/{vehicle_id}/insurances` | bearer (owner or service) | list insurances |
| POST | `/v1/vehicles/{vehicle_id}/insurances` | bearer (owner) | add an insurance policy |
| DELETE | `/v1/vehicles/{vehicle_id}/insurances/{insurance_id}` | bearer (owner) | remove |
| GET | `/v1/vehicles/{vehicle_id}/inspections` | bearer (owner or service) | list inspections |
| POST | `/v1/vehicles/{vehicle_id}/inspections` | bearer (owner) | add an inspection |
| DELETE | `/v1/vehicles/{vehicle_id}/inspections/{inspection_id}` | bearer (owner) | remove |
| POST | `/v1/vehicles/{vehicle_id}/owners` | bearer (owner or admin) | add a co-owner |
| DELETE | `/v1/vehicles/{vehicle_id}/owners/{owner_id}` | bearer (owner or admin) | remove a co-owner |
| POST | `/v1/vehicles/{vehicle_id}/approve` | bearer (admin) | approve |
| POST | `/v1/vehicles/{vehicle_id}/erase` | bearer (admin) | GDPR erasure |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `vehicle.registered.v1` | A new vehicle row is created | `driver-service`, `courier-service`, `audit-service`, `analytics-service` |
| `vehicle.approved.v1` | A vehicle is approved (after admin review) | `driver-service`, `courier-service`, `notification-service`, `audit-service` |
| `vehicle.insurance.expired.v1` | An insurance policy is past expiry | `driver-service`, `courier-service`, `driver-availability-service`, `audit-service` |
| `vehicle.inspection.expired.v1` | An inspection certificate is past expiry | `driver-service`, `courier-service`, `driver-availability-service`, `audit-service` |
| `vehicle.erased.v1` | GDPR erasure | `audit-service`, `analytics-service` |
| `vehicle.insurance.expiring.v1` | Insurance is expiring (30, 7, 1 day) | `notification-service`, `audit-service` |
| `vehicle.inspection.expiring.v1` | Inspection is expiring (30, 7, 1 day) | `notification-service`, `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | hot-reload vehicle config | reload in-process config |

## 12. External Integrations

- **Vault** — DB credentials; no third-party API
  keys.
- **Redis** — claim hot-cache.
- **Kafka** — event bus.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `vehicle.documents.expiry_warning_days` | int[] | configuration-service | default `[30, 7, 1]` |
| `vehicle.documents.expiry_grace_days` | int | configuration-service | default 7 |
| `vehicle.plate_format_per_country` | JSONB | configuration-service | per-country plate format validation |
| `vehicle.insurance.min_coverage_minor` | int | configuration-service | per country; default 0 |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer
  token. Self-service endpoints accept the
  gateway-injected `X-User-Id`. Service endpoints
  require `client_credentials` with the
  `vehicle.read` / `vehicle.write` / `vehicle.read.any`
  client role.
- **AuthZ**: resource-level check — only the
  primary owner (driver or courier) or an admin
  can mutate the vehicle.
- **Secrets**: Vault; rotated quarterly.
- **PII**: the `vehicles` row contains
  `owner_driver_id`, `owner_courier_id` (PII
  references); plate (PII, encrypted at rest).
  No name / email / phone stored.
- **GDPR**: `POST /v1/vehicles/{id}/erase` anonymizes
  the row; the `vehicle_id` is preserved for
  referential integrity; trip / delivery records
  retain the `vehicle_id` reference but their PII
  fields are redacted.
- **mTLS**: in-cluster mTLS via sidecar.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=vehicle-service`, `version`, `env`,
  `region`, `correlation_id`, `request_id`,
  `trace_id`, `user_id`, `action`, `result`,
  `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `vehicle_state_distribution{state}`
  - `vehicle_documents_expiring_total{type,days}`
  - `vehicle_documents_expired_total{type}`
  - `vehicle_owners_per_vehicle_histogram`
- **Traces**: OpenTelemetry. Sample 100% on errors,
  10% on success.
- **Health**: `/health`, `/ready`, `/started`.

## 16. Scalability

- **Replicas**: default 4 per region; minimum 2.
- **HPA**: CPU 60% target; custom metric
  `vehicle_lookups_per_second` (target 2k/replica).
- **Hot path**: vehicle read by `vehicle_id` (PK
  index hit) → return row. P99 ≤ 30 ms.

## 17. Local Development

- Run with `make up-vehicle` (the platform's
  docker-compose v2 starts Postgres, Redis, Kafka,
  and a stub vehicle-insurance integration).

## 18. Deployment

- **Image**: `registry.example.com/services/vehicle-service:{semver}`.
- **Replicas**: 4 (prod, per region), 2 (staging),
  1 (dev).
- **Resource limits**: 500m vCPU / 512 MiB RAM per
  pod.
- **Migrations**: Kubernetes Job before the
  deployment's pods start.
- **Pod disruption budget**: `minAvailable: 2` in
  production.
- **Network policy**: ingress from `api-gateway`,
  `admin-service`; egress to `identity-service`,
  the DB, Redis, Kafka, Vault.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-service`](../courier-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`driver-service`](../driver-service/README.md)

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
- [`../../workflows/COURIER_WORKFLOWS.md`](../../workflows/COURIER_WORKFLOWS.md) — courier shifts, dispatch, delivery
