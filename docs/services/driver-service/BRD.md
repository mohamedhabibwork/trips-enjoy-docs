# driver-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's ride-hailing product
team, the driver operations team, the fraud team, and the
SRE on-call. It captures *why* the `driver-service` exists,
the business capabilities it provides, the business rules
it enforces, and the KPIs against which it is evaluated. It
is the input to the SRS, ERD, and INTEGRATION docs in this
folder.

## 2. Business Context

The platform needs a canonical, single source of truth for
the **driver** — a Keycloak user who has been onboarded as
a driver. Without it, every consumer (dispatch, trip,
availability, earnings) would have to grow its own driver
table, and KYC, document expiry, and eligibility would be
inconsistent across the platform. The `driver-service`:

- **Single source of truth** for the driver aggregate
  (KYC, documents, eligibility, rating).
- **Consistent KYC enforcement** so a driver with an
  expired license or insurance is blocked from accepting
  rides.
- **Document expiry warnings** so drivers renew on
  time and avoid being auto-suspended.
- **City-level eligibility** so a driver is only
  matched to rides in cities they are registered in.
- **GDPR right-to-erasure** is owned centrally.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable `driver_id` (UUIDv7) for every platform driver. | 100% of `identity.user.created.v1` result in a driver row within 5 seconds. |
| BR--002 | Onboard a driver in < 24 hours of all documents being uploaded. | Median time from document complete to `approved`. |
| BR--003 | Send 99% of document expiry warnings 7+ days before expiry. | warning lag distribution. |
| BR--004 | Auto-suspend a driver with an expired critical document within 7 days of expiry. | 100% auto-suspend within grace period. |
| BR--005 | Maintain accurate city-level eligibility. | 0 rides dispatched to a driver ineligible in the pickup city. |
| BR--006 | Maintain a rating read-model. | Rating updated within 5 minutes of `review.aggregated.v1`. |
| BR--007 | Meet the Tier-1 SLO of 99.95% availability and P99 ≤ 30 ms on the read path. | SLO burn rate. |
| BR--008 | Implement GDPR right-to-erasure consistently. | 100% of `identity.user.erased.v1` result in driver anonymization within 60 seconds. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Ride-hailing product | owner | driver onboarding UX, eligibility |
| Driver operations | consumer | KYC, document expiry, suspensions |
| Dispatch / trip / availability | consumer | eligibility, state |
| Fraud team | consumer | `driver.suspended.v1` |
| Compliance | reviewer | GDPR, KYC |
| SRE on-call | operator | alerts, MTTR |

## 5. Actors / Personas

- **Driver** — uploads documents, manages profile, sees
  rating and earnings.
- **Internal admin / support** — approves, rejects,
  suspends, re-instates, erases, sets city eligibility.
- **KYC provider / background-check provider** (system)
  — verify documents.
- **Downstream services** (system) — read the driver
  for ride matching, earnings, etc.

## 6. Business Capabilities

- **Driver onboarding** — create a driver on
  `identity.user.created.v1`; collect documents;
  review by admin; approve or reject.
- **KYC documents** — license, vehicle registration,
  insurance, selfie, background check; tracked with
  expiry dates.
- **Document expiry warnings** — 30, 7, 1 day before
  expiry; auto-suspend after grace period.
- **City-level eligibility** — driver is eligible in
  cities they are registered in; admin can add /
  remove eligibility.
- **Rating read-model** — aggregated from
  ``trip-service` / `food-order-service` / `search-service` (review projections)`; min-rating eligibility
  threshold.
- **Driver state machine** — `pending_review`,
  `approved`, `rejected`, `suspended`, `inactive`,
  `erased`.
- **Vehicle association** — link to the primary
  vehicle (and additional vehicles).
- **Suspension / disable / erasure** — admin actions
  with reason codes.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Every platform driver MUST have a `driver.drivers` row. | MUST | architecture |
| BR--011 | The service MUST be the only writer of the `driver` schema. | MUST | data ownership |
| BR--012 | The service MUST emit `driver.created.v1` on creation. | MUST | event architecture |
| BR--013 | The service MUST emit `driver.approved.v1` on approval. | MUST | event architecture |
| BR--014 | The service MUST emit `driver.suspended.v1` on suspension. | MUST | event architecture |
| BR--015 | The service MUST emit `driver.disabled.v1` on disablement. | MUST | event architecture |
| BR--016 | The service MUST emit `driver.erased.v1` on GDPR erasure. | MUST | GDPR |
| BR--017 | The service MUST emit `driver.document.expiring.v1` 30, 7, 1 day before expiry. | MUST | product |
| BR--018 | The service MUST auto-suspend a driver whose critical document is expired (after grace period). | MUST | safety |
| BR--019 | The service MUST track city-level eligibility. | MUST | dispatch |
| BR--020 | A driver with rating below `min_rating` MUST be ineligible. | MUST | quality |
| BR--021 | The service MUST anonymize PII on erasure; preserve `driver_id`. | MUST | GDPR |
| BR--022 | The service SHOULD link to the primary vehicle via ``driver-service` (vehicles)`. | MUST | operations |
| BR--023 | The service MUST support multi-vehicle registration. | SHOULD | operations |
| BR--024 | The service MUST support per-city document requirements (e.g. some cities require a medical certificate). | SHOULD | operations |
| BR--025 | The service MUST mark a driver `inactive` after `inactive_after_days` of no online state. | SHOULD | hygiene |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A driver cannot be `approved` until all required documents are uploaded and verified. | Admin review is the last step. |
| BR--031 | An expired license or insurance is a critical document; expired selfie is not. | Auto-suspend applies only to critical documents. |
| BR--032 | A `suspended` driver cannot go online. | Enforced by ``driver-service` (availability)`. |
| BR--033 | A driver's eligibility in a city is removed if their rating drops below `min_rating` for that city. | Per-city `min_rating` override possible. |
| BR--034 | A driver's primary vehicle reference MUST be set before they can be `approved`. | Operations gate. |
| BR--035 | `driver_id` is never recycled, even on erasure. | Stability. |
| BR--036 | The `pending_review` state has a 30-day TTL; after that, the driver is auto-`expired`. | Admin reminder. |
| BR--037 | The auto-suspend grace period is `expiry_grace_days` (default 7). | Configurable. |
| BR--038 | GDPR erasure preserves `driver_id`; financial records retain the reference but redact PII. | Legal hold. |

## 9. Assumptions

- `identity-service` emits `identity.user.created.v1`
  for every new user before the driver attempts any
  read/write.
- ``driver-service` (vehicles)` emits `vehicle.registered.v1` and
  `vehicle.insurance.expired.v1` for the driver's
  primary vehicle.
- ``trip-service` / `food-order-service` / `search-service` (review projections)` emits `review.aggregated.v1`
  with the updated rating for the driver.
- The KYC and background-check providers are
  reachable; the platform has a fallback (admin
  override) if a provider is down.

## 10. Constraints

- The service MUST NOT store document file content;
  only the `file_id` reference in `file-service`.
- The service MUST NOT store location data; only the
  `driver_id` is referenced.
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
| ``driver-service` (availability)` | consumer | `driver.approved.v1`, `driver.suspended.v1` |
| ``driver-service` (dispatch)` | consumer | same |
| ``trip-service` (ride-request)` | consumer | `driver.suspended.v1` |
| `notification-service` | consumer | `driver.*.v1` |
| `fraud-risk-service` | consumer | `driver.suspended.v1` |
| `audit-service` | consumer | `driver.*.v1` |
| ``reporting-service` (data lake)` | consumer | `driver.*.v1` |
| Redis | infra | claim hot-cache, eligibility projection |
| Kafka | infra | event bus |
| Vault | infra | provider credentials, DB credentials |

## 12. Business Workflows

- **Driver onboarding (KYC)** (detailed in
  `WORKFLOWS.md`).
- **Driver approval** (detailed in `WORKFLOWS.md`).
- **Document expiry** (detailed in `WORKFLOWS.md`).
- **Driver suspension** (detailed in `WORKFLOWS.md`).
- **City-level eligibility** (detailed in
  `WORKFLOWS.md`).
- **GDPR right-to-erasure** (detailed in
  `WORKFLOWS.md`).

## 13. Exception Workflows

- **KYC provider unreachable** — the service
  degrades to admin-override; a ticket is opened
  and the driver is told to retry.
- **Approval + suspension race** — the `driver_id`
  row has an optimistic-lock version; the second
  action is rejected with `409 CONFLICT`.
- **Document expired but in grace period** — the
  driver is `approved` but with a `documents_warn`
  flag; the platform shows a banner; the grace
  period counts down.
- **Vehicle insurance expired** — the
  `vehicle.insurance.expired.v1` event triggers
  auto-suspension of the driver if no replacement
  is uploaded within the grace period.
- **Erasure on a driver with active financial
  records** — the service performs the erasure
  but populates `warnings[]` in the response;
  financial records retain the `driver_id`
  reference but redact PII.

## 14. Success Criteria

- 100% of platform drivers have a `driver_id`; no
  service references `kc_sub` directly in its
  database.
- 100% of `driver.*.v1` events are observed by
  all declared consumers within 10 seconds (P99).
- 99% of document expiry warnings are sent 7+
  days before expiry.
- 100% of drivers with expired critical documents
  are auto-suspended within the grace period.
- 0 rides dispatched to a driver ineligible in
  the pickup city.
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
| Tier-limit enforcement (KYC) | 0 false-negatives | support ticket review |
| Erasure SLA | 100% within 24 h expedited | support ticket resolution time |

## 16. Acceptance Criteria

- An `identity.user.created.v1` event results in a
  `drivers` row within 5 seconds.
- A document upload results in a
  `driver.documents` row and triggers the KYC
  provider verification.
- An admin approval results in `driver.approved.v1`
  emitted; the driver can go online.
- An admin rejection results in
  `driver.rejected.v1` emitted with a reason.
- A document expiring in 30 / 7 / 1 day results in
  `driver.document.expiring.v1` emitted with the
  correct `days_remaining`.
- A document expired past the grace period results
  in `driver.document.expired.v1` emitted and the
  driver auto-suspended.
- A `vehicle.insurance.expired.v1` event results
  in the driver auto-suspended if no replacement
  is uploaded within the grace period.
- A `review.aggregated.v1` event results in the
  driver's rating updated within 5 minutes.
- A GDPR erasure request results in PII redaction
  and `driver.erased.v1` emitted.
- A ``driver-service` (dispatch)` request to check a driver's
  eligibility returns `true` for an approved,
  non-suspended driver with valid documents in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

