# ride-safety-service

## 1. Purpose

`ride-safety-service` owns the **trip safety state** and the
**emergency response** flow. It is the system that handles SOS,
share-trip, audio recording, and incident reports during a ride.
It is on the critical path for life-safety events.

## 2. Bounded Context

Bounded context: **Trip Safety / Emergency**.

In scope:

- The trip safety state (active, in-incident, recording,
  sharing).
- SOS events (customer or driver triggered).
- Share-trip links (trusted contacts).
- Audio recording (in-trip).
- Incident reports and audit.
- Encryption of sensitive artifacts.

Out of scope (explicitly):

- The trip aggregate — `trip-service`.
- The driver profile — `driver-service`.
- The customer profile — `customer-service`.
- The general support ticket — `support-service` (we open
  incidents; they own the queue).

## 3. Responsibilities

- Accept SOS events from the customer or driver app.
- Notify trusted contacts (SMS + push).
- Open P1 support tickets.
- Persist incidents (encrypted, audit).
- Accept share-trip requests and send SMS to trusted contacts
  with a live location link.
- Accept audio recording requests; stream to `file-service` with
  encryption.
- Maintain the trip safety state (a per-trip row).

## 4. Explicitly NOT Owned

- The trip aggregate.
- Driver / customer profile.
- General support queue.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer app | system | SOS, share, record |
| Driver app | system | SOS, share, record |
| `trip-service` | system | read trip context; emit `trip.started.v1` |
| `notification-service` | system | notify trusted contacts |
| `support-service` | system | open P1 ticket |
| `file-service` | system | store audio recordings |
| `audit-service` | system | immutable audit log |
| `admin-service` | system | read; close incident with reason |

## 6. Dependencies

### Synchronous (REST)

- `trip-service` — get trip context — SLO 200ms — circuit breaker:
  yes.
- `customer-service` — get trusted contacts — SLO 200ms — circuit
  breaker: yes.
- `driver-location-service` — live location — SLO 100ms — circuit
  breaker: yes.

### Asynchronous (events consumed)

- `trip.started.v1` from `trip-service` — initialise the trip
  safety state — duplicate handling: inbox dedup.
- `trip.completed.v1` from `trip-service` — close the trip safety
  state — duplicate handling: inbox dedup.
- `ride.safety.sos.v1` (self-consumed) — for audit / replay —
  duplicate handling: inbox dedup.

### Asynchronous (events produced)

- `ride.safety.sos.v1` — on SOS.
- `ride.safety.share.v1` — on share-trip activation.
- `ride.safety.incident.v1` — on incident opened.
- `ride.safety.recording.finalized.v1` — on audio recording
  complete.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `ride_safety`.
- Cache: Redis (per-service) for the per-trip state.
- Event broker: Kafka.
- File storage: `file-service` (encrypted blobs).
- Notification: `notification-service`.

## 8. Database Ownership

- Schema: `ride_safety` (owned exclusively by this service).
- Migrations: `services/ride-safety-service/migrations/`.
- Soft delete: no (incidents are immutable).
- Partitioning: no (volume is moderate).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/safety/sos | bearer (customer / driver) | trigger SOS |
| POST | /v1/safety/share | bearer (customer / driver) | share-trip |
| POST | /v1/safety/record | bearer (customer / driver) | start audio recording |
| GET | /v1/safety/trips/{trip_id} | bearer (safety, admin, support) | read trip safety state |
| GET | /v1/safety/incidents/{id} | bearer (safety, admin, support) | read incident |
| POST | /v1/safety/incidents/{id}/close | bearer (admin) | close incident |
| GET | /v1/safety/incidents | bearer (safety, admin, support) | list |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `ride.safety.sos.v1` | on SOS | `notification-service`, `support-service`, `audit-service` |
| `ride.safety.share.v1` | on share-trip activation | `notification-service`, `audit-service` |
| `ride.safety.incident.v1` | on incident opened | `notification-service`, `support-service`, `audit-service` |
| `ride.safety.recording.finalized.v1` | on audio recording complete | `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.started.v1` | `trip-service` | initialise | create `ride_safety.trips` row |
| `trip.completed.v1` | `trip-service` | close | mark `closed` |
| `ride.safety.sos.v1` (self) | self | audit / replay | (informational) |

## 12. External Integrations

- `file-service` (in-cluster) for audio storage.
- No external map provider.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `ride_safety.sos.notify_trusted_contacts_max` | int | configuration-service | default 5 |
| `ride_safety.sos.escalate_to_oncall_seconds` | int | configuration-service | default 30 |
| `ride_safety.recording.max_duration_seconds` | int | configuration-service | default 600 |
| `ride_safety.share.refresh_seconds` | int | configuration-service | default 30 |
| `ride_safety.incident.p1_min_severity` | text | configuration-service | default "high" |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: customer / driver can trigger SOS / share / record for
  their own trip; safety / admin / support can read all.
- Secrets: Vault at `secret/ride_safety/{env}/*`.
- PII: precise location, audio recordings — encrypted at rest
  (per-column encryption for location, encrypted blob in
  `file-service` for audio).
- Audit: every SOS / share / record is audited.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `trip_id`,
  `actor_id`, `actor_type`, `route`, `latency_ms`, `status`.
- Metrics: `ride_safety_sos_total{city, actor_type}`,
  `ride_safety_sos_seconds` (histogram),
  `ride_safety_share_total{city}`,
  `ride_safety_recording_total{city}`,
  `ride_safety_incident_total{city, severity}`,
  `ride_safety_oncall_paged_total`.
- Traces: OpenTelemetry, root span per request; child spans per
  notification call.
- Health: `/health`, `/ready` (DB + Kafka + Redis + downstream
  readiness), `/started`.

## 16. Scalability

- Replicas: 6 (default); HPA on CPU and on
  `ride_safety_sos_seconds_p99`.
- Hot path: SOS. The notify-trusted-contacts fan-out is bounded
  by `notify_trusted_contacts_max`.
- Read replicas: 1 for the incident read path.

## 17. Local Development

```bash
docker compose up ride-safety-service postgres kafka redis
bun run --filter ride-safety-service dev
```

Seed data: a default customer with trusted contacts; a default
trip.

## 18. Deployment

- Image: `registry.uber.io/ride-safety-service:<sha>`.
- Replicas: 6 (HPA to 30).
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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`notification-service`](../notification-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`communication-gateway-service`](../communication-gateway-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`file-service`](../file-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`notification-service`](../notification-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md), [`zone-service`](../zone-service/README.md)

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
- [`../../workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) — SOS, fraud, emergency response
