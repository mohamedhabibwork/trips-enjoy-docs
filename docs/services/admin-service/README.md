# Admin Service

## 1. Purpose

`admin-service` is the platform's **operations console**. It hosts
the web UI for operators (support, finance, ops, security) to
inspect data, take manual actions, and audit changes. It is also the
**gate** for every high-value mutation: payouts, refunds,
configuration changes, feature-flag toggles, account suspensions. The
service is the single entry point that enforces the platform's
"admin security" controls: RBAC, request signing, audit reason,
break-glass.

## 2. Bounded Context

**Bounded context**: Admin operations. In scope:

- The admin console web UI.
- A unified action API for high-value mutations across all services
  (e.g. "issue a refund", "suspend a customer", "roll back a
  config").
- An immutable log of every admin action.
- RBAC scopes (delegated to Keycloak; the service maps scopes to
  actions).
- Request signing enforcement.
- Break-glass (co-signature for super-admin actions).
- **Pricing geo-config CRUD** — per-location and city-to-city
  (OD-pair) pricing rules operated on behalf of `pricing-service`.
  This service owns the records, the audit, and emits
  `pricing.geo_config.updated.v1`; `pricing-service` only consumes
  the event. Per-city rule overrides can be set by a city admin;
  OD-pair corridors require `pricing.admin` + break-glass.

Out of scope:

- Direct database access (production has no DB console).
- The actual data mutation in target services (this service calls
  the target service's API).
- Audit log persistence (owned by `audit-service`; this service
  emits the events and a local cache).
- The pricing algorithm itself — `pricing-service` (this service only
  manages the **rules** that `pricing-service` consumes).
- Quote computation / customer-facing pricing UX — `pricing-service`.

## 3. Responsibilities

- Host the admin console web UI.
- Authenticate admin users (delegated to Keycloak).
- Authorize actions (RBAC + per-action scopes).
- Enforce request signing for high-value actions.
- Enforce `X-Audit-Reason` on every mutation.
- Call the target service's API to perform the action.
- Record every action in `admin.action_log` (local cache).
- Emit `admin.action.performed.v1` for `audit-service`.
- Provide a search / read view over the action log (admin-only).
- Support break-glass with a second admin's co-signature.

## 4. Explicitly NOT Owned

- **Target service data** — every other service.
- **Identity / auth** — Keycloak; `identity-service` proxies.
- **Immutable audit log** — `audit-service`.
- **Customer support workflow** — ``admin-service` (support module)` (the two share
  patterns but the support service is customer-facing).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Operator (admin) | human | full (per role) |
| Super admin | human | break-glass |
| Security on-call | human | read-only, alert escalation |
| Compliance auditor | human | read-only |
| Target service | system | recipient of admin actions |

## 6. Dependencies

### Synchronous (REST)

- **Every service** — for the actual mutation.
- `identity-service` — admin token validation.
- ``geolocation-service` (zones)` — zone id validation when creating an OD-pair
  geo-config record (the operator picks the origin and destination
  zones; this service asks ``geolocation-service` (zones) POST /v1/zones/exists` to
  confirm each id before persisting).
- `pricing-service` — none directly (the live read path is via the
  async event); optional `GET /v1/admin/pricing/geo-config/{id}` for
  admin debug fetch.

### Asynchronous (events produced only)

- `admin.action.performed.v1` — every action.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript) for the API; React 19 for the web
  UI bundle.
- Database: PostgreSQL 18 (per-service schema `admin`).
- Cache: Redis cluster.
- Event broker: Kafka.
- CDN: the web UI is served from a CDN with the API behind the
  gateway.

## 8. Database Ownership

- Schema: `admin`.
- Migrations: `services/admin-service/migrations/`.
- Soft delete: no (`action_log` is append-only).
- Partitioning: `admin.action_log` partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/admin/{service}/{action}` | bearer (admin) | dispatch an action |
| GET | `/v1/admin/actions` | bearer (admin) | search action log |
| GET | `/v1/admin/actions/{id}` | bearer (admin) | read action detail |
| POST | `/v1/admin/actions/{id}/break-glass` | bearer (super_admin) | co-sign |
| GET | `/v1/admin/permissions` | bearer | list current user's scopes |
| POST | `/v1/admin/pricing/geo-config` | bearer (`pricing.admin`) | create a per-location or OD-pair geo-config override |
| GET | `/v1/admin/pricing/geo-config/{id}` | bearer (`pricing.admin`) | read a geo-config record by id |
| PATCH | `/v1/admin/pricing/geo-config/{id}` | bearer (`pricing.admin`) | update a geo-config record (creates a new version) |
| POST | `/v1/admin/pricing/geo-config/{id}/disable` | bearer (`pricing.admin`) | soft-disable a record (retained for audit; effective_to = now()) |
| POST | `/v1/admin/pricing/geo-config/{id}/rollback` | bearer (`pricing.admin`) + break-glass | roll back to a prior version (creates a new history row + new head) |
| GET | `/v1/admin/pricing/geo-config?kind=LOCATION_OVERRIDE\|OD_CORRIDOR&status=ACTIVE\|RETIRED` | bearer (`pricing.admin`) | list / filter |
| GET | `/v1/admin/services` | bearer (`platform.admin`) | service catalog (20 services × admin scopes × `SUPER_ADMIN` preset membership) |
| GET | `/v1/admin/presets` | bearer (`platform.admin`) | list permission presets (currently `SUPER_ADMIN` = `platform.super_admin` + 58 `<service>.admin` scopes) |
| GET | `/v1/admin/identity/permissions/{user_id}` | bearer (`platform.admin`) | read a user's current roles + computed preset membership |
| POST | `/v1/admin/identity/grant-super-admin` | bearer (`platform.super_admin`) + break-glass + signature + MFA + super-admin IP allowlist | grant the `SUPER_ADMIN` preset (1 × `platform.super_admin` + 58 × `<service>.admin`); pages security |
| DELETE | `/v1/admin/identity/revoke-super-admin` | bearer (`platform.super_admin`) + break-glass + signature + MFA + super-admin IP allowlist | revoke the `SUPER_ADMIN` preset; pages security |

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `admin.action.performed.v1` | every action | `audit-service` |
| `pricing.geo_config.updated.v1` | every CRUD on `/v1/admin/pricing/geo-config[...]` (create, update via PATCH, disable, rollback) | `pricing-service` (consumes to refresh in-memory hash), ``reporting-service` (data lake)`, `audit-service` |
| `admin.super_admin.granted.v1` | every `SUPER_ADMIN` preset grant | `audit-service`, `notification-service` (pages security on-call), ``reporting-service` (data lake)` |
| `admin.super_admin.revoked.v1` | every `SUPER_ADMIN` preset revoke | `audit-service`, `notification-service` (pages security on-call), ``reporting-service` (data lake)` |

## 11. Events Consumed

This service is primarily a producer; it does not consume domain
events except:

- `identity.session.revoked.v1` — invalidate the operator's
  in-memory permission cache.

## 12. External Integrations

- **HashiCorp Vault** — DB credentials, signing keys.
- **Keycloak** — admin realm (`platform-internal`).

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | string | env | |
| `ADMIN_REALM` | string | env | `platform-internal` |
| `BREAK_GLASS_REQUIRED` | bool | env | false (default) |

Runtime configuration keys read from `configuration-service`:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `admin.super_admin_allowlist.ips` | string[] (CIDR) | configuration-service | consumed by `api-gateway` and `identity-service` on every `platform.super_admin` grant attempt |
| `admin.break_glass.cosigner_pool` | string[] (UUIDs) | configuration-service | quarterly-rotated eligible co-signers |
| `admin.audit.retention_days` | int | configuration-service | local `audit_log` mirror retention |
| `admin.action.permissions_cache_ttl_seconds` | int | configuration-service | default 30 |
| `admin.support.categories` | string[] | configuration-service | mirrors `lookup_types` `support.category` |
| `admin.support.priorities` | string[] | configuration-service | mirrors `lookup_types` `support.priority` |
| `admin.action.force_state.confirmation_min_age_seconds` | int | configuration-service | minimum target age before `force-state` is allowed (default 300) |

> **Canonical key index.** See
> [`../configuration-service/INTEGRATION.md` 10.1](../configuration-service/INTEGRATION.md#101-admin-service)
> for the full `admin.*` key family.

## 14. Security

- AuthN: JWT bearer; Keycloak realm `platform-internal`; MFA
  mandatory.
- AuthZ: per-action RBAC + per-action scope.
- Secrets: Vault.
- PII: every action carries a `target_user_id` (UUID); no contact
  info.
- Request signing: HMAC-SHA256 for high-value actions.
- Step-up MFA: required for super-admin / off-hours.
- IP allowlist: required for super-admin.

## 15. Observability

- Logs: JSON to stdout; standard fields + `actor_id`, `action`,
  `target_service`, `target_resource_id`, `result`.
- Metrics: RED per route + `admin_actions_total{service, action,
  result}`.
- Traces: OpenTelemetry; one root span per action.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 4; HPA on CPU.
- Hot path: the action dispatch (a single round-trip to the target
  service).

## 17. Local Development

```bash
docker compose -f deploy/compose/admin-service.yml up -d db
make -C services/admin-service migrate-up
pnpm --filter @platform/admin-service dev
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/admin-service:<sha>`.
- Replicas: 4 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.
- The action log is the source of truth for "what was changed";
  recovery is from the event stream + the immutable `audit-service`
  store.

## 20. Accounting impact

`admin-service` is the **human-operator entry point** for every
accounting action that requires manual intervention.

- **Manual journal entries:** operators with the `ledger.admin` role
  can post to `POST /v1/journal-entries` on `ledger-service`. The
  payload MUST carry an `audit_note` of ≥ 10 characters; every
  manual entry is reversible only by another manual entry (no
  UPDATE / DELETE on financial ledgers).
- **Force-capture / force-refund / force-payout:** admin actions
  against `payment-service`, ``payment-service` (wallet)`,
  ``payment-service` (driver earnings)`, ``payment-service` (courier earnings)`, and
  ``payment-service` (merchant settlement)` route through the same RBAC and
  audit emission as their operational paths.
- **Tax remittance workflow:** operators review the per-jurisdiction
  `tax_provision_report` from `reporting-service`, prepare the
  filing, and post the remittance journal entry (debit
  `tax_payable` ↔ credit `tax_remitted`).
- **CIT / regulatory fee provisioning:** operators review and
  approve the `tax_provision_report`, then post the CIT provision
  journal entry (debit `tax_provision_expense` ↔ credit
  `tax_provision_payable`).
- **Audit emission:** every admin action emits
  `admin.action.performed.v1` (auto-emitted via
  `platform-spring-boot-starter`); the immutable record lives in
  `audit-service`.
- **Pricing geo-config CRUD** (this service's role in the platform's
  pricing accounting story): operators with the `pricing.admin`
  role manage per-location and OD-pair pricing overrides via
  `POST /v1/admin/pricing/geo-config[...]`. The records are not
  financial postings themselves — they change the rules
  (`pricing-service` consumes them and emits pricing quotes).
  Reconciliation is via `reporting-service` (see
  [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
  "Rating-Density Surge Surcharge + Loyalty Discount" and
  "Workflow: Cross-Border Trip Pricing"). Every CRUD action emits
  `pricing.geo_config.updated.v1`; rollbacks require break-glass and
  are recorded as a new history row in
  `pricing.rule_bindings_history` (not as an UPDATE).
- **Reconciliation:** none directly; admin-triggered journal
  entries surface to `reporting-service` reconciliation jobs.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.

## 21. On-Call Runbook

### 21.1 Action Dispatch Failing

1. Check the target service's `/ready`; if it's down, the action
   is logged as `failed` and the operator can retry.
2. Check the network policy; a recent change may have blocked
   the egress to the target service.
3. Check the signature; a recent key rotation may have invalidated
   the cached signature.

### 21.2 Break-Glass Pending

1. The on-call is paged; review the action in the admin console.
2. Verify the requester's identity and the reason; if legitimate,
   approve; if not, deny.
3. The co-sign is itself audited; the audit log records the
   decision.

### 21.3 Action Log Replay

1. A support engineer needs to reconstruct what happened during
   an incident.
2. Use `GET /v1/admin/actions?actor_id=...&from=...&to=...` to
   search the action log.
3. The action log is append-only; no UPDATE / DELETE is allowed.

### 21.4 Off-Hours Action Without Co-Sign

1. The service returns 403 `BREAK_GLASS_REQUIRED`.
2. The operator must request a co-sign from the on-call; the
   on-call reviews and approves / denies.
3. A denied break-glass is itself audited.

### 21.5 Super Admin Grant Pending / Partial Fan-Out

1. Every `POST /v1/admin/identity/grant-super-admin` call pages
   security on-call via `notification-service` (per `SEC--013`).
2. The on-call opens the `admin.super_admin_grant` row (use
   `GET /v1/admin/identity/permissions/{user_id}` to confirm the
   current state).
3. **Pending** (no `super_admin_grant` row appears within 5s):
   the request was rejected at the gate; check the response code
   (`FORBIDDEN` / `CO_SIGNER_REQUIRED` / `IP_NOT_ALLOWED` /
   `MFA_REQUIRED` / `SIGNATURE_INVALID` / `OFF_HOURS_RESTRICTED` /
   `TENANT_MISMATCH`) and re-submit with the fix.
4. **Partial fan-out** (`super_admin_grant.roles_failed > 0`):
   the service has already started a compensating revoke
   (`compensation_id` is set on the failed row; the compensating
   revoke row carries the same `source_request_id`). Wait for the
   compensating revoke to land, then re-submit the original grant
   with a fresh `Idempotency-Key`.
5. **Compensating revoke also failed**: the role state in
   Keycloak is partially out of sync with the operator UI. Use
   `identity-service GET /admin/v1/identities/{user_id}/roles` to
   inspect the actual role list; reconcile manually by calling
   `POST/DELETE /admin/v1/identities/{user_id}/roles/{role}` for
   each missing or extra role. Every manual reconciliation call
   appears in the audit log.


---

## Appendix A — Removed predecessor capability (support)

The capability that used to live in ``admin-service` (support module)` (support
tickets, conversations, attachments, escalations) is now absorbed
into this service as a **separately permissioned module** with
the `support.admin` scope. The canonical source is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.38.

> The `SUPER_ADMIN` permission preset remains the single break-
> glass role and is unchanged. The preset membership is now
> **1 × `platform.super_admin` + 20 × `<service>.admin` scopes**
> (one per survivor). The `support.admin` scope is an additional
> non-preset scope that grants access to the support module.

### A.1 Bounded context (post-merger)

Operations console + admin user permissions + admin action log +
support tickets + conversations + escalations. The service is the
**only** writer of the `admin` schema.

### A.2 Absorbed responsibilities (from `admin-service` (support module))

- Tickets (`admin.support_tickets`).
- Conversations (`admin.support_conversations`).
- Attachments (`admin.support_attachments`).
- Escalations (`admin.support_escalations`).
- Emit `support.ticket.opened.v1`, `support.ticket.resolved.v1`.
- Consume `payment.disputed.v1`, `customer.suspended.v1`.

### A.3 Absorbed REST endpoints (support module; require `support.admin`)

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/support/tickets` | bearer (admin) | open |
| GET  | `/v1/support/tickets/{id}` | bearer (admin) | read |
| POST | `/v1/support/tickets/{id}/messages` | bearer (admin) | post message |
| POST | `/v1/support/tickets/{id}/resolve` | bearer (admin) | resolve |

### A.4 Compatibility window

For at least six calendar months from 2026-08-05:

- `support.ticket.*.v1` are published under the same topic names
  and schema versions by this service.
- `/v1/support/*` continue to be served from this service.
- Old schema name `support.*` remains readable as a view in the
  `admin` schema.

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

- **Depends on**: [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (manual journal entries; tax remittance; CIT provision; force-capture / force-refund / force-payout)
