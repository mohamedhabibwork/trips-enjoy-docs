# Review and Rating Service — Business Requirements Document

## 1. Document Purpose

Read by the product team, the customer experience team, the driver
engagement team, the merchant success team, and the
review-rating-service engineering team. It informs the design of
the review lifecycle, the aggregation window, the prompt timing,
and the operational SLOs.

## 2. Business Context

The platform collects reviews after every trip and every food
order. The reviews:

- Drive aggregated ratings per driver, courier, and restaurant.
- Surface trust signals to customers.
- Provide feedback to drivers, couriers, and restaurants.

This service exists so that **the aggregated rating is a single
source of truth** — never silently inconsistent across services —
and so that **prompts are sent at the right time** without spamming
customers.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.5% availability on the read path so ratings always load. | Availability SLO; P99 read latency < 200ms. |
| BR--002 | Send a review prompt 24h after a trip / order, only if not already submitted. | Prompt timing accuracy. |
| BR--003 | Compute aggregated ratings with a rolling window. | Aggregation accuracy. |
| BR--004 | Allow drivers, couriers, and restaurants to reply to a review. | Reply feature. |
| BR--005 | Emit `review.submitted.v1` and `review.aggregated.v1` for analytics and downstream consumers. | Event publication. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Customer Experience | owner | Review flow |
| Driver Engagement | consumer | Aggregated rating |
| Merchant Success | consumer | Restaurant rating |
| Analytics | consumer | Review events |
| Support | consumer | Moderation |

## 5. Actors / Personas

- **Customer** — submits a review in the mobile / web app.
- **Driver / Courier** — reads reviews, replies.
- **Restaurant staff** — reads reviews, replies.
- **`trip-service` (event)** — emits `trip.completed.v1`; the service
  schedules a prompt.
- **`delivery-service` (event)** — emits `food.order.delivered.v1`;
  the service schedules a prompt.
- **`notification-service`** — sends the prompt.
- **`support-service`** — moderates reviews.

## 6. Business Capabilities

- Submit review (rating 1-5, comment, tags).
- Read review.
- Reply to a review.
- Aggregated rating (rolling window).
- Prompt scheduling.
- Soft delete (with reason).
- Basic moderation (auto-flag on low rating + keywords).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | A customer MUST be able to submit a review for a completed trip or order. | MUST | Product |
| BR--011 | A review MUST be attributed to a customer, a subject (driver / courier / restaurant), and a source event (trip / order). | MUST | Engineering |
| BR--012 | An aggregated rating MUST be computed with a rolling window (default 90 days). | MUST | Engineering |
| BR--013 | A review prompt MUST be sent 24h after a trip / order, only if not already submitted. | MUST | Product |
| BR--014 | A driver / courier / restaurant MUST be able to reply to a review. | MUST | Product |
| BR--015 | The service MUST emit `review.submitted.v1` on every successful submission. | MUST | Analytics |
| BR--016 | The service MUST emit `review.aggregated.v1` on every aggregation update. | MUST | Engineering |
| BR--017 | The service MUST support soft delete (with reason) for moderation. | MUST | Support |
| BR--018 | The service MUST auto-flag a review with rating ≤ 2 and a keyword match. | SHOULD | Support |
| BR--019 | The service MUST rate-limit review submissions per customer. | MUST | Security |
| BR--020 | The service MUST keep the full review history for at least 7 years. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A customer may submit at most one review per trip / order. | UNIQUE on (customer_id, source_event_id). |
| BR--031 | A review's rating MUST be 1-5. | Standard. |
| BR--032 | An aggregated rating is the rolling average of the last 90 days. | Standard. |
| BR--033 | A reply is limited to 500 characters. | Standard. |
| BR--034 | A review is editable for 24h after submission; after that, it's read-only. | Standard. |
| BR--035 | A prompt is sent only if no review is submitted within 24h. | Standard. |
| BR--036 | A soft-deleted review is excluded from aggregation. | Standard. |

## 9. Assumptions

- A customer has at most one review per trip / order.
- A driver / courier / restaurant has at most one reply per review.
- The aggregation window is configurable per tenant.

## 10. Constraints

- The service must be hot-reloadable (a config change is live in 5
  seconds).
- The service must be deployable without a code change for any new
  rule.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `trip-service` | service (read) | fetch trip context |
| `food-order-service` | service (read) | fetch order context |
| `driver-service` | service (read) | fetch driver profile |
| `courier-service` | service (read) | fetch courier profile |
| `restaurant-service` | service (read) | fetch restaurant profile |
| `notification-service` | service | send prompt |
| PostgreSQL 18 | database | Per-service schema `review` |
| Redis | cache | Aggregation cache |
| Kafka | broker | Publishes + consumes |
| HashiCorp Vault | secrets | DB credentials |

## 12. Business Workflows

- Schedule prompt on trip / order completion (workflow 1).
- Customer submits a review (workflow 2).
- Driver / courier / restaurant replies (workflow 3).
- Aggregated rating update (workflow 4).

## 13. Exception Workflows

- **Duplicate submission** — second submission is rejected.
- **Auto-flag** — review is hidden from the subject until support
  reviews.
- **Soft delete** — review is removed from aggregation but retained
  for audit.

## 14. Success Criteria

- 99.5% read availability.
- 95% of trips / orders get a prompt within 24h.
- 0 double-prompts.
- Aggregation accuracy 100% (reconciliation job).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Read availability | 99.5% | Synthetic probes |
| P99 read latency | 200ms | RED metrics |
| Prompt timing accuracy | 95% within 24h | Reconciliation |
| Aggregation accuracy | 100% | Reconciliation job |
| Median propagation latency | 2s | Event publish to consumer ack |

## 16. Acceptance Criteria

- A customer can submit a review within 60 seconds of the prompt.
- A driver / courier / restaurant can reply to a review.
- An aggregated rating reflects the last 90 days.
- A duplicate submission is rejected.
- A soft-deleted review is excluded from aggregation.

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

