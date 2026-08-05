# identity-service

## 1. Purpose

The `identity-service` is the platform's thin adapter over Keycloak. It
mirrors Keycloak's `sub` to a stable internal `identity_id`, caches the
canonical user profile claims for low-latency reads, exposes a
normalized REST surface for other services, and propagates
suspension, disablement, and session-revocation events so that
downstream services can react. It is the only writer of the
`identity.identities` table and the canonical source of
`identity_id` for the platform.

## 2. Bounded Context

**Identity / Keycloak adapter.** In scope: Keycloak federation,
identity normalization, claim caching, suspension and disable
propagation, session revocation fan-out, GDPR erasure. Out of scope:
authentication (Keycloak does that), authorization (RBAC + resource
ownership lives elsewhere), business profiles (customer, driver,
courier, merchant — those are separate services).

## 3. Responsibilities

- Create and maintain the `identity.identities` mapping table
  (`identity_id` ↔ `kc_sub` ↔ realm).
- Provide `GET /v1/identities/{identity_id}` and
  `GET /v1/identities?kc_sub=...` to other services.
- Provide `POST /v1/identities/introspect` for partner and
  service-to-service callers that need a normalized view of a
  token's claims.
- Cache user profile claims (name, email, phone, locale, MFA
  status) in `identity.identity_claims` for low-latency reads.
- React to Keycloak's user lifecycle: create, update, suspend,
  disable, re-instate, delete.
- Emit `identity.user.created.v1`, `identity.user.suspended.v1`,
  `identity.user.disabled.v1`, `identity.user.reinstated.v1`,
  `identity.user.updated.v1`, and `identity.session.revoked.v1`.
- Provide an admin REST surface for `admin-service` to perform
  emergency suspension, disable, and erasure on behalf of
  support / compliance.
- Implement GDPR right-to-erasure: anonymize the row while
  keeping the `identity_id` and a `deleted_at` tombstone for
  referential integrity.

## 4. Explicitly NOT Owned

- **Credentials, passwords, OTPs, MFA factors.** Keycloak owns
  these. The service never stores them.
- **Authorization decisions.** RBAC is enforced downstream
  (gateway does coarse; services do fine).
- **Customer, driver, courier, merchant profiles.** Those
  services own their own profile data; `identity-service` only
  holds the normalized identity reference and a few shared
  claims.
- **Authentication endpoints.** `identity-service` does not
  expose login, logout, or token-issuance endpoints. Those are
  Keycloak's.
- **Sessions.** Keycloak manages sessions; this service
  propagates their revocation.
- **Refresh-token storage.** Keycloak owns refresh tokens; the
  gateway caches an access-token revocation set in Redis.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Keycloak (admin events / events listener) | system (IdP) | read/write via SPI; read via JWKS |
| `api-gateway` | service (client_credentials) | introspect, lookup |
| Downstream services (customer, driver, courier, merchant, user-profile, etc.) | service (client_credentials) | lookup by `identity_id` or `kc_sub` |
| `admin-service` | service (client_credentials) | admin actions (suspend, disable, erase) |
| `audit-service` | consumer | reads emitted events |
| `fraud-risk-service` | consumer | reads `identity.session.*.v1` |
| Compliance / GDPR operator | human | invokes erasure via `admin-service` |

## 6. Dependencies

### Synchronous (REST)

- `keycloak` admin API — `GET /admin/realms/{realm}/users`,
  `PUT /admin/realms/{realm}/users/{id}`,
  `POST /admin/realms/{realm}/users/{id}/logout`,
  `DELETE /admin/realms/{realm}/users/{id}` — SLO 99.95% —
  circuit breaker: yes. (On the write path; read path uses
  cache.)
- **None on the hot read path.** All other reads are served
  from the local cache + database.

### Asynchronous (events consumed)

- `customer.created.v1` from `customer-service` — optional
  back-channel: ensures an `identities` row exists for every
  customer. Duplicate handling: idempotent on `identity_id`.
- `driver.created.v1` from `driver-service` — same.
- `courier.created.v1` from `courier-service` — same.
- `merchant.created.v1` from ``restaurant-service` (merchant)` — same.
- `restaurant.created.v1` from `restaurant-service` — same
  (for restaurant staff identities).
- `configuration.updated.v1` from `configuration-service` —
  reloads claim-cache TTL, JWKS refresh interval, and
  Keycloak client secrets. Duplicate handling: configuration
  version stamp.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript) — fits the rest of the
  platform's API layer; type safety for the JWT/Keycloak
  adapter. Alternative acceptable: **Java 21** with Quarkus
  or **Go 1.22**.
- Database: PostgreSQL 18 (per-service schema `identity`).
- Cache: Redis (per-service logical DB; revocation
  projection + claim hot-cache).
- Event broker: Kafka.
- Keycloak SPI plugin: a custom EventListener (deployed as a
  `keycloak-providers` JAR inside the Keycloak cluster) writes
  the user-lifecycle events to Kafka for the
  `identity-service` consumer.

## 8. Database Ownership

- Schema: `identity`.
- Migrations: `services/identity-service/migrations/` (versioned,
  forward-only, golang-migrate).
- Soft delete: yes (for `identities`, to support GDPR
  right-to-erasure while preserving referential integrity).
- Partitioning: no. The `identities` table is small (one row
  per user) and does not warrant partitioning. The
  `identity_claim_history` table is range-partitioned by
  month (see `ERD.md`).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/identities/{identity_id}` | bearer (service) | lookup by internal id |
| GET | `/v1/identities?kc_sub={sub}&realm={realm}` | bearer (service) | lookup by Keycloak sub |
| POST | `/v1/identities/introspect` | bearer (service) | normalize a token's claims |
| POST | `/v1/identities` | bearer (service) | create mapping (used by profile services on first reference) |
| PATCH | `/v1/identities/{identity_id}` | bearer (admin) | update claims (name, locale, email_verified) |
| POST | `/v1/identities/{identity_id}/suspend` | bearer (admin) | suspend a user |
| POST | `/v1/identities/{identity_id}/disable` | bearer (admin) | disable a user |
| POST | `/v1/identities/{identity_id}/reinstate` | bearer (admin) | re-instate a suspended user |
| POST | `/v1/identities/{identity_id}/erase` | bearer (admin) | GDPR erasure (anonymize) |
| GET | `/v1/identities/{identity_id}/claims` | bearer (service) | cached claims |
| POST | `/v1/identities/{identity_id}/logout-everywhere` | bearer (admin) | force-logout all sessions |
| GET | `/v1/identities/{identity_id}/sessions` | bearer (admin) | list active sessions (read-through to Keycloak) |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `identity.user.created.v1` | A new identity mapping is created | ``customer-service` (cross-persona profile)`, `customer-service`, `driver-service`, `courier-service`, ``restaurant-service` (merchant)`, `audit-service`, ``reporting-service` (data lake)` |
| `identity.user.updated.v1` | Cached claims change (name, locale, email_verified, phone_verified) | ``customer-service` (cross-persona profile)`, `customer-service`, `driver-service`, `courier-service`, ``restaurant-service` (merchant)`, `notification-service` |
| `identity.user.suspended.v1` | Admin / fraud / payment-failure suspension | every service that owns a profile, `notification-service`, `fraud-risk-service`, `api-gateway` |
| `identity.user.disabled.v1` | Compliance / legal hold | every service that owns a profile, ``admin-service` (support module)`, `api-gateway` |
| `identity.user.reinstated.v1` | Suspension lifted | every service that owns a profile |
| `identity.user.erased.v1` | GDPR right-to-erasure completed | every service that owns a profile, `audit-service` |
| `identity.session.revoked.v1` | A Keycloak session was revoked (logout, theft, force) | `notification-service` (new-device alert), `audit-service`, `api-gateway` (revocation fan-out) |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `customer.created.v1` | `customer-service` | back-channel: ensure an `identities` row exists for every customer | upsert identity mapping if missing |
| `driver.created.v1` | `driver-service` | same, for drivers | upsert |
| `courier.created.v1` | `courier-service` | same, for couriers | upsert |
| `merchant.created.v1` | ``restaurant-service` (merchant)` | same, for merchants | upsert |
| `restaurant.created.v1` | `restaurant-service` | same, for restaurant staff | upsert |
| `configuration.updated.v1` | `configuration-service` | claim-cache TTL, JWKS refresh, Keycloak admin client secret rotation | hot-reload in-process config |

## 12. External Integrations

- **Keycloak** — admin API + SPI event listener. The
  `identity-service` is the only service authorized to call
  Keycloak's admin API. Credentials in Vault at
  `vault://platform/identity/keycloak-admin`.
- **Keycloak SPI plugin** — a custom `EventListenerProvider`
  subscribes to Keycloak's user-lifecycle events
  (`LOGIN`, `LOGOUT`, `REGISTER`, `UPDATE_PROFILE`,
  `SUSPEND`, `DELETE_USER`) and writes them to the
  `identity.lifecycle` Kafka topic for the
  `identity-service` consumer to process.
- **Vault** — Keycloak admin client secret, JWT signing keys
  (if used for any internal token), DB credentials.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `identity.cache.claim_ttl_seconds` | int | configuration-service | default 300 |
| `identity.keycloak.realm` | string | configuration-service | per environment |
| `identity.keycloak.admin_url` | URL | configuration-service | Keycloak base URL |
| `identity.keycloak.client_id` | string | configuration-service | admin client id |
| `identity.erasure.keep_financial_years` | int | configuration-service | legal retention (default 7) |
| `identity.sessions.refresh_min_interval_s` | int | configuration-service | min interval between session-list refreshes (default 60) |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer token.
  Service-to-service callers use `client_credentials` from
  `platform-services`. Admin endpoints require
  `platform-internal` realm with role `identity.admin` or
  `super_admin`.
- **AuthZ**: resource-level checks ensure that only the owning
  service or an admin can mutate an `identities` row.
- **Secrets**: Keycloak admin client secret, DB credentials in
  Vault. Rotated quarterly.
- **PII**: the row contains `name`, `email`, `phone` (cached
  claims). These are column-level encrypted with a per-tenant
  DEK. The service does not log PII in production.
- **GDPR**: a `POST /v1/identities/{id}/erase` action
  anonymizes the row (`name`, `email`, `phone` replaced with
  `REDACTED`; `email_verified`, `phone_verified` set to
  `false`) and emits `identity.user.erased.v1`. The
  `identity_id`, `kc_sub`, and `deleted_at` are preserved
  for referential integrity; financial records in
  `ledger-service` and `payment-service` are retained per
  legal hold but with their PII fields removed.
- **mTLS**: in-cluster mTLS via sidecar (Istio/Linkerd).
- **Audit**: every state-changing action (suspend, disable,
  reinstate, erase, force-logout) emits
  `admin.action.performed.v1` (via `admin-service`) and an
  `identity.*.v1` event.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=identity-service`, `version`, `env`, `region`,
  `correlation_id`, `request_id`, `trace_id`, `user_id`
  (the `identity_id` being touched), `action`, `actor`,
  `result`, `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `identity_cache_hit_ratio{claim}`
  - `identity_keycloak_admin_call_seconds{endpoint,
    result}`
  - `identity_lag_seconds{event}` — time from Keycloak
    event to `identity-service` row update
  - `identity_erasure_total`
  - `identity_session_revocations_total`
  - `identity_circuit_breaker_state{upstream}`
- **Traces**: OpenTelemetry. Root span per request. Child
  spans for Keycloak admin calls, DB queries, Redis
  lookups, Kafka publishes. Sample 100% on errors, 10% on
  success.
- **Health**: `/health` (process up), `/ready` (DB +
  Redis + Keycloak reachable, JWKS cached, consumer
  running), `/started` (initial claim-cache warmup done).

## 16. Scalability

- **Replicas**: default 6 per region; minimum 3 for HA.
- **HPA**: CPU 60% target; custom metric on
  `identity_lookups_per_second` (target 5k/replica).
- **Hot path**: claim lookup by `identity_id` (PostgreSQL
  index hit) → return cached claims. P99 ≤ 30 ms.

## 17. Local Development

- Run locally with `make up-identity` (the platform's
  docker-compose v2 file starts Keycloak with the SPI
  plugin, the database, Redis, and Kafka). A dev realm is
  pre-seeded with a few test users.
- The dev profile uses a permissive rate limit and a
  pre-issued dev admin JWT.
- Seed data: a `dev/seed/identities.json` fixture with 5
  test users, one per realm.

## 18. Deployment

- **Image**: `registry.example.com/services/identity-service:{semver}`.
- **Replicas**: 6 (prod, per region), 3 (staging), 1 (dev).
- **Resource limits**: 1 vCPU / 1 GiB RAM per pod.
- **Migrations**: run as a Kubernetes Job before the
  deployment's pods start; the Job uses the same image with
  the `migrate` subcommand.
- **Pod disruption budget**: `minAvailable: 3` in production.
- **Topology spread**: anti-affinity across nodes.
- **Network policy**: ingress from `api-gateway`,
  `admin-service`, and the Kafka consumer; egress to
  Keycloak, the DB, Redis, Kafka, Vault.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [``reporting-service` (data lake)`](../`reporting-service` (data lake)/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`ledger-service`](../ledger-service/README.md), [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [``admin-service` (support module)`](../`admin-service` (support module)/README.md), [``customer-service` (cross-persona profile)`](../`customer-service` (cross-persona profile)/README.md)
- **Depended on by**: [``customer-service` (addresses)`](../`customer-service` (addresses)/README.md), [`admin-service`](../admin-service/README.md), [`api-gateway`](../api-gateway/README.md), [``restaurant-service` (branch)`](../`restaurant-service` (branch)/README.md), [``notification-service` (provider ACL)`](../`notification-service` (provider ACL)/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [``configuration-service` (flags)`](../`configuration-service` (flags)/README.md), [`file-service`](../file-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [``restaurant-service` (staff)`](../`restaurant-service` (staff)/README.md), [``admin-service` (support module)`](../`admin-service` (support module)/README.md), [``customer-service` (cross-persona profile)`](../`customer-service` (cross-persona profile)/README.md), [``driver-service` (vehicles)`](../`driver-service` (vehicles)/README.md), [``geolocation-service` (zones)`](../`geolocation-service` (zones)/README.md)

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
