# Promotion Service

## 1. Purpose

`promotion-service` is the platform's source of truth for
**promotions** — coupons, campaigns, and redemption rules. It owns
the lifecycle of a promotion (created, scheduled, active, disabled,
expired), validates an applied code against a cart context, and
records every redemption with idempotency and anti-fraud
protections.

## 2. Bounded Context

**Bounded context**: Promotions / coupons. In scope:

- Promotion definitions (coupons, campaigns, automatic discounts).
- Redemption rules (eligible users, segments, products, branches,
  min cart value, dates, total uses per user, total uses overall).
- Redemption history with idempotency.
- Anti-fraud checks (velocity, device fingerprint, IP).
- Segmented targeting.

Out of scope:

- The actual cart / order persistence (owned by `cart-service` /
  `food-order-service`).
- Customer segment computation (owned by `customer-service`).
- Pricing math (owned by `pricing-service`); the service only
  applies the discount as a separate line item.

## 3. Responsibilities

- CRUD on promotions (operator).
- Validation of a code against a cart / customer context.
- Recording a redemption with idempotency keys.
- Enforcing redemption caps (per user, per promotion, per branch).
- Detecting and rejecting fraud patterns.
- Emitting `promotion.created.v1`, `promotion.disabled.v1`,
  `promotion.redeemed.v1`.

## 4. Explicitly NOT Owned

- **Customer segment computation** — `customer-service`.
- **Cart / order persistence** — `cart-service`,
  `food-order-service`.
- **Pricing math** — `pricing-service`.
- **Configuration of business rules** (min cart value, etc.) —
  `configuration-service` (this service reads them).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Operator (admin) | human | create / disable / rollback |
| Marketing manager | human | create campaigns |
| `cart-service` | system | validate + redeem |
| `checkout-service` | system | validate |
| `pricing-service` | system | apply discount (read) |
| `food-payment-integration-service` | system | redeem (capture) |
| Customer (via mobile / web) | human | apply code |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — read customer segment (SLO 99.9%; circuit
  breaker: yes; cached).
- `configuration-service` — read min cart value, max discount,
  currency rules (cached).
- `fraud-risk-service` — risk score for a redemption
  (SLO 99.5%; circuit breaker: yes; non-blocking — on failure,
  default to `allow`).

### Asynchronous (events consumed)

- `customer.segment.changed.v1` — invalidate per-segment caches.
- `customer.suspended.v1` — invalidate per-user caches and reject
  redemptions.
- `customer.created.v1` — pre-warm per-user caches (optional).

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `promotion`).
- Cache: Redis cluster.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `promotion`.
- Migrations: `services/promotion-service/migrations/`.
- Soft delete: yes (`promotions.disabled_at`).
- Partitioning: `promotion.redemptions` partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/promotions` | bearer | list promotions |
| POST | `/v1/promotions` | bearer (admin) | create promotion |
| GET | `/v1/promotions/{code}` | bearer | read promotion by code |
| POST | `/v1/promotions/{code}/disable` | bearer (admin) | disable |
| POST | `/v1/promotions/validate` | bearer (service) | validate a code against a cart |
| POST | `/v1/promotions/redeem` | bearer (service) | record a redemption |
| GET | `/v1/promotions/{code}/redemptions` | bearer (admin) | list redemptions |

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `promotion.created.v1` | promotion committed | `cart-service`, `pricing-service` |
| `promotion.disabled.v1` | promotion disabled | `cart-service`, `pricing-service` |
| `promotion.redeemed.v1` | successful redemption | `analytics-service`, `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `customer.segment.changed.v1` | `customer-service` | segment rules may now match | invalidate per-segment cache |
| `customer.suspended.v1` | `customer-service` | suspended customers cannot redeem | mark `blocked_user_id` in cache |
| `customer.created.v1` | `customer-service` | pre-warm per-user caches | optional |

## 12. External Integrations

- **HashiCorp Vault** — DB credentials, signing key.
- **AWS S3** — daily export of redemptions for analytics.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | string | env | |
| `ADMIN_REALM` | string | env | `platform-internal` |
| `REDEMPTION_DEDUP_TTL_HOURS` | int | env | 24 (default) |
| `MAX_REDEMPTIONS_PER_USER_PER_DAY` | int | env | 5 (default; configurable per promo) |

## 14. Security

- AuthN: JWT bearer.
- AuthZ: `promotion.admin` for writes; `promotion.read` for reads;
  `promotion.redeem` for the redemption endpoint.
- Secrets: Vault.
- PII: customer id (UUID) in redemptions; no contact info.
- Request signing: high-value mutations (large campaign rollouts)
  require `X-Signature`.

## 15. Observability

- Logs: JSON to stdout; standard fields + `promotion_code`,
  `customer_id`, `redemption_id`.
- Metrics: RED per route + `promotion_redeem_total{code, result}`,
  `promotion_validate_seconds`, `promotion_redemptions_per_user`.
- Traces: OpenTelemetry; one root span per request.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 6; HPA on CPU and `promotion_redeem_total`.
- Hot path: `POST /v1/promotions/redeem` (read from cache; write to
  DB inside a tx).

## 17. Local Development

```bash
docker compose -f deploy/compose/promotion-service.yml up -d db
make -C services/promotion-service migrate-up
pnpm --filter @platform/promotion-service dev
pnpm --filter @platform/promotion-service seed
```

Seed: sample `PERCENT_OFF`, `AMOUNT_OFF`, `FREE_DELIVERY` campaigns.

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/promotion-service:<sha>`.
- Replicas: 6 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.

## 20. Accounting impact

`promotion-service` produces **expense recognition** for every redeemed
promotion (coupon, code, referral credit, partner discount). A
redeemed promotion reduces recognised revenue on the captured order
and is recorded as an `expense` posting against a dedicated
promotion-discount account.

- **What money facts it owns:** promotion campaigns, coupons,
  redemptions, eligibility rules, per-program accounting code.
- **Postings:** on `promotion.redeemed.v1`, the ledger records
  `6310_promotion_discount` (expense) ↔ `revenue` (offset); the
  revenue at capture is the **net** amount (gross − discount).
- **B2B reverse-charge:** when a partner-issued promotion is
  subject to reverse-charge VAT, the discount is grossed up and
  the tax-service snapshot is re-applied at capture.
- **Liability accounting:** unredeemed promotional balances (gift
  cards, store credit) are tracked as a `promotional_liability`
  liability account at issuance; redemption reverses the
  liability and posts the expense.
- **Idempotency:** `promotion.redeem.v1` consumer uses an
  idempotency key (`Idempotency-Key=cart:<cart_id>:promo`) and is
  reconciled by `reporting-service`.
- **Reconciliation:** indirect — `reporting-service` reconciles
  promotion redemptions against captured orders; drift opens a
  P1 ticket.
- **Human operator path:** admin CRUD on campaigns via
  `promotion.admin` role; campaign changes emit
  `configuration.updated.v1`.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.

## 21. References

- Workflows: `docs/workflows/FOOD_ORDER_WORKFLOWS.md`,
  `docs/workflows/PAYMENT_WORKFLOWS.md`,
  `docs/workflows/ACCOUNTING_WORKFLOWS.md`.

## 22. On-Call Runbook

### 22.1 Promotion Not Redeeming

1. Check `promotion_redis_cache_hit_ratio`; if < 80%, the cache is
   cold; warm it by re-reading the promotion list.
2. Check the `customer.suspended.v1` consumer lag; a lag means
   recently-suspended customers are not yet blocked.
3. Check the per-user cap; if the cap is reached, the customer
   sees `PROMOTION_CAP_REACHED`.

### 22.2 Double Redemption Detected

1. The reconciliation job opens a P1 ticket; do NOT auto-reverse
   (the cart has been paid for the discount).
2. Add a `promotion.reverse` event with the prior `redemption_id`.
3. Notify the customer via `notification-service`; offer an
   alternative discount for the next order.

### 22.3 Fraud Spike

1. The `fraud-risk-service` is returning high scores.
2. The default is `allow`; check the alert and consider a manual
   kill switch via `POST /v1/promotions/{code}/disable`.
3. Coordinate with the security on-call before disabling a live
   campaign.


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

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md)
- **Depended on by**: [`cart-service`](../cart-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md)

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

- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (promotion discount expense; redemption idempotency)
