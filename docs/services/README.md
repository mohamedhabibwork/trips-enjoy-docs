# Service Catalog

> The catalog of all **20 active services** in the platform. Each
> service has a `README.md`, `BRD.md`, `SRS.md`, `ERD.md`,
> `INTEGRATION.md`, `WORKFLOWS.md`, and `TECH.md` under its
> directory.
>
> **38 services were consolidated** into 15 absorbing survivors on
> 2026-08-05 per
> [ADR-0017](../architecture/adrs/0017-20-service-architecture.md).
> The removed services (`address-service`, `analytics-service`,
> `branch-service`, `cart-service`, `checkout-service`,
> `communication-gateway-service`, `courier-dispatch-service`,
> `courier-earnings-service`, `courier-tracking-service`,
> `delivery-service`, `dispatch-service`,
> `driver-availability-service`, `driver-earnings-service`,
> `driver-incentive-service`, `driver-location-service`,
> `eta-routing-service`, `feature-flag-service`,
> `food-payment-integration-service`, `inventory-service`,
> `loyalty-service`, `menu-service`, `merchant-service`,
> `promotion-service`, `restaurant-order-mgmt-service`,
> `restaurant-settlement-service`, `restaurant-staff-service`,
> `review-rating-service`, `ride-history-service`,
> `ride-payment-integration-service`, `ride-request-service`,
> `ride-safety-service`, `scheduled-ride-service`,
> `support-service`, `tax-service`, `user-profile-service`,
> `vehicle-service`, `wallet-service`, `zone-service`) have been
> absorbed into the 15 absorbing survivors listed in
> [`../MIGRATION_HUB.md`](../MIGRATION_HUB.md). See the hub for the
> per-capability migration record and the six-month compatibility
> window policy.
>
> See [`RECOMMENDATIONS.md`](./RECOMMENDATIONS.md) for the technology map
> (language + framework + key libraries) and the platform-wide baseline in
> [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) for
> the shared infrastructure (PostgreSQL 18, Kafka, Keycloak, etc.) that
> every service inherits.
>
> **Super-admin permission to access all services** is managed by
> [`admin-service`](./admin-service/README.md) through the
> `SUPER_ADMIN` permission preset (1 × `platform.super_admin` + 20 ×
> `<service>.admin` scopes). The preset is enumerable at
> `GET /v1/admin/presets`, the 20-service catalog with each service's
> preset membership is at `GET /v1/admin/services`, and grant / revoke
> are `POST/DELETE /v1/admin/identity/(grant|revoke)-super-admin`.
> Both grant and revoke require break-glass co-signature (per
> `SECURITY_ARCHITECTURE.md` §14). See
> [`admin-service/INTEGRATION.md`](./admin-service/INTEGRATION.md) §1.12–§1.16.

## How to read this catalog

- **Grouped by bounded context** — the same grouping as
  [`../architecture/DOMAIN_MAP.md`](../architecture/DOMAIN_MAP.md).
- **One-line summary per service** — taken from that service's README §1
  (Purpose). Click through for the full contract.
- **Cross-cutting views** at the bottom: by data owner, by event producer,
  by tech profile.

---

## Platform overview

```mermaid
flowchart LR
  Client(["Customer / Driver / Courier / Merchant / Admin / Partner"])

  subgraph Edge
    gw["api-gateway"]
    id["identity-service"]
  end

  subgraph Foundation
    cfg["configuration-service"]
    notif["notification-service"]
    file["file-service"]
    audit["audit-service"]
    admin["admin-service"]
    reporting["reporting-service"]
    fr["fraud-risk-service"]
  end

  subgraph Customer["Customer & cross-persona"]
    cust["customer-service"]
    srch["search-service"]
  end

  subgraph Drivers
    drv["driver-service"]
  end

  subgraph Ride["Ride"]
    trip["trip-service"]
    prc["pricing-service"]
  end

  subgraph Food["Food marketplace"]
    rest["restaurant-service"]
    fos["food-order-service"]
    cou["courier-service"]
  end

  subgraph Geo
    geo["geolocation-service"]
  end

  subgraph Money["Payments & financial"]
    pay["payment-service"]
    led["ledger-service"]
  end

  Client --> gw --> id
  gw --> Customer
  gw --> Drivers
  gw --> Ride
  gw --> Food
  gw --> Money
  Customer & Drivers & Ride & Food & Geo & Money --> Foundation
  fr -.scores.-> Money
```

---

## Edge & stable (4 services)

- **[`api-gateway`](./api-gateway/README.md)** — single stateless north-south edge for every external client; JWT validation, rate limiting, request transformation.
- **[`identity-service`](./identity-service/README.md)** — thin adapter over Keycloak; mirrors `sub` → stable internal `identity_id`; caches profile claims.
- **[`file-service`](./file-service/README.md)** — file/media storage abstraction; KYC, menu photos, vehicle photos, support attachments.
- **[`audit-service`](./audit-service/README.md)** — immutable audit log of every audit-relevant event with strict-RBAC search API.

## Foundation (5 services)

- **[`configuration-service`](./configuration-service/README.md)** — source of truth for business rules and numerical values; absorbed feature flags.
- **[`notification-service`](./notification-service/README.md)** — user-visible messaging orchestrator (push, SMS, email, in-app, WhatsApp); templates, preferences, delivery state, immutable template-history audit chain, absorbed provider anti-corruption layer.
- **[`admin-service`](./admin-service/README.md)** — operations console web UI; absorbs **support** as a separately permissioned module (`support.admin` scope); CRUD producer for `pricing.geo_config.updated.v1` via `/v1/admin/pricing/geo-config[...]`.
- **[`reporting-service`](./reporting-service/README.md)** — read model + dashboard service; materialises domain events into queryable views; exports to CSV / Parquet; absorbs data-lake ingestion.
- **[`fraud-risk-service`](./fraud-risk-service/README.md)** — real-time risk scoring and fraud detection.

## Customer & cross-persona (2 services)

- **[`customer-service`](./customer-service/README.md)** — source of truth for the customer profile + cross-persona user data + saved addresses; exposes the **loyalty account**.
- **[`search-service`](./search-service/README.md)** — search index coordination authority across multiple verticals; absorbs the search-review projection.

## Drivers (1 service)

- **[`driver-service`](./driver-service/README.md)** — source of truth for the driver profile + KYC; absorbs online state, high-frequency location stream, match attempts + assignment ledger, quests / bonuses / guarantees / incentive accruals, and **vehicles**.

## Ride (2 services)

- **[`trip-service`](./trip-service/README.md)** — owns the trip aggregate, the ride booking aggregate, scheduled rides, ride safety, ride history, and the **trip-review projection**; evaluates guaranteed rewards at `state=completed`.
- **[`pricing-service`](./pricing-service/README.md)** — pure computational engine for ride and order price quotes; absorbs **tax rules**, **promotion rules**, and the **loyalty pricing rules**; per-location / OD-pair overrides; cross-border tax handling.

## Food marketplace (3 services)

- **[`restaurant-service`](./restaurant-service/README.md)** — owns the restaurant aggregate plus absorbed **merchant**, **branch**, **menu**, **inventory**, and **staff** capabilities.
- **[`food-order-service`](./food-order-service/README.md)** — owns the food order aggregate plus absorbed **cart**, **checkout**, **restaurant-side queue**, and the **food-review projection**.
- **[`courier-service`](./courier-service/README.md)** — owns the courier profile plus absorbed dispatch, tracking, and **delivery aggregate**.

## Geospatial (1 service)

- **[`geolocation-service`](./geolocation-service/README.md)** — geocoding + ETA + routing + zones + cities; absorbs **eta-routing** and **zone** capabilities.

## Payments & financial (2 services)

- **[`payment-service`](./payment-service/README.md)** — anti-corruption layer over payment providers; tokens (never raw PAN); intents, attempts, refunds, voids; absorbs **ride-payment-integration**, **food-payment-integration**, **wallet**, **driver-earnings**, **courier-earnings**, **restaurant-settlement** (incl. COD money); the **46-gateway registry** is the single source of truth.
- **[`ledger-service`](./ledger-service/README.md)** — platform's authoritative double-entry financial ledger (unchanged).

---

## Cross-cutting views

### By data ownership (source of truth)

The full source-of-truth matrix lives in
[`../architecture/DATA_OWNERSHIP.md`](../architecture/DATA_OWNERSHIP.md).
The single owners in this catalog:

| Entity | Owner service |
|---|---|
| Customer profile + cross-persona + addresses + loyalty account | `customer-service` |
| Driver profile + online state + location stream + match attempts + vehicles + incentives | `driver-service` |
| Trip + ride-request + scheduled-ride + ride-safety + ride-history + trip reviews | `trip-service` |
| Courier profile + dispatch + tracking + delivery | `courier-service` |
| Merchant + restaurant + branch + menu + inventory + staff | `restaurant-service` |
| Food order + cart + checkout + queue + food reviews | `food-order-service` |
| Configuration values + feature flags | `configuration-service` |
| Notification templates + deliveries + provider ACL | `notification-service` |
| Payment intents + wallet + sagas + earnings + settlement + COD | `payment-service` |
| Double-entry ledger | `ledger-service` |
| Search index + search reviews | `search-service` |
| Admin permissions + admin action log + support | `admin-service` |
| Reporting read models + analytics | `reporting-service` |
| Fraud scores + blocklists | `fraud-risk-service` |
| Audit log | `audit-service` |
| File / media metadata | `file-service` |
| Keycloak identity | `identity-service` |
| Zone geometry + ETA + routes + geocode | `geolocation-service` |
| Pricing engine + tax + promotions + loyalty rules | `pricing-service` |

### By technology profile (see also `RECOMMENDATIONS.md`)

| Profile | Services |
|---|---|
| **Edge / hot path** (Go) | `api-gateway`, `geolocation-service`, `configuration-service`, `notification-service` |
| **Business core** (Kotlin + Spring Boot 4) | Most domain services (`customer-service`, `driver-service`, `trip-service`, `restaurant-service`, `food-order-service`, `courier-service`, `identity-service`, `audit-service`, `admin-service`) |
| **Financial / correctness** (Kotlin + Spring Boot 4 + `BigDecimal` + jOOQ) | `payment-service`, `ledger-service`, `pricing-service` |
| **Math / scoring / ML** (Python + FastAPI) | `fraud-risk-service` |
| **Streaming / event ingest** (Kotlin Spring Kafka or Go `segmentio/kafka-go`) | `reporting-service`, `audit-service` |

For the full per-service table with language, framework, image, replicas,
HPA signal, and p99 target, see [`RECOMMENDATIONS.md` §2](./RECOMMENDATIONS.md).

### By workflow participation

| Workflow doc | Services participating |
|---|---|
| [`../workflows/RIDE_WORKFLOWS.md`](../workflows/RIDE_WORKFLOWS.md) | `trip-service`, `pricing-service`, `customer-service`, `driver-service`, `payment-service`, `notification-service` |
| [`../workflows/FOOD_ORDER_WORKFLOWS.md`](../workflows/FOOD_ORDER_WORKFLOWS.md) | `food-order-service`, `restaurant-service`, `courier-service`, `customer-service`, `pricing-service`, `payment-service`, `notification-service` |
| [`../workflows/PAYMENT_WORKFLOWS.md`](../workflows/PAYMENT_WORKFLOWS.md) | `payment-service`, `ledger-service`, `pricing-service`, `fraud-risk-service` |
| [`../workflows/DRIVER_WORKFLOWS.md`](../workflows/DRIVER_WORKFLOWS.md) | `driver-service`, `payment-service`, `notification-service` |
| [`../workflows/COURIER_WORKFLOWS.md`](../workflows/COURIER_WORKFLOWS.md) | `courier-service`, `payment-service`, `notification-service` |
| [`../workflows/MERCHANT_WORKFLOWS.md`](../workflows/MERCHANT_WORKFLOWS.md) | `restaurant-service`, `payment-service`, `notification-service` |
| [`../workflows/REFUND_WORKFLOWS.md`](../workflows/REFUND_WORKFLOWS.md) | `payment-service`, `ledger-service`, `customer-service`, `admin-service` |
| [`../workflows/SAFETY_WORKFLOWS.md`](../workflows/SAFETY_WORKFLOWS.md) | `trip-service`, `fraud-risk-service`, `customer-service`, `notification-service`, `admin-service` |
| [`../workflows/ACCOUNTING_WORKFLOWS.md`](../workflows/ACCOUNTING_WORKFLOWS.md) | `payment-service`, `ledger-service`, `pricing-service`, `reporting-service`, `admin-service` |

---

## See also

- [`../README.md`](../README.md) — top-level platform documentation reading order
- [`../main.md`](../../main.md) — top-level platform specification
- [`./RECOMMENDATIONS.md`](./RECOMMENDATIONS.md) — language/framework recommendation per service
- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, etc.
- [`../shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English summary of the platform
- [`../architecture/MICROSERVICES_MAP.md`](../architecture/MICROSERVICES_MAP.md) — service catalog with ownership, data, dependencies (table form)
- [`../MIGRATION_HUB.md`](../MIGRATION_HUB.md) — the 38-to-20 migration hub (removed → survivor mapping, six-month compatibility window)
- [`../architecture/DOMAIN_MAP.md`](../architecture/DOMAIN_MAP.md) — bounded contexts and how they map to services
- [`../architecture/CONTEXT_MAP.md`](../architecture/CONTEXT_MAP.md) — context relationships (customer/supplier, conformist, etc.)
- [`../architecture/DATA_OWNERSHIP.md`](../architecture/DATA_OWNERSHIP.md) — full source-of-truth matrix
- [`../architecture/EVENT_ARCHITECTURE.md`](../architecture/EVENT_ARCHITECTURE.md) — event catalog and delivery semantics
- [`../architecture/SERVICE_DOC_TEMPLATE.md`](../architecture/SERVICE_DOC_TEMPLATE.md) — the contract every service in this catalog follows
- [`../architecture/SERVICE_ISOLATION.md`](../architecture/SERVICE_ISOLATION.md) — **how every service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)