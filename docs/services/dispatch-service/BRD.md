# dispatch-service — Business Requirements Document

## 1. Document Purpose

Read by product, engineering, operations, and driver experience to
align on what `dispatch-service` does. Matching is the marketplace's
core: a good match is fast, fair, and respects driver choice; a bad
match is a stranded customer and an unhappy driver.

## 2. Business Context

A ride request becomes a real ride only when a driver accepts. The
window between "the customer wants a ride" and "a driver has
accepted" is owned by `dispatch-service`. It is the marketplace's
auction house — it chooses who gets the offer, how long they have
to respond, and what happens when nobody does.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for match attempts | 100% of `ride.request.created.v1` lead to a `dispatch.*` outcome |
| BR--002 | Match within 90s for ≥ 95% of requests | `dispatch_match_seconds` p95 |
| BR--003 | Be fair to drivers (no driver gets all the offers) | Gini coefficient on offers per driver ≤ 0.3 |
| BR--004 | Honour the offer TTL (15s) | 100% of offers expire on time |
| BR--005 | Detect "no driver" within the configured window | 100% of attempts end with `matched` or `no_driver` |
| BR--006 | Be robust to driver going offline mid-offer | 100% of offline-during-offer result in next candidate |
| BR--007 | Allow surge to influence order without unfairness | surge + fairness both apply |
| BR--008 | Emit the right event for every outcome | 100% of attempts emit one of `matched` / `no_driver` / `offer_expired` |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | match rate, customer ETA |
| Driver Operations | reviewer | fairness, offer TTL |
| Engineering (Rides) | builder | correctness, latency |
| Trust & Safety | reviewer | bad-actor detection (a driver who accepts but never shows) |
| Customer Support | operator | ability to read a match attempt |

## 5. Actors / Personas

- **Customer (rider)** — initiates a request; the result of the
  match determines whether they get a ride.
- **Driver** — receives offers; may accept or let the offer expire.
- **Driver Operations** — tunes fairness and TTL.
- **Customer Support** — reads match attempts for dispute
  investigation.
- **`ride-request-service`** — produces the request event.
- **`driver-availability-service`** — provides online drivers.
- **`driver-location-service`** — provides last known positions.

## 6. Business Capabilities

- Begin a match attempt on `ride.request.created.v1`.
- Search for candidates in the pickup zone, sorted by ETA, fairness,
  and recent activity.
- Send an offer to the top candidate with a 15s TTL.
- On accept: emit `dispatch.matched.v1`.
- On expire: emit `dispatch.offer.expired.v1` and try the next.
- After N attempts: emit `dispatch.no_driver.v1`.
- Honour the customer's cancellation (drop the attempt).
- Honour the driver's offline transition (drop the candidate).
- Persist the attempt for audit and fairness analysis.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST consume `ride.request.created.v1` and begin a match attempt within 1 second. | MUST | Product |
| BR--011 | The service MUST consider only online drivers in the pickup zone who accept the requested ride type. | MUST | Product |
| BR--012 | The service MUST sort candidates by ETA (ascending), with a fairness tie-breaker. | MUST | Driver Operations |
| BR--013 | The service MUST send an offer to the top candidate with a 15s TTL. | MUST | Product |
| BR--014 | The service MUST emit `dispatch.offer.expired.v1` when the TTL elapses without an accept. | MUST | Product |
| BR--015 | The service MUST emit `dispatch.matched.v1` on accept and stop the search. | MUST | Product |
| BR--016 | The service MUST emit `dispatch.no_driver.v1` after N attempts without an accept. | MUST | Product |
| BR--017 | The service MUST honour a `ride.request.cancelled.v1` and drop the attempt. | MUST | Product |
| BR--018 | The service MUST honour a `driver.availability.offline.v1` and drop the candidate. | MUST | Product |
| BR--019 | The service MUST expand the search radius each attempt (configurable) before giving up. | MUST | Product |
| BR--020 | The service MUST persist the attempt (candidates considered, offers, outcomes) for audit. | MUST | Compliance |
| BR--021 | The service MUST support surge: a higher surge zone gets a larger candidate pool. | SHOULD | Product |
| BR--022 | The service MUST allow surge to influence the order without breaking fairness. | MUST | Product |
| BR--023 | The service MUST record an audit event for every state transition with `correlation_id` and `actor_id`. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The 15s offer TTL is non-extendable. | The driver either accepts in 15s or the next candidate is tried. |
| BR--031 | A driver who recently got an offer (within `recent_offers_window_minutes`) is deprioritised. | Fairness. |
| BR--032 | A driver who cancelled the last N rides is deprioritised. | Quality. |
| BR--033 | The search radius expands by `expansion_factor` each attempt. | Default 1.5. |
| BR--034 | A driver who is currently `online_busy` is excluded. | Dispatch should not offer to a busy driver. |
| BR--035 | A driver with a high fraud score is excluded. | Consumed from `fraud.risk.scored.v1`. |

## 9. Assumptions

- The driver's location is approximate (last known + small drift);
  we use it to estimate ETA.
- The driver app is the source of truth for accept/reject.
- The 15s offer timer is enforced server-side via a Redis sorted-set;
  the driver app's local timer is advisory only.

## 10. Constraints

- One match attempt per ride request.
- The match attempt is bounded: at most N attempts, then
  `dispatch.no_driver.v1`.
- All emitted events go through the outbox.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `ride-request-service` | service | request event |
| `driver-availability-service` | service | online drivers |
| `driver-location-service` | service | last known positions |
| `eta-routing-service` | service | ETA to pickup |
| `driver-service` | service | driver rating, recent cancellations |
| `configuration-service` | service | fairness, TTL, max attempts |
| `fraud-risk-service` | service | exclude high-risk drivers |

## 12. Business Workflows

- **Match attempt (happy path)** — see `WORKFLOWS.md`.
- **Match attempt (offer expires)** — see `WORKFLOWS.md`.
- **No driver after N attempts** — see `WORKFLOWS.md`.
- **Customer cancels mid-match** — see `WORKFLOWS.md`.
- **Driver goes offline mid-offer** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- ETA service down: fall back to haversine distance; if haversine
  is also unavailable, use the candidate's last known location as
  the ETA proxy.
- Driver app down (no response): the offer expires after 15s; the
  next candidate is tried.
- Push delivery failure: the offer is still tracked server-side; the
  driver app will see the offer on next foreground via
  `GET /v1/dispatch/drivers/{id}/offers`.

## 14. Success Criteria

- Match rate is within the city's target band; no-driver rate is
  low.
- Fairness metrics are within the published envelope.
- The 15s TTL is honoured 100% of the time.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Match rate (city, ride type) | ≥ 95% within 90s | `dispatch_match_seconds` histogram |
| No-driver rate (city) | < 5% | `dispatch_no_driver_total` / attempts |
| Offer expiration rate | < 30% | `dispatch_offer_expirations_total` / offers |
| Fairness (Gini) | ≤ 0.3 | `reporting-service` aggregate |
| Match latency P99 | ≤ 30s | `dispatch_match_seconds` p99 |

## 16. Acceptance Criteria

- A ride request leads to exactly one of `dispatch.matched.v1`,
  `dispatch.no_driver.v1`, or `ride.request.cancelled.v1` (the
  attempt is dropped).
- The 15s TTL is enforced server-side; the offer cannot be
  accepted after expiry.
- The fairness policy is applied to every attempt; the
  `dispatch_fairness_skips_total` metric is non-zero when
  fairness is active.
- All match attempts are persisted in `dispatch.attempts` with the
  list of candidates considered and the outcome.

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

