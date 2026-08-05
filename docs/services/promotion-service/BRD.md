# Promotion Service — Business Requirements Document

## 1. Document Purpose

Read by marketing, growth, finance, the promotion-service
engineering team, and the fraud / risk team. It informs the design
of the rule engine, the redemption idempotency, the anti-fraud
checks, and the operational SLOs.

## 2. Business Context

The platform runs hundreds of promotions concurrently — coupons,
automatic discounts, free delivery, first-ride credit, restaurant-
specific campaigns. The service centralizes:

- The lifecycle of a promotion (created, scheduled, active,
  disabled, expired).
- The rule engine that decides whether a code applies to a given
  cart / customer.
- The redemption history with idempotency (a cart retry must not
  double-redeem).
- Anti-fraud checks (velocity, segment abuse).
- Targeted campaigns by segment, region, branch.

This service exists so that **marketing can launch a campaign in
minutes** (no engineering ticket) and so that **double-redemption is
impossible**.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.9% availability on the validate / redeem path so promotions never block a checkout. | Availability SLO; P99 redeem latency < 200ms. |
| BR--002 | Prevent double-redemption even under cart retry. | Idempotency key check. |
| BR--003 | Support segmented campaigns (region, branch, customer segment, product). | Rule engine. |
| BR--004 | Allow marketing to launch a campaign in under 60 seconds from console click. | Time from `POST /v1/promotions` to consumers picking up. |
| BR--005 | Make every change attributable to a user with a reason. | 100% write attribution. |
| BR--006 | Detect and reject fraud patterns. | < 0.1% fraud rate. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Marketing | owner | Launch campaigns fast |
| Growth | operator | A/B test campaigns |
| Finance | consumer | Discount accounting |
| Fraud / Risk | consumer | Anti-fraud signals |
| Engineering (consumers) | consumer | Stable SDK; safe defaults |
| Compliance | auditor | Full change history |

## 5. Actors / Personas

- **Operator (admin)** — opens the admin console, creates a
  promotion, sets the rule, schedules it, and saves.
- **Marketing manager** — same as operator, scoped to a tenant.
- **`cart-service`** — validates a code on `POST /v1/carts/{id}/promotions`.
- **`checkout-service`** — validates the final cart at checkout.
- **`food-payment-integration-service`** — records the redemption on
  capture (idempotency key).
- **Customer** — applies a code in the mobile / web app.
- **Fraud / risk** — monitors redemption patterns.

## 6. Business Capabilities

- Promotion types: `PERCENT_OFF`, `AMOUNT_OFF`, `FREE_DELIVERY`,
  `FIXED_PRICE`, `FIRST_RIDE_CREDIT`.
- Eligibility rules: user segment, region, branch, product,
  min cart value, dates, total uses per user, total uses overall.
- Scheduled start / end dates.
- Automatic discounts (no code) for segmented campaigns.
- Idempotent redemption keyed on `(cart_id, code)` or
  `(order_id, code)`.
- Anti-fraud: velocity, IP, device fingerprint.
- Audit log of every change.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Operators MUST be able to create a promotion without code change. | MUST | Marketing |
| BR--011 | A change MUST propagate to consumers within 5 seconds. | MUST | Operations |
| BR--012 | Every change MUST be attributed to a user and carry a reason. | MUST | Compliance |
| BR--013 | A redemption MUST be idempotent on `(cart_id, code)`. | MUST | Engineering |
| BR--014 | The service MUST support segmented campaigns (region, branch, customer segment, product). | MUST | Marketing |
| BR--015 | The service MUST support scheduled start and end dates. | MUST | Marketing |
| BR--016 | The service MUST support automatic discounts (no code). | MUST | Marketing |
| BR--017 | The service MUST support multiple discount types (percent, amount, free delivery, fixed price). | MUST | Marketing |
| BR--018 | The service MUST support per-user and overall redemption caps. | MUST | Finance |
| BR--019 | The service MUST emit `promotion.redeemed.v1` for every successful redemption. | MUST | Analytics |
| BR--020 | The service MUST detect and reject fraud patterns (velocity, segment abuse). | MUST | Fraud |
| BR--021 | The service MUST support "stackable" promotions (a cart can apply one of each type, but no two of the same type). | SHOULD | Marketing |
| BR--022 | A disabled promotion MUST NOT be redeemable. | MUST | Engineering |
| BR--023 | A promotion with a future `starts_at` MUST NOT be redeemable until that time. | MUST | Engineering |
| BR--024 | The service MUST return the discount as a separate line item so the cart's total math is auditable. | MUST | Finance |
| BR--025 | The service MUST export daily redemption snapshots to S3. | SHOULD | Analytics |
| BR--026 | The service MUST support per-tenant promotions. | MUST | Marketing |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A redemption is recorded in the same DB transaction that returns success; partial writes are not allowed. | Idempotency. |
| BR--031 | A redemption is rejected if the user has already redeemed the same code on the same cart. | Duplicate prevention. |
| BR--032 | A redemption is rejected if the per-user cap is reached. | Cap enforcement. |
| BR--033 | A redemption is rejected if the overall cap is reached. | Cap enforcement. |
| BR--034 | A redemption is rejected if the user is suspended. | Account state. |
| BR--035 | A redemption is rejected if the cart's currency does not match the promotion's currency. | Currency. |
| BR--036 | A redemption is rejected if the cart's total is below the min cart value. | Eligibility. |
| BR--037 | A redemption is rejected if the cart contains a non-eligible product / branch. | Eligibility. |
| BR--038 | A redemption's discount is computed by the rule and returned as a separate line item. | Audit. |
| BR--039 | The rule engine evaluates conditions in order; the first match wins. | Determinism. |

## 9. Assumptions

- The number of active promotions is bounded at < 5,000.
- The redemption rate is bounded at < 100/sec; HPA scales out
  beyond that.
- A cart / order is the unit of redemption; a customer may have many
  carts.

## 10. Constraints

- The service must be deployable without a code change for any new
  promotion.
- The service must be hot-reloadable (a promotion change is live in
  5 seconds).
- The service must not silently fall back on a server error; the
  caller is told to retry or skip the promotion.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `customer-service` | service | Segment lookup |
| `configuration-service` | service | Min cart value, currency rules |
| `fraud-risk-service` | service (optional) | Risk score |
| Keycloak | provider | JWKS |
| PostgreSQL 18 | database | Per-service schema `promotion` |
| Redis | cache | Promotion definitions |
| Kafka | broker | Publishes `promotion.*.v1` |
| HashiCorp Vault | secrets | DB credentials |
| AWS S3 | storage | Daily redemption export |

## 12. Business Workflows

- Marketing creates a campaign (workflow 1).
- Cart validates a code (workflow 2).
- Cart records a redemption at checkout (workflow 3).
- Operator disables a promotion (workflow 4).

## 13. Exception Workflows

- **Code invalid** — cart shows "code not valid".
- **Code expired** — cart shows "code expired".
- **Cap reached** — cart shows "code already used".
- **Fraud detected** — silent rejection + alert; the customer sees
  a generic error.

## 14. Success Criteria

- 99.9% validate / redeem availability.
- 0 double-redemptions under cart retry.
- A campaign is live in < 60 seconds.
- Fraud rate < 0.1% of redemptions.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Validate / redeem availability | 99.9% | Synthetic probes |
| P99 redeem latency | 200ms | RED metrics |
| Double-redemption rate | 0% | Reconciliation job |
| Fraud rate | < 0.1% | fraud-risk-service reports |
| Median propagation latency | 2s | Event publish to consumer ack |
| Write attribution coverage | 100% | Audit completeness |

## 16. Acceptance Criteria

- A marketing user can create a campaign in under 60 seconds.
- A code can be validated and redeemed in < 200ms P99.
- A cart retry does not double-redeem.
- A disabled promotion is rejected at validation.
- A future-dated promotion is rejected until its `starts_at`.
- A per-user cap is enforced.
- An overall cap is enforced.

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

