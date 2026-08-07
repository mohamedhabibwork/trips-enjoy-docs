# trip-service — Business Requirements Document

## 1. Document Purpose

Read by product, engineering, operations, finance, and trust & safety
to align on what `trip-service` does. The trip is the unit of
revenue, the unit of dispute, and the unit of safety. This document
defines the business rules and KPIs for the service that owns it.

## 2. Business Context

A "ride" is a sequence: a customer requests, a driver matches, the
driver picks up, the customer rides, the driver drops off, the
customer is charged, the driver is paid, both can rate, and both can
dispute. The middle of that sequence — from the moment the driver
accepts to the moment the trip ends — is the trip. It is the
authoritative record of "what actually happened on the road." Without
it:

- The platform cannot charge the right amount.
- The driver cannot be paid the right amount.
- The customer cannot dispute a wrong dropoff or a wrong route.
- Trust & Safety cannot investigate an incident.
- Loyalty, ratings, and analytics have nothing to aggregate.

`trip-service` is the system of record for that record.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the source of truth for the trip lifecycle | 100% of `trip.*` events originate here |
| BR--002 | Produce a state-correct, auditable trip | state machine rejects all invalid transitions |
| BR--003 | Make "driver at pickup" detectable automatically | ≥ 90% of arrivals detected by geofence (city/zone) |
| BR--004 | Produce a final fare that matches what the customer agreed to | ≥ 99% of completed trips have a final fare within ±5% of the original quote |
| BR--005 | Keep live tracking accurate | ≥ 95% of customer app tracking requests are within 10m of actual |
| BR--006 | Enable mid-trip changes safely | a stop add and a dropoff change are reflected within 5s in the driver app |
| BR--007 | Support safety investigations | 100% of trips have a location trail recoverable for 2h after completion |
| BR--008 | Re-trigger dispatch promptly on driver cancel | new search within 30s of `trip.cancelled.v1` |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | state correctness, completion latency |
| Trust & Safety | consumer | live location for SOS; trail for incidents |
| Finance | reviewer | final fare accuracy, dispute support |
| Driver Operations | reviewer | driver-cancel policy, no-show rules |
| Customer Support | operator | ability to view, annotate, and force-cancel |
| Engineering (Rides) | builder | state machine, performance at peak |
| Legal & Compliance | reviewer | retention, GDPR, audit trail |

## 5. Actors / Personas

- **Driver** — owns the active trip; pushes state transitions
  (`arrive`, `start`, `complete`, `cancel`); streams location.
- **Customer (rider)** — sees the trip live; can add a stop or
  change the dropoff (within policy); can request to cancel before
  pickup; cannot cancel after pickup.
- **Trust & Safety agent** — can view any trip's live location and
  location trail for the duration of an incident.
- **Customer Support agent** — can view any trip; can force-cancel
  with a reason; can add notes.
- **Admin** — same as support, plus broader audit.
- **``trip-service` (ride-request)`** — the upstream that hands a matched
  request to this service.
- **``payment-service` (ride saga)`** — the downstream that
  settles the trip on `trip.completed.v1`.
- **``trip-service` (safety)`** — the consumer of live location during a
  trip.
- **`pricing-service`** — re-quotes the trip on completion.

## 6. Business Capabilities

- Create a trip from a `ride.request.matched.v1`.
- Maintain the state machine `assigned → en_route_pickup → arrived →
  in_progress → completed | cancelled`.
- Accept and persist a high-frequency location stream for the
  duration of the trip.
- Detect arrival via geofence; allow driver manual override.
- Support mid-trip additions and changes (one stop; dropoff change
  within radius).
- Recompute the final fare and emit `trip.completed.v1`.
- Recompute or revert on driver cancellation, with a penalty.
- Expose a read API for the trip and a tracking view for the apps.
- Emit `trip.*` events for the rest of the platform.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST create a trip within 1 second of `ride.request.matched.v1`. | MUST | Product |
| BR--011 | The service MUST allow only the assigned driver to push state transitions for a trip. | MUST | Trust & Safety |
| BR--012 | The service MUST auto-detect arrival at pickup when the driver's GPS is within the geofence for ≥ 5s. | MUST | Product |
| BR--013 | The service MUST allow the driver to manually mark `arrived` if the geofence is miscalibrated. | MUST | Driver Operations |
| BR--014 | The service MUST recompute the fare on completion using the actual route and stop count. | MUST | Finance |
| BR--015 | The service MUST emit `trip.completed.v1` within 5 seconds of `state=completed`. | MUST | Product / Finance |
| BR--016 | The service MUST support one mid-trip additional stop and one mid-trip dropoff change within 5 km of the original. | SHOULD | Product |
| BR--017 | The service MUST allow a driver-cancellation within the early window; otherwise the trip must reach `arrived` or `in_progress` to be cancellable by the driver. | MUST | Driver Operations |
| BR--018 | The service MUST apply a driver-cancellation penalty for cancels after the early window. | MUST | Finance |
| BR--019 | The service MUST retain the location trail for at least 2 hours after completion. | MUST | Trust & Safety |
| BR--020 | The service MUST redact precise GPS coordinates from any response sent to the customer after 2 hours. | MUST | Privacy |
| BR--021 | The service MUST allow trust-and-safety staff to view the location trail for any trip currently in `in_progress` or within 24h of completion. | MUST | Trust & Safety |
| BR--022 | The service MUST limit the customer's mid-trip changes to 1 add-stop and 1 dropoff-change per trip. | SHOULD | Product / Policy |
| BR--023 | The service MUST emit a `trip.cancelled.v1` event with the `actor` and `reason` on any cancellation. | MUST | Platform Event Standards |
| BR--024 | The service SHOULD support a customer-initiated "no-show" complaint while the driver is `arrived`, transitioning the trip to `cancelled` with `actor=customer` and `reason=no_show`. | SHOULD | Product |
| BR--025 | The service MUST record an audit event for every state transition with `correlation_id` and `actor_id`. | MUST | Compliance |
| BR--026 | On `state=completed`, the service MUST evaluate driver-side and user-side reward eligibility from the captured config snapshot and emit `trip.reward.granted.v1` with the granted amounts (or with `decision_reason = "ineligible"` when no reward qualifies). | MUST | Drivers / Customers / Finance |
| BR--027 | The driver-side reward MUST include a per-trip top-up (`max(0, configured_minor − base_driver_earnings)`), an hourly floor (rolling 60-min window), and a daily floor (rolling 24-h window); precedence: the period floor captures the residual after the per-trip top-up. | MUST | Finance |
| BR--028 | The user-side reward MUST be a configurable per-trip credit (default 100 minor units; default kind = `wallet_credit`); the user kind MUST be overridable per city (`trip.reward.user.kind.{city_id}`). | MUST | Customers |
| BR--029 | The service MUST emit `trip.reward.reversed.v1` whenever a previously-granted reward is reversed (admin re-evaluation, trip correction, or payment-capture failure with `actor = provider`); the reversal MUST carry `reversal_of_event_id`, the original `grant_id`, the `actor_type` (`admin` / `provider` / `system`), and a human-readable `reason`. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Driver can cancel only in `assigned`, `en_route_pickup`, or `arrived` (within 2 minutes of arrival). | Driver Operations policy |
| BR--031 | Driver-cancellation after the early window incurs a penalty, charged via the driver earnings or a separate hold. | Penalty per city |
| BR--032 | Customer can cancel only in `assigned` (before the driver accepts the ride) or `en_route_pickup` (before pickup). | No cancel after pickup; the customer uses the in-trip dispute flow |
| BR--033 | Mid-trip add-stop is limited to 1, within 5 km of the current route. | Avoids surge abuse |
| BR--034 | Mid-trip dropoff change is limited to 1, within 5 km of the original dropoff. | Avoids fare surprises |
| BR--035 | The trip's `final_fare` is the lower of the recomputed amount and (quote × 1.05). | Bound the customer's exposure |
| BR--036 | The trip's `final_fare` is the higher of the recomputed amount and (quote × 0.95). | Protect the driver from lowball fares |
| BR--037 | A driver-cancellation emits `trip.cancelled.v1` with `actor=driver`; the upstream ``trip-service` (ride-request)` re-issues dispatch. | Re-uses the request aggregate |
| BR--038 | The location trail is deleted by partition drop after the configured retention window. | Default 2h |
| BR--039 | On `state=completed`, the driver-side reward (per-trip top-up, hourly top-up, daily top-up) is granted as a separate balanced posting in `6302_guaranteed_minimum`; the user-side per-trip credit is granted as a separate balanced posting in `2100_customer_credit_liability`. Precedence: the period floor (hourly / daily) captures the residual after the per-trip top-up; no double-counting with quests from ``driver-service` (incentives)`. | Drivers / users; configurable per city and ride_type |
| BR--040 | The reward config snapshot captured with the trip is the authoritative rule set used; no retroactive recompute even if `configuration.updated.v1` is fired later. | Audit reproducibility |
| BR--041 | The user-side per-trip credit has a city-level cap (`trip.reward.user.per_trip_minor.{currency}`); the loyalty-points variant (when `trip.reward.user.kind = loyalty_points`) is routed via ``pricing-service` (loyalty rules) / `customer-service` (account)` instead of ``payment-service` (wallet)`. | Routing rule |
| BR--042 | Reward evaluation runs in the same transaction as `state=completed`; the outbox row for `trip.reward.granted.v1` is written atomically with the trip's terminal state. | Atomicity |
| BR--043 | Admin re-evaluation (`POST /v1/trips/{id}/reward/re-evaluate`) and reversal (`POST /v1/trips/{id}/reward/reverse`) require the `pricing.admin` scope and a required `reason` ≥ 8 chars; the reversal emits `trip.reward.reversed.v1` carrying `reversal_of_event_id`. | Operationally gated |
| BR--044 | A reversal is always a NEW row in `trip.trip_reward_reversal` and a NEW ledger posting — `UPDATE` / `DELETE` are forbidden via `REVOKE UPDATE, DELETE` on the table and a Postgres trigger that blocks them on `ledger.postings`. | Mirrors the reversal rule from the accounting four-layer truth model |
| BR--045 | Reward eligibility filters: (a) driver rating >= `trip.reward.driver.eligibility.min_rating` (default `4.0`); (b) driver has at least `trip.reward.driver.eligibility.min_completed_trips` completed trips in the rolling 24-h window (default `5`); (c) `pickup_zone_id` matches a zone that the city config marks as reward-eligible. A trip that fails any filter is rewarded with zero but the `trip.reward.granted.v1` is still emitted (with `decision_reason = "ineligible"`). | Eligibility model |

## 9. Assumptions

- The driver app is a trusted client; it authenticates the driver
  per request.
- The customer app reads the live tracking view via short-poll (every
  4–6s) against `GET /v1/trips/{id}/track`. A push channel is used
  for state transitions only.
- Pricing re-quote is idempotent (same inputs → same output).
- The ``geolocation-service` (ETA/routing)` provides the route and distance for the
  recomputed fare.

## 10. Constraints

- One driver can have at most one active trip.
- The `trip.location_points` partition is created per day; the
  retention is 2h after the trip completes; the partition is dropped
  2h + 30 minutes later.
- All emitted events go through the outbox; the customer's tracking
  view does not.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| ``trip-service` (ride-request)` | service | creates the trip |
| `driver-service` | service | driver profile (read) |
| `customer-service` | service | customer profile (read) |
| ``geolocation-service` (ETA/routing)` | service | route + ETA + distance |
| `pricing-service` | service | final fare |
| ``driver-service` (location)` | service | live location stream |
| `notification-service` | service | customer/driver updates |
| ``trip-service` (safety)` | service | SOS, share-trip |
| `configuration-service` | service | policy keys |
| `audit-service` | service | audit events |

## 12. Business Workflows

- **Happy path** — assigned → en_route_pickup → arrived →
  in_progress → completed.
- **Mid-trip stop add** — see `WORKFLOWS.md`.
- **Mid-trip dropoff change** — see `WORKFLOWS.md`.
- **Driver cancels after early window** — see `WORKFLOWS.md`.
- **Customer no-show at pickup** — see `WORKFLOWS.md`.
- **Auto-arrival via geofence** — see `WORKFLOWS.md`.
- **Final fare recompute** — see `WORKFLOWS.md`.
- **Guaranteed rewards at trip completion** — see `WORKFLOWS.md`.
- **Reward reversal on trip correction** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- ETA service down at completion: keep the trip `in_progress` and
  retry the recompute; alert if it doesn't complete in 60s.
- Driver app crash mid-trip: heartbeat detection in
  ``driver-service` (location)`; if no GPS in 2 minutes and no driver
  app ping in 5 minutes, open a P1 safety ticket.
- Customer app crash mid-trip: state machine continues; on
  completion, the customer is notified.
- Trip record lost (DR): rebuild from `ride.request.matched.v1` +
  the `driver.location.updated.v1` curated stream.

## 14. Success Criteria

- The trip state machine is auditable end-to-end for every trip.
- Final fares match the agreed quote within ±5% in ≥ 99% of trips.
- Driver cancellation in the early window has no penalty; after the
  window, the penalty is applied correctly.
- Live tracking is accurate to within 10m for ≥ 95% of customers.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Match-to-create latency (P95) | < 1s | trip_create_seconds histogram |
| Arrive-to-start latency (P95) | < 60s | trip_arrive_to_start_seconds |
| Trip duration accuracy | within ±5% of ETA | trip_duration_seconds vs predicted |
| Final fare accuracy | within ±5% of quote in 99% of trips | reconciliation |
| Auto-arrival rate | ≥ 90% (per zone) | auto_arrival_total / arrivals_total |
| Driver-cancel early-window rate | < 5% (per city) | trip_cancellations_total{actor=driver,window=early} / trips_created_total |
| Live tracking accuracy | 95% within 10m | location_drift_meters histogram (sampled) |

## 16. Acceptance Criteria

- The state machine refuses all invalid transitions with 409
  `STATE_INVALID`.
- A driver-cancellation within the early window completes without a
  penalty.
- A driver-cancellation after the early window applies the penalty
  and emits the event.
- A customer no-show transitions the trip to `cancelled` with
  `actor=customer, reason=no_show` and emits the event.
- The location trail is queryable by Trust & Safety for trips in
  `in_progress` and for 24h after `completed`.
- For every `state=completed` trip, exactly one `trip.reward.granted.v1`
  is emitted (within 1s of completion) with at least one of the four
  kinds (`driver_per_trip_topup`, `driver_hourly_topup`,
  `driver_daily_topup`, `user_per_trip_credit`) or `decision_reason =
  "ineligible"`.
- For every reversal request, exactly one `trip.reward.reversed.v1`
  is emitted with the matching `reversal_of_event_id` and a new
  balanced row in `trip.trip_reward_reversal` (never UPDATE/DELETE
  on `trip.trip_reward`).
- All 29 business requirements (BR--001..BR--008, BR--010..BR--029)
  are implemented, together with the 16 business rules (BR--030..BR--045);
  the 16 list above is the acceptance contract every release must
  satisfy.

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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

