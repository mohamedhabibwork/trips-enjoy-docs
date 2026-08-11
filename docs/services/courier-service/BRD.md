# courier-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's food marketplace
product team, the courier operations team, the fraud
team, and the SRE on-call. It captures *why* the
`courier-service` exists, the business capabilities it
provides, the business rules it enforces, and the KPIs
against which it is evaluated. It is the input to the
SRS, ERD, and INTEGRATION docs in this folder.

## 2. Business Context

The platform needs a canonical, single source of truth
for the **courier** — a Keycloak user who has been
onboarded as a courier for the food delivery
marketplace. Without it, every consumer (dispatch,
delivery, earnings) would have to grow its own courier
table, and KYC, document expiry, and shift management
would be inconsistent across the platform. The
`courier-service`:

- **Single source of truth** for the courier
  aggregate (KYC, vehicle type, shifts, eligibility,
  rating).
- **Consistent KYC enforcement** so a courier with an
  expired ID or vehicle doc is blocked from accepting
  deliveries.
- **Shift schedule** so the platform can plan
  capacity and the courier can plan their work.
- **Vehicle type tracking** so the dispatch matches
  the right courier to the right order (e.g.
  bicycle for short trips, car for long).
- **GDPR right-to-erasure** is owned centrally.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable `courier_id` (UUIDv7) for every platform courier. | 100% of `identity.user.created.v1` result in a courier row within 5 seconds. |
| BR--002 | Onboard a courier in < 24 hours of all documents being uploaded. | Median time from document complete to `approved`. |
| BR--003 | Send 99% of document expiry warnings 7+ days before expiry. | warning lag distribution. |
| BR--004 | Auto-suspend a courier with an expired critical document within 7 days of expiry. | 100% auto-suspend within grace period. |
| BR--005 | Maintain accurate vehicle type. | Dispatch matches vehicle type 100% of the time. |
| BR--006 | Maintain accurate shift schedule. | Shift changes propagate within 10 seconds (P99). |
| BR--007 | Maintain city-level eligibility. | 0 deliveries dispatched to a courier ineligible in the restaurant's city. |
| BR--008 | Maintain a rating read-model. | Rating updated within 5 minutes of `review.aggregated.v1`. |
| BR--009 | Meet the Tier-1 SLO of 99.95% availability and P99 ≤ 30 ms on the read path. | SLO burn rate. |
| BR--010 | Implement GDPR right-to-erasure consistently. | 100% of `identity.user.erased.v1` result in courier anonymization within 60 seconds. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Food marketplace product | owner | courier onboarding, shifts |
| Courier operations | consumer | KYC, document expiry, suspensions |
| Dispatch / delivery | consumer | eligibility, vehicle type, shifts |
| Fraud team | consumer | `courier.suspended.v1` |
| Compliance | reviewer | GDPR, KYC |
| SRE on-call | operator | alerts, MTTR |

## 5. Actors / Personas

- **Courier** — uploads documents, sets vehicle type,
  schedules shifts, sees rating and earnings.
- **Internal admin / support** — approves, rejects,
  suspends, re-instates, erases, sets city
  eligibility.
- **KYC provider / background-check provider**
  (system) — verify documents.
- **Downstream services** (system) — read the
  courier for delivery matching, earnings, etc.

## 6. Business Capabilities

- **Courier onboarding** — create a courier on
  `identity.user.created.v1`; collect documents;
  review by admin; approve or reject.
- **KYC documents** — ID, vehicle doc, selfie, bag
  photo; tracked with expiry dates.
- **Document expiry warnings** — 30, 7, 1 day before
  expiry; auto-suspend after grace period.
- **Vehicle type** — `bicycle`, `motorcycle`, `car`,
  `scooter`, `walking`.
- **Shift schedule** — planned vs. actual shifts;
  min/max duration enforced.
- **City-level eligibility** — courier is eligible in
  cities they are registered in.
- **Rating read-model** — aggregated from
  ``trip-service` / `food-order-service` / `search-service` (review projections)`.
- **Courier state machine** — `pending_review`,
  `approved`, `rejected`, `suspended`, `inactive`,
  `erased`.
- **Vehicle association** — link to the primary
  vehicle.
- **Suspension / disable / erasure** — admin
  actions with reason codes.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Every platform courier MUST have a `courier.couriers` row. | MUST | architecture |
| BR--011 | The service MUST be the only writer of the `courier` schema. | MUST | data ownership |
| BR--012 | The service MUST emit `courier.created.v1` on creation. | MUST | event architecture |
| BR--013 | The service MUST emit `courier.approved.v1` on approval. | MUST | event architecture |
| BR--014 | The service MUST emit `courier.suspended.v1` on suspension. | MUST | event architecture |
| BR--015 | The service MUST emit `courier.disabled.v1` on disablement. | MUST | event architecture |
| BR--016 | The service MUST emit `courier.erased.v1` on GDPR erasure. | MUST | GDPR |
| BR--017 | The service MUST emit `courier.shift.scheduled.v1`, `courier.shift.started.v1`, `courier.shift.ended.v1`. | MUST | dispatch |
| BR--018 | The service MUST support vehicle type per courier. | MUST | dispatch |
| BR--019 | The service MUST track city-level eligibility. | MUST | dispatch |
| BR--020 | A courier with rating below `min_rating` MUST be ineligible. | MUST | quality |
| BR--021 | The service MUST anonymize PII on erasure; preserve `courier_id`. | MUST | GDPR |
| BR--022 | The service MUST auto-suspend a courier whose critical document is expired (after grace period). | MUST | safety |
| BR--023 | The service MUST link to the primary vehicle via ``driver-service` (vehicles)`. | MUST | operations |
| BR--024 | The service MUST support multi-vehicle registration. | SHOULD | operations |
| BR--025 | The service SHOULD mark a courier `inactive` after `inactive_after_days` of no online state. | SHOULD | hygiene |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A courier cannot be `approved` until all required documents are uploaded and verified. | Admin review is the last step. |
| BR--031 | An expired ID is a critical document; expired selfie is not. | Auto-suspend applies only to critical documents. |
| BR--032 | A `suspended` courier cannot go online. | Enforced by the online flag check. |
| BR--033 | A shift MUST be at least `min_duration_minutes` (default 60). | Prevents micro-shifts. |
| BR--034 | A shift MUST NOT exceed `max_duration_hours` (default 12). | Fatigue prevention. |
| BR--035 | A courier can have at most one active shift at a time. | Overlap check on schedule. |
| BR--036 | A courier's eligibility in a city is removed if their rating drops below `min_rating` for that city. | Per-city override possible. |
| BR--037 | A courier's primary vehicle reference MUST be set before they can be `approved`. | Operations gate. |
| BR--038 | `courier_id` is never recycled, even on erasure. | Stability. |
| BR--039 | GDPR erasure preserves `courier_id`; financial records retain the reference but redact PII. | Legal hold. |

## 9. Assumptions

- `identity-service` emits `identity.user.created.v1`
  for every new user before the courier attempts any
  read/write.
- ``driver-service` (vehicles)` emits `vehicle.registered.v1` and
  `vehicle.insurance.expired.v1` for the courier's
  primary vehicle.
- ``trip-service` / `food-order-service` / `search-service` (review projections)` emits `review.aggregated.v1`
  with the updated rating.
- The KYC and background-check providers are
  reachable; the platform has a fallback (admin
  override) if a provider is down.

## 10. Constraints

- The service MUST NOT store document file content;
  only the `file_id` reference in `file-service`.
- The service MUST NOT store location data; only
  the `courier_id` is referenced.
- The service MUST NOT call other services'
  databases directly.
- The service MUST use the standard event and error
  envelopes.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | emits `identity.*.v1` |
| ``driver-service` (vehicles)` | service | emits `vehicle.*.v1` |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | service | emits `review.aggregated.v1` |
| `geolocation-service`, ``geolocation-service` (zones)` | service | city / zone validation |
| KYC provider, background-check provider | external | verification |
| `configuration-service` | service | config hot-reload |
| ``courier-service` (dispatch)` | consumer | `courier.approved.v1`, `courier.suspended.v1` |
| ``courier-service` (tracking)` | consumer | `courier.approved.v1` |
| ``courier-service` (delivery)` | consumer | `courier.suspended.v1` |
| `notification-service` | consumer | `courier.*.v1` |
| `fraud-risk-service` | consumer | `courier.suspended.v1` |
| `audit-service` | consumer | `courier.*.v1` |
| ``reporting-service` (data lake)` | consumer | `courier.*.v1` |
| Redis | infra | claim hot-cache, eligibility projection |
| Kafka | infra | event bus |
| Vault | infra | provider credentials, DB credentials |

## 12. Business Workflows

- **Courier onboarding (KYC)** (detailed in
  `WORKFLOWS.md`).
- **Courier approval** (detailed in `WORKFLOWS.md`).
- **Shift schedule** (detailed in `WORKFLOWS.md`).
- **Vehicle type change** (detailed in
  `WORKFLOWS.md`).
- **Document expiry** (detailed in `WORKFLOWS.md`).
- **Courier suspension** (detailed in `WORKFLOWS.md`).
- **City-level eligibility** (detailed in
  `WORKFLOWS.md`).
- **GDPR right-to-erasure** (detailed in
  `WORKFLOWS.md`).

## 13. Exception Workflows

- **KYC provider unreachable** — the service
  degrades to admin-override; a ticket is opened
  and the courier is told to retry.
- **Approval + suspension race** — the
  `courier_id` row has an optimistic-lock version;
  the second action is rejected with `409 CONFLICT`.
- **Shift overlap** — `422 BUSINESS_RULE_VIOLATION`
  with `code: "SHIFT_OVERLAP"`.
- **Shift too short / too long** — `422
  BUSINESS_RULE_VIOLATION` with `code:
  "SHIFT_DURATION_OUT_OF_RANGE"`.
- **Erasure on a courier with active financial
  records** — the service performs the erasure
  but populates `warnings[]` in the response.

## 14. Success Criteria

- 100% of platform couriers have a `courier_id`.
- 100% of `courier.*.v1` events are observed by
  all declared consumers within 10 seconds (P99).
- 99% of document expiry warnings are sent 7+
  days before expiry.
- 100% of couriers with expired critical
  documents are auto-suspended within the grace
  period.
- 0 deliveries dispatched to a courier ineligible
  in the restaurant's city.
- A GDPR erasure completes end-to-end in ≤ 24
  hours (expedited) and is auditable.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Availability | ≥ 99.95% per 30d | uptime / total time per region |
| P99 read latency | ≤ 30 ms | request duration histogram |
| P99 propagation lag | ≤ 10 s | event time → consumer ack |
| Onboarding time | < 24 h median | document complete → approved |
| Expiry warning lag | 99% sent 7+ days before | warning timestamp vs. expiry |
| Auto-suspend lag | 100% within grace period | expiry → auto-suspended |
| Erasure SLA | 100% within 24 h expedited | support ticket resolution time |

## 16. Acceptance Criteria

- An `identity.user.created.v1` event results in a
  `couriers` row within 5 seconds.
- A document upload results in a
  `courier.documents` row and triggers the KYC
  provider verification.
- An admin approval results in
  `courier.approved.v1` emitted.
- An admin rejection results in
  `courier.rejected.v1` emitted with a reason.
- A shift schedule POST results in
  `courier.shift.scheduled.v1` emitted.
- A vehicle type change results in
  `courier.updated.v1` emitted with the new
  `vehicle_type`.
- A document expiring in 30 / 7 / 1 day results
  in `courier.document.expiring.v1` emitted.
- A document expired past the grace period
  results in `courier.document.expired.v1` emitted
  and the courier auto-suspended.
- A `review.aggregated.v1` event results in the
  courier's rating updated within 5 minutes.
- A GDPR erasure request results in PII redaction
  and `courier.erased.v1` emitted.
- A ``courier-service` (dispatch)` request to check
  eligibility returns `true` for an approved,
  non-suspended courier with valid documents in
  the city.

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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

