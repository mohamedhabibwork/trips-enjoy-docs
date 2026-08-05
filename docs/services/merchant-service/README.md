# merchant-service

## 1. Purpose

`merchant-service` is the canonical owner of the **merchant aggregate** — the
legal entity that contracts with the platform to operate one or more
restaurants. The service captures the merchant's legal identity, tax
information, banking and payout details, primary contacts, KYC documents,
and lifecycle state (pending review, approved, suspended, closed). It
issues no order, no menu, and no branch — those are owned by sibling
bounded contexts — but every downstream service that touches a merchant
(e.g. `restaurant-service`, `restaurant-settlement-service`,
`restaurant-staff-service`) reads from this source of truth.

## 2. Bounded Context

- **In scope**: merchant legal entity, KYC documents, tax info, bank
  account, payout schedule, primary and secondary contacts, lifecycle
  state, audit trail, suspension and re-instatement, payout holds.
- **Out of scope**: restaurants (operational brand under a merchant),
  branches, menus, staff, orders, settlements/payouts as a financial
  process (settlement is owned by `restaurant-settlement-service`;
  payouts as bank transfers are owned by `payment-service`). The
  merchant's *view* of its own settlements is composed in
  `restaurant-settlement-service` and surfaced back here for the
  operator console.

## 3. Responsibilities

- Onboard new merchants via a multi-step KYC intake (legal info,
  documents, bank account).
- Persist and serve the legal entity, tax, and banking record.
- Maintain merchant lifecycle state (pending_review, approved,
  rejected, suspended, closed).
- Manage contacts (legal signatory, ops lead, finance) and their
  notification preferences.
- Hold and freeze/unfreeze payouts at the merchant level.
- Emit `merchant.*.v1` events for every state transition.
- Expose admin endpoints for review, approval, suspension, and re-
  instatement with full audit and reason codes.
- Maintain idempotent onboarding flows (resubmit after rejection).

## 4. Explicitly NOT Owned

- **Restaurants** (the operational brand) — owned by `restaurant-service`.
- **Branches** (physical locations) — owned by `branch-service`.
- **Menus** — owned by `menu-service`.
- **Settlement amounts, payout runs** — owned by
  `restaurant-settlement-service`; this service only stores the
  merchant-level payout preferences (bank account, hold flag, schedule).
- **Tax calculation** — owned by `tax-service`; this service stores the
  merchant's tax registration and tax certificates for KYC.
- **Identity and authentication** — owned by `identity-service` and
  Keycloak. A `keycloak_user_id` is referenced, never duplicated.
- **KYC document bytes** — stored in object storage, metadata only here.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant Owner | human | read/write own merchant; submit KYC |
| Merchant Ops Lead | human | read/write own merchant (contact updates) |
| Merchant Finance | human | read own payouts (read-only here) |
| Platform Admin | human | read/write any merchant; approve, suspend, close |
| Platform Compliance | human | read; mark for review |
| `identity-service` | system | read (token validation) |
| `restaurant-service` | system | read (merchant exists check) |
| `restaurant-staff-service` | system | read (merchant context) |
| `restaurant-settlement-service` | system | read (payout config) |
| `admin-service` | system | read/write (admin actions) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — verify Keycloak subject, retrieve user type —
  SLO 99.95%, circuit breaker: **yes**.
- `file-service` — request signed URL for KYC document upload, fetch
  scan status — SLO 99.9%, circuit breaker: **yes**.
- `restaurant-service` — validate merchant exists before downstream
  operations (read-only, optional cache) — SLO 99.95%, circuit breaker:
  **yes**.
- `configuration-service` — read onboarding configuration (required
  document types, supported countries) — SLO 99.95%, circuit breaker:
  **yes**.
- `notification-service` — trigger welcome / suspension / re-instate
  messages — SLO 99.9%, circuit breaker: **yes**.

### Asynchronous (events consumed)

- `identity.user.created.v1` from `identity-service` — provision a
  merchant profile linked to the new user — duplicate handling:
  **inbox dedup** on `event_id`.
- `identity.user.suspended.v1` from `identity-service` — cascade to all
  merchants owned by the user; auto-suspend if `payout_hold` policy
  requires — duplicate handling: **inbox dedup**.
- `customer.suspended.v1` from `customer-service` — informational only;
  no action — duplicate handling: **inbox dedup**.
- `configuration.updated.v1` from `configuration-service` — invalidate
  cache for `merchant.onboarding.*` keys — duplicate handling: **inbox
  dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript) on a NestJS/Fastify base.
- Database: **PostgreSQL 18** (per-service schema `merchant`).
- Cache: **Redis** (per-service, used for read-through of approved
  merchant lookups by other services).
- Event broker: **Kafka**.
- File storage: **S3-compatible** via `file-service`; this service
  never holds document bytes.
- ORM: **Prisma** (TypeScript, transactional outbox supported).
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `merchant` (owned exclusively by this service).
- Tables: `merchants`, `merchant_contacts`, `merchant_documents`,
  `merchant_bank_accounts`, `merchant_audit_log`, `outbox`, `inbox`.
- Migrations: `services/merchant-service/prisma/migrations/`.
- Soft delete: **yes** (`deleted_at` on `merchants`,
  `merchant_contacts`, `merchant_bank_accounts`).
- Partitioning: **no** (low write volume; merchants are created in the
  thousands, not millions).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/merchants | bearer (merchant_owner) | submit KYC (Idempotency-Key required) |
| GET | /v1/merchants/{id} | bearer (owner / admin) | read merchant |
| PATCH | /v1/merchants/{id} | bearer (owner / admin) | update legal/tax fields |
| POST | /v1/merchants/{id}/submit | bearer (owner) | submit for review |
| POST | /v1/merchants/{id}/approve | bearer (admin) | approve merchant |
| POST | /v1/merchants/{id}/reject | bearer (admin) | reject (reason required) |
| POST | /v1/merchants/{id}/suspend | bearer (admin) | suspend (reason required) |
| POST | /v1/merchants/{id}/reinstate | bearer (admin) | clear suspension |
| POST | /v1/merchants/{id}/close | bearer (admin) | permanent close |
| GET | /v1/merchants/{id}/documents | bearer (owner / admin) | list documents |
| POST | /v1/merchants/{id}/contacts | bearer (owner / admin) | add contact |
| PATCH | /v1/merchants/{id}/contacts/{cid} | bearer (owner / admin) | update contact |
| DELETE | /v1/merchants/{id}/contacts/{cid} | bearer (owner / admin) | remove contact |
| PUT | /v1/merchants/{id}/bank-account | bearer (owner / admin) | set primary bank account |
| GET | /v1/merchants | bearer (admin) | list with filters (cursor pagination) |
| GET | /v1/merchants/by-user/{kc_sub} | bearer (system) | lookup by Keycloak subject |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `merchant.created.v1` | KYC submitted | `restaurant-service`, `restaurant-staff-service`, `restaurant-settlement-service`, `audit-service`, `analytics-service` |
| `merchant.updated.v1` | legal/tax/contact fields changed | `restaurant-settlement-service`, `audit-service`, `analytics-service` |
| `merchant.approved.v1` | admin approval | `restaurant-service`, `restaurant-settlement-service`, `notification-service`, `audit-service` |
| `merchant.rejected.v1` | admin rejection | `notification-service`, `audit-service` |
| `merchant.suspended.v1` | admin or cascade suspension | `restaurant-service`, `restaurant-settlement-service`, `payment-service`, `notification-service`, `audit-service` |
| `merchant.reinstated.v1` | suspension cleared | `restaurant-service`, `restaurant-settlement-service`, `notification-service`, `audit-service` |
| `merchant.closed.v1` | permanent closure | `restaurant-service`, `restaurant-settlement-service`, `payment-service`, `notification-service`, `audit-service` |
| `merchant.payout.hold.v1` | payout frozen | `restaurant-settlement-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `identity.user.created.v1` | `identity-service` | owner identity is established | look up merchant by `kc_sub`; create draft merchant if owner role |
| `identity.user.suspended.v1` | `identity-service` | user is suspended | cascade: auto-suspend merchants owned by the user if policy `cascade_on_owner_suspend` is true |
| `customer.suspended.v1` | `customer-service` | customer-only signal | informational log; no action |
| `configuration.updated.v1` | `configuration-service` | config changed | invalidate Redis cache for `merchant.onboarding.*` |

(Full contracts in `INTEGRATION.md`.)

## 12. External Integrations

- **Business KYC provider** (e.g. Onfido, Trulioo) — sanctions/AML
  screening during onboarding — credentials in Vault at
  `secret/merchant-service/kyc/{env}`.
- **Bank account validation** (e.g. Stripe Issuing / Plaid) — IBAN
  ownership and validity check on `PUT /bank-account` — credentials in
  Vault at `secret/merchant-service/bank-validator/{env}`.
- **Tax authority lookup** (where applicable) — VAT/GSTIN
  verification — credentials in Vault at
  `secret/merchant-service/tax-lookup/{env}`.
- **S3-compatible object store** (via `file-service`) — never direct.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `merchant.onboarding.required_documents` | array<string> | configuration-service | KYC document checklist per country |
| `merchant.onboarding.sla_hours` | int | configuration-service | target approval SLA |
| `merchant.payout.hold_on_owner_suspend` | bool | configuration-service | cascade policy |
| `merchant.suspension.grace_period_hours` | int | configuration-service | warning window |
| `merchant.bank.min_supported_currencies` | array<string> | configuration-service | ISO-4217 list |
| `merchant.rate_limit.submit_per_hour` | int | configuration-service | throttle resubmissions |
| `merchant.review.required_kyc_score` | int | configuration-service | auto-approval threshold |
| `feature_flag.merchant.auto_approve_enabled` | bool | feature-flag-service | rollout of auto-approval |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway (RS256, Keycloak JWKS).
  Service-to-service via `client_credentials`.
- AuthZ: **RBAC** at gateway (`merchant_owner`, `merchant_finance`,
  `merchant_ops`, `platform_admin`, `platform_compliance`); fine-grained
  resource ownership at the service (`merchant.owner_kc_sub == sub`).
- Secrets: Vault paths `secret/merchant-service/{kyc,tax-lookup,
  bank-validator}/{env}`.
- PII: legal name, tax ID, bank account, contact phone/email — marked
  `confidential`; column-level encryption (envelope, per-tenant DEK);
  access logged at service level.
- PCI scope: none. Bank account numbers handled by external validator
  via tokenized reference; raw IBAN encrypted at rest.
- Audit: every admin action (`approve`, `reject`, `suspend`,
  `reinstate`, `close`) emits an `admin.audit.merchant.*` event
  consumed by `audit-service`.

## 15. Observability

- Logs: JSON to stdout, fields: `service=merchant-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`, `latency_ms`,
  `status`, `merchant_id`, `merchant_state`.
- Metrics:
  - RED: `http_requests_total`, `http_request_duration_seconds`,
    `http_requests_in_flight`.
  - Business: `merchants_created_total{country}`,
    `merchants_approved_total{country}`,
    `merchants_suspended_total{reason}`,
    `merchant_approval_seconds` (histogram),
    `kyc_provider_call_seconds`,
    `kyc_provider_failure_total{reason}`.
  - USE: `db_connections_in_use`, `kafka_consumer_lag`,
    `outbox_pending_total`.
- Traces: OpenTelemetry auto-instrumented; one root span per request;
  propagated to `identity-service`, `file-service`, downstream
  consumers; sample 100% on errors, 10% on success in production.
- Health: `/health` (liveness, 200), `/ready` (DB + Kafka + Vault
  reachable + config loaded), `/started` (migrations + warmup done).

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`.
- Hot path: `GET /v1/merchants/by-user/{kc_sub}` is the most frequent
  read (called by `restaurant-service`, `restaurant-staff-service` on
  every order placement) — cached in Redis with 60s TTL; key
  `merchant:by_user:{kc_sub}`.
- Vertical ceiling: 4 CPU / 8 GiB per pod.
- DB read replicas: 1 read replica in production (per Tier-1 SLO
  reserve).
- Cache: Redis cluster, per-service.

## 17. Local Development

- `docker compose up` boots PostgreSQL 18, Kafka, Redis, Vault, and the
  service in dev mode with auto-migrations and seed data.
- Seed: one approved merchant, one pending merchant, one suspended
  merchant, with one bank account and one contact each.
- `bun run test` runs unit + integration tests against the dev DB.
- `bun run e2e` runs Playwright tests against the operator console +
  admin console (where merchant endpoints are surfaced).
- API explorer at `http://localhost:8080/docs` (Swagger UI from
  OpenAPI spec).

## 18. Deployment

- Image: `registry.platform.io/merchant-service:{git-sha}`.
- Replicas: 3 baseline, HPA up to 12.
- Resource limits: 500m–2000m CPU, 512Mi–2Gi memory.
- Migrations: run as init container in the pod, with a pre-stop hook
  to drain the in-flight requests before the pod terminates.
- Rollout strategy: rolling update with `maxUnavailable: 0`,
  `maxSurge: 1`. A failed readiness probe blocks the rollout.
- Region: deployed in `eu-west` and `ap-southeast` initially; cross-
  region read replicas where required by data residency.
- Network policies: egress restricted to PostgreSQL, Kafka, Redis,
  Vault, and known provider endpoints.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`file-service`](../file-service/README.md), [`identity-service`](../identity-service/README.md), [`menu-service`](../menu-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`restaurant-settlement-service`](../restaurant-settlement-service/README.md), [`restaurant-staff-service`](../restaurant-staff-service/README.md), [`tax-service`](../tax-service/README.md)
- **Depended on by**: [`branch-service`](../branch-service/README.md), [`file-service`](../file-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`restaurant-settlement-service`](../restaurant-settlement-service/README.md), [`search-service`](../search-service/README.md), [`support-service`](../support-service/README.md), [`user-profile-service`](../user-profile-service/README.md), [`zone-service`](../zone-service/README.md)

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

- [`../../workflows/MERCHANT_WORKFLOWS.md`](../../workflows/MERCHANT_WORKFLOWS.md) — merchant onboarding, menu ops
