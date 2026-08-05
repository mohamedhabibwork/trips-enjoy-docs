# eta-routing-service

## 1. Purpose

`eta-routing-service` is a **stateless adapter** that hides the map
provider behind a stable internal API. It returns ETAs, route
polylines, distance, and alternative routes for the ride-hailing and
food-delivery products. It is the only service in the platform that
talks to the map provider.

## 2. Bounded Context

Bounded context: **ETA / Routing Adapter**.

In scope:

- ETA computation (point-to-point, point-to-zone, zone-to-zone).
- Route computation (with traffic).
- Alternative routes.
- Caching of recent results.
- Provider failover.

Out of scope (explicitly):

- Geocoding (place lookup) — `geolocation-service`.
- The driver's location stream — `driver-location-service`.
- Pricing — `pricing-service`.
- The trip aggregate — `trip-service`.

## 3. Responsibilities

- Accept ETA / route requests from internal services.
- Call the map provider (e.g. HERE, Google Maps) with retries and
  failover.
- Cache results in Redis with a TTL (short for traffic-sensitive
  results, longer for static distance / route shape).
- Return a stable response shape regardless of provider.
- Emit `eta.computed.v1` and `route.computed.v1` for analytics.

## 4. Explicitly NOT Owned

- Geocoding.
- Driver location.
- Trip state.
- Pricing.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `pricing-service` | system | read ETA for quote |
| `dispatch-service` | system | read ETA for candidate ranking |
| `trip-service` | system | read route for completion fare |
| `delivery-service` | system | read route for delivery |
| `ride-request-service` | system | read ETA for the customer-facing ETA |
| `admin-service` | system | read (debug) |

## 6. Dependencies

### Synchronous (REST)

- Map provider (HERE / Google Maps) — HTTPS with a provider API key
  in Vault.

### Asynchronous (events consumed)

- `configuration.updated.v1` from `configuration-service` — reload
  provider config, cache TTL.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `eta_routing` (cache
  only).
- Cache: Redis (per-service) for the hot cache.
- Event broker: Kafka.
- HTTP client: native `fetch` with `undici`; mTLS to the provider
  if supported.

## 8. Database Ownership

- Schema: `eta_routing` (owned exclusively by this service).
- Migrations: `services/eta-routing-service/migrations/`.
- The schema is **cache only** — the source of truth is the
  provider. The cache table is rebuilt on TTL or provider update.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/routing/eta | bearer (system) | compute ETA |
| POST | /v1/routing/route | bearer (system) | compute a route |
| POST | /v1/routing/alternatives | bearer (system) | alternative routes |
| GET | /v1/routing/health | system | provider health |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `eta.computed.v1` | every successful ETA | `analytics-service` |
| `route.computed.v1` | every successful route | `analytics-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | reload config | cache invalidation |

## 12. External Integrations

- **Map provider** (HERE / Google Maps / OSRM) — credentials in
  Vault at `secret/eta_routing/{env}/map_provider`. Failover
  provider is configured in `configuration-service`.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `eta_routing.provider.primary` | string | configuration-service | e.g. `here` |
| `eta_routing.provider.failover` | string | configuration-service | e.g. `google` |
| `eta_routing.cache.eta_ttl_seconds` | int | configuration-service | default 60 |
| `eta_routing.cache.route_ttl_seconds` | int | configuration-service | default 300 |
| `eta_routing.timeout_ms` | int | configuration-service | default 800 |
| `eta_routing.retry.max_attempts` | int | configuration-service | default 2 |

## 14. Security

- AuthN: Bearer JWT (system only for routing endpoints; the
  provider call uses mTLS or an API key).
- AuthZ: callers must have the `internal_service` role.
- Secrets: provider API key in Vault; rotated per the platform's
  rotation policy.
- PII: pickup/dropoff (lat/lon) are passed in; we do not store
  them in the cache (the cache key is a hash of the request).

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `caller_service`,
  `latency_ms`, `cache_hit`, `provider`, `status`. Do not log
  precise lat/lon.
- Metrics: `eta_compute_seconds` (histogram, label `provider`,
  `cache_hit`), `route_compute_seconds` (histogram),
  `eta_cache_hit_ratio`, `eta_provider_errors_total{provider,code}`,
  `eta_failover_total`.
- Traces: OpenTelemetry, root span per request; the provider call
  is a child span.
- Health: `/health`, `/ready` (provider reachability + DB +
  Kafka), `/started`.

## 16. Scalability

- Replicas: 6 (default); HPA on CPU and on
  `eta_compute_seconds_p99`.
- Hot path: the ETA / route compute. Caching absorbs most of the
  load.
- The provider is the bottleneck; we cache aggressively.

## 17. Local Development

```bash
docker compose up eta-routing-service postgres kafka redis
bun run --filter eta-routing-service dev
```

Seed data: a fake provider (OSRM local container or a static JSON
fixture) returning predictable ETAs.

## 18. Deployment

- Image: `registry.uber.io/eta-routing-service:<sha>`.
- Replicas: 6 (HPA to 30).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.

## 19. Cross-Service Coordination Notes

This service participates in the platform's
cross-service choreography. The following notes summarize
how it fits with the broader event-driven architecture
(see `architecture/EVENT_ARCHITECTURE.md`):

- **Idempotency**: every non-idempotent write is
  protected by an `Idempotency-Key` header and the
  platform-standard idempotency store. A retried
  request with the same key and body returns the
  stored response.
- **Outbox**: every state change that needs to be
  published to Kafka is written to the local outbox
  table in the same database transaction as the
  state change. A separate poller publishes to Kafka
  with `acks=all` and retries on failure. Outbox rows
  are purged 24 h after a successful publish.
- **Inbox**: every consumed event is recorded in the
  local inbox table keyed by `event_id` with a 24 h
  TTL, so re-deliveries are de-duplicated.
- **Cross-service references**: every cross-service
  reference (e.g. `identity_id`, `customer_id`,
  `driver_id`, `courier_id`, `vehicle_id`,
  `address_id`, `payment_method_id`) is stored as a
  UUID column WITHOUT database FK. The owning
  service is the source of truth; this service
  validates the reference exists and is current
  before persisting.
- **Distributed tracing**: OpenTelemetry
  `traceparent` is propagated to every downstream
  call. The platform's `correlation_id` is enriched on
  every span and emitted in every event's envelope.
- **Graceful degradation**: when a non-critical
  dependency is unavailable, the service degrades
  to a safe fallback (e.g. cached read, degraded
  write). The fallback is documented in the
  relevant workflow's `WORKFLOWS.md`.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`configuration-service`](../configuration-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md)

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
