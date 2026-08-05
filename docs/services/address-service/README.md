# address-service

## 1. Purpose

The `address-service` is the platform's source of truth
for **saved addresses** — the addresses a user has saved
for ride pickup, food delivery, or any other context. It
stores the geocoded and normalized address, the tag (e.g.
`home`, `work`, `gym`), and a default-address flag. It is
the only writer of the `address` schema and the
canonical source of `address_id` for the platform.

## 2. Bounded Context

**Saved addresses.** In scope: saved addresses
(geocoded, normalized, tagged), default address per user,
geocoding integration with `geolocation-service`. Out of
scope: persona profiles (only references), trip /
delivery records (only references), payment / KYC.

## 3. Responsibilities

- Create and maintain the `address.addresses` row for
  every saved address.
- Geocode the address via `geolocation-service` on
  create / update.
- Normalize the address (street, city, region,
  country, postal code).
- Tag the address (e.g. `home`, `work`, `gym`,
  `other`).
- Support a default address per user per context
  (`ride_pickup`, `food_delivery`, etc.).
- Emit `address.created.v1`, `address.updated.v1`,
  `address.deleted.v1`.
- Provide the platform's address management API.

## 4. Explicitly NOT Owned

- **Geocoding engine.** `geolocation-service` (this
  service consumes its geocoding API).
- **Persona profiles.** `customer-service`,
  `driver-service`, `courier-service`.
- **Trip / delivery records.** `trip-service`,
  `delivery-service` (only references).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer / driver / courier (any persona) | human | read/write on their own addresses |
| `geolocation-service` | service | provides geocoding |
| `customer-service` | service (consumer) | reads default address for ride / order |
| `cart-service`, `checkout-service` | service (consumer) | reads default address |
| `notification-service` | service (consumer) | reads `address.*.v1` |
| `admin-service` | service | admin actions (GDPR erasure) |
| `audit-service` | consumer | reads `address.*.v1` |

## 6. Dependencies

### Synchronous (REST)

- `geolocation-service` — geocode an address on
  create / update — SLO 99.95% — circuit breaker:
  yes. On circuit open, accept the address but mark
  it `pending_geocode`; a backfill job retries
  later.
- `identity-service` — read claims on ownership
  validation — SLO 99.95% — circuit breaker: yes.

### Asynchronous (events consumed)

- `configuration.updated.v1` from
  `configuration-service` — reload supported
  country list, default address limits, geocoding
  retries. Duplicate handling: configuration version
  stamp.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript).
- Database: PostgreSQL 18 (per-service schema
  `address`) with **PostGIS** extension for the
  geometry column.
- Cache: Redis (per-service logical DB).
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `address`.
- Migrations: `services/address-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (`addresses` use `deleted_at`).
- Partitioning: no. The `addresses` table is one row
  per address (small per user).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/addresses/{address_id}` | bearer (owner or service) | get an address |
| POST | `/v1/addresses` | bearer (self) | create an address (geocodes) |
| PATCH | `/v1/addresses/{address_id}` | bearer (self) | update (re-geocodes) |
| DELETE | `/v1/addresses/{address_id}` | bearer (self) | soft-delete |
| GET | `/v1/addresses?identity_id={id}` | bearer (self or service) | list my addresses |
| PUT | `/v1/addresses/{address_id}/default` | bearer (self) | set as default for a context |
| DELETE | `/v1/addresses/{address_id}/default` | bearer (self) | unset as default |
| GET | `/v1/addresses/{address_id}/geocode` | bearer (self or service) | trigger re-geocode |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `address.created.v1` | A new address row is created | `customer-service` (cache invalidation), `notification-service`, `audit-service`, `analytics-service` |
| `address.updated.v1` | An address is updated (re-geocoded, tag changed, etc.) | same as created |
| `address.deleted.v1` | An address is soft-deleted | same as created |
| `address.geocoded.v1` | An address was successfully geocoded | `customer-service` (cache update) |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | hot-reload config | reload in-process config |

## 12. External Integrations

- **`geolocation-service`** — geocoding
  (`POST /v1/geocode`); reverse geocoding
  (`POST /v1/reverse-geocode`); the underlying map
  provider (Google Maps, Mapbox) is owned by
  `geolocation-service`.
- **Vault** — DB credentials.
- **Redis** — claim hot-cache, address hot-cache.
- **Kafka** — event bus.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `address.supported_countries` | string[] | configuration-service | ISO 3166-1 alpha-2 codes |
| `address.max_per_user` | int | configuration-service | default 20 |
| `address.geocode.provider` | string | configuration-service | default `geolocation-service` |
| `address.geocode.timeout_ms` | int | configuration-service | default 2000 |
| `address.geocode.retry_attempts` | int | configuration-service | default 3 |
| `address.default_contexts` | string[] | configuration-service | e.g. `["ride_pickup", "food_delivery"]` |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer
  token. Self-service endpoints accept the
  gateway-injected `X-User-Id`. Service endpoints
  require `client_credentials` with the
  `address.read` / `address.write` / `address.read.any`
  client role.
- **AuthZ**: resource-level check — a user can only
  read/write their own addresses; cross-user reads
  require `address.read.any` admin scope.
- **Secrets**: Vault; rotated quarterly.
- **PII**: addresses are PII (street, city, country,
  postal code). The `street_line1`, `street_line2`,
  `city`, `postal_code` columns are column-level
  encrypted.
- **GDPR**: `DELETE /v1/addresses/{id}` soft-deletes
  the row; a `POST /v1/addresses/{id}/erase` (admin
  only) anonymizes and emits `address.deleted.v1`
  with `reason: "gdpr"`.
- **mTLS**: in-cluster mTLS via sidecar.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=address-service`, `version`, `env`,
  `region`, `correlation_id`, `request_id`,
  `trace_id`, `user_id` (`identity_id`), `action`,
  `result`, `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `address_geocode_seconds{result}`
  - `address_geocode_failures_total{reason}`
  - `address_count_per_user{histogram}`
  - `address_tag_distribution{tag}`
  - `address_cache_hit_ratio`
- **Traces**: OpenTelemetry. Sample 100% on errors,
  10% on success.
- **Health**: `/health`, `/ready`, `/started`.

## 16. Scalability

- **Replicas**: default 4 per region; minimum 2.
- **HPA**: CPU 60% target; custom metric
  `address_lookups_per_second` (target 2k/replica).
- **Hot path**: address read by `address_id` (PK
  index hit) → return row. P99 ≤ 30 ms.

## 17. Local Development

- Run with `make up-address` (the platform's
  docker-compose v2 starts Postgres+PostGIS, Redis,
  Kafka, and a stub `geolocation-service` that
  returns fixed coordinates for a few sample
  addresses).

## 18. Deployment

- **Image**: `registry.example.com/services/address-service:{semver}`.
- **Replicas**: 4 (prod, per region), 2 (staging),
  1 (dev).
- **Resource limits**: 500m vCPU / 512 MiB RAM per
  pod.
- **Migrations**: Kubernetes Job before the
  deployment's pods start; same image with the
  `migrate` subcommand (enables PostGIS extension
  and applies schema).
- **Pod disruption budget**: `minAvailable: 2` in
  production.
- **Network policy**: ingress from `api-gateway`,
  `admin-service`; egress to `geolocation-service`,
  `identity-service`, the DB, Redis, Kafka, Vault.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-service`](../driver-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`checkout-service`](../checkout-service/README.md), [`customer-service`](../customer-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`zone-service`](../zone-service/README.md)

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
