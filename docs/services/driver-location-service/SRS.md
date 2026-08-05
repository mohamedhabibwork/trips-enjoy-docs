# driver-location-service — Software Requirements Specification

## 1. Introduction

This document specifies the requirements for
`driver-location-service`. The service is the spatial truth for
online drivers; the implementation must optimise for write
throughput, freshness, and read latency.

## 2. Scope

In scope:

- GPS point ingest at up to 5 Hz per driver.
- UPSERT into `current_location`.
- INSERT into `locations` (partitioned).
- Curated 1 Hz event stream.
- Reads: per-driver current, per-driver trail, per-zone current.
- Batch upload on reconnect.

Out of scope:

- Driver online state.
- Trip tracking as a first-class concern.
- Geocoding or ETA.
- Map provider integration.

## 3. System Context

```mermaid
flowchart LR
    DR[Driver app] --> DL[driver-location-service]
    DA[driver-availability-service] -. driver.availability.online.v1 / .offline.v1 .-> DL
    DL --> PG[(PostgreSQL 18 + PostGIS)]
    DL --> RD[(Redis)]
    DL -. driver.location.updated.v1 (1Hz curated) .-> K[(Kafka)]
    K --> DSP[dispatch-service]
    K --> TR[trip-service]
    K --> RS[ride-safety-service]
    K --> ETA[eta-routing-service]
    DL --> DSP
    DL --> RS
    DL --> ETA
```

## 4. Actors

- **Driver app** — JWT role `driver`. Writes only own location.
- **System / safety / admin** — read with RBAC.
- **driver-availability-service** — system actor via events.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | Accept `POST /v1/location` with `{lat, lon, bearing, speed_mps, accuracy_m, recorded_at}` from the assigned driver. | MUST |
| FR--002 | Reject if `lat ∉ [-90,90]` or `lon ∉ [-180,180]`. | MUST |
| FR--003 | Reject if `accuracy_m > 100`. | MUST |
| FR--004 | Reject if `recorded_at` is more than 60s in the past or 5s in the future. | MUST |
| FR--005 | UPSERT into `current_location` keyed by `driver_id`. | MUST |
| FR--006 | INSERT into `locations` (partitioned by day) within the same DB transaction. | MUST |
| FR--007 | Publish a curated `driver.location.updated.v1` at most once per second per driver. | MUST |
| FR--008 | Throttle at the producer side; multiple points within a second collapse to the latest. | MUST |
| FR--009 | Reject points for a driver that has gone offline (with a warning, not a hard error — the driver app may be in a tunnel). | SHOULD |
| FR--010 | Accept `POST /v1/location/batch` with an array of points for the same driver. | MUST |
| FR--011 | Reject batches larger than 1000 points. | MUST |
| FR--012 | For each point in a batch, perform the same UPSERT + INSERT. | MUST |
| FR--013 | For each point in a batch, publish a curated event at 1Hz (collapsed to the latest). | MUST |
| FR--014 | `GET /v1/location/{driver_id}/current` returns the last known position with `recorded_at`, `stale` flag, `accuracy_m`. | MUST |
| FR--015 | `GET /v1/location/{driver_id}/trail?from=…&to=…` returns up to N points in the time window. | MUST |
| FR--016 | `GET /v1/location/zone/{zone_id}/current?radius_m=…` returns drivers in the zone (PostGIS `ST_DWithin`). | MUST |
| FR--017 | The `stale` flag is true if the last point is older than 30s. | MUST |
| FR--018 | Reject any point from a `driver_id` that does not match the JWT subject. | MUST |
| FR--019 | All curated events go through the transactional outbox. | MUST |
| FR--020 | Do not log precise lat/lon in any log line. | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P99 latency for `POST /v1/location` | ≤ 100ms |
| NFR--002 | performance | P99 latency for `GET /v1/location/{id}/current` | ≤ 50ms |
| NFR--003 | performance | P99 latency for per-zone read | ≤ 200ms |
| NFR--004 | throughput | sustained points/s per region | 1M |
| NFR--005 | throughput | peak points/s per region | 3M |
| NFR--006 | availability | uptime | 99.95% (Tier-1) |
| NFR--007 | scalability | concurrent online drivers | 1M per region |
| NFR--008 | maintainability | MTTR for a bad deploy | ≤ 15 minutes |
| NFR--009 | freshness | curated stream lag P99 | ≤ 2s |
| NFR--010 | observability | tracing coverage | 100% (sampled at 1% for writes) |

## 7. API Requirements

REST per `architecture/API_STANDARDS.md`. The driver's `POST
/v1/location` is rate-limited per driver (10/s) at the gateway and
internally (5/s). The batch endpoint has a separate, looser limit.
Errors use the standard envelope. Full contract in `INTEGRATION.md`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | One row per driver in `current_location` (PK = `driver_id`) | UPSERT only |
| DATA--002 | `locations` is range-partitioned by `recorded_at` (day) | append-only |
| DATA--003 | `geog` is `geography(Point, 4326)` | PostGIS |
| DATA--004 | All timestamps `timestamptz` UTC | RFC3339 at the wire |
| DATA--005 | `accuracy_m`, `bearing`, `speed_mps` are nullable | driver app may not report them |
| DATA--006 | No PII beyond the driver_id | encrypted at rest (disk-level KMS) |
| DATA--007 | Audit columns on `current_location` only | trail is append-only |

## 9. Validation Rules

- `lat ∈ [-90, 90]`, `lon ∈ [-180, 180]`.
- `accuracy_m ≤ 100` if present.
- `recorded_at` within ±60s of server time.
- `driver_id` matches the JWT subject.

## 10. State Transitions

The service is largely stateless; the "state" is the per-driver
cache of online/offline and the last-known position. There is no
explicit state machine; the row in `current_location` is the truth.

## 11. Authorization Requirements

- Driver can write only own location.
- System / safety / admin can read.
- Driver cannot read another driver's location.

## 12. Configuration Requirements

Consumed from `configuration-service` and refreshed on
`configuration.updated.v1`. See `README.md` §13.

## 13. Error Handling

| Error | Response | Recovery |
|-------|----------|----------|
| Bad coordinates | 400 `VALIDATION_FAILED` | driver app corrects |
| Bad accuracy | 400 `GPS_TOO_NOISY` | driver app retries |
| Driver id mismatch | 403 `FORBIDDEN` | none |
| Rate limited | 429 `RATE_LIMITED` | backoff |
| DB down | 503 `DEPENDENCY_TIMEOUT` | driver app retries |

## 14. Concurrency Requirements

- One UPSERT per `driver_id` at a time (PG row-level lock).
- The trail INSERT is append-only; no contention.
- The curated stream is throttled at the producer side; only one
  emit per driver per second.

## 15. Idempotency Requirements

- `Idempotency-Key` optional on `POST /v1/location` (the driver
  app can omit it; we accept the small chance of a duplicate on
  retry — UPSERT is idempotent by PK).
- `Idempotency-Key` required on `POST /v1/location/batch`.

## 16. Performance

- Dominant path: `POST /v1/location`.
- P50 / P95 / P99: 30ms / 70ms / 100ms.
- Throughput: 1M points/s sustained per region.

## 17. Scalability

- Horizontal: stateless, scale by HPA on
  `driver_location_points_total` and CPU.
- Vertical: 1 vCPU / 1 GiB per replica is the minimum; scale up
  first.
- The `locations` table is partitioned by day; the partition
  maintenance job pre-creates 30 days and drops old partitions.

## 18. Availability

- SLO: 99.95% over 30 days.
- Error budget: ~22 minutes per 30 days.
- Maintenance window: weekly Sun 04:00–06:00 UTC.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require a valid JWT bearer token | gateway validates |
| SEC--002 | Driver ownership is enforced for writes | `driver_id == sub` |
| SEC--003 | PII (precise GPS) is encrypted at rest | disk-level KMS |
| SEC--004 | Do not log precise coordinates | enforced by a custom log filter |
| SEC--005 | Idempotency keys are opaque UUIDs | |
| SEC--006 | TLS 1.3 at edge; mTLS in cluster | platform standard |

## 20. Privacy

- PII stored: precise GPS trail.
- Retention: 2h for the trail (partition drop); 30 days for the
  current location (overwritten).
- Erasure: per GDPR, the trail is dropped by partition; the
  current location is overwritten on the next point.

## 21. Auditability

- Online / offline transitions are recorded in
  `driver_location.driver_state_cache` (a small table fed from the
  `driver.availability.*.v1` events).
- Every state transition is logged with `correlation_id` and
  `driver_id`.
- The trail itself is the audit log of the driver's location.

## 22. Observability

- Logs: JSON to stdout with `correlation_id`, `driver_id`, `route`,
  `latency_ms`, `status`. **No lat/lon.**
- Metrics: see `README.md` §15.
- Traces: OpenTelemetry, sampled at 1% for writes.
- Alerts: SLO burn-rate, curated stream lag, write throughput,
  stale-read rate.

## 23. Maintainability

- Code style: TypeScript with `strict: true`; ESLint + Prettier.
- Test coverage: ≥ 80% line / branch.
- Documentation: this folder.

## 24. Disaster Recovery

- RPO: ≤ 5 seconds (a few seconds of points may be lost in a
  region-wide failure; the driver app resends buffered points on
  reconnect).
- RTO: ≤ 15 minutes (warm standby in the same region).

## 25. Acceptance Criteria

- 1M points/s load test sustains for 1 hour with P99 ≤ 100ms.
- Curated stream lag P99 ≤ 2s under load.
- Per-driver read returns the latest point within 50ms p99.
- Per-zone read returns within 200ms p99.
- The trail is recoverable for at least 2h after the last point.

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

