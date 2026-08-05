# driver-availability-service — Business Requirements Document

## 1. Document Purpose

Read by product, engineering, operations, and trust & safety to align
on what `driver-availability-service` does. The driver's online state
is the gate for dispatch; getting it right is the difference between
a healthy marketplace and a stranded customer.

## 2. Business Context

A driver is either available to take a ride or not. That answer is
needed everywhere dispatch decides who gets an offer. A stale
"available" means an offer goes to a driver who is on break; a stale
"offline" means a customer sees a longer ETA than necessary.
`driver-availability-service` is the system of record for that answer.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for driver online state | 100% of dispatch decisions consume our state events |
| BR--002 | Move drivers to `busy` within 1 second of trip assignment | p99 ≤ 1s |
| BR--003 | Move drivers back to `available` within 1 second of trip completion | p99 ≤ 1s |
| BR--004 | Refuse to take a driver offline mid-trip | 100% of mid-trip offline attempts return 409 |
| BR--005 | Force-offline a suspended driver within 30 seconds of `driver.suspended.v1` | p99 ≤ 30s |
| BR--006 | Make zone and ride-type changes immediately visible to dispatch | p99 ≤ 5s |
| BR--007 | Honour the break policy | a driver can be on break ≤ 30 minutes; after that, alert |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | healthy marketplace |
| Engineering (Rides) | builder | correctness, low latency |
| Driver Operations | reviewer | break policy, idle flagging |
| Trust & Safety | reviewer | suspend and force-offline |
| Customer Support | operator | force-offline for a driver in trouble |

## 5. Actors / Personas

- **Driver** — owns the online state; can go online/offline, change
  zone, change ride types, take a break.
- **Trust & Safety** — can force-offline a driver with a reason.
- **Customer Support** — can force-offline a driver on request.
- **Admin** — full read; can suspend via `driver-service` (which
  triggers our consumer).

## 6. Business Capabilities

- Online and offline transitions.
- Zone and ride-type changes.
- Breaks (start/end).
- React to driver approval, suspension, document expiry.
- React to trip state transitions to mark the driver busy or
  available.
- Expose a per-zone "online drivers" view (used by dispatch and
  reporting).
- Emit `driver.availability.*.v1` events.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | A driver MUST be `approved` and not `suspended` to go online. | MUST | Driver Operations |
| BR--011 | A driver MUST be in a served zone to go online. | MUST | Operations |
| BR--012 | A driver MUST be able to choose at least one ride type to go online. | MUST | Product |
| BR--013 | A driver in `busy` (on a trip) MUST NOT be allowed to go offline. | MUST | Product |
| BR--014 | A driver in `busy` (on a trip) MUST NOT be allowed to change zone or ride types. | MUST | Product |
| BR--015 | A driver MUST be able to take a break up to N minutes. | MUST | Driver Operations |
| BR--016 | On `driver.suspended.v1`, a driver who is online MUST be forced offline within 30 seconds. | MUST | Trust & Safety |
| BR--017 | On `driver.document.expired.v1`, a driver who is online MUST be forced offline within 30 seconds. | MUST | Compliance |
| BR--018 | On `trip.started.v1`, the driver MUST be marked `busy` within 1 second. | MUST | Product |
| BR--019 | On `trip.completed.v1` (and on pre-pickup `trip.cancelled.v1`), the driver MUST be marked `available` within 1 second. | MUST | Product |
| BR--020 | A driver idle (online, no movement, no trip) for more than N minutes SHOULD be flagged for operations. | SHOULD | Driver Operations |
| BR--021 | The service MUST emit `driver.availability.{online,offline,busy,available}.v1` for every transition. | MUST | Platform Event Standards |
| BR--022 | The service MUST record an audit event for every state transition with `correlation_id` and `actor_id`. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A driver is in exactly one of: `offline`, `online_available`, `online_busy`, `on_break`. | |
| BR--031 | On `trip.started.v1`, transition to `online_busy`. | |
| BR--032 | On `trip.completed.v1`, transition to `online_available` (unless on break, in which case stay `on_break`). | |
| BR--033 | On pre-pickup `trip.cancelled.v1`, transition to `online_available` (unless on break). | |
| BR--034 | On mid-trip `trip.cancelled.v1` (e.g. driver-cancel after pickup), transition to `online_available`. | driver is back on the road |
| BR--035 | A break is at most `driver_availability.break.max_minutes` minutes. | After that, the driver is auto-set to `offline` and notified |
| BR--036 | A driver who goes offline and back online within `cooldown_seconds_after_offline` is flagged (but allowed). | Prevents churn gaming |

## 9. Assumptions

- The driver's profile (ride types, zone eligibility, KYC) is
  managed by `driver-service`. We read it on online and cache the
  result for the duration of the shift.
- The driver's location is owned by `driver-location-service`. We
  only consume the availability events; we do not look at GPS.
- Idle detection uses `driver-location-service` events (separate
  concern; we maintain a counter).

## 10. Constraints

- One row per driver (a `driver_availability` row).
- Online state is durable; the service recovers from a crash by
  replaying recent events (inbox + outbox).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `driver-service` | service | profile, approved, suspended, document expiry |
| `zone-service` | service | zone validation |
| `trip-service` | service | trip start/complete/cancel events |
| `dispatch-service` | service | consumes our events |
| `driver-location-service` | service | consumes our online events to start streaming |
| `notification-service` | service | driver notifications (e.g. "you are back online") |

## 12. Business Workflows

- **Driver goes online** — see `WORKFLOWS.md`.
- **Driver goes offline** — see `WORKFLOWS.md`.
- **Driver takes a break** — see `WORKFLOWS.md`.
- **Driver changes zone** — see `WORKFLOWS.md`.
- **Driver is suspended mid-shift** — see `WORKFLOWS.md`.
- **Trip start → busy** — see `WORKFLOWS.md`.
- **Trip end → available** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- Driver goes offline while in `busy`: 409 `CANNOT_OFFLINE_BUSY`;
  the driver is told to wait for the trip to end.
- Driver goes online while suspended: 403 `DRIVER_SUSPENDED`.
- Driver tries to go online in a zone that is not served: 422
  `ZONE_UNSERVED`.
- Document expires mid-shift: forced offline within 30s; the driver
  is notified; the trip (if any) is unaffected.

## 14. Success Criteria

- The marketplace experiences fewer "ghost drivers" (online but
  unresponsive) thanks to the idle flag.
- Suspended drivers are removed from the marketplace within the
  stated window.
- Trip assignment latency is unaffected by the state service
  (it is read from the cached event stream).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Time-to-busy after `trip.started.v1` | p99 ≤ 1s | driver_state_transitions_total{to=busy} |
| Time-to-available after `trip.completed.v1` | p99 ≤ 1s | driver_state_transitions_total{to=available} |
| Force-offline latency after suspend | p99 ≤ 30s | driver_offline_total{reason=suspended} vs `driver.suspended.v1` |
| Idle-driver flag rate | < 5% of online drivers per hour | reporting |
| Break-policy violations | 0 (auto-offline at N min) | reporting |

## 16. Acceptance Criteria

- A driver cannot go offline mid-trip; the API returns 409.
- A suspended driver is force-offline within 30s of the event.
- A driver idle for more than N minutes is flagged in the metrics
  and in the admin UI.
- All state transitions emit events and are recorded in
  `driver_availability.availability_history`.

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

