# restaurant-staff-service

## 1. Purpose

`restaurant-staff-service` is the canonical owner of the
**restaurant staff aggregate** — the set of users (other than
the merchant owner) who can operate a specific restaurant or
branch. It owns staff invitations, role assignments (manager,
cashier, kitchen, dispatcher), per-device login, and deactivation.
It does NOT own the restaurant, the merchant, or the Keycloak
identity — Keycloak holds the credentials; this service holds the
business assignments.

## 2. Bounded Context

- **In scope**: staff records (linked to a Keycloak subject),
  role assignments per restaurant or branch, invitations, device
  list, activation / deactivation.
- **Out of scope**: Keycloak identity, restaurant brand, branch
  data, menu, orders.

## 3. Responsibilities

- Invite a staff member by email; issue an invitation token.
- Activate a staff member (after they complete Keycloak sign-up
  and the invitation token is presented).
- Assign roles per restaurant or per branch.
- Manage per-device login state (allow-list of device IDs per
  staff member).
- Deactivate a staff member (admin or owner action).
- Emit `staff.invited.v1`, `staff.activated.v1`,
  `staff.deactivated.v1`.

## 4. Explicitly NOT Owned

- **Keycloak identity** — owned by `identity-service` and
  Keycloak. A `kc_sub` is referenced, never duplicated.
- **Restaurant brand** — owned by `restaurant-service`.
- **Branch data** — owned by `branch-service`.
- **Restaurant operator console UI** — the console calls this
  service; the UI is owned by the admin web app.
- **Permissions enforcement at runtime** — the operator console
  and the operator's POS terminal enforce roles; this service is
  the source of truth for "who has which role" and answers
  RBAC queries.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant Owner | human | invite, deactivate, manage roles |
| Merchant Ops | human | read |
| Restaurant Manager (staff) | human | manage own branch staff |
| Platform Admin | human | read/write any; deactivate |
| `identity-service` | system | keycloak sub verification |
| `restaurant-service` | system | read (parent) |
| `branch-service` | system | read (parent) |
| `restaurant-order-mgmt-service` | system | read (RBAC for operator console) |
| `admin-service` | system | read/write (admin console) |
| `notification-service` | system | send invitation, deactivation |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — verify Keycloak subject, create user on
  invitation acceptance — SLO 99.95%, circuit breaker: **yes**.
- `restaurant-service` — verify parent restaurant is approved —
  SLO 99.95%, circuit breaker: **yes**.
- `branch-service` — verify parent branch exists (when assigning
  per-branch) — SLO 99.95%, circuit breaker: **yes**.
- `configuration-service` — read role enums and limits — SLO
  99.95%, circuit breaker: **yes**.
- `notification-service` — send invitation / deactivation
  messages — SLO 99.9%, circuit breaker: **yes**.

### Asynchronous (events consumed)

- `restaurant.created.v1` from `restaurant-service` — note: this
  restaurant can now have staff — duplicate handling: **inbox
  dedup**.
- `restaurant.suspended.v1` from `restaurant-service` — cascade
  deactivation of all staff scoped only to that restaurant —
  **inbox dedup**.
- `restaurant.closed.v1` from `restaurant-service` — cascade
  deactivation — **inbox dedup**.
- `identity.user.suspended.v1` from `identity-service` —
  deactivate all staff records of the user — **inbox dedup**.
- `identity.user.disabled.v1` from `identity-service` —
  deactivate — **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema
  `restaurant_staff`).
- Cache: **Redis** (per-service, used for fast RBAC lookups).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `restaurant_staff` (owned exclusively by this
  service).
- Tables: `staff`, `staff_roles`, `staff_devices`,
  `staff_invitations`, `outbox`, `inbox`.
- Migrations: `services/restaurant-staff-service/prisma/migrations/`.
- Soft delete: **yes** (`deactivated_at` on `staff`).
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/staff/invitations | bearer (merchant_owner) | invite a staff member |
| GET | /v1/staff/invitations/{token} | bearer (public-ish) | fetch invitation details (for acceptance) |
| POST | /v1/staff/invitations/{token}/accept | bearer (Keycloak) | accept invitation (creates staff record) |
| GET | /v1/staff | bearer (owner / manager / admin) | list staff (filterable) |
| GET | /v1/staff/{id} | bearer (owner / admin) | read |
| PATCH | /v1/staff/{id}/roles | bearer (owner / admin) | change roles |
| POST | /v1/staff/{id}/devices | bearer (staff self / owner) | register a device |
| DELETE | /v1/staff/{id}/devices/{device_id} | bearer (staff self / owner) | remove a device |
| POST | /v1/staff/{id}/deactivate | bearer (owner / admin) | deactivate (with reason) |
| POST | /v1/staff/{id}/reactivate | bearer (owner / admin) | reactivate |
| GET | /v1/staff/by-user/{kc_sub} | bearer (system) | lookup by Keycloak subject |
| GET | /v1/staff/rbac/check | bearer (system) | check role for (user, restaurant, branch, role) |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `staff.invited.v1` | invitation created | `notification-service`, `audit-service` |
| `staff.activated.v1` | staff accepts invitation | `restaurant-order-mgmt-service`, `notification-service`, `audit-service` |
| `staff.role_changed.v1` | role assignment changes | `restaurant-order-mgmt-service`, `audit-service` |
| `staff.device_registered.v1` | a device is added | `audit-service` |
| `staff.deactivated.v1` | staff deactivated | `restaurant-order-mgmt-service`, `notification-service`, `audit-service` |
| `staff.reactivated.v1` | staff reactivated | `restaurant-order-mgmt-service`, `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `restaurant.created.v1` | `restaurant-service` | parent eligible | log only |
| `restaurant.suspended.v1` | `restaurant-service` | cascade deactivation of restaurant-scoped staff | set `deactivated_at` on staff with only that restaurant in scope; reason `restaurant_suspended` |
| `restaurant.closed.v1` | `restaurant-service` | cascade deactivation | same |
| `identity.user.suspended.v1` | `identity-service` | user suspension | deactivate all staff records of the user |
| `identity.user.disabled.v1` | `identity-service` | user disabled | deactivate |

## 12. External Integrations

- **Email / SMS for invitations** via `notification-service` —
  credentials in Vault at `secret/notification-service/{env}`.
- **Keycloak user creation** via `identity-service` (when a
  staff member accepts an invitation and is a new user).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `staff.roles.list` | array<string> | configuration-service | `manager`, `cashier`, `kitchen`, `dispatcher` |
| `staff.invitation.ttl_hours` | int | configuration-service | token lifetime |
| `staff.devices.max_per_user` | int | configuration-service | allow-list limit |
| `staff.rate_limit.invite_per_hour` | int | configuration-service | throttle invitations |
| `staff.cascade.suspend_user_to_staff` | bool | configuration-service | policy |
| `feature_flag.staff.self_device_register` | bool | feature-flag-service | allow staff to self-register devices |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC** (`merchant_owner`, `merchant_ops`,
  `restaurant_manager`, `platform_admin`); fine-grained
  resource ownership.
- Secrets: Vault paths `secret/restaurant-staff-service/{env}`.
- PII: minimal; the staff member's email is held in the
  invitation record (encrypted) and the `kc_sub` after
  activation.
- Audit: every state change emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=restaurant-staff-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`, `latency_ms`,
  `status`, `staff_id`, `restaurant_id`, `kc_sub`.
- Metrics:
  - RED: standard.
  - Business: `staff_invited_total{role}`,
    `staff_activated_total{role}`,
    `staff_deactivated_total{reason}`,
    `staff_role_changes_total{from,to}`,
    `staff_rbac_check_total{cache_hit,result}`,
    `staff_invitation_ttl_hours`.
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/staff/rbac/check` (called on every
  operator console action and POS device request) — Redis-cached
  with 60 s TTL; key `staff:rbac:{kc_sub}:{restaurant_id}:
  {branch_id}:{role}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 1 manager, 1 cashier, 1 kitchen, 1 dispatcher under an
  approved restaurant.
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/restaurant-staff-service:{git-sha}`.
- Replicas: 3 baseline, HPA up to 12.
- Resource limits: 500m–2000m CPU, 512Mi–2Gi memory.
- Migrations: init container.
- Rollout: rolling update with `maxUnavailable: 0`,
  `maxSurge: 1`.
- Region: `eu-west` and `ap-southeast`.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`configuration-service`](../configuration-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md)
- **Depended on by**: [`merchant-service`](../merchant-service/README.md), [`restaurant-service`](../restaurant-service/README.md)

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
