# user-profile-service

## 1. Purpose

The `user-profile-service` holds the **common, cross-persona
user data** that does not belong to any specific business
profile (customer, driver, courier, merchant). It stores the
user's preferred languages, notification preferences, the list
of devices the user has logged in from, and a reference to
the avatar file in `file-service`. It is the only writer of
the `user_profile` schema and the canonical source of these
fields for every persona.

## 2. Bounded Context

**Common user data.** In scope: language preferences,
notification preferences (per channel and per topic), device
list (with platform, model, OS, last-seen, push token
reference), and avatar reference. Out of scope: persona
profiles (customer, driver, courier, merchant), credentials
(`identity-service`), and notification delivery
(`notification-service`).

## 3. Responsibilities

- Create and maintain the `user_profile.profiles` row for
  every platform user (idempotent on `identity_id`).
- Store language preferences (BCP-47 codes, primary +
  secondary).
- Store notification preferences (per channel: push, email,
  SMS, in-app; per topic: marketing, transactional,
  safety, etc.).
- Maintain the `user_profile.devices` list (platform, model,
  OS version, app version, push token reference, last-seen).
- Store an avatar reference (file_id in `file-service`).
- Emit `user.profile.updated.v1` on any change.
- Provide read APIs for the user's own profile
  (`GET /v1/profiles/{identity_id}`) and for downstream
  services to fetch a profile in batch.
- Provide write APIs for the user to manage their own
  preferences; admin APIs for `admin-service` to set
  defaults or fix issues.

## 4. Explicitly NOT Owned

- **Credentials, sessions, MFA.** `identity-service` (via
  Keycloak).
- **Persona-specific profiles.** `customer-service`,
  `driver-service`, `courier-service`, `merchant-service`.
- **Notification delivery.** `notification-service` reads
  the preferences from this service.
- **File storage.** The avatar file is in
  `file-service`; this service only stores the `file_id`.
- **Push token validation.** The push provider validates
  tokens; this service stores the token reference for
  `notification-service` to use.
- **User authentication.** The service relies on the
  gateway-validated JWT.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer / driver / courier / merchant (any persona) | human | read/write on their own profile |
| `identity-service` | service (producer) | emits `identity.user.created.v1` and `identity.user.updated.v1` |
| `notification-service` | service (consumer) | reads notification preferences; subscribes to `user.profile.updated.v1` |
| `file-service` | service | stores avatar file; provides `file_id` |
| `admin-service` | service (client_credentials) | admin actions (set defaults, force-merge) |
| `customer-service`, `driver-service`, `courier-service`, `merchant-service` | service | read API to render a profile header (avatar, name) |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — read claims (`name`, `email`,
  `phone`, `locale`) on profile creation — SLO 99.95% —
  circuit breaker: yes (cached locally after first read).
- `file-service` — fetch avatar metadata on read;
  upload avatar on write — SLO 99.9% — circuit breaker:
  yes.
- **None on the hot read path** beyond local cache.

### Asynchronous (events consumed)

- `identity.user.created.v1` from `identity-service` —
  ensure a `user_profile.profiles` row exists. Duplicate
  handling: idempotent on `identity_id`.
- `identity.user.updated.v1` from `identity-service` —
  refresh cached claims. Duplicate handling: idempotent.
- `identity.user.suspended.v1` from `identity-service` —
  mark profile `read_only` (no preference/device changes
  while suspended). Duplicate handling: idempotent.
- `identity.user.disabled.v1` from `identity-service` —
  same; permanent.
- `identity.user.erased.v1` from `identity-service` —
  anonymize the row (PII, push tokens). Duplicate
  handling: idempotent.
- `configuration.updated.v1` from `configuration-service` —
  reload defaults, i18n catalogs, retention policies.
  Duplicate handling: configuration version stamp.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript) — fits the rest of
  the platform's API layer.
- Database: PostgreSQL 18 (per-service schema
  `user_profile`).
- Cache: Redis (per-service logical DB).
- Event broker: Kafka.
- i18n: messages catalogs in
  `services/user-profile-service/i18n/{locale}.json`,
  loaded at startup and on `configuration.updated.v1`.

## 8. Database Ownership

- Schema: `user_profile`.
- Migrations: `services/user-profile-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (`profiles` and `devices` use
  `deleted_at`).
- Partitioning: no. The `profiles` table is one row per
  user; the `devices` table is small (few per user).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/profiles/{identity_id}` | bearer (self or service) | get a profile |
| POST | `/v1/profiles/{identity_id}` | bearer (self or service) | create (idempotent on `identity_id`) |
| PATCH | `/v1/profiles/{identity_id}` | bearer (self or admin) | update preferences |
| GET | `/v1/profiles/{identity_id}/devices` | bearer (self or service) | list devices |
| POST | `/v1/profiles/{identity_id}/devices` | bearer (self) | register a device |
| DELETE | `/v1/profiles/{identity_id}/devices/{device_id}` | bearer (self) | unregister |
| PATCH | `/v1/profiles/{identity_id}/devices/{device_id}` | bearer (self) | update push token, last-seen |
| GET | `/v1/profiles/{identity_id}/notification-preferences` | bearer (self or service) | list prefs |
| PUT | `/v1/profiles/{identity_id}/notification-preferences` | bearer (self) | set prefs |
| POST | `/v1/profiles/{identity_id}/avatar` | bearer (self) | upload avatar (delegates to `file-service`) |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `user.profile.updated.v1` | any change to a profile (preferences, device, avatar) | `customer-service`, `notification-service`, `admin-service`, `analytics-service` |
| `user.profile.erased.v1` | GDPR erasure applied to the profile | `audit-service`, `analytics-service` |
| `user.device.added.v1` | a device is registered | `notification-service`, `audit-service` |
| `user.device.removed.v1` | a device is unregistered | `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `identity.user.created.v1` | `identity-service` | ensure a `profiles` row exists for every user | upsert if missing; pull claims for defaults |
| `identity.user.updated.v1` | `identity-service` | refresh cached claims (name, locale) | update cached fields; update `preferred_locale` if changed |
| `identity.user.suspended.v1` | `identity-service` | set profile `read_only` flag | set `read_only_at`; reject preference changes |
| `identity.user.disabled.v1` | `identity-service` | same; permanent | same |
| `identity.user.reinstated.v1` | `identity-service` | clear `read_only` flag | clear `read_only_at` |
| `identity.user.erased.v1` | `identity-service` | anonymize PII and clear devices | anonymize; emit `user.profile.erased.v1` |
| `configuration.updated.v1` | `configuration-service` | reload defaults, i18n catalogs, retention | hot-reload in-process config |

## 12. External Integrations

- **Vault** — DB credentials; no third-party API keys
  (the avatar is in `file-service`; push tokens are
  provider-specific strings passed in by the mobile
  client and forwarded to `notification-service`).
- **Redis** — claim hot-cache and device list
  projection.
- **Kafka** — event bus.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `user_profile.default_locale` | string (BCP-47) | configuration-service | default when user has no preference (default `en-US`) |
| `user_profile.supported_locales` | string[] | configuration-service | BCP-47 codes the platform supports (default `["en-US", "ar-SA"]`) |
| `user_profile.notification_defaults` | JSONB | configuration-service | per-topic default (e.g. `marketing: push+email`, `transactional: push`) |
| `user_profile.devices.max_per_user` | int | configuration-service | default 10 |
| `user_profile.devices.idle_unregister_days` | int | configuration-service | default 180 |
| `user_profile.avatar.max_size_bytes` | int | configuration-service | default 5 MiB |
| `user_profile.avatar.allowed_mime` | string[] | configuration-service | `image/jpeg`, `image/png`, `image/webp` |
| `user_profile.cache.claim_ttl_seconds` | int | configuration-service | default 600 |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer token.
  Self-service endpoints require a `platform-customer`,
  `platform-driver`, or `platform-courier` realm token;
  the gateway's `X-User-Id` header is the `identity_id`.
  Service-to-service endpoints require
  `client_credentials` from `platform-services` with
  the `user-profile.read` / `user-profile.write`
  client role.
- **AuthZ**: resource-level check — a user can only
  read/write their own profile; cross-user reads require
  the `user-profile.read.any` admin scope.
- **Secrets**: DB credentials in Vault; rotated
  quarterly.
- **PII**: the `profiles` row contains `preferred_locale`
  (not PII), `notification_preferences` (not PII but
  privacy-sensitive), `devices` (push tokens are
  privacy-sensitive). Push tokens are column-level
  encrypted with a per-tenant DEK. No PII is logged
  in production.
- **GDPR**: an `identity.user.erased.v1` event causes
  the row to be anonymized and devices cleared.
- **mTLS**: in-cluster mTLS via sidecar.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=user-profile-service`, `version`, `env`,
  `region`, `correlation_id`, `request_id`,
  `trace_id`, `user_id` (`identity_id`), `action`,
  `result`, `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `user_profile_locale_distribution{locale}`
  - `user_profile_notification_optin_ratio{topic,channel}`
  - `user_profile_devices_per_user{histogram}`
  - `user_profile_cache_hit_ratio{claim}`
- **Traces**: OpenTelemetry. Sample 100% on errors,
  10% on success.
- **Health**: `/health` (process up), `/ready` (DB +
  Redis + Kafka reachable), `/started` (initial
  config loaded).

## 16. Scalability

- **Replicas**: default 4 per region; minimum 2.
- **HPA**: CPU 60% target; custom metric on
  `user_profile_lookups_per_second` (target 2k/replica).
- **Hot path**: profile read by `identity_id` (PK index
  hit) → return row. P99 ≤ 30 ms.

## 17. Local Development

- Run with `make up-user-profile` (the platform's
  docker-compose v2 starts Postgres, Redis, Kafka,
  and a Keycloak dev realm with a test user).
- A dev profile is pre-seeded with 3 test users in
  `dev/seed/profiles.json`.

## 18. Deployment

- **Image**: `registry.example.com/services/user-profile-service:{semver}`.
- **Replicas**: 4 (prod, per region), 2 (staging),
  1 (dev).
- **Resource limits**: 500m vCPU / 512 MiB RAM per
  pod.
- **Migrations**: Kubernetes Job before the
  deployment's pods start; same image with the
  `migrate` subcommand.
- **Pod disruption budget**: `minAvailable: 2` in
  production.
- **Network policy**: ingress from `api-gateway` and
  `admin-service`; egress to `identity-service`,
  `file-service`, the DB, Redis, Kafka, Vault.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`identity-service`](../identity-service/README.md), [`merchant-service`](../merchant-service/README.md), [`notification-service`](../notification-service/README.md)
- **Depended on by**: [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md)

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
