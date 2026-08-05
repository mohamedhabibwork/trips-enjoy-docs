# Tax Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT. Errors
use the standard envelope.

### 1.1 `POST /v1/tax/calculate`

- **Purpose**: Calculate tax for a `(country, region, city, product,
  amount, inclusive?)` query.
- **Auth**: Bearer JWT. Required scope: `tax.read`.
- **Request**:
  ```json
  {
    "country": "NL",
    "region": null,
    "city": "amsterdam",
    "product_code": "FOOD",
    "amount_minor": 1000,
    "currency": "EUR",
    "inclusive": false,
    "merchant_id": null,
    "customer_id": null,
    "at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Response (200)**:
  ```json
  {
    "jurisdiction_id": "01HZX…",
    "product_tax_code_id": "01HZX…",
    "rate_pct": 9.00,
    "taxable_minor": 1000,
    "tax_minor": 90,
    "total_minor": 1090,
    "currency": "EUR",
    "reverse_charge": false,
    "exemption_id": null,
    "snapshot": {
      "version": 4123,
      "jurisdiction": { "country": "NL", "city": "amsterdam" },
      "rate_rule_id": "01HZX…"
    }
  }
  ```
- **Errors**:
  - 422 `NO_TAX_RULE` (no rule and no default).
  - 422 `CURRENCY_MISMATCH`.
  - 503 `CIRCUIT_OPEN`.

### 1.2 `GET /v1/jurisdictions`

- **Purpose**: List jurisdictions.
- **Auth**: Bearer JWT. Required scope: `tax.read`.
- **Query params**: `country`, `region`, `city`, `cursor`, `limit`.
- **Response (200)**: paginated list.
- **Errors**: 401 / 403.

### 1.3 `POST /v1/jurisdictions`

- **Purpose**: Create a jurisdiction.
- **Auth**: Bearer JWT. Required role: `tax.admin`. Required
  headers: `X-Audit-Reason`, `X-Signature`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "country": "NL",
    "region": null,
    "city": "amsterdam",
    "name": "Amsterdam",
    "currency": "EUR",
    "rounding_rule": "round_half_up",
    "rounding_precision": 2,
    "tax_type": "VAT",
    "default_rate_pct": 21.0,
    "effective_from": "2026-01-01T00:00:00Z",
    "reason": "Initial setup"
  }
  ```
- **Response (201)**: the jurisdiction.
- **Errors**: 400 / 401 / 403 / 409 / 422.

### 1.4 `GET /v1/product-tax-codes`

- **Purpose**: List product tax codes.
- **Auth**: Bearer JWT. Required scope: `tax.read`.
- **Response (200)**: array of product codes.

### 1.5 `POST /v1/product-tax-codes`

- **Purpose**: Create a product tax code.
- **Auth**: Bearer JWT. Required role: `tax.admin`.
- **Idempotency**: required.
- **Response (201)**: the product code.

### 1.6 `POST /v1/rate-rules`

- **Purpose**: Create a rate rule.
- **Auth**: Bearer JWT. Required role: `tax.admin`.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "jurisdiction_id": "01HZX…",
    "product_tax_code_id": "01HZX…",
    "rate_pct": 9.0,
    "reduced_rate": true,
    "reverse_charge": false,
    "effective_from": "2026-01-01T00:00:00Z"
  }
  ```
- **Response (201)**: the rate rule.

### 1.7 `POST /v1/exemptions`

- **Purpose**: Create an exemption.
- **Auth**: Bearer JWT. Required role: `tax.admin`.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "jurisdiction_id": "01HZX…",
    "product_tax_code_id": "01HZX…",
    "merchant_id": "01HZX…",
    "reason": "Tax-exempt merchant",
    "effective_from": "2026-01-01T00:00:00Z"
  }
  ```
- **Response (201)**: the exemption.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `configuration-service` | GET | `/v1/configurations/{key}` | read base rates | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `tax.calculated.v1`

- **Producer**: `tax-service`.
- **Topic**: `tax.calculated`.
- **Trigger**: every successful calculation.
- **Schema version**: 1.
- **Partition key**: `jurisdiction_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "tax.calculated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "tax-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "TaxCalculation",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "jurisdiction_id": "01HZX…",
      "product_code": "FOOD",
      "rate_pct": 9.0,
      "amount_minor": 1000,
      "tax_minor": 90,
      "currency": "EUR"
    }
  }
  ```
- **Retry / DLQ**: outbox / `tax.calculated.dlq`.

### 3.2 `tax.rule.updated.v1`

- **Producer**: `tax-service`.
- **Topic**: `tax.rule.updated`.
- **Trigger**: rule create / update / delete.
- **Schema version**: 1.
- **Partition key**: `jurisdiction_id`.
- **Schema**: includes `entity_type`, `entity_id`, `old_value`,
  `new_value`, `actor_id`, `reason`.
- **Retry / DLQ**: outbox.

### 3.3 `tax.calculated.v1`

- **Producer**: this service.
- **Topic**: `tax.calculated`.
- **Trigger**: A tax calculation completes (per line item, per order).
- **Schema version**: 1.
- **Partition key**: `calculation_id`.
- **Consumers**: `analytics-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "tax.calculated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `tax.calculated.dlq`.


### 3.4 `tax.exemption.applied.v1`

- **Producer**: this service.
- **Topic**: `tax.exemption`.
- **Trigger**: A tax exemption is applied.
- **Schema version**: 1.
- **Partition key**: `exemption_id`.
- **Consumers**: `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "tax.exemption.applied.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `tax.exemption.dlq`.


### 3.5 `tax.rule.updated.v1`

- **Producer**: this service.
- **Topic**: `platform.tax`.
- **Trigger**: A tax rule is updated (jurisdiction, rate, exemption).
- **Schema version**: 1.
- **Partition key**: `rule_id`.
- **Consumers**: `pricing-service`, `menu-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "tax.rule.updated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `platform.tax.dlq`.



## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: base rates changed.
- **Handler**: invalidate cache for the affected key; reload on next
  read.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.2 `menu.item.price.changed.v1`

- **Producer**: `menu-service`.
- **Reason**: Recalculate tax for the item.
- **Handler**: Recompute.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `order.line_item_created.v1`

- **Producer**: `food-order-service`.
- **Reason**: Tax calculation needed for a line item.
- **Handler**: Calculate.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, fall back
  to in-memory cache; on cache miss, 503.
- **Bulkheads**: separate outbound pool per dependency.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic above has a paired DLQ.
- **Reconciliation**: daily job verifies rules vs. S3 snapshot.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry: one root span per request; child spans for DB, Redis,
Kafka. `traceparent` propagated through Kafka headers. Sample rate
100% for errors, 10% for successes.

### 3.3 `tax.jurisdiction.created.v1`

- **Producer**: `tax-service`.
- **Topic**: `tax.jurisdiction.created`.
- **Trigger**: a new jurisdiction is created.
- **Schema version**: 1.
- **Partition key**: `jurisdiction_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "tax.jurisdiction.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "tax-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Jurisdiction",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "country": "NL",
      "region": null,
      "city": "amsterdam",
      "tax_type": "VAT",
      "default_rate_pct": 21.0
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 4.2 `menu.created.v1`

- **Producer**: `menu-service`.
- **Reason**: the tax service may need to refresh its product
  code cache when a new menu item is created (the item's
  `product_tax_code` is a foreign reference).
- **Handler**: invalidate `cache:product:<id>` in Redis.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `merchant.updated.v1`

- **Producer**: `merchant-service`.
- **Reason**: a merchant's tax exemption (e.g. a tax-exempt
  merchant in NL) may change; the tax service must refresh.
- **Handler**: invalidate `cache:exemption:<merchant_id>` in Redis.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.4 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: a customer suspension may affect a customer-specific
  exemption; the cache is invalidated.
- **Handler**: invalidate `cache:exemption:customer:<customer_id>`
  in Redis.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` §1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in §1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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

