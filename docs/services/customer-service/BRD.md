# customer-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's rides + food product
team, the payments team, the fraud team, and the SRE
on-call. It captures *why* the `customer-service` exists,
the business capabilities it provides, the business rules
it enforces, and the KPIs against which it is evaluated.
It is the input to the SRS, ERD, and INTEGRATION docs in
this folder.

## 2. Business Context

The platform needs a canonical, single source of truth
for the **customer** — a Keycloak user who has been
onboarded as a customer. Without it, every consumer
(ride-request, food-order, cart, checkout, payment)
would have to grow its own customer table, and KYC,
LTV, and segment would be inconsistent across the
platform. The `customer-service`:

- **Single source of truth** for the customer
  aggregate (KYC, LTV, segment, default method/address).
- **Consistent KYC enforcement** so a customer is
  blocked from high-value actions if their tier is too
  low.
- **Single segment view** so promotions, loyalty, and
  pricing are based on the same segment.
- **GDPR right-to-erasure** is owned centrally.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable `customer_id` (UUIDv7) for every platform customer. | 100% of `identity.user.created.v1` result in a customer row within 5 seconds. |
| BR--002 | Enforce KYC tier-based payment limits platform-wide. | 0 payments above the customer's tier limit succeed. |
| BR--003 | Maintain an accurate LTV (rolling 365 days). | LTV updates within 5 minutes of `*.payment.completed.v1`. |
| BR--004 | Maintain an accurate segment. | Segment changes propagated to `promotion-service`, `loyalty-service`, `pricing-service` within 10 seconds (P99). |
| BR--005 | Meet the Tier-1 SLO of 99.95% availability and P99 ≤ 30 ms on the read path. | SLO burn rate. |
| BR--006 | Implement GDPR right-to-erasure consistently. | 100% of `identity.user.erased.v1` result in customer anonymization within 60 seconds. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Rides + food product | owner | customer features, KYC UX |
| Payments team | consumer | KYC tier, default method |
| Fraud team | consumer | `customer.suspended.v1` |
| Promotion / loyalty / pricing | consumer | segment changes |
| Compliance | reviewer | GDPR, KYC |
| SRE on-call | operator | alerts, MTTR |

## 5. Actors / Personas

- **Customer** — manages profile, KYC, default method /
  address.
- **Internal admin / support** — suspends, re-instates,
  erases, forces KYC tier changes.
- **KYC provider** (system) — verifies documents and
  returns a tier.
- **Downstream services** (system) — read the customer
  for ride/order/checkout/payment decisions.

## 6. Business Capabilities

- **Customer onboarding** — create a customer on
  `identity.user.created.v1`.
- **KYC** — tiered verification (`tier_0` to
  `tier_3`); document upload, provider verification,
  tier upgrade.
- **LTV** — rolling 365-day sum of completed
  payments; recomputed incrementally.
- **Segment** — `standard`, `frequent`, `vip`,
  `churned`; recomputed nightly + on LTV changes.
- **Default payment method** — set / unset; the
  reference lives in `payment-service`.
- **Default address** — set / unset; the reference
  lives in `address-service`.
- **Suspension** — `customer.suspended.v1` blocks
  ride / order / cart / payment actions.
- **Re-instatement** — admin action.
- **Disablement** — permanent (compliance / legal).
- **Erasure** — GDPR; anonymize PII; preserve
  `customer_id`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Every platform customer MUST have a `customer.customers` row. | MUST | architecture |
| BR--011 | The service MUST be the only writer of the `customer` schema. | MUST | data ownership |
| BR--012 | The service MUST emit `customer.created.v1` on creation. | MUST | event architecture |
| BR--013 | The service MUST emit `customer.suspended.v1` on suspension. | MUST | event architecture |
| BR--014 | The service MUST emit `customer.disabled.v1` on disablement. | MUST | event architecture |
| BR--015 | The service MUST emit `customer.reinstated.v1` on re-instatement. | MUST | event architecture |
| BR--016 | The service MUST emit `customer.erased.v1` on GDPR erasure. | MUST | GDPR |
| BR--017 | The service MUST emit `customer.segment.changed.v1` on segment change. | MUST | promotion / loyalty / pricing |
| BR--018 | The service MUST enforce KYC tier limits. | MUST | payments |
| BR--019 | The service MUST update LTV within 5 minutes of a completed payment event. | MUST | analytics |
| BR--020 | The service MUST support default payment method reference (no PAN stored). | MUST | PCI |
| BR--021 | The service MUST support default address reference. | SHOULD | product |
| BR--022 | The service MUST anonymize PII on erasure; preserve `customer_id`. | MUST | GDPR |
| BR--023 | The service MUST compute segments nightly and on LTV change. | MUST | product |
| BR--024 | A suspended customer MUST be blocked from ride / order / cart / payment actions. | MUST | risk |
| BR--025 | The service SHOULD support a "do not ride" or "do not deliver" preference per city. | SHOULD | product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A KYC tier upgrade requires a verified KYC document from the provider. | Provider's `verification_id` stored. |
| BR--031 | A KYC tier downgrade can be automatic (e.g. document expired) or admin. | Emits `customer.kyc.tier_changed.v1`. |
| BR--032 | A suspended customer's ride / order / cart / payment attempts are rejected with `CUSTOMER_SUSPENDED`. | Enforced by downstream services consuming the event. |
| BR--033 | LTV is computed in `BIGINT` minor units, with a `currency` column. | No floats. |
| BR--034 | Segment transitions: `standard` ↔ `frequent` (rides per month), `frequent` → `vip` (LTV), `*` → `churned` (idle days). | Recomputed nightly + on LTV change. |
| BR--035 | A `customer_id` is never recycled, even on erasure. | Stability. |
| BR--036 | The default payment method is a reference to `payment-service`; no PAN stored. | PCI. |
| BR--037 | The default address is a reference to `address-service`. | No address data duplicated. |
| BR--038 | GDPR erasure preserves `customer_id`; financial records retain the reference but redact PII. | Legal hold. |

## 9. Assumptions

- `identity-service` emits `identity.user.created.v1`
  for every new user before the customer attempts
  any read/write.
- `payment-service` emits
  `payment.method.saved.v1` and
  `payment.method.removed.v1` for every change.
- `ride-payment-integration-service` and
  `food-payment-integration-service` emit
  `*.payment.completed.v1` for every completed
  transaction.
- The KYC provider is reachable; the platform has a
  fallback (admin override) if the provider is down.
- The platform's default currency is the merchant's
  primary currency; LTV is single-currency (no FX
  conversion) for the rolling window.

## 10. Constraints

- The service MUST NOT store PAN, CVV, or full card
  numbers.
- The service MUST NOT store full address data; only
  the `address_id` reference.
- The service MUST NOT call other services' databases
  directly.
- The service MUST use the standard event and error
  envelopes.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | emits `identity.*.v1` |
| `payment-service` | service | emits payment events; default method reference |
| `address-service` | service | default address reference |
| `geolocation-service` | service | primary city lookup |
| KYC provider (e.g. Onfido) | external | document verification |
| `configuration-service` | service | config hot-reload |
| `ride-payment-integration-service` | producer | `ride.payment.completed.v1` |
| `food-payment-integration-service` | producer | `food.payment.completed.v1` |
| `promotion-service`, `loyalty-service`, `pricing-service` | consumer | segment changes |
| `notification-service` | consumer | `customer.*.v1` |
| `audit-service` | consumer | `customer.*.v1` |
| `analytics-service` | consumer | `customer.*.v1` |
| Redis | infra | claim hot-cache, default-method projection |
| Kafka | infra | event bus |
| Vault | infra | KYC provider credentials, DB credentials |

## 12. Business Workflows

- **Customer onboarding** (detailed in `WORKFLOWS.md`).
- **KYC tier upgrade** (detailed in `WORKFLOWS.md`).
- **Default payment method change** (detailed in
  `WORKFLOWS.md`).
- **LTV update on payment** (detailed in
  `WORKFLOWS.md`).
- **Segment change** (detailed in `WORKFLOWS.md`).
- **Suspension** (detailed in `WORKFLOWS.md`).
- **GDPR right-to-erasure** (detailed in
  `WORKFLOWS.md`).

## 13. Exception Workflows

- **KYC provider unreachable** — the service
  degrades to admin-override; a ticket is opened
  and the customer is told to retry.
- **Suspension + re-instatement race** — the
  `customer_id` row has an optimistic-lock version;
  the second action is rejected with `409 CONFLICT`.
- **Erasure with active financial records** — the
  service performs the erasure but populates
  `warnings[]` in the response; financial records
  in `ledger-service` and `payment-service` retain
  the `customer_id` reference but their PII fields
  are redacted by the owning service.
- **Default method removed externally** — the
  `payment.method.removed.v1` event clears the
  default; the customer is prompted to set a new
  one.

## 14. Success Criteria

- 100% of platform customers have a `customer_id`;
  no service references `kc_sub` directly in its
  database.
- 100% of `customer.*.v1` events are observed by
  all declared consumers within 10 seconds (P99).
- 0 payments above the customer's tier limit
  succeed.
- LTV updates within 5 minutes of a completed
  payment event (P99).
- Segment changes propagate to consumers within 10
  seconds (P99).
- A GDPR erasure completes end-to-end in ≤ 24 hours
  (expedited) and is auditable.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Availability | ≥ 99.95% per 30d | uptime / total time per region |
| P99 read latency | ≤ 30 ms | request duration histogram |
| P99 propagation lag | ≤ 10 s | event time → consumer ack |
| LTV update lag | ≤ 5 min P99 | payment time → LTV row updated |
| Tier-limit enforcement | 0 false-negatives | support ticket review |
| Erasure SLA | 100% within 24 h expedited | support ticket resolution time |

## 16. Acceptance Criteria

- An `identity.user.created.v1` event results in a
  `customers` row within 5 seconds.
- A KYC tier upgrade request results in a
  `customer.kyc.tier_changed.v1` event after the
  provider's verification.
- A `payment.method.saved.v1` event results in
  the default method reference updated and
  `customer.updated.v1` emitted.
- A `ride.payment.completed.v1` or
  `food.payment.completed.v1` event results in LTV
  updated within 5 minutes.
- A suspension request results in
  `customer.suspended.v1` emitted; subsequent ride
  / order / cart / payment attempts by the customer
  are rejected by downstream services.
- A GDPR erasure request results in PII redaction
  and `customer.erased.v1` emitted.
- A `payment.method.removed.v1` event with the
  matching `payment_method_id` clears the default
  reference.

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

