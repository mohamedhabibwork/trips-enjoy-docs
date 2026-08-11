# customer-service

## 1. Purpose

The `customer-service` is the platform's source of truth for
the **customer profile** — the data attached to a Keycloak
identity that has been onboarded as a customer. It owns the
customer's name, email, phone, KYC tier, lifetime value (LTV),
segment, default payment method reference, and the audit
trail of customer state transitions. It is the only writer of
the `customer` schema and the canonical source of
`customer_id` for the platform.

## 2. Bounded Context

**Customer profile.** In scope: customer profile (KYC, LTV,
segment, default payment method ref, default address ref),
customer state machine (active, suspended, disabled, erased),
segment changes, suspension / disable / erasure, GDPR
right-to-erasure. Out of scope: authentication (Keycloak via
`identity-service`), ride/order history (those are separate
read models), payment data (the payment provider owns PAN;
this service holds only the tokenized reference).

## 3. Responsibilities

- Create and maintain the `customer.customers` row for every
  platform customer.
- Maintain the KYC tier (`tier_0` to `tier_3`); drive KYC
  upgrades / downgrades.
- Track customer LTV (lifetime value, computed from
  completed trips and orders; updated via events).
- Compute and emit segment changes (`standard`,
  `frequent`, `vip`, `churned`).
- Store the default payment method reference (a UUID
  reference to `payment-service`).
- Store the default address reference (a UUID reference to
  ``customer-service` (addresses)`).
- Maintain the customer state machine: `active` /
  `suspended` / `disabled` / `erased`.
- React to `identity.*.v1` events: create on
  `identity.user.created.v1`, suspend on
  `identity.user.suspended.v1`, anonymize on
  `identity.user.erased.v1`.
- React to `payment.method.saved.v1` to update the default
  payment method.
- Emit `customer.created.v1`, `customer.updated.v1`,
  `customer.suspended.v1`, `customer.disabled.v1`,
  `customer.reinstated.v1`, `customer.erased.v1`,
  `customer.segment.changed.v1`.
- Provide the platform's customer-facing profile API.
- Provide admin APIs for `admin-service` (suspend,
  reinstate, KYC upgrade, segment change, GDPR erasure).

## 4. Explicitly NOT Owned

- **Credentials, sessions, MFA.** `identity-service` (via
  Keycloak).
- **Common user preferences** (language, notification,
  device). ``customer-service` (cross-persona profile)`.
- **Payment data.** `payment-service`; this service only
  holds a reference.
- **Saved addresses.** ``customer-service` (addresses)`; this service
  only holds a default-address reference.
- **Ride / order history.** ``trip-service` (history)`,
  `food-order-service`.
- **Reviews.** ``trip-service` / `food-order-service` / `search-service` (review projections)`.
- **Loyalty points.** ``pricing-service` (loyalty rules) / `customer-service` (account)`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer | human | read/write on their own profile |
| `identity-service` | service (producer) | emits `identity.*.v1` |
| `payment-service` | service (producer) | emits `payment.method.saved.v1`, `payment.method.removed.v1` |
| ``payment-service` (ride saga)` | service (producer) | emits `ride.payment.completed.v1` (for LTV) |
| ``payment-service` (food saga)` | service (producer) | emits `food.payment.completed.v1` (for LTV) |
| `fraud-risk-service` | service (consumer) | reads `customer.suspended.v1` |
| ``pricing-service` (promotion)` | service (consumer) | reads `customer.segment.changed.v1` |
| ``pricing-service` (loyalty rules) / `customer-service` (account)` | service (consumer) | reads `customer.segment.changed.v1` |
| `pricing-service` | service (consumer) | reads `customer.segment.changed.v1` |
| `notification-service` | service (consumer) | reads `customer.*.v1` |
| `admin-service` | service | admin actions |
| `audit-service` | consumer | reads `customer.*.v1` |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — read claims on creation — SLO 99.95%
  — circuit breaker: yes (cached).
- `payment-service` — read payment method metadata
  (`GET /v1/payment-methods/{id}`) on default-method
  reference resolution — SLO 99.95% — circuit breaker:
  yes.
- ``customer-service` (addresses)` — read address metadata
  (`GET /v1/addresses/{id}`) on default-address
  resolution — SLO 99.9% — circuit breaker: yes.
- `geolocation-service` — read city for the customer's
  primary city — SLO 99.95% — circuit breaker: yes.

### Asynchronous (events consumed)

- `identity.user.created.v1` from `identity-service` —
  back-channel: ensure a `customers` row exists. Duplicate
  handling: idempotent on `identity_id`.
- `identity.user.updated.v1` from `identity-service` —
  refresh cached claims.
- `identity.user.suspended.v1` — mark customer suspended.
- `identity.user.disabled.v1` — mark customer disabled.
- `identity.user.reinstated.v1` — clear suspension.
- `identity.user.erased.v1` — GDPR erasure.
- `payment.method.saved.v1` from `payment-service` —
  update default payment method if it matches the
  customer's most-recent. Duplicate handling:
  idempotent.
- `payment.method.removed.v1` from `payment-service` —
  clear default if it matches.
- `ride.payment.completed.v1` from
  ``payment-service` (ride saga)` — update LTV.
- `food.payment.completed.v1` from
  ``payment-service` (food saga)` — update LTV.
- `trip.reward.granted.v1` from `trip-service` — record the
  per-trip customer credit (when `trip.reward.user.kind =
  wallet_credit`) on the customer's history; the actual
  wallet credit is owned by ``payment-service` (wallet)`. This is purely
  historical / display-side; the customer profile shows the
  credit in the trip history. Idempotent on `event_id`.
- `configuration.updated.v1` from
  `configuration-service` — reload KYC tier rules,
  segment thresholds, retention policies. Duplicate
  handling: configuration version stamp.

## 7. Technology Assumptions

- Runtime: **Java 21** (Spring Boot) — fits the rest of
  the platform's financial-adjacent services.
- Database: PostgreSQL 19 (per-service schema
  `customer`).
- Cache: Redis (per-service logical DB).
- Event broker: Kafka.
- LTV: stored as a `BIGINT` minor-units value, recomputed
  incrementally on each completed payment.

## 8. Database Ownership

- Schema: `customer`.
- Migrations: `services/customer-service/migrations/`
  (versioned, forward-only, Flyway).
- Soft delete: yes (`customers` use `deleted_at`).
- Partitioning: no. The `customers` table is one row per
  customer; `customer_ltv_history` is
  range-partitioned by month (volume of completed
  payments is the driver).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/customers/{customer_id}` | bearer (self or service) | get a customer |
| POST | `/v1/customers` | bearer (service) | create a customer (idempotent on `identity_id`) |
| PATCH | `/v1/customers/{customer_id}` | bearer (self or admin) | update profile fields |
| GET | `/v1/customers/{customer_id}/kyc` | bearer (self or service) | get KYC tier |
| POST | `/v1/customers/{customer_id}/kyc/upgrade` | bearer (self or admin) | request KYC upgrade |
| POST | `/v1/customers/{customer_id}/suspend` | bearer (admin) | suspend |
| POST | `/v1/customers/{customer_id}/reinstate` | bearer (admin) | re-instate |
| POST | `/v1/customers/{customer_id}/disable` | bearer (admin) | disable |
| POST | `/v1/customers/{customer_id}/erase` | bearer (admin) | GDPR erasure |
| PUT | `/v1/customers/{customer_id}/default-payment-method/{payment_method_id}` | bearer (self) | set default payment method |
| PUT | `/v1/customers/{customer_id}/default-address/{address_id}` | bearer (self) | set default address |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `customer.created.v1` | A new customer row is created | `audit-service`, ``reporting-service` (data lake)`, `identity-service` (back-channel) |
| `customer.updated.v1` | Any change to the customer profile (name, KYC, default method) | `notification-service`, `admin-service`, ``reporting-service` (data lake)` |
| `customer.suspended.v1` | A customer is suspended | ``trip-service` (ride-request)`, `food-order-service`, ``food-order-service` (cart)`, `payment-service`, `notification-service`, `fraud-risk-service`, `audit-service` |
| `customer.disabled.v1` | A customer is disabled (permanent) | same as suspended, plus ``admin-service` (support module)` |
| `customer.reinstated.v1` | A suspended customer is re-instated | ``trip-service` (ride-request)`, `food-order-service`, ``food-order-service` (cart)`, `payment-service`, `notification-service` |
| `customer.erased.v1` | GDPR erasure | `audit-service`, ``reporting-service` (data lake)`, every service that owns a profile |
| `customer.segment.changed.v1` | The segment changes | ``pricing-service` (promotion)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`, `pricing-service`, `notification-service` |
| `customer.kyc.tier_changed.v1` | The KYC tier changes | `payment-service`, ``trip-service` (ride-request)`, `food-order-service`, `notification-service` |

## 11. Events Consumed

Listed in 6 (asynchronous).

## 12. External Integrations

- **KYC provider** (e.g. Onfido, Jumio) — KYC document
  verification and tier upgrade. The service sends the
  customer's documents to the provider and stores the
  provider's `verification_id` and the resulting tier.
  Credentials in Vault at
  `vault://platform/customer/kyc-provider`.
- **Vault** — KYC provider credentials, DB credentials.
- **Redis** — claim hot-cache, default-method
  projection.
- **Kafka** — event bus.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `customer.kyc.tier_0_limit_minor` | int | configuration-service | default 0 (no payments) |
| `customer.kyc.tier_1_limit_minor` | int | configuration-service | default 50000 (low-value rides / orders) |
| `customer.kyc.tier_2_limit_minor` | int | configuration-service | default 500000 (mid-value) |
| `customer.kyc.tier_3_limit_minor` | int | configuration-service | unlimited (full KYC) |
| `customer.segment.frequent_rides` | int | configuration-service | rides per month to qualify as `frequent` (default 20) |
| `customer.segment.vip_ltv_minor` | int | configuration-service | LTV threshold for `vip` (default 1000000) |
| `customer.segment.churned_idle_days` | int | configuration-service | days of inactivity for `churned` (default 90) |
| `customer.ltv.refresh_window_days` | int | configuration-service | rolling window for LTV (default 365) |
| `customer.erasure.keep_financial_years` | int | configuration-service | default 7 |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer token.
  Self-service endpoints accept the gateway-injected
  `X-User-Id`; service endpoints require
  `client_credentials` from `platform-services` with
  `customer.read` / `customer.write` client roles.
- **AuthZ**: resource-level check — a user can only
  read/write their own customer profile. Cross-customer
  reads require `customer.read.any` admin scope.
- **Secrets**: Vault; rotated quarterly.
- **PII**: the `customers` row contains
  `name`, `email`, `phone` (PII), KYC document
  references (sensitive), default method / address
  references. PII columns are column-level encrypted.
  KYC documents are stored in `file-service`; this
  service holds only the `file_id`.
- **PCI**: no PAN stored; only the tokenized
  `payment_method_id` reference.
- **GDPR**: `POST /v1/customers/{id}/erase` anonymizes
  PII; the `customer_id` is preserved for referential
  integrity; financial records in
  `ledger-service` and `payment-service` retain the
  `customer_id` reference but their PII fields are
  redacted.
- **mTLS**: in-cluster mTLS via sidecar.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=customer-service`, `version`, `env`,
  `region`, `correlation_id`, `request_id`,
  `trace_id`, `user_id` (`customer_id`), `action`,
  `result`, `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `customer_kyc_tier_distribution{tier}`
  - `customer_segment_distribution{segment}`
  - `customer_ltv_histogram{percentile}`
  - `customer_ltv_updates_total{source}`
  - `customer_cache_hit_ratio{claim}`
  - `customer_suspension_reasons_total{reason}`
- **Traces**: OpenTelemetry. Sample 100% on errors,
  10% on success.
- **Health**: `/health` (process up), `/ready` (DB +
  Redis + Kafka reachable), `/started` (initial
  config loaded).

## 16. Scalability

- **Replicas**: default 6 per region; minimum 3.
- **HPA**: CPU 60% target; custom metric
  `customer_lookups_per_second` (target 5k/replica).
- **Hot path**: customer read by `customer_id` (PK
  index hit) → return row. P99 ≤ 30 ms.

## 17. Local Development

- Run with `make up-customer` (the platform's
  docker-compose v2 starts Postgres, Redis, Kafka,
  Keycloak dev realm, and a stub KYC provider).
- A dev KYC provider returns a fixed tier for
  testing.

## 18. Deployment

- **Image**: `registry.example.com/services/customer-service:{semver}`.
- **Replicas**: 6 (prod, per region), 3 (staging),
  1 (dev).
- **Resource limits**: 1 vCPU / 1 GiB RAM per pod.
- **Migrations**: Kubernetes Job before the
  deployment's pods start; same image with the
  `migrate` subcommand (Flyway).
- **Pod disruption budget**: `minAvailable: 3` in
  production.
- **Network policy**: ingress from `api-gateway`,
  `admin-service`; egress to `identity-service`,
  `payment-service`,
  `geolocation-service`, the KYC provider, the DB,
  Redis, Kafka, Vault.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``customer-service` (cross-persona profile)`
(cross-persona user data — display name, avatar, locale,
notification preferences, device list), ``customer-service` (addresses)` (saved
addresses), and the **loyalty account** slice that used to be
exposed by ``pricing-service` (loyalty rules) / `customer-service` (account)` is now absorbed into this service.
The canonical source is [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md)
3.1 (user-profile), 3.2 (address), 3.15 (loyalty-rules). The
**loyalty pricing rules** are owned by `pricing-service`.

### A.1 Bounded context (post-merger)

Customer profile + KYC + cross-persona user profile + saved
addresses + loyalty account exposure. The service is the **only**
writer of the `customer` schema. Out of scope: loyalty pricing
rules (owned by `pricing-service`), authentication (owned by
`identity-service`).

### A.2 Absorbed responsibilities (from `customer-service` (cross-persona profile))

- Maintain cross-persona user profile: display name, avatar ref,
  locale, notification preferences, device list.
- Per-device login allow-list.
- Emit `user.profile.updated.v1`, `user.device.registered.v1`,
  `user.device.removed.v1`,
  `user.notification_preferences.updated.v1`.
- Consume `identity.user.created.v1` to seed profile.

### A.3 Absorbed responsibilities (from `customer-service` (addresses))

- Saved addresses per user (geocoded, normalised, tagged with
  `home`, `work`, `favorite`, etc.).
- CRUD over addresses.
- Emit `address.created.v1`, `address.updated.v1`,
  `address.deleted.v1`.

### A.4 Loyalty account exposure

- Per-user **loyalty account** (balance, tier, earn / burn
  history). Owned here; the **loyalty rules** (earn / burn math,
  tier thresholds, eligibility, promo-binding) live in
  `pricing-service`.
- The canonical resource path is `/v1/customers/{id}/loyalty-account`.
- Emit `customer.loyalty_account.changed.v1` for downstream
  consumers (`pricing-service`, `reporting-service`).
- Consume `loyalty.tier.changed.v1` from `pricing-service`.

### A.5 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/users/{id}/profile` | bearer (self) | read cross-persona profile |
| PATCH | `/v1/users/{id}/profile` | bearer (self) | update cross-persona profile |
| GET | `/v1/users/{id}/devices` | bearer (self) | list devices |
| DELETE | `/v1/users/{id}/devices/{device_id}` | bearer (self) | remove device |
| GET | `/v1/users/{id}/notification-preferences` | bearer (self) | read prefs |
| PATCH | `/v1/users/{id}/notification-preferences` | bearer (self) | update prefs |
| POST | `/v1/users/{id}/addresses` | bearer (self) | create address |
| GET  | `/v1/users/{id}/addresses` | bearer (self) | list addresses |
| PATCH | `/v1/addresses/{address_id}` | bearer (self) | update address |
| DELETE | `/v1/addresses/{address_id}` | bearer (self) | delete address |
| GET  | `/v1/customers/{id}/loyalty-account` | bearer (customer) | read loyalty account |
| GET  | `/v1/customers/{id}/loyalty-account/history` | bearer (customer) | earn / burn history |

### A.6 Compatibility window

For at least six calendar months from 2026-08-05:

- `user.profile.*.v1`, `address.*.v1`,
  `user.device.*.v1`, `user.notification_preferences.*.v1` are
  published under the same topic names and schema versions.
- `/v1/users/{id}/profile`, `/v1/users/{id}/devices*`,
  `/v1/users/{id}/notification-preferences*`,
  `/v1/users/{id}/addresses*` continue to be served from this
  service.
- Old schema names `user_profile.*`, `address.*` remain readable
  as views in the `customer` schema.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
- [`../../shared/TYPE_CATALOG.md`](../../shared/TYPE_CATALOG.md) — **platform-wide type vocabulary** — customer segments (standard / frequent / vip / churned) catalogued in [6](../../shared/TYPE_CATALOG.md#6-customer-segments); CHECK at `customer.customers.segment` plus the `customer.segment.*` configuration thresholds; transitions emit `customer.segment.changed.v1` for the `pricing-service` loyalty pipeline.

### Workflows this service participates in

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
