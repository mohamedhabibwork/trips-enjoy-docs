# scheduled-ride-service — Business Requirements Document

## 1. Document Purpose

Read by product, engineering, and customer support to align on
what `scheduled-ride-service` does. Scheduled rides are a key
product feature for airport runs, medical appointments, and
commuters; the service must materialise them on time.

## 2. Business Context

A customer who books a ride for tomorrow at 7am does not need the
app open at 7am. The platform's job is to create the live ride
request at the right time so that dispatch can find a driver.
This service is the system of record for those future-dated
bookings and the scheduler that materialises them.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for scheduled rides | 100% of scheduled rides originate here |
| BR--002 | Materialise a scheduled ride within the lead time window | p99 ≤ lead_time - 1 min |
| BR--003 | Allow customer cancellation up to N minutes before pickup | always |
| BR--004 | Be idempotent: replaying the same scheduled ride does not double-materialise | 100% |
| BR--005 | Notify the customer on booking, on the day, and on failure | 100% |
| BR--006 | Allow admin force-cancel with reason | always |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | scheduled ride conversion, on-time rate |
| Engineering (Rides) | builder | scheduler correctness, idempotency |
| Customer Support | operator | cancel, view |
| Trust & Safety | reviewer | customer suspension propagation |

## 5. Actors / Personas

- **Customer** — books, views, cancels own scheduled rides.
- **`ride-request-service`** — materialises the live request.
- **`pricing-service`** — pre-quotes at booking.
- **`customer-service`** — emits `customer.suspended.v1`.
- **Customer Support** — reads and force-cancels.
- **Admin** — same, with broader rights.

## 6. Business Capabilities

- Create, read, cancel, and limited-update scheduled rides.
- Schedule a job to fire `scheduled_ride.due.v1` at the right
  time.
- Materialise via `ride-request-service` on fire.
- Retry on materialisation failure.
- Notify the customer.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accept a scheduled ride for a time between now + 15 min and now + 30 days. | MUST | Product |
| BR--011 | The service MUST pre-quote the ride at booking time (best-effort; the actual quote at materialisation may differ). | SHOULD | Product |
| BR--012 | The service MUST fire `scheduled_ride.due.v1` at `scheduled_for - lead_time_minutes` (default 15 min). | MUST | Product |
| BR--013 | The service MUST retry materialisation up to N times on failure. | MUST | Product |
| BR--014 | The service MUST emit `scheduled_ride.failed.v1` on persistent failure. | MUST | Platform Event Standards |
| BR--015 | The service MUST allow customer cancellation up to N minutes before pickup. | MUST | Product |
| BR--016 | The service MUST allow admin force-cancel with a reason. | MUST | Customer Support |
| BR--017 | The service MUST auto-cancel on `customer.suspended.v1`. | MUST | Trust & Safety |
| BR--018 | The service MUST notify the customer on booking, on the day, and on failure. | MUST | Product |
| BR--019 | The service MUST be idempotent: replaying the same event does not double-materialise. | MUST | Product |
| BR--020 | The service MUST record an audit event for every state transition. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A scheduled ride is in one of: `pending`, `materialised`, `cancelled`, `failed`, `expired`. | |
| BR--031 | The free cancellation window is `scheduled_ride.cancellation.free_window_minutes` before pickup. | Configurable per city. |
| BR--032 | A scheduled ride that is past `scheduled_for + grace_minutes` without materialisation is marked `expired`. | |
| BR--033 | Parameters of a scheduled ride cannot be changed after creation; the customer must cancel and re-book. | |
| BR--034 | The scheduler uses `SELECT … FOR UPDATE SKIP LOCKED` for safe parallelism. | |

## 9. Assumptions

- The customer's identity and payment method are managed by
  `customer-service` and `payment-service`.
- Pricing at materialisation may differ from the pre-quote; the
  customer is told the final amount at live-ride time.

## 10. Constraints

- The scheduler sweep is bounded; jobs are picked up within
  `sweep_interval_seconds`.
- The materialisation is idempotent on `scheduled_ride_id`.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `customer-service` | service | validate customer |
| `pricing-service` | service | pre-quote |
| `zone-service` | service | validate zone |
| `ride-request-service` | service | materialise (event consumer) |
| `notification-service` | service | notify customer |
| `support-service` | service | open ticket on failure |

## 12. Business Workflows

- **Book a scheduled ride** — see `WORKFLOWS.md`.
- **Scheduler fires** — see `WORKFLOWS.md`.
- **Materialisation retry** — see `WORKFLOWS.md`.
- **Customer cancel** — see `WORKFLOWS.md`.
- **Customer suspended** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- `pricing-service` down at booking: 503; the customer retries.
- `ride-request-service` down at materialisation: retry with
  backoff; on persistent failure, emit
  `scheduled_ride.failed.v1` and notify the customer.
- Customer suspended between booking and materialisation: the
  job is auto-cancelled.

## 14. Success Criteria

- Scheduled rides are materialised on time.
- Materialisation success rate is ≥ 99%.
- Customer cancellation flow is straightforward.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Materialisation on-time rate | ≥ 99% | `scheduled_ride_lead_seconds` p99 |
| Materialisation success rate | ≥ 99% | `scheduled_rides_failed_total` / total |
| Customer cancellation rate | tracked | `scheduled_rides_cancelled_total{actor=customer}` / total |

## 16. Acceptance Criteria

- A scheduled ride within the allowed time window is accepted.
- A scheduled ride outside the window is rejected.
- The scheduler fires `scheduled_ride.due.v1` at the lead time.
- The materialisation is idempotent.
- A customer cancellation within the free window incurs no fee.
- A customer suspended mid-window auto-cancels the job.

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

