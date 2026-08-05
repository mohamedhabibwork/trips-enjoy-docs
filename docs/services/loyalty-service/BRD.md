# Loyalty Service — Business Requirements Document

## 1. Document Purpose

Read by the growth team, the loyalty program manager, finance, the
loyalty-service engineering team, and customer support. It informs
the design of the points engine, the tier system, the earn / burn
rules, and the operational SLOs.

## 2. Business Context

The platform runs a loyalty program that rewards customers for
rides and food orders. The program:

- Earns points on every qualifying trip / order.
- Promotes customers through tiers (bronze / silver / gold /
  platinum) based on qualifying spend.
- Lets customers burn points at checkout (as a discount) or for
  upgrades.
- Provides a statement history for transparency.

This service exists so that **the points balance is a single source
of truth** — never silently inconsistent across services — and so
that **tier changes are observable in real time**.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.5% availability on the earn / burn path so loyalty never blocks a transaction. | Availability SLO; P99 latency < 200ms. |
| BR--002 | Prevent double-earn on a duplicate event. | Idempotency on `(customer_id, source_event_id)`. |
| BR--003 | Tier changes visible within 5 seconds of the threshold being crossed. | Event propagation. |
| BR--004 | Provide a complete statement for a customer. | GET /v1/accounts/{id}/transactions. |
| BR--005 | Allow marketing to change earn / burn rules without code change. | All rules in `configuration-service`. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Loyalty program manager | owner | Earn / burn rules, tiers |
| Marketing | operator | Campaign-driven boosts |
| Finance | consumer | Liability accounting |
| Customer Support | consumer | Statement for disputes |
| Customer | end user | Balance, tier, statement |
| Compliance | auditor | Full change history |

## 5. Actors / Personas

- **Customer** — reads balance / statement in the app; burns points
  at checkout.
- **`trip-service` (event)** — emits `trip.completed.v1`; the
  loyalty service earns points.
- **`delivery-service` (event)** — emits `food.order.delivered.v1`;
  the loyalty service earns points.
- **`cart-service` / `checkout-service`** — burns points at
  checkout.
- **`pricing-service`** — reads `points_value_minor` (the
  conversion rate) for burn.
- **Operator (admin)** — manual adjust with reason.

## 6. Business Capabilities

- Points balance per customer.
- Tier per customer (bronze / silver / gold / platinum).
- Earn rules (per ride, per order, per category, per region, per
  tier).
- Burn rules (redemption at checkout, redemption for upgrades).
- Tier thresholds (qualifying spend in the last 90 days).
- Statement history.
- Manual adjust (admin).
- Idempotent earn / burn on `(customer_id, source_event_id)`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST earn points on `trip.completed.v1`. | MUST | Engineering |
| BR--011 | The service MUST earn points on `food.order.delivered.v1`. | MUST | Engineering |
| BR--012 | The service MUST support idempotent earn keyed on `(customer_id, source_event_id)`. | MUST | Engineering |
| BR--013 | The service MUST compute tier from the documented threshold rules. | MUST | Loyalty |
| BR--014 | The service MUST support burn at checkout (as a discount line item). | MUST | Product |
| BR--015 | The service MUST return `points_value_minor` so the pricing engine can apply a burn. | MUST | Pricing |
| BR--016 | The service MUST emit `loyalty.points.earned.v1`, `loyalty.points.burned.v1`, `loyalty.tier.changed.v1`. | MUST | Analytics |
| BR--017 | The service MUST support manual adjust by an admin with a reason. | MUST | Support |
| BR--018 | The service MUST NOT allow earn / burn for a suspended customer. | MUST | Compliance |
| BR--019 | The service MUST persist every change in `loyalty.audit_log` with `actor_id` and `reason`. | MUST | Compliance |
| BR--020 | The service MUST support tier-based earn boosts (e.g. gold = 2x points). | SHOULD | Loyalty |
| BR--021 | The service MUST support time-bounded earn campaigns (e.g. "2x points in July"). | SHOULD | Marketing |
| BR--022 | The service MUST support burn for upgrades (e.g. free upgrade to premium). | SHOULD | Product |
| BR--023 | The service MUST export daily statements to S3. | SHOULD | Support |
| BR--024 | The service MUST keep the full transaction history for at least 7 years. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | An earn is recorded in the same DB transaction that returns success; partial writes are not allowed. | Idempotency. |
| BR--031 | A burn is rejected if the balance is insufficient. | Cap. |
| BR--032 | A burn is rejected if the customer is suspended. | Account state. |
| BR--033 | A tier is computed from qualifying spend in the last 90 days. | Window. |
| BR--034 | A tier change is recorded in the same DB transaction that updates the tier. | Atomic. |
| BR--035 | Points have an expiry (configurable, default 24 months). | Liability. |
| BR--036 | A burn is rounded to the nearest minor unit. | Rounding. |
| BR--037 | Manual adjust requires `X-Audit-Reason` and `X-Signature`. | Audit. |

## 9. Assumptions

- A customer has at most one loyalty account.
- A trip / order is the unit of earn.
- Tier rules and earn / burn rules are read from
  `configuration-service` and may be changed without code.

## 10. Constraints

- The service must be hot-reloadable (a rule change is live in 5
  seconds).
- The service must be deployable without a code change for any new
  rule.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `configuration-service` | service | Earn / burn / tier rules |
| `customer-service` | async | suspend / reinstate |
| `trip-service` | async | trip.completed.v1 |
| `delivery-service` | async | food.order.delivered.v1 |
| PostgreSQL 18 | database | Per-service schema `loyalty` |
| Redis | cache | Rule cache |
| Kafka | broker | Publishes + consumes |
| HashiCorp Vault | secrets | DB credentials |
| AWS S3 | storage | Daily statement export |

## 12. Business Workflows

- Earn points on trip completion (workflow 1).
- Earn points on order delivery (workflow 2).
- Burn points at checkout (workflow 3).
- Tier change (workflow 4).
- Manual adjust (workflow 5).

## 13. Exception Workflows

- **Duplicate event** — idempotency check returns the prior result.
- **Insufficient balance** — burn is rejected with a clear error.
- **Suspended customer** — earn / burn rejected.
- **Configuration unreachable** — service uses last cached rules;
  alert.

## 14. Success Criteria

- 99.5% earn / burn availability.
- 0 double-earn on duplicate events.
- Tier change visible within 5 seconds of threshold.
- Statement is complete and accurate.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Earn / burn availability | 99.5% | Synthetic probes |
| P99 earn / burn latency | 200ms | RED metrics |
| Double-earn rate | 0% | Reconciliation job |
| Tier change propagation | < 5s | Event publish to consumer ack |
| Statement accuracy | 100% | Reconciliation job |
| Write attribution coverage | 100% | Audit completeness |

## 16. Acceptance Criteria

- A trip completion earns points within 5 seconds.
- An order delivery earns points within 5 seconds.
- A burn at checkout is reflected in the balance immediately.
- A tier change is visible in the customer profile within 5 seconds.
- A duplicate event does not double-earn.
- A suspended customer cannot earn or burn.
- A manual adjust is attributed to an admin with a reason.

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

