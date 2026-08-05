# ride-request-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by product, engineering, and operations stakeholders
to align on what `ride-request-service` does and why. It frames the
business problem, capabilities, rules, and KPIs that justify the
service's existence and scope. Engineering detail (APIs, data model,
state machine) is in `SRS.md`, `ERD.md`, and `WORKFLOWS.md`.

## 2. Business Context

A ride-hailing product begins with a customer's decision to go
somewhere. From that moment until a driver is assigned, the business
needs a single, durable record of "this customer wants a ride, here,
now, to there, at this price." Without that record:

- Dispatch cannot reliably search for a driver.
- The trip record that follows cannot be priced.
- Cancellations cannot be reconciled.
- Scheduled rides cannot materialise on time.
- The platform's marketplace cannot be observed (match rate, ETA, no-
  driver rate, cancellation rate).

`ride-request-service` is the system of record for that request. It is
the place where pricing, dispatch, payment pre-auth, and the customer's
own cancellation rights converge.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for ride requests | 100% of `dispatch.matched.v1` consumers can resolve the request via this service |
| BR--002 | Drive a high match rate | ≥ 95% of requests are matched within 90 seconds (city/zone dependent) |
| BR--003 | Honour free-cancellation policy | 100% of cancellations within the free window incur no fee |
| BR--004 | Provide predictable cancellation fee semantics | Cancellation fee matches the published city schedule 100% of the time |
| BR--005 | Make scheduled rides materialise on time | ≥ 99% of scheduled rides convert to live requests within 60 seconds of the due time |
| BR--006 | Make no-driver failure safe and recoverable | 100% of `dispatch.no_driver.v1` produce an `expired` request and a customer notification within 30 seconds |
| BR--007 | Never lose a request due to a customer retry | 100% of retries with the same `Idempotency-Key` produce the same response |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | match rate, cancellation behaviour, customer trust |
| Engineering (Rides) | builder | correct state machine, low P99 latency |
| Driver Operations | reviewer | cancellation fee policy, no-driver rate per city |
| Customer Support | operator | ability to read/cancel a request, explain fees |
| Finance | reviewer | pre-auth correctness, fee revenue |
| Legal & Compliance | reviewer | PII handling, GDPR erasure, audit trail |

## 5. Actors / Personas

- **Customer (rider)**: opens the app, enters pickup and dropoff, picks
  a ride type, sees a quote, confirms. May cancel during the
  `requested` state.
- **Scheduled-rider**: a customer who set a ride for a future time.
  The app is closed when the request materialises.
- **Customer Support agent**: a human operator who can view and
  cancel any request on behalf of a customer.
- **Admin (Trust & Safety)**: an operator with elevated rights to
  cancel any request and to view the audit trail.
- **Dispatcher (system)**: an automated agent that consumes the
  `ride.request.created.v1` event to find a driver.
- **Pricing service (system)**: an automated agent that returns a
  quote for the request.
- **Scheduled-ride service (system)**: an automated agent that
  triggers future-dated requests.

## 6. Business Capabilities

- Create a ride request with pickup, dropoff, ride type, and payment
  method reference, after a successful price quote.
- Hold a price quote for the duration of the request.
- Trigger dispatch and react to its match/no-driver/offer-expired
  results.
- Apply the cancellation policy and collect (or waive) a fee.
- Materialise a scheduled ride as a live request at the due time.
- Expose a read API for the customer's own requests and for admin
  tooling.
- Emit domain events for the rest of the platform.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accept a ride request only when the customer is active (not suspended) and has a usable payment method on file. | MUST | Product / Risk |
| BR--011 | The service MUST refuse a ride request when pickup or dropoff is outside any served zone. | MUST | Operations |
| BR--012 | The service MUST hold a `PriceQuote` against the request and reject matches that arrive after the quote's TTL. | MUST | Product / Finance |
| BR--013 | The service MUST allow the customer to cancel at any time before a driver is assigned without a fee. | MUST | Regulatory / Customer Charter |
| BR--014 | The service MUST allow the customer to cancel after a driver is assigned, with a per-city cancellation fee applied per the published schedule. | MUST | Product / Finance |
| BR--015 | The service MUST cancel any open request for a customer within 60 seconds of `customer.suspended.v1`. | MUST | Trust & Safety |
| BR--016 | The service MUST materialise a scheduled ride into a live `requested` request within 60 seconds of the scheduled time. | MUST | Product |
| BR--017 | The service MUST treat the same `Idempotency-Key` as a single request and return the prior response. | MUST | API Standards |
| BR--018 | The service MUST emit one of `ride.request.{created,matched,cancelled,expired}.v1` for every state transition. | MUST | Platform Event Standards |
| BR--019 | The service MUST retry dispatch up to N times (configurable per city) before declaring no-driver. | SHOULD | Operations |
| BR--020 | The service SHOULD support a "rebook" action that uses the same parameters and a fresh quote. | SHOULD | Product |
| BR--021 | The service SHOULD expose a "cancellation fee preview" endpoint so the customer app can show the fee before confirming the cancellation. | SHOULD | Product / UX |
| BR--022 | The service SHOULD support coupon/promotion reference at request time. | SHOULD | Product / Growth |
| BR--023 | The service MUST record an audit event for every state transition with `correlation_id` and `actor_id`. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Free cancellation window is 60 seconds from `ride.request.created.v1` to customer-cancel attempt. | Configurable per city |
| BR--031 | Cancellation fee before pickup is the lower of the published "post-match" amount and the time-based fee. | Driver has not been at pickup |
| BR--032 | Cancellation fee at pickup is the higher "no-show" amount. | Driver arrived; see platform's `pricing-service` for the rule |
| BR--033 | A customer-cancel after pickup is not allowed; the customer must use the in-trip dispute flow. | Trip state machine owns this |
| BR--034 | A `dispatch.matched.v1` arriving after the quote's TTL is rejected and re-dispatched. | Prevents stale pricing |
| BR--035 | If a customer is suspended while a request is in `requested`, the request is auto-cancelled with no fee. | Trust & Safety override |
| BR--036 | Scheduled rides are read-only after creation; parameters can only be changed by cancelling and re-creating. | Avoids surprising the driver |

## 9. Assumptions

- The customer's KYC, payment methods, and default city are managed by
  `customer-service`. This service does not store those.
- Pricing is computed by `pricing-service`; this service holds the
  result.
- Driver matching is owned by `dispatch-service`. This service is a
  caller and a consumer of its result.
- Time is in UTC at the wire. The customer app renders in the user's
  timezone.
- The `pricing-service` quote is a snapshot, not a guarantee; the
  final trip fare may differ if the route changed.

## 10. Constraints

- A request lives at most a few minutes in `requested` (default cap
  90s). State machine prevents long-lived open requests.
- One customer can have at most 3 concurrent `requested` requests.
- A request that emits an event is committed atomically (outbox
  pattern).
- Soft delete is not used for active requests; the cancel state is the
  "deleted-by-user" equivalent.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `customer-service` | service | customer status, payment method ref |
| `pricing-service` | service | price quote |
| `dispatch-service` | service | matching |
| `driver-availability-service` | service | optional pre-check |
| `zone-service` | service | served zone check |
| `payment-service` | service | cancellation fee capture |
| `scheduled-ride-service` | service | materialise future-dated jobs |
| `notification-service` | service | customer updates |
| `audit-service` | service | immutable audit log |
| `configuration-service` | service | cancellation policy, timeouts |

## 12. Business Workflows

- **Customer requests a ride (happy path)** — see `WORKFLOWS.md`.
- **Scheduled ride materialises** — see `WORKFLOWS.md`.
- **Customer cancels before match** — see `WORKFLOWS.md`.
- **Customer cancels after match** — see `WORKFLOWS.md`.
- **No driver found** — see `WORKFLOWS.md`.
- **Customer suspended mid-request** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- Pricing service timeout: 503 to the customer app; customer prompted
  to retry. Request not created.
- Dispatch service timeout: 202 to the customer app; the request is
  persisted and the dispatch retry runs in the background.
- Customer cancels after match but before payment: fee captured;
  `ride.request.cancelled.v1` and (if a trip was created)
  `trip.cancelled.v1` are emitted.
- Scheduled ride cannot materialise (customer suspended, payment
  method expired): the scheduled-ride service is informed via
  `scheduled_ride.failed.v1`; the customer is notified.

## 14. Success Criteria

- Match rate is within the city's target band; no-driver rate trends
  down with new cities.
- Cancellation fee revenue is reconciled daily and matches the
  per-cancellation event stream.
- Scheduled rides are converted on time, with rare exceptions visible
  in dashboards.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Match rate (city, ride type) | ≥ 95% within 90s | ride_request.match_seconds histogram |
| No-driver rate (city) | < 5% | ride_request_expirations_total / ride_request_created_total |
| Cancellation fee accuracy | 100% | reconciliation between ride.request.cancelled.v1 and payment.captured.v1 |
| Scheduled-ride on-time materialisation | ≥ 99% within 60s of due time | scheduled_ride_service diff |
| P99 create-to-match latency | < 30s end-to-end | trace analytics |
| Idempotency-key reuse conflicts | < 0.1% | 422 IDEMPOTENCY_KEY_REUSED counter |

## 16. Acceptance Criteria

- A new city can be onboarded by setting configuration keys only; no
  code change required to enable a new ride type, zone, or fee
  schedule.
- The state machine enforces all transitions; an attempt to move
  `cancelled → matched` returns 409 `STATE_INVALID`.
- The audit log can answer "who cancelled this request and when" for
  every cancellation.
- A simulated "no driver" path is observable end-to-end in
  integration tests.

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

