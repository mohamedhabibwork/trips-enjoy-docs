# Review and Rating Service

## 1. Purpose

`review-rating-service` owns the platform's **review and rating
system** — post-trip / post-order reviews, aggregated ratings per
driver / courier / restaurant, and reply handling. It is the single
source of truth for "what is the rating of this driver / courier /
restaurant".

## 2. Bounded Context

**Bounded context**: Reviews / ratings. In scope:

- Reviews (rides and food).
- Aggregated ratings (per driver, per courier, per restaurant).
- Replies (driver / restaurant / customer).
- Review prompt timing.
- Review moderation (basic; advanced is `support-service`).

Out of scope:

- Driver / courier / restaurant profile (owned by their respective
  services).
- Trip / order persistence (owned by `trip-service`,
  `food-order-service`).
- Notification delivery (owned by `notification-service`; this
  service triggers prompts but does not send them).

## 3. Responsibilities

- CRUD on reviews.
- Aggregated rating computation (rolling window).
- Reply to a review.
- Review prompt timing (24h after trip / delivery).
- Emit `review.submitted.v1`, `review.aggregated.v1`.
- Emit `review.zone_aggregated.v1` on debounced zone-level
  driver-rating recomputes (denser window for downstream pricing).
- Expose `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15`
  for the pricing engine's hot-path rating-density lookup.
- Consume `trip.completed.v1`, `food.order.delivered.v1` to schedule
  prompts.

## 4. Explicitly NOT Owned

- **Trip / order** — `trip-service`, `food-order-service`.
- **Driver / courier / restaurant profile** — respective services.
- **Notification delivery** — `notification-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer (mobile / web) | human | submit review, read replies |
| Driver / Courier | human | read reviews, reply |
| Restaurant staff | human | read reviews, reply |
| `trip-service` (event) | system | schedule prompt |
| `delivery-service` (event) | system | schedule prompt |
| `notification-service` (system) | system | send prompt |

## 6. Dependencies

### Synchronous (REST)

- `trip-service` (read) — fetch trip context for review submission.
- `food-order-service` (read) — fetch order context.
- `driver-service` (read) — fetch driver profile.
- `courier-service` (read) — fetch courier profile.
- `restaurant-service` (read) — fetch restaurant profile.
- `notification-service` — schedule prompt.
- `pricing-service` (DEGRADABLE) — consumes `review.zone_aggregated.v1`
  to warm its rating-density cache; on `pricing-service` outage this
  service keeps producing, the event is buffered.

### Asynchronous (events consumed)

- `trip.completed.v1` — schedule prompt.
- `food.order.delivered.v1` — schedule prompt.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `review`).
- Cache: Redis cluster.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `review`.
- Migrations: `services/review-rating-service/migrations/`.
- Soft delete: yes (`reviews.deleted_at`).
- Partitioning: `review.aggregations` by month; `review.audit_log` by
  month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/reviews` | bearer | submit review |
| GET | `/v1/reviews/{id}` | bearer | read review |
| POST | `/v1/reviews/{id}/reply` | bearer (driver/courier/restaurant) | reply |
| GET | `/v1/drivers/{id}/reviews` | bearer | list driver reviews |
| GET | `/v1/couriers/{id}/reviews` | bearer | list courier reviews |
| GET | `/v1/restaurants/{id}/reviews` | bearer | list restaurant reviews |
| GET | `/v1/drivers/{id}/rating` | bearer | read driver aggregated rating |
| GET | `/v1/couriers/{id}/rating` | bearer | read courier aggregated rating |
| GET | `/v1/restaurants/{id}/rating` | bearer | read restaurant aggregated rating |
| GET | `/v1/zones/{zone_id}/driver-rating?window_minutes=15` | bearer (service) | read zone-level driver rating (pricing hot-path) |

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `review.submitted.v1` | review submitted | `driver-service`, `courier-service`, `restaurant-service`, `analytics-service` |
| `review.aggregated.v1` | aggregation updated | `driver-service`, `courier-service`, `restaurant-service` |
| `review.zone_aggregated.v1` | zone-level driver rating recomputed (debounced; 15-minute window) | `pricing-service` (rating-density cache), `analytics-service`, `reporting-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.completed.v1` | `trip-service` | schedule prompt | enqueue prompt |
| `food.order.delivered.v1` | `delivery-service` | schedule prompt | enqueue prompt |

## 12. External Integrations

- **HashiCorp Vault** — DB credentials.
- **AWS S3** — daily export of reviews for analytics.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | string | env | |
| `PROMPT_DELAY_HOURS` | int | env | 24 (default) |
| `AGGREGATION_WINDOW_DAYS` | int | env | 90 (default) |

## 14. Security

- AuthN: JWT bearer.
- AuthZ: `review.read` for reads; `review.submit` for submit;
  `review.reply` for replies.
- Secrets: Vault.
- PII: customer id (UUID); review text may contain PII — stored as
  `confidential`.
- Rate limiting: per-customer review submission.

## 15. Observability

- Logs: JSON to stdout; standard fields + `review_id`,
  `subject_type`, `subject_id`, `rating`.
- Metrics: RED per route + `review_submitted_total{subject_type}`,
  `review_aggregated_total{subject_type}`.
- Traces: OpenTelemetry; one root span per request.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 4; HPA on CPU and review rate.
- Hot path: `POST /v1/reviews`.

## 17. Local Development

```bash
docker compose -f deploy/compose/review-rating-service.yml up -d db
make -C services/review-rating-service migrate-up
pnpm --filter @platform/review-rating-service dev
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/review-rating-service:<sha>`.
- Replicas: 4 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.

## 20. References

- Workflows: `docs/workflows/RIDE_WORKFLOWS.md`,
  `docs/workflows/FOOD_ORDER_WORKFLOWS.md`.

## 21. On-Call Runbook

### 21.1 Prompt Not Sent

1. Check the `trip.completed.v1` / `food.order.delivered.v1`
   consumer lag.
2. Check the prompt worker (`SELECT * FROM review.prompts WHERE
   status='pending' AND scheduled_for <= now()`).
3. If the worker is stuck, restart the pod; the worker is
   idempotent on `(customer_id, source_event_id)`.

### 21.2 Aggregated Rating Drift

1. The reconciliation job opens a ticket; the
   `review.aggregations` row does not match the sum of
   `review.reviews`.
2. Run a recompute job that rebuilds the aggregation from
   `reviews` for the affected subject.
3. Emit a fresh `review.aggregated.v1` after the recompute.

### 21.3 Spam Reviews Detected

1. The auto-flag is triggered by `rating <= 2` + a keyword match.
2. The review is hidden from the subject; support reviews it.
3. If confirmed spam, soft-delete the review; the aggregation
   excludes it.

### 21.4 Review Edit Window Expired

1. A customer tries to edit a review after 24h; the service
   returns 409 `EDIT_WINDOW_EXPIRED`.
2. If the customer has a legitimate reason (e.g. they were on
   vacation), the support agent can manually `PATCH` the review
   with `review.admin`.


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

### Related services

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`courier-service`](../courier-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — cross-service accounting view (the `review.zone_aggregated.v1` event feeds pricing-service's rating-density cache, which is an input to the per-trip fare quote that ultimately flows into `payment.captured.v1` and the ledger; a ride trip's full accounting trail is in this document)
