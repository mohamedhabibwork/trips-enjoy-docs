# ride-history-service — Business Requirements Document

## 1. Document Purpose

Read by product, engineering, and customer support to align on
what `ride-history-service` does. The read model is what the
customer sees as "my trips"; it must be fast, complete, and
honest.

## 2. Business Context

A customer wants to see "the rides I've taken"; a driver wants
to see "the trips I've completed"; an admin wants to see "all
trips." These views are read-heavy and latency-sensitive. The
trip aggregate is the source of truth, but querying it directly
for every history view is too slow. `ride-history-service` is a
denormalised read model that is fast and easy to query.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for the ride history read model | 100% of trip.completed.v1 lead to an entry |
| BR--002 | Project the trip within 30 seconds of completion | p99 ≤ 30s |
| BR--003 | Serve "my trips" reads in ≤ 200ms p99 | read latency |
| BR--004 | Be honest: the entry is consistent within 30 seconds of the trip completing | 100% |
| BR--005 | Honour retention (7 years) | 100% |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | fast, complete history |
| Customer | consumer | sees "my trips" |
| Driver | consumer | sees "my trips" |
| Customer Support | operator | reads on behalf |
| Engineering (Rides) | builder | read replica performance |

## 5. Actors / Personas

- **Customer** — sees "my trips" in the app.
- **Driver** — sees "my trips" in the app.
- **Customer Support** — reads on behalf.
- **Admin** — reads all.
- **`trip-service`** — emits the trigger.
- **`ride-payment-integration-service`** — emits the fare.
- **`review-rating-service`** — emits the rating.

## 6. Business Capabilities

- Consume `trip.completed.v1`, `ride.payment.completed.v1`,
  `review.submitted.v1`.
- Project into the read model.
- Serve the per-customer, per-driver, and admin reads.
- Filter and paginate.
- Cache hot reads.
- Apply retention.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST project `trip.completed.v1` into an entry within 30 seconds. | MUST | Product |
| BR--011 | The service MUST update the entry on `ride.payment.completed.v1`. | MUST | Product |
| BR--012 | The service MUST update the entry on `review.submitted.v1`. | MUST | Product |
| BR--013 | The service MUST serve `GET /v1/history/trips` (customer) with P99 ≤ 200ms. | MUST | Product |
| BR--014 | The service MUST serve `GET /v1/drivers/{id}/trips` (driver) with P99 ≤ 200ms. | MUST | Driver |
| BR--015 | The service MUST serve `GET /v1/admin/trips` (admin) with pagination. | MUST | Customer Support |
| BR--016 | The service MUST support cursor-based pagination. | MUST | Product |
| BR--017 | The service MUST support filters (date range, ride type, status). | MUST | Product |
| BR--018 | The service MUST apply 7-year retention. | MUST | Compliance |
| BR--019 | The service MUST be idempotent: replaying the same event does not double-project. | MUST | Engineering |
| BR--020 | The service MUST record an audit event for every projection. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The entry is the union of trip + payment + review. | |
| BR--031 | The entry shows "pending payment" if the trip is completed but the payment is not yet captured. | Honest. |
| BR--032 | The entry shows "paid" after `ride.payment.completed.v1`. | |
| BR--033 | The entry shows the rating after `review.submitted.v1`. | |
| BR--034 | The read model is eventually consistent with the source of truth; lag is acceptable up to 30 seconds. | |

## 9. Assumptions

- The trip's data is final by the time `trip.completed.v1` is
  emitted.
- The customer's name and the driver's name are read from
  `customer-service` / `driver-service` and cached.

## 10. Constraints

- The service is read-only from the API; all writes are
  event-driven.
- All money in minor units with currency.
- All times in UTC.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `trip-service` | service | trip completed event |
| `ride-payment-integration-service` | service | payment completed event |
| `review-rating-service` | service | review submitted event |
| `customer-service` | service | name (cached) |
| `driver-service` | service | name (cached) |
| `configuration-service` | service | cache TTL, retention |

## 12. Business Workflows

- **Project trip.completed.v1** — see `WORKFLOWS.md`.
- **Project ride.payment.completed.v1** — see `WORKFLOWS.md`.
- **Project review.submitted.v1** — see `WORKFLOWS.md`.
- **Read "my trips"** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- `customer-service` / `driver-service` down: use the cached name;
  if no cache, fall back to the ID-only view.
- Projection lag: the reconciliation job in `reporting-service`
  detects stale entries and pages on-call.

## 14. Success Criteria

- The customer's "my trips" is always within 30 seconds of the
  trip completing.
- The admin's "all trips" is paginated and fast.
- The 7-year retention is applied.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Projection lag P99 | ≤ 30s | `ride_history_projection_lag_seconds` |
| Read latency P99 | ≤ 200ms | `ride_history_read_seconds` |
| Cache hit ratio | ≥ 80% | `ride_history_cache_hit_ratio` |

## 16. Acceptance Criteria

- A trip.completed.v1 leads to an entry within 30 seconds.
- A ride.payment.completed.v1 updates the entry within 30
  seconds.
- A review.submitted.v1 updates the entry within 30 seconds.
- The customer's "my trips" is paginated and fast.
- The 7-year retention is applied.

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

