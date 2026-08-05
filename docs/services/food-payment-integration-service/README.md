# food-payment-integration-service

## 1. Purpose

`food-payment-integration-service` is the **saga orchestrator** for
the food payment flow. It is the only service in the platform that
coordinates the chain of financial consequences of a food
delivery: authorize → capture → merchant settlement → courier
earning → tip → optional refund. It owns the saga state and the
idempotency keys that make the chain safe to retry.

## 2. Bounded Context

Bounded context: **Food Payment Saga Orchestrator**.

- **In scope**: the orchestrated saga for a food order's money
  movements, idempotency keys for each step, compensation on
  failure, partial refund handling, ledger posting orchestration.
- **Out of scope**: payment provider integration (owned by
  `payment-service`), merchant payable balances (owned by
  `restaurant-settlement-service`), courier earnings (owned by
  `courier-earnings-service`), wallet mechanics (owned by
  `wallet-service`), the chart of accounts (owned by
  `ledger-service`).

## 3. Responsibilities

- Receive `delivery.completed.v1` and start the food-payment saga.
- Call `payment-service` to authorize (at checkout) and capture
  (at delivery completion), with idempotency keys derived from
  the `food_order_id`.
- Trigger courier earning accrual via
  `courier-earnings-service` on capture.
- Trigger merchant settlement accrual via
  `restaurant-settlement-service` on capture.
- Post a double-entry to `ledger-service` that reflects the
  net movement.
- Handle refunds: partial, full, and post-delivery; coordinate
  with `payment-service`, `wallet-service`, and
  `restaurant-settlement-service` to reverse the right
  components.
- Persist saga state in its own database so a re-run is a no-op
  (or a continuation).
- Provide a saga-status API for the admin / support tools.

## 4. Explicitly NOT Owned

- The actual money movement (the `payment-service`, `wallet-service`,
  `ledger-service`, and provider integration).
- Merchant settlement bank transfer (owned by
  `restaurant-settlement-service`).
- Courier earning balance (owned by `courier-earnings-service`).
- Food order state (owned by `food-order-service`).
- Delivery state (owned by `delivery-service`).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `food-order-service` | system | triggers refund / cancellation flows (write) |
| `delivery-service` | system | triggers capture flow (write) |
| `payment-service` | system | executes authorize / capture / refund (read / write) |
| `wallet-service` | system | credits / debits wallet (read / write) |
| `ledger-service` | system | posts double-entry (write) |
| `courier-earnings-service` | system | accrues courier earnings (write) |
| `restaurant-settlement-service` | system | accrues merchant payable (write) |
| `support-service` / `admin-service` | system | reads saga status, force-compensates (admin) |

## 6. Dependencies

### Synchronous (REST)

- `payment-service` — `authorize`, `capture`, `refund`,
  `void` (Idempotency-Key per call) — circuit breaker: yes.
- `wallet-service` — `credit`, `debit` (for wallet refunds and
  COD reconciliation) — circuit breaker: yes.
- `ledger-service` — `post` (double-entry) — circuit breaker: yes.
- `courier-earnings-service` — `accrue` (base + tip) — circuit
  breaker: yes.
- `restaurant-settlement-service` — `accrue` (merchant payable)
  — circuit breaker: yes.
- `food-order-service` — `GET /v1/orders/{id}` — read.

### Asynchronous (events consumed)

- `delivery.completed.v1` from `delivery-service` — start
  capture step — dedup: inbox.
- `payment.captured.v1` from `payment-service` — advance saga —
  dedup: inbox.
- `payment.failed.v1` from `payment-service` — start
  compensation — dedup: inbox.
- `payment.refund.completed.v1` from `payment-service` — advance
  refund saga — dedup: inbox.
- `food.order.cancelled.v1` from `food-order-service` — start
  refund saga — dedup: inbox.
- `customer.tip.added.v1` (own produce; consumed for accrual) —
  dedup: inbox.
- `configuration.updated.v1` — refresh in-memory.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema
  `food_payment_integration`).
- Cache: Redis (per-service) for saga state cache.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `food_payment_integration` (saga state).
- Migrations: `services/food-payment-integration-service/migrations/`.
- Soft delete: no (saga rows are terminal; old ones are
  archived).
- Partitioning: yes — `saga_steps` is range-partitioned by month
  (for retention).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/food-sagas` | bearer (service) | create a saga (from checkout) |
| GET | `/v1/food-sagas/{id}` | bearer (service / admin) | read saga state |
| POST | `/v1/food-sagas/{id}/capture` | bearer (service) | trigger capture step |
| POST | `/v1/food-sagas/{id}/refund` | bearer (service / support) | trigger refund step |
| POST | `/v1/food-sagas/{id}/force-compensate` | bearer (admin) | force compensation |
| GET | `/v1/food-sagas?order_id=…` | bearer (service / admin) | list sagas |
| GET | `/v1/food-sagas/metrics` | bearer (admin) | operational counters |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `food.payment.completed.v1` | capture step done; all downstream steps succeeded | `customer-service` (history), `restaurant-settlement-service`, `courier-earnings-service`, `audit-service` |
| `food.payment.failed.v1` | capture step failed and not retriable | `support-service`, `notification-service`, `audit-service` |
| `food.payment.partial_refund.v1` | partial refund applied | `restaurant-settlement-service`, `courier-earnings-service`, `audit-service` |
| `food.payment.full_refund.v1` | full refund applied | `restaurant-settlement-service`, `courier-earnings-service`, `audit-service` |
| `customer.tip.added.v1` | tip added | `courier-earnings-service` |
| `merchant.settlement.created.v1` | merchant payable accrued | `restaurant-settlement-service` |
| `food_payment_integration.audit.saga_advanced.v1` | every saga step | `audit-service` |
| `food_payment_integration.audit.saga_compensated.v1` | every compensation | `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `delivery.completed.v1` | `delivery-service` | start capture step | advance saga |
| `payment.captured.v1` | `payment-service` | capture confirmed | advance to merchant + courier + ledger |
| `payment.failed.v1` | `payment-service` | capture failed | start compensation |
| `payment.refund.completed.v1` | `payment-service` | refund confirmed | advance refund saga |
| `food.order.cancelled.v1` | `food-order-service` | refund needed | start refund saga |
| `configuration.updated.v1` | `configuration-service` | reload | refresh in-memory |

## 12. External Integrations

None direct. The provider is reached via `payment-service`.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `food_payment.capture_timeout_seconds` | int | `configuration-service` | default 60 |
| `food_payment.refund_timeout_seconds` | int | `configuration-service` | default 60 |
| `food_payment.saga_max_retries` | int | `configuration-service` | default 3 |
| `food_payment.tip_max_hours_after_delivery` | int | `configuration-service` | default 24 |
| `food_payment.partial_refund_max_pct` | int | `configuration-service` | default 50 (per-call) |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-services` for
  service-to-service; `platform-internal` for admin).
- AuthZ: admin endpoints require `food_payment.admin`.
- Secrets: no direct provider secrets.
- PII: customer name and email are NOT stored here; only
  `customer_id`.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `saga_id`,
  `order_id`, `step`, `state`.
- Metrics: `food_saga_started_total{trigger}`,
  `food_saga_step_total{step,outcome}`,
  `food_saga_compensated_total{step}`,
  `food_saga_completed_total{trigger}`,
  `food_payment_capture_seconds`,
  `food_payment_refund_seconds`.
- Traces: OpenTelemetry; root span per saga; child spans per step.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 6 (default) — HPA on `kafka_consumer_lag` and
  `food_saga_in_flight`.
- Hot path: capture step on `delivery.completed.v1`; throughput
  is bounded by deliveries completed per second per region.

## 17. Local Development

- `docker compose up food-payment-integration-service` brings up
  the service, PostgreSQL, Kafka, and synthetic downstream
  services.
- Tests: `pnpm test`, `pnpm test:e2e`.

## 18. Deployment

- Image: `registry.platform.io/food-payment-integration-service:{version}`.
- Replicas: 6 (per region).
- Resource limits: 1 vCPU / 1 GiB.
- Migrations: separate job.


## 19. Accounting impact

`food-payment-integration-service` is the **food-payment saga
orchestrator** and the service that posts the merchant payable +
customer receivable to the ledger on `food.payment.completed.v1`.

- **Trigger:** `delivery.completed.v1` from `delivery-service`.
- **Saga steps:** authorize → capture (via `payment-service`) →
  accrue merchant payable (via `restaurant-settlement-service`) →
  accrue courier earning (via `courier-earnings-service`) → emit
  `food.payment.completed.v1`.
- **Idempotency keys:** `order:<order_id>:auth`,
  `order:<order_id>:cap`, `delivery:<delivery_id>:earn`,
  `merchant:<merchant_id>:accrue`.
- **Resulting ledger postings:**
  - `cash` ↔ `revenue` + `tax_payable` (capture),
  - `merchant_receivable` ↔ `merchant_payable` +
    `commission_revenue` + `tax_payable_marketplace` (accrual),
  - `courier_payable` ↔ `commission_revenue` + `tax_withheld_payable`
    (courier earning accrual).
- **Refund flow:** full or partial refund flows through
  `food.payment.partial_refund.v1` /
  `food.payment.full_refund.v1` and posts proportional reversals
  via `payment-service` and `restaurant-settlement-service`.
- **Tip flow:** `customer.tip.added.v1` posts to
  `courier-earnings-service` and the ledger as a commission-free
  earning within the 24-hour tip window.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`food-order-service`](../food-order-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-settlement-service`](../restaurant-settlement-service/README.md), [`support-service`](../support-service/README.md), [`wallet-service`](../wallet-service/README.md)
- **Depended on by**: [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`food-order-service`](../food-order-service/README.md), [`ledger-service`](../ledger-service/README.md), [`payment-service`](../payment-service/README.md), [`promotion-service`](../promotion-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-settlement-service`](../restaurant-settlement-service/README.md), [`support-service`](../support-service/README.md), [`wallet-service`](../wallet-service/README.md)

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
- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (food-payment saga postings)
