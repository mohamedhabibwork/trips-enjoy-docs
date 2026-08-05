# Loyalty Service

## 1. Purpose

`loyalty-service` owns the platform's **loyalty program**: customer
points balances, tier membership, earn rules, burn rules, and
statements. It is the source of truth for "how many points does
this customer have" and "what tier are they in" — referenced by the
mobile app, the pricing engine (read-only), and the customer
profile.

## 2. Bounded Context

**Bounded context**: Loyalty points / tiers. In scope:

- Customer points balance.
- Tier (bronze / silver / gold / platinum).
- Earn rules (per ride, per order, per category).
- Burn rules (redemption at checkout, redemption for upgrades).
- Earn / burn history (statement).
- Tier thresholds and roll-over.

Out of scope:

- Customer profile (owned by `customer-service`).
- Pricing math (owned by `pricing-service`); the service exposes a
  read-only `points_value_minor` that the pricing engine uses to
  apply a burn at checkout.
- Wallet / payment (owned by `wallet-service` / `payment-service`).
  A burn is a wallet hold + capture, not a points-deduction-as-payment.

## 3. Responsibilities

- CRUD on points balances (earn / burn).
- Tier calculation (read from `configuration-service` rules).
- Earn rule evaluation (e.g. "2x points on rides in EU").
- Burn rule evaluation (e.g. "1000 points = 5 EUR discount").
- Statement history.
- Expose `GET /v1/accounts/{customer_id}/frequent-zones?window_days=30`
  for the pricing engine's frequent-rider discount hot path.
- Emitting `loyalty.points.earned.v1`,
  `loyalty.points.burned.v1`, `loyalty.tier.changed.v1`,
  `loyalty.frequent_zone.aggregated.v1` (debounced daily).

## 4. Explicitly NOT Owned

- **Customer profile** — `customer-service`.
- **Wallet / payment** — `wallet-service` / `payment-service`.
- **Promotion** — `promotion-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer (mobile / web) | human | read balance, read statement |
| `trip-service` (event) | system | earn on trip complete |
| `food-order-service` (event) | system | earn on delivery complete |
| `cart-service` | system | burn at checkout |
| `pricing-service` | system | read `points_value_minor` |
| Operator (admin) | human | adjust (with reason) |

## 6. Dependencies

### Synchronous (REST)

- `configuration-service` — read earn / burn / tier rules (cached).
- `customer-service` — read customer for tier eligibility
  (SLO 99.9%; circuit breaker: yes; cached).
- `pricing-service` (DEGRADABLE) — consumes the
  `loyalty.frequent_zone.aggregated.v1` event to warm its
  frequent-rider discount cache; on `pricing-service` outage
  this service keeps producing, the event is buffered.

### Asynchronous (events consumed)

- `trip.completed.v1` (from `trip-service`) — earn points.
- `food.order.delivered.v1` (from `delivery-service`) — earn points.
- `customer.suspended.v1` (from `customer-service`) — block earn /
  burn.
- `configuration.updated.v1` (from `configuration-service`) — reload
  rule cache.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `loyalty`).
- Cache: Redis cluster.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `loyalty`.
- Migrations: `services/loyalty-service/migrations/`.
- Soft delete: no.
- Partitioning: `loyalty.transactions` partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/accounts/{customer_id}` | bearer | read balance + tier |
| GET | `/v1/accounts/{customer_id}/transactions` | bearer | statement |
| POST | `/v1/accounts/{customer_id}/earn` | bearer (service) | earn (idempotent) |
| POST | `/v1/accounts/{customer_id}/burn` | bearer (service) | burn (idempotent) |
| POST | `/v1/accounts/{customer_id}/adjust` | bearer (admin) | manual adjust |
| GET | `/v1/tiers` | bearer | list tiers |
| GET | `/v1/accounts/{customer_id}/frequent-zones?window_days=30` | bearer (service) | list frequent zones for the customer (pricing hot-path) |

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `loyalty.points.earned.v1` | points earned | `customer-service` (UI), `analytics-service` |
| `loyalty.points.burned.v1` | points burned | `customer-service` (UI), `analytics-service` |
| `loyalty.tier.changed.v1` | customer tier changed | `customer-service` (UI), `analytics-service`, `promotion-service` |
| `loyalty.frequent_zone.aggregated.v1` | frequent-zone aggregation changed (debounced daily) | `pricing-service` (loyalty discount cache), `analytics-service`, `reporting-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.completed.v1` | `trip-service` | earn points | idempotent insert |
| `food.order.delivered.v1` | `delivery-service` | earn points | idempotent insert |
| `customer.suspended.v1` | `customer-service` | block earn / burn | mark `blocked` |
| `configuration.updated.v1` | `configuration-service` | reload rule cache | cache invalidation |

## 12. External Integrations

- **HashiCorp Vault** — DB credentials.
- **AWS S3** — daily export of statements for customer support.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | string | env | |
| `EARN_DEDUP_TTL_HOURS` | int | env | 72 (default) |

## 14. Security

- AuthN: JWT bearer.
- AuthZ: `loyalty.read` for read; `loyalty.earn` / `loyalty.burn`
  for service writes; `loyalty.admin` for manual adjust.
- Secrets: Vault.
- PII: customer id (UUID).
- Request signing: manual adjust requires `X-Signature`.

## 15. Observability

- Logs: JSON to stdout; standard fields + `customer_id`,
  `points_delta`, `transaction_type`.
- Metrics: RED per route + `loyalty_points_earned_total`,
  `loyalty_points_burned_total`, `loyalty_tier_distribution`.
- Traces: OpenTelemetry; one root span per request.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 4; HPA on CPU and earn RPS.
- Hot path: `POST /v1/accounts/{customer_id}/earn` (event-driven).

## 17. Local Development

```bash
docker compose -f deploy/compose/loyalty-service.yml up -d db
make -C services/loyalty-service migrate-up
pnpm --filter @platform/loyalty-service dev
pnpm --filter @platform/loyalty-service seed
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/loyalty-service:<sha>`.
- Replicas: 4 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.

## 20. References

- Architecture: `docs/architecture/CONSISTENCY_STRATEGY.md`.

## 21. On-Call Runbook

### 21.1 Points Not Credited After Trip

1. Check the `trip.completed.v1` consumer lag; a lag means the
   earn is pending.
2. Check the `customer.suspended.v1` consumer lag; a recently
   suspended customer is blocked.
3. Run a manual earn via `POST /v1/accounts/{id}/adjust` if the
   event is permanently lost (DLQ).

### 21.2 Balance Drift Detected

1. The reconciliation job opens a P1 ticket; the sum of
   `transactions.points_delta` does NOT equal `account.balance`.
2. Read the most recent `transactions` for the customer; identify
   the missing row.
3. Insert a compensating `type='adjust'` row with `reason` and
   `actor_id`; the audit log is preserved.

### 21.3 Tier Not Updating

1. Check the `tier_qualifying_spend_minor` and the
   `AGGREGATION_WINDOW_DAYS` config.
2. Check the `loyalty.tier.changed.v1` consumer lag on the
   `customer-service` side.
3. Force a re-compute by inserting a `type='adjust'` row of
   `points_delta=0` (a no-op that re-runs the tier logic).

### 21.4 Bulk Re-Tier

When a threshold is changed retroactively (e.g. an error in the
`loyalty.tier.*` rules):

1. Pause consumers (`feature-flag-service` flag
   `loyalty.recompute_paused`).
2. Run a one-off script that recomputes the tier for every account.
3. Re-enable consumers.


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

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`cart-service`](../cart-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`promotion-service`](../promotion-service/README.md), [`trip-service`](../trip-service/README.md), [`wallet-service`](../wallet-service/README.md)
- **Depended on by**: [`customer-service`](../customer-service/README.md), [`trip-service`](../trip-service/README.md)

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
- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows (earn / burn events)
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — cross-service accounting view (loyalty burns are wallet holds + captures that flow through the operational layer; the platform's loyalty revenue and customer-credit liability positions are tracked here)
