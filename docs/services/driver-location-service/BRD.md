# driver-location-service — Business Requirements Document

## 1. Document Purpose

Read by engineering, operations, and trust & safety to align on what
`driver-location-service` does. The driver location stream is the
foundation of matching, ETA, and safety; it must be fast, fresh, and
honest.

## 2. Business Context

Every online driver emits GPS points at up to 5 Hz. The platform
needs to know "where is this driver right now?" within seconds so
that dispatch can offer the right ride, ETA can be accurate, and
safety can intervene when something is wrong. The default schema
(one row per driver) cannot absorb 10k drivers × 5 Hz = 50k writes
per second. `driver-location-service` is the system of record for
that stream.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for driver location | 100% of dispatch reads come from this service |
| BR--002 | Sustain 1M points/s writes per region | sustained throughput |
| BR--003 | Publish a curated 1Hz stream to consumers | freshness p99 ≤ 2s |
| BR--004 | Serve the per-driver "current" read in ≤ 50ms p99 | read latency |
| BR--005 | Retain the recent trail for at least 2 hours | trail retention |
| BR--006 | Be honest about stale data | if no point in 30s, mark `stale=true` on the read |
| BR--007 | Not lose points under retry | durable; PG + WAL + outbox |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | match quality, ETA accuracy |
| Trust & Safety | consumer | live location for SOS |
| Engineering (Rides) | builder | throughput, freshness |
| Driver Operations | reviewer | idle detection (uses location) |
| Analytics | consumer | aggregated hourly cells |

## 5. Actors / Personas

- **Driver app** — emits GPS points.
- **Dispatch system** — reads the curated stream and the
  per-zone current view.
- **Trip service** — reads the curated stream for tracking.
- **ETA / routing service** — reads the curated stream for live
  ETA.
- **Trust & Safety** — reads the live location for any active trip.
- **Reporting** — reads the aggregated hourly cells (separate
  pipeline).

## 6. Business Capabilities

- Accept GPS points at up to 5 Hz per driver.
- UPSERT last known position.
- INSERT into the partitioned trail.
- Publish a curated 1 Hz event stream.
- Expose per-driver and per-zone reads.
- Tolerate a driver going offline (no new points expected).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accept a GPS point within 100ms p99. | MUST | Product |
| BR--011 | The service MUST UPSERT the `current_location` row atomically with the trail INSERT (same DB transaction). | MUST | Engineering |
| BR--012 | The service MUST publish a curated `driver.location.updated.v1` event within 2s of accepting a point. | MUST | Product |
| BR--013 | The service MUST throttle the curated stream to 1Hz per driver. | MUST | Product |
| BR--014 | The service MUST retain the trail for at least 2 hours. | MUST | Trust & Safety |
| BR--015 | The service MUST mark `stale=true` on the read response if the last point is older than 30s. | MUST | Trust & Safety |
| BR--016 | The service MUST NOT log precise coordinates in plain text. | MUST | Privacy |
| BR--017 | The service MUST NOT allow a driver to write another driver's location. | MUST | Security |
| BR--018 | The service MUST support a batch upload for offline recovery (e.g. tunnel). | SHOULD | Engineering |
| BR--019 | The service MUST return the per-zone "drivers in zone" list in ≤ 200ms p99. | MUST | Dispatch |
| BR--020 | The service MUST record an audit event for every state transition (online/offline) it observes. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The trail is partitioned by day; the retention is 2h after the last point per driver (the partition is dropped at 2h+30min). | |
| BR--031 | A point with `accuracy_m > 100` is rejected (GPS is too noisy). | Driver app retries. |
| BR--032 | A point with `lat` or `lon` outside the world is rejected. | Validation. |
| BR--033 | The curated stream uses a 1Hz throttle per driver; multiple points within a second collapse to the latest. | Producer-side throttle. |
| BR--034 | The service is a sink on the write path; it does not call downstream services. | Keeps the write path fast. |

## 9. Assumptions

- The driver app is the source of truth for "the driver is at this
  GPS point right now." The service records what the app says.
- The driver's online state is owned by `driver-availability-service`.
  We react to its events to know whether to expect points.
- The map provider integration is owned by `eta-routing-service` and
  `geolocation-service`. We do not call the provider directly.

## 10. Constraints

- One row per driver in `current_location`; UPSERT only.
- The `locations` table is append-only; never updated, never deleted
  by application code (partition drop only).
- The curated stream partition key is `driver_id` (per-aggregate
  ordering).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `driver-availability-service` | service | online/offline events |
| Kafka | broker | curated stream |
| PostgreSQL 18 | DB | durable storage + PostGIS |

## 12. Business Workflows

- **Driver streams GPS** — see `WORKFLOWS.md`.
- **Driver batch uploads on reconnect** — see `WORKFLOWS.md`.
- **Driver goes offline** — see `WORKFLOWS.md`.
- **Dispatch reads per-zone** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- Driver app is offline (tunnel): points are buffered client-side
  and uploaded in a batch on reconnect. We accept the batch and
  emit curated events at 1Hz for the duration of the batch.
- DB down on write: 503 to the driver app; the app retries with
  backoff.
- Kafka down: the write still succeeds (the outbox holds the
  curated event); reconciliation detects a stuck outbox.

## 14. Success Criteria

- Dispatch match quality is unaffected by the location service.
- ETA is accurate to within 10% of the actual arrival.
- Trust & Safety can see live location for any active trip.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Ingest latency P99 | ≤ 100ms | `driver_location_ingest_seconds` |
| Curated stream lag P99 | ≤ 2s | end-to-end metric |
| Sustained throughput | ≥ 1M points/s | `driver_location_points_total` rate |
| Stale read rate | < 1% | `driver_location_stale_reads_total` / reads |

## 16. Acceptance Criteria

- A 1M points/s load test sustains for 1 hour with P99 ≤ 100ms.
- The curated stream lag stays ≤ 2s under the same load.
- A driver going offline stops appearing in the per-zone list within
  5s.
- The trail is recoverable for at least 2 hours after the last
  point.

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

