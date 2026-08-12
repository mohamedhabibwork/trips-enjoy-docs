# admin-service — Technology Profile

> One-page technology reference for `admin-service`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`INTEGRATION.md`](./INTEGRATION.md) · [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Business core |
| **Language** | Kotlin 2.2.x |
| **Framework** | Spring Boot 4.x |
| **Build** | Gradle 9 (Kotlin DSL) |
| **Container** | `eclipse-temurin:25-jre-jammy` (multi-stage build, JRE-only final stage, non-root user) |

## 2. Key libraries

- Spring Data JPA (Hibernate 7)
- Spring Security 7 (Keycloak resource server)
- MapStruct

## 3. Data layer

- **Database**: PostgreSQL 19, schema `admin` (BFF — minimal own state)
- **Migrations**: Flyway 11.x
- **ORM / DSL**: Hibernate 7 (Spring Data JPA)

## 4. Cache

None.

## 5. External integrations

aggregates internal services (BFF only)

## 6. Security

- **AuthN**: Keycloak resource server (Spring Security 7 for Kotlin, `coreos/go-oidc` v3 for Go, `authlib` for Python)
- **AuthZ**: RBAC (JWT scopes / roles)
- **Secrets**: external secret manager (Kubernetes External Secrets / Vault — no secret in env)
- **mTLS**: linkerd sidecar, all intra-cluster traffic

## 7. Observability

- **Tracing**: OpenTelemetry SDK 1.40+ → OTLP
- **Metrics**: Micrometer → Prometheus
- **Logs**: structured JSON to stdout (Loki)
- **Health**: `/actuator/health` (Spring Boot Actuator 4)

## 8. Scaling

- **HPA signal**: CPU 60%, 2–5 replicas, p99 < 500ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: ./gradlew bootRun
- **Test**: ./gradlew test
- **Compose profile**: `docker compose --profile admin up`

## 10. Admin endpoints & RBAC

This service exposes `/admin/v1/...` endpoints for the `admin-service`
BFF and platform operators. The platform-wide admin pattern (roles,
audit format, network policy, common endpoints) is in
[`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac);
this section documents the **per-service specifics**.

### 10.1 Keycloak admin roles accepted

This service accepts admin calls from these Keycloak roles:

- `platform.super_admin`
- `platform.admin`
- `platform.engineering`
- `admin.admin`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.admin.v1`
- **Consumer**: `audit-service` (writes to its immutable `audit` schema)
- **Fields**: `actor_id`, `actor_username`, `roles`, `endpoint`,
  `target_resource`, `action`, `reason_code` (required for PII access),
  `request_id`, `trace_id`, `result`, `duration_ms`

### 10.3 Data access policy (per-service)

The platform-wide policy table is in
[RECOMMENDATIONS.md 6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide).
This service refines it as follows:

| Data class | super_admin | admin | ops | support | finance | engineering | data_eng |
|---|---|---|---|---|---|---|---|
| Console sessions / RBAC | ✓ | ✓ | — | — | — | ✓ | — |
| Aggregated data (passthrough) | ✓ | ✓ | scrubbed | scrubbed+reason | via downstream | — | scrubbed |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/console/sessions` | `admin.admin` | Create a console session token for a Keycloak user |
| `GET` | `/admin/v1/console/rbac/{username}` | `admin.admin` | Resolve the effective RBAC for a user (all roles flattened) |
| `POST` | `/admin/v1/console/cache/invalidate` | `admin.admin` | Invalidate the BFF's response cache (per-path) |
| `POST` | `/admin/v1/pricing/geo-config` | `pricing.admin` | Create a per-location / OD-pair pricing override (validates origin/destination zones; emits `pricing.geo_config.updated.v1`) |
| `GET` | `/admin/v1/pricing/geo-config/{id}` | `pricing.admin` | Read the current head of a record |
| `PATCH` | `/admin/v1/pricing/geo-config/{id}` | `pricing.admin` | Update (creates a new version; refuses ambiguous priority/scope) |
| `POST` | `/admin/v1/pricing/geo-config/{id}/disable` | `pricing.admin` | Soft-disable (sets `effective_to = now()`, `status = RETIRED`) |
| `POST` | `/admin/v1/pricing/geo-config/{id}/rollback` | `pricing.admin` + `break_glass` | Roll back to a prior version (new history row + new head; never UPDATE/DELETE) |
| `GET` | `/admin/v1/pricing/geo-config?kind=...&status=...` | `pricing.admin` | List (paginated by `created_at DESC`, cursor-based) |
| `GET` | `/admin/v1/services` | `platform.admin` | Service catalog: all 20 services with their accepted admin scopes (10.1) and `SUPER_ADMIN` preset membership (10.7). Used by the operator UI to preview what a super-admin grant touches |
| `GET` | `/admin/v1/presets` | `platform.admin` | List permission presets (currently `SUPER_ADMIN` = `platform.super_admin` + 58 `<service>.admin` scopes) |
| `GET` | `/v1/admin/identity/permissions/{user_id}` | `platform.admin` | Read a user's current roles + computed preset membership (forwards to `identity-service GET /admin/v1/identities/{id}/roles`) |
| `POST` | `/v1/admin/identity/grant-super-admin` | `platform.super_admin` + `break_glass` (always) + `X-Signature` + MFA + super-admin IP allowlist | Grant the `SUPER_ADMIN` preset (1 × `platform.super_admin` + 58 × `<service>.admin`). Fan-out is 59 calls to `identity-service POST /admin/v1/identities/{id}/roles/{role}`. Emits `admin.super_admin.granted.v1` |
| `DELETE` | `/v1/admin/identity/revoke-super-admin` | `platform.super_admin` + `break_glass` (always) + `X-Signature` + MFA + super-admin IP allowlist | Revoke the `SUPER_ADMIN` preset. Emits `admin.super_admin.revoked.v1` |

### 10.5 Admin enforcement

- **Pattern**: Spring Security 7 method security (`@PreAuthorize("hasRole('platform.admin')")`) on `@RestController` mounted at `/admin/v1`
- **Network**: admin port (`8081`) is reachable only from the
  `admin-service`, `platform-ops`, and `platform-engineering`
  namespaces + bastion. Public ingress is not routed to it.
- **mTLS**: linkerd sidecar on every admin call.
- **Super-admin grant / revoke gates** (per `SECURITY_ARCHITECTURE.md`
  14):
  - `@PreAuthorize("hasRole('platform.super_admin')")` on the
    controller method.
  - `@BreakGlassRequired` — rejects without a valid
    `X-Break-Glass-Cosigner` header (a different admin holding
    `platform.super_admin`); co-signer is **never** optional.
  - `@RequestSignatureRequired` — rejects without a valid
    HMAC-SHA256 `X-Signature` header.
  - `@MfaRequired` — rejects without a step-up MFA claim in the JWT.
  - `IP_ALLOWLIST_SUPER_ADMIN` env (separate from `IP_ALLOWLIST`)
    — rejects requests whose source IP is not on the list.
  - `TIME_OF_DAY_RESTRICTION` — outside the configured business
    hours window the co-signer is mandatory even when the actor
    holds `platform.super_admin`.

### 10.7 Super Admin preset membership

This service is included in the platform's `SUPER_ADMIN` permission
preset. An operator granted the preset receives:

- The platform-wide guard role `platform.super_admin`.
- This service's per-service admin role `admin.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` 14) and
emit `audit.admin.admin.v1` (per 10.2) for each touched
record.

This service is the **canonical owner** of the `SUPER_ADMIN`
permission preset. The preset bundles `platform.super_admin` with
the 58 `<service>.admin` scopes (one per service in
`docs/services/`). It is enumerable at `GET /v1/admin/presets` and
its membership is declared per-service in each service's
`TECH.md` 10.7.

The preset is the **management surface** for the `platform.super_admin`
realm role; the realm role itself is still the source of truth for
enforcement. Grant / revoke is handled by
`POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin` (10.4) and
delegates the actual Keycloak role-mappings calls to
[`identity-service`](../identity-service/INTEGRATION.md#112-post-adminv1identitiesidrolesrole)
(the platform's sole authorized Keycloak admin caller).

---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Business core — Kotlin / Spring Boot 4.

**External vendor SDK.** aggregates internal services (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 3 *Kotlin / Spring Boot OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: Spring Data JPA · Spring Security 7 · MapStruct.

**Extractability.** This service can be lifted out of the platform and run as a
standalone project without code changes. The minimum dependency manifest
that demonstrates this is [`SKELETON.gradle.kts`](./SKELETON.gradle.kts)
(doc-only stub; not a runnable build). The split between platform-required
and swappable dependencies is:

| Dependency class | Platform-required | Swappable |
|---|---|---|
| Language runtime | — | JDK 25 / Go 1.25 / Python 3.14 (use whatever your env needs) |
| Web/framework | `platform-spring-boot-starter` (Kotlin) / `net/http` + `chi` (Go) / FastAPI (Python) | Replace with your preferred framework |
| Database | PostgreSQL 19 (per-service schema) | H2 (in tests) / any PostgreSQL 14+ compatible |
| Migrations | Flyway 11 (Kotlin) / `golang-migrate` v4 (Go) / Alembic (Python) | Any tool that produces the same SQL |
| Cache | Redis 8 (cluster) | Caffeine (in-process) / no cache |
| Messaging | Apache Kafka 3.9 | In-process `BlockingQueue` for tests |
| Identity | Keycloak | Stub JWT verifier (JWKS = a static fixture) |
| Observability | OpenTelemetry SDK → OTLP | Logback / logrus / structlog direct to stdout |
| External vendor SDK | (per the "External" column of [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) 2) | Swap or stub at the driver boundary (`PaymentGatewayDriver`, `MapProvider`, etc.) |

**Single source of truth.** The full licence catalogue (SPDX IDs,
license-text URLs, NOTICE / THIRD-PARTY-LICENSES generation tooling,
license compatibility matrix) is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
The version pin for every library is in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
Do not pin versions in this file.

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

---

> All pinned versions are in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
> Admin endpoints, roles, and audit conventions are pinned in
> [`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac).
> To bump versions or change the admin pattern, open a PR against the
> corresponding section — never pin versions directly in this file.

## Conductor SDK

This service participates in Conductor workflows per
[ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md).

- **SDK**: `io.conductor:conductor-client:3.x (Kotlin/Spring)`
- **License**: Apache-2.0 (Netflix Conductor OSS)
- **Worker registration model**: workers are colocated in this service's binary; each task implementation is annotated `@ConductorTask(<task_name>)` and registers at startup with the Conductor server via `ConductorClient.startWorkers(...)`.
- **Connection settings** (Helm-injected, per env):
  - `conductor.server.url` — e.g. `https://conductor.prod.uber.io`
  - `conductor.task.<task_name>.timeout_seconds` — default 30s
  - `conductor.task.<task_name>.retry_count` — default 3
  - `conductor.worker.heartbeat_interval_seconds` — default 5s
  - `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration
- **Operational references**: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 8 (runbook), 7 (observability); [`MASTER_TASK.md`](../MASTER_TASK.md) 7-9 for per-service task IDs.
