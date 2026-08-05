# courier-tracking-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for **what** the courier-tracking
service does for the business. It informs product decisions
(geo-aware matching, ETA accuracy), operations (live pool
visibility), and the engineering SRS.

## 2. Business Context

The courier's location is the most important real-time signal in
the food delivery system. The dispatcher needs to know which
couriers are *near* the restaurant to assign an order; the delivery
service needs the courier's location to compute an accurate ETA to
the customer; the safety service may need to detect an idle or
off-route courier.

The `courier-tracking-service` exists to make this signal available
at the rate and latency the rest of the system needs, while keeping
storage costs bounded and PII exposure minimal.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Ingest a location ping in p99 ≤ 100ms | ingestion latency |
| BR--002 | Emit a curated `courier.location.updated.v1` at ≥ 1 Hz per active courier | emit rate |
| BR--003 | Make "where is X right now" available in p99 ≤ 30ms | last-known read |
| BR--004 | Detect a stale courier within 60s of their last ping | stale detection |
| BR--005 | Keep per-courier storage cost bounded | trail retention ≤ 30 days |
| BR--006 | Never lose a ping (at-least-once, dedup'd on retry) | durability |
| BR--007 | Provide a city-level operational view of the live pool | admin metrics |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Food) | owns the food marketplace | accurate ETA; freshness |
| Operations | city ops | live pool visibility; stale couriers |
| Couriers (Trust & Safety) | end users | minimal battery / data impact |
| Engineering (Courier Domain) | implements | reliability; throughput |
| Privacy / Legal | governance | short retention; minimal PII |

## 5. Actors / Personas

- **Courier** — their mobile app pings location every second while
  on shift.
- **Dispatcher** (`courier-dispatch-service`) — reads current
  location to rank couriers.
- **Delivery service** — reads last-known for ETA computation.
- **Safety service** — reads location for anomaly detection.
- **Admin** — uses the operational view for city ops.

## 6. Business Capabilities

- Ingest location pings at high rate (target 1 Hz per courier, max
  5 Hz).
- Persist the current location (UPSERT).
- Persist a recent trail (partitioned by day, 30-day retention).
- Emit a curated outbound stream (`courier.location.updated.v1`)
  at a configurable rate.
- Serve synchronous reads (last-known, recent trail, nearby).
- Detect stale couriers (no ping in N seconds).
- Provide aggregate operational metrics (pool size, stale count,
  emit rate).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accept a location ping from the assigned courier only. | MUST | Security |
| BR--011 | The service MUST emit `courier.location.updated.v1` for every online courier at the configured curated rate. | MUST | Dispatcher |
| BR--012 | The service MUST persist the current location in a single-row UPSERT. | MUST | Architecture |
| BR--013 | The service MUST persist a trail of recent pings for at most 30 days. | MUST | Privacy / cost |
| BR--014 | The service MUST mark a courier as "stale" when no ping has been received in `stale_threshold_seconds`. | MUST | Operations |
| BR--015 | The service MUST throttle pings to `max_ping_hz` per courier. | SHOULD | Battery / data |
| BR--016 | The service MUST NOT emit curated events for offline couriers. | MUST | Operations |
| BR--017 | The service MUST serve a "last known" read in p99 ≤ 30ms. | MUST | Dispatcher |
| BR--018 | The service MUST support a "nearby couriers" query for dispatch. | MUST | Dispatcher |
| BR--019 | The service MUST pre-create daily partitions for the next 30 days. | MUST | Operations |
| BR--020 | The service MUST support reading the recent trail for an admin / support / safety context. | SHOULD | Safety |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A courier can ping only when they are `online` (per `courier-service` state). | Pings from offline couriers are rejected. |
| BR--031 | The curated stream rate is at most `curated_rate_hz` per courier. | Excess pings are persisted in the trail but NOT re-emitted until the next interval. |
| BR--032 | The trail is bounded to 30 days; older partitions are dropped. | Daily partition maintenance job. |
| BR--033 | A "stale" courier's curated events are suppressed until a fresh ping arrives. | The current-location read still returns the last known point (with `is_stale=true`). |
| BR--034 | The service MUST handle ~50k pings per second per region (peak). | Sustained; bursts higher are absorbed by the connection pool. |

## 9. Assumptions

- Couriers have granted location permission to the mobile app.
- The mobile app throttles to 1 Hz in steady state and 5 Hz during
  a state transition.
- `courier-service` emits `courier.availability.online.v1` when the
  courier starts a shift and `offline.v1` when they end.
- The dispatcher reads the curated stream for ranking and the
  synchronous read only when it needs a fresher point.

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST complete an ingest call in p99 ≤ 100ms.
- The service MUST NOT store location trails beyond 30 days.
- The service MUST NOT expose individual courier locations to
  customer-facing endpoints.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `courier-service` | service | online/offline state |
| `courier-dispatch-service` | consumer | reads location for matching |
| `delivery-service` | consumer | reads last-known for ETA |
| `eta-routing-service` | consumer | reads location for routing |
| `ride-safety-service` | consumer | reads location for safety |
| `configuration-service` | service | tuning parameters |
| `audit-service` | consumer | subscribes to audit events |
| PostgreSQL with PostGIS | platform | schema `courier_tracking` |

## 12. Business Workflows

- Courier comes online → starts pinging.
- Courier pings continuously while on shift.
- Courier comes offline → stops pinging; trail is retained for
  30 days.
- Stale detection (no ping in 60s).
- Daily partition maintenance (pre-create next 30 days, drop
  older).

## 13. Exception Workflows

- Courier is offline but pings: the service rejects the ping with
  409 `COURIER_OFFLINE`.
- Pings arrive faster than `max_ping_hz`: the service persists the
  trail but does not re-emit curated events until the next
  interval.
- Database is unavailable: the service buffers in memory up to
  10s of pings, then rejects with 503.
- Redis is unavailable: the service falls back to PostgreSQL
  `current_locations` for the last-known read (slower but
  available).

## 14. Success Criteria

- 99.95% ingestion availability over 30 days.
- P99 ingestion latency ≤ 100ms in production.
- Curated emit rate ≥ 1 Hz per active courier.
- Last-known read p99 ≤ 30ms.
- Stale detection within 60s of last ping.
- Zero data loss for pings (at-least-once + dedup).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Ingestion P99 | ≤ 100ms | from HTTP receive to 200 OK |
| Curated emit rate | ≥ 1 Hz per active courier | `courier_location_curated_emitted_total / active_courier_seconds` |
| Last-known P99 | ≤ 30ms | `GET /v1/couriers/{id}/location` |
| Stale detection lag | ≤ 60s | `now() - last_ping_at` for stale couriers |
| Trail write success | ≥ 99.99% | successful inserts / received pings |
| Data loss | 0 | verified by daily reconciliation with the curated stream |

## 16. Acceptance Criteria

- The service sustains 50k pings/s per region with p99 ≤ 100ms.
- Stale detection triggers within 60s for a paused courier.
- Trail retention is enforced (verified by partition-drop job).
- The "last known" read returns within 30ms p99.
- The "nearby couriers" query returns within 200ms p99 for a
  bounding box of a city.

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

