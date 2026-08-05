# Configuration Service

## 1. Purpose

`configuration-service` is the platform's single source of truth for
**business rules and numerical values** (fares, fees, taxes, zones,
ride types, eligibility thresholds, copy, and feature-policy blobs).
Every other service reads its operating parameters from this service
either at startup or via long-poll / event push. The service exists so
that operators can change a business rule without redeploying any
service, and so that overrides are auditable and roll-backable.

## 2. Bounded Context

**Bounded context**: Externalized configuration. The service is the
owner of the `Configuration` aggregate: hierarchical, versioned,
scope-tagged documents that resolve to a concrete value given an
evaluation context.

In scope:

- Versioned configuration documents keyed by `(scope_type, scope_id,
  key)`.
- Hierarchical scope resolution (user → restaurant → branch → merchant
  → ride type → zone → city → country → segment → tenant → global).
- Long-poll delivery and event push of changes.
- Audit log of every write.
- Historical snapshots for the purposes of `pricing-service`'s
  `PriceQuote` reproducibility.
- Read APIs, filtered per channel (edge subset) for mobile clients.

Out of scope:

- Feature flags and rollouts (owned by ``configuration-service` (flags)`).
- Promotion / coupon rules (owned by ``pricing-service` (promotion)`).
- Tax jurisdiction rules (owned by ``pricing-service` (tax)`).
- Per-customer preferences (owned by ``customer-service` (cross-persona profile)`).

## 3. Responsibilities

- CRUD on configuration documents, each producing a new immutable
  version.
- Enforce schema validation per key (typed accessors in clients).
- Serve reads via REST and long-poll stream.
- Emit `configuration.updated.v1` on every write.
- Serve per-channel filtered configuration payloads for mobile clients.
- Maintain a history of every version of every key for audit and
  rollback.
- Capture "configuration snapshot" responses (read-your-writes) so
  consumers can pin a value to a specific version.

## 4. Explicitly NOT Owned

- **Feature flags** — ``configuration-service` (flags)` (different model: rules
  engine with percentage rollouts).
- **Promotion / coupon rules** — ``pricing-service` (promotion)` (different
  aggregate, different lifecycle).
- **Tax calculations** — ``pricing-service` (tax)` (jurisdictional logic).
- **Customer preferences** — ``customer-service` (cross-persona profile)`.
- **Zone geometry** — ``geolocation-service` (zones)` (configuration overlays on top).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Operator (admin) | human | write (gated by RBAC + reason) |
| Mobile / web client | system | read filtered subset per channel |
| Internal service | system | read (full key set) + subscribe to changes |
| Compliance auditor | human | read audit log only |
| Reconciliation job | system | read historical versions |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — validates the admin's token for write endpoints
  (circuit breaker: yes; SLO 99.95%).

### Asynchronous (events consumed)

- This service does not consume domain events; it is the source of
  truth for `configuration.*.v1`.

### Owned config-key families (cross-link)

The following config-key families live in this service as plain
values (no aggregates or workflows) and are consumed by their
owning services via `configuration.updated.v1`:

| Key prefix | Owning consumer | Notes |
|------------|-----------------|-------|
| `trip.reward.*` | `trip-service` | per-trip guaranteed-reward tuning (driver / customer / currency tables); the per-trip `trip.reward.user.kind` discriminator (`wallet_credit` / `loyalty_credit` / `none`) decides which downstream service consumes `trip.reward.granted.v1` for the user-side credit |
| `pricing.rating_density.*` | `pricing-service` | rating-density multipliers for fare calculation; `pricing-service` consumes `review.zone_aggregated.v1` to populate the cache |
| `pricing.loyalty.frequent_rider.*` | `pricing-service` | frequent-rider loyalty thresholds and bonuses; `pricing-service` consumes `loyalty.frequent_zone.aggregated.v1` to populate the cache |
| `pricing.geo_overrides.*` | `pricing-service` | geo-specific fare / surge / fee overrides; the *head* `geo_config` value lives in `admin-service` (see `admin-service` §3.6 `pricing.geo_config.updated.v1`) and is mirrored here as a pointer for operator-friendly reads |
| `payment.gateway.*` | `payment-service` | the gateway registry for the 46 supported payment gateways (enumerated in [`payment-service/GATEWAYS.md`](../payment-service/GATEWAYS.md)) — `payment.gateway.default`, `payment.gateway.<id>.{enabled,priority,regions,supported_currencies,supported_methods,signature_scheme,verify_style,health_url,webhook_ttl_seconds}`, and per-scope overrides `payment.gateway.override.{tenant,region,currency,payment_method}.<id>`. Mirrored into `payment_gateways` on `configuration.updated.v1`. |

This service owns storage + versioning + audit; it does **not**
interpret the values. Operators tune the values here; the
owning services (`trip-service`, `pricing-service`) consume the
events and `admin-service` separately owns the geo-config CRUD
that ultimately publishes `pricing.geo_config.updated.v1`.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript) for the REST surface; Go 1.22 for the
  long-poll streaming workers.
- Database: PostgreSQL 18 (per-service schema `configuration`).
- Cache: Redis cluster (per-service) for the read-side hot cache.
- Event broker: Kafka (publishes `configuration.updated.v1`).
- Search: PostgreSQL full-text on `key` and `value` (no external
  search dependency).

## 8. Database Ownership

- Schema: `configuration`.
- Migrations: `services/configuration-service/migrations/` (forward-only,
  versioned; runs in CI before deploy).
- Soft delete: no — versions are immutable; "deletion" is a
  deactivation that records the version.
- Partitioning: `configuration.documents` partitioned by
  `scope_type` (hash) for write parallelism; `configuration.history`
  partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/configurations` | bearer | list keys (paged, filtered) |
| GET | `/v1/configurations/{key}` | bearer | read latest (resolved) |
| GET | `/v1/configurations/{key}/versions` | bearer | read history |
| GET | `/v1/configurations/{key}/versions/{version}` | bearer | read a specific version |
| POST | `/v1/configurations` | bearer (admin) | create new key |
| PUT | `/v1/configurations/{key}/versions` | bearer (admin) | create new version |
| POST | `/v1/configurations/{key}/rollback` | bearer (admin) | revert to prior version |
| GET | `/v1/configurations/stream` | bearer | long-poll update stream |
| GET | `/v1/configurations/snapshot` | bearer | bulk read of a service's known keys |
| GET | `/v1/channels/{channel}/configurations` | bearer | filtered client subset |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `configuration.updated.v1` | any new version commit | every service (cache invalidation) |
| `configuration.rolled_back.v1` | explicit rollback | every service |
| `configuration.key.deprecated.v1` | a key is marked deprecated | consumer services still depending on it |
| `configuration.snapshot.exported.v1` | snapshot job writes to S3 | `reporting-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

This service is the **source of truth** for configuration. It does not
consume other services' domain events. The only external signal it
listens to is the `customer.segment.changed.v1` event, which it uses
to **invalidate** any per-user override caches (the override itself is
computed lazily; the service does not store per-user rows).

## 12. External Integrations

- **HashiCorp Vault** — DB credentials, JWT signing key for admin
  endpoints at `secret/configuration-service/<env>`.
- **AWS S3** — version snapshots exported for offline audit, path
  `s3://trips-enjoy-platform-audit/configuration/snapshots/<yyyy>/<mm>/<dd>/`.
- **OpenSearch** — optional; only when full-text search across values
  is enabled per tenant.

## 13. Configuration

The service is configured by environment variables (build-time only):

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | Per-env database URL from Vault |
| `KAFKA_BROKERS` | string | env | Bootstrap servers |
| `REDIS_URL` | string | env | Read cache |
| `ADMIN_REALM` | string | env | `platform-internal` |
| `LONGPOLL_MAX_WAIT_SECONDS` | int | env | Default 25 |
| `SNAPSHOT_CRON` | string | env | `0 3 * * *` |

The service itself does **not** read from its own configuration store
— that would be a chicken-and-egg loop.

## 14. Security

- AuthN: JWT bearer (RS256, Keycloak). Admin endpoints require the
  `config.admin` realm role.
- AuthZ: RBAC at the gateway; per-endpoint scope checks inside the
  service. Mutations require an `X-Audit-Reason` header.
- Secrets: Vault paths `secret/configuration-service/{env}/{resource}`.
- PII: configuration values may contain merchant copy and customer
  segment definitions; treat all values as `internal` by default,
  `confidential` if they reference identifiable users.
- Request signing: high-value mutations (production rollouts, mass
  rollback) require `X-Signature: t=<unix>,v1=<hex>` (HMAC-SHA256).

## 15. Observability

- Logs: structured JSON to stdout; fields: `correlation_id`,
  `user_id`, `service`, `route`, `latency_ms`, `status`, `key`,
  `version`.
- Metrics: RED per route + `config_writes_total{key,scope_type}`,
  `config_reads_total{key,cache_hit}`, `config_longpoll_connections`.
- Traces: OpenTelemetry; one root span per request; long-poll spans
  open until response or timeout.
- Health: `/health`, `/ready`, `/started`. `/ready` checks DB,
  Redis, Kafka producer readiness, and Keycloak JWKS reachability.

## 16. Scalability

- Replicas: default 6; HPA on CPU > 60% and
  `config_longpoll_connections > 1000`.
- Hot path: `GET /v1/configurations/{key}` is the dominant read; served
  by Redis with TTL = 5 min; long-poll only on cache miss + open
  connection.
- Write throughput: 100s/day; no scaling concern.

## 17. Local Development

```bash
# 1. Bring up the schema
docker compose -f deploy/compose/configuration-service.yml up -d db
# 2. Run migrations
make -C services/configuration-service migrate-up
# 3. Start the service
pnpm --filter @platform/configuration-service dev
# 4. Seed sample configuration
pnpm --filter @platform/configuration-service seed
```

Seed data ships with a `dev` profile only (city defaults, sample
ride-type multipliers, sample cancellation rules).

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/configuration-service:<sha>`.
- Replicas: 6 in production (3 per AZ).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: run from a `pre-upgrade` Job before the rolling deploy.
- Rollback: re-deploy the prior image; the prior configuration
  versions are still in the DB; nothing in code references values
  by version.

## 19. Disaster Recovery

- RPO: 5 minutes (PostgreSQL PITR + WAL shipping).
- RTO: 30 minutes (warm standby in another region).
- Backups: nightly logical + continuous WAL; 30-day retention.

## 20. Workflows this service participates in

| Workflow | Role | Reference |
|----------|------|-----------|
| Accounting workflows (customer transaction recognition, driver income, guaranteed-rewards settlement, restaurant settlement, reconciliation & period close) | supplies the `trip.reward.*`, `pricing.rating_density.*`, `pricing.loyalty.frequent_rider.*`, and `pricing.geo_overrides.*` config keys to the owning services via `configuration.updated.v1`; appears in no accounting workflow as an orchestrator | [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) |

This service is **informational only** in those workflows — it
stores and pushes the values; the orchestrators are
`trip-service`, `pricing-service`, ``payment-service` (driver earnings)`,
``payment-service` (merchant settlement)`, and `ledger-service`.

## 21. References

- Architecture: `docs/architecture/CONFIGURATION_ARCHITECTURE.md`.
- Event spec: `docs/architecture/EVENT_ARCHITECTURE.md`.
- API standards: `docs/architecture/API_STANDARDS.md`.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``configuration-service` (flags)` (flag
definitions, override rules, rollout percentages) is now absorbed
into this service. The canonical source is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) §3.36.

### A.1 Bounded context (post-merger)

Configuration documents + feature flags + flag overrides. The
service is the **only** writer of the `configuration` schema.

### A.2 Absorbed responsibilities (from `configuration-service` (flags))

- Flag definitions (boolean, multivariate, % rollout,
  segment-targeted, time-windowed).
- Flag overrides.
- Per-flag audit trail.
- Emit `feature_flag.updated.v1` (same topic + schema version).

### A.3 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/flags` | bearer | list |
| GET  | `/v1/flags/{name}` | bearer | read |
| POST | `/v1/flags` | bearer (admin) | create |
| POST | `/v1/flags/{name}/override` | bearer (admin) | override |

### A.4 Compatibility window

For at least six calendar months from 2026-08-05:

- `feature_flag.updated.v1` is published under the same topic
  name and schema version by this service.
- `/v1/flags*` continue to be served from this service.
- Old schema name `feature_flag.*` remains readable as a view in
  the `configuration` schema.

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

- **Depends on**: [`audit-service`](../audit-service/README.md), [`identity-service`](../identity-service/README.md), [`pricing-service`](../pricing-service/README.md)
- **Depended on by**: [`admin-service`](../admin-service/README.md), [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)

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
