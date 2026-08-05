# Tax Service

## 1. Purpose

`tax-service` owns the platform's **tax calculation engine**. It
resolves the applicable tax rules for a given jurisdiction and
product, and returns a tax breakdown (rate, exemptions, net amount)
that `pricing-service` integrates into the price quote. The service
is read-mostly: rules are loaded at startup, refreshed on
`configuration.updated.v1`, and queried on every quote and every
checkout.

## 2. Bounded Context

**Bounded context**: Tax calculation. In scope:

- Jurisdiction rules (country, state, city, special zones).
- Product tax codes (food, alcohol, ride fare, delivery fee, tip).
- Exemptions (baby food, medicines, certain categories).
- Tax calculation API (synchronous).
- Tax cache for hot jurisdictions.

Out of scope:

- Business rule values (owned by `configuration-service`).
- Pricing math (owned by `pricing-service`); the service only
  returns the tax breakdown.
- Customer / order persistence.

## 3. Responsibilities

- CRUD on jurisdiction rules (admin).
- CRUD on product tax codes (admin).
- CRUD on exemptions (admin).
- Tax calculation API: given `(country, region, product_code,
  amount_minor)`, return `(rate, taxable_minor, tax_minor)`.
- Emit `tax.calculated.v1` for analytics.
- Reload cache on `configuration.updated.v1`.

## 4. Explicitly NOT Owned

- **Pricing** — `pricing-service`.
- **Order / cart** — `cart-service`, `checkout-service`,
  `food-order-service`.
- **Customer profile** — `customer-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Operator (admin) | human | write (RBAC + reason) |
| `pricing-service` | system | read (calculate) |
| `menu-service` | system | read (product tax code) |
| `analytics-service` | system | consumer of `tax.calculated.v1` |

## 6. Dependencies

### Synchronous (REST)

- `configuration-service` — read base rates (cached).

### Asynchronous (events consumed)

- `configuration.updated.v1` (from `configuration-service`) — reload
  rule cache.

## 7. Technology Assumptions

- Runtime: Go 1.22 (CPU-bound, predictable).
- Database: PostgreSQL 18 (per-service schema `tax`).
- Cache: Redis cluster.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `tax`.
- Migrations: `services/tax-service/migrations/`.
- Soft delete: yes (`jurisdictions.deleted_at`,
  `product_tax_codes.deleted_at`).
- Partitioning: no.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/tax/calculate` | bearer (service) | calculate tax |
| GET | `/v1/jurisdictions` | bearer | list jurisdictions |
| POST | `/v1/jurisdictions` | bearer (admin) | create jurisdiction |
| GET | `/v1/product-tax-codes` | bearer | list product codes |
| POST | `/v1/product-tax-codes` | bearer (admin) | create product code |
| GET | `/v1/exemptions` | bearer | list exemptions |
| POST | `/v1/exemptions` | bearer (admin) | create exemption |

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `tax.calculated.v1` | every successful calculation | `analytics-service` |
| `tax.rule.updated.v1` | rule change | `pricing-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | reload base rate cache | cache invalidation + reload |

## 12. External Integrations

- **HashiCorp Vault** — DB credentials.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | env | | |
| `DEFAULT_TAX_PCT` | float | env | fallback if no rule |

## 14. Security

- AuthN: JWT bearer.
- AuthZ: `tax.read` for reads; `tax.admin` for writes.
- Secrets: Vault.
- PII: none.
- Request signing: rule updates require `X-Signature`.

## 15. Observability

- Logs: JSON to stdout; standard fields + `jurisdiction_id`,
  `product_code`, `tax_minor`.
- Metrics: RED per route + `tax_calculate_seconds`,
  `tax_cache_hit_ratio`.
- Traces: OpenTelemetry; one root span per calculation.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 6; HPA on CPU and `tax_calculate_seconds`.
- Hot path: `POST /v1/tax/calculate` (read from in-memory cache).

## 17. Local Development

```bash
docker compose -f deploy/compose/tax-service.yml up -d db
make -C services/tax-service migrate-up
go run services/tax-service/cmd/server
```

Seed: NL, US (CA, NY), AE, SA, EG, UK, DE jurisdictions with
standard VAT / sales-tax rules.

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/tax-service:<sha>`.
- Replicas: 6 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.

## 20. Accounting impact

`tax-service` is the **sole owner of tax calculation** on the platform:
rates, exemptions, rounding, multi-currency, inclusive vs. exclusive,
B2B reverse-charge, destination vs. origin. It is read-mostly and
**does not post to the ledger directly**; it exposes a synchronous
`POST /v1/tax/calculate` and emits `tax.calculated.v1` for analytics.
Pricing / cart / checkout / payment-integration services consume the
calculated tax and integrate it into the quote / capture. The
collected tax becomes a `tax_payable` ledger liability on
`payment.captured.v1` (recorded by `ledger-service`), and is
periodically remitted via `admin-service` journal entries that move
the balance from `tax_payable` to `tax_remitted`.

- **What money facts it owns:** tax rate rules, exemptions,
  jurisdiction configuration, audit log of every write.
- **Coverage:** customer-facing VAT / sales tax / GST; marketplace
  VAT; driver / courier income tax withholding (gross-to-net);
  corporate income tax and regulatory fees (jurisdiction support).
- **Reconciliation:** indirect — the recognised tax flows into the
  ledger via `payment.captured.v1`, `food.payment.completed.v1`,
  `ride.payment.completed.v1`, `driver.earning.accrued.v1`,
  `courier.earning.accrued.v1`.
- **Human operator path:** admin CRUD via `tax.admin` role; every
  write emits `tax.audit.*` events consumed by `audit-service`.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view (tax recognition, remittance, driver /
courier withholding, CIT).

## 21. References

- Workflows: `docs/workflows/FOOD_ORDER_WORKFLOWS.md`,
  `docs/workflows/RIDE_WORKFLOWS.md`.

## 21. On-Call Runbook

### 21.1 Tax Calculation Returning Default

1. Check the cache hit ratio; if < 80%, the cache is cold.
2. Check the rule version; if a rule was just updated, the cache
   may be stale.
3. Run a manual refresh via the admin console
   (`POST /v1/tax/refresh`).

### 21.2 Jurisdiction Missing

1. The caller passed a `(country, region, city)` with no matching
   jurisdiction; the service returns 422 `NO_TAX_RULE`.
2. Check the `jurisdictions` table for the closest match (e.g.
   a city without a specific rule falls back to the country).
3. Create the missing jurisdiction with the `tax.admin` role.

### 21.3 Rounding Mismatch with Authority

1. The authority publishes a different total than the platform
   computed; the rounding rule is wrong.
2. Update the `rounding_rule` on the jurisdiction (e.g. from
   `round_half_up` to `round_half_even`).
3. Re-issue invoices for the affected period; the `audit-service`
   has the historical totals.

### 21.4 Schema Change to Product Tax Code

1. Add a new code with a new `category`; do NOT edit the existing
   code.
2. Add rate rules for the new code in the affected jurisdictions.
3. Update the `menu-service` mapping to use the new code; old
   events continue to use the old code (backward compat).


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

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`food-order-service`](../food-order-service/README.md), [`menu-service`](../menu-service/README.md), [`pricing-service`](../pricing-service/README.md)
- **Depended on by**: [`configuration-service`](../configuration-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md), [`pricing-service`](../pricing-service/README.md)

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
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (tax recognition & remittance; driver/courier withholding; corporate income tax)
