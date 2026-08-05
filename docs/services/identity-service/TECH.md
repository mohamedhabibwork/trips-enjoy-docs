# identity-service — Technology Profile

> One-page technology reference for `identity-service`. The platform-wide
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

- Spring Data JPA (Hibernate 7) — Keycloak `sub` mirror
- Spring Security 7 (Keycloak resource server)
- Spring Cache (Caffeine + Redis)

## 3. Data layer

- **Database**: PostgreSQL 18, schema `identity` (`sub` → internal `identity_id` mapping + claim cache)
- **Migrations**: Flyway 11.x
- **ORM / DSL**: Hibernate 7 (Spring Data JPA)

## 4. Cache

Redis — claim cache (TTL 5m, event-invalidate on Keycloak events)

## 5. External integrations

Keycloak (JWKS + admin events)

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

- **HPA signal**: CPU 60%, 2–8 replicas, p99 < 50ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: ./gradlew bootRun
- **Test**: ./gradlew test
- **Compose profile**: `docker compose --profile identity up`

## 10. Admin endpoints & RBAC

This service exposes `/admin/v1/...` endpoints for the `admin-service`
BFF and platform operators. The platform-wide admin pattern (roles,
audit format, network policy, common endpoints) is in
[`../RECOMMENDATIONS.md` §6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac);
this section documents the **per-service specifics**.

### 10.1 Keycloak admin roles accepted

This service accepts admin calls from these Keycloak roles:

- `platform.super_admin`
- `platform.admin`
- `platform.support`
- `identity.admin`
- `identity.support`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.identity.v1`
- **Consumer**: `audit-service` (writes to its immutable `audit` schema)
- **Fields**: `actor_id`, `actor_username`, `roles`, `endpoint`,
  `target_resource`, `action`, `reason_code` (required for PII access),
  `request_id`, `trace_id`, `result`, `duration_ms`

### 10.3 Data access policy (per-service)

The platform-wide policy table is in
[RECOMMENDATIONS.md §6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide).
This service refines it as follows:

| Data class | super_admin | admin | ops | support | finance | engineering | data_eng |
|---|---|---|---|---|---|---|---|
| Identity mappings | ✓ | ✓ | ✓ | read+reason | — | — | scrubbed |
| PII (email, phone) | ✓ | ✓ | scrubbed | scrubbed+reason | — | — | scrubbed |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md §6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/identities/{id}/suspend` | `platform.admin` | Suspend a Keycloak user (also revokes active sessions) |
| `POST` | `/admin/v1/identities/{id}/unsuspend` | `platform.admin` | Unsuspend a Keycloak user |
| `POST` | `/admin/v1/identities/{id}/force-claims-refresh` | `identity.admin` | Force-refresh the local claim cache from Keycloak |
| `GET` | `/admin/v1/identities/{id}/roles` | `platform.admin` | List a user's current realm roles + computed preset membership (used by `admin-service` to render the operator-UI permission view) |
| `POST` | `/admin/v1/identities/{id}/roles/{role}` | `platform.super_admin` (for `role = platform.super_admin`); `platform.admin` (for any other role) | Grant a realm role. Requires `X-Audit-Reason` ≥ 8 chars; for `platform.super_admin` requires `X-Signature` (HMAC-SHA256) and `X-Break-Glass-Cosigner`; emits `identity.role.granted.v1` |
| `DELETE` | `/admin/v1/identities/{id}/roles/{role}` | `platform.super_admin` (for `role = platform.super_admin`); `platform.admin` (for any other role) | Revoke a realm role. Same gates as grant; emits `identity.role.revoked.v1` |

### 10.5 Admin enforcement

- **Pattern**: Spring Security 7 method security (`@PreAuthorize("hasRole('platform.admin')")`) on `@RestController` mounted at `/admin/v1`
- **Role-grant gates** (new for `platform.super_admin`):
  - `@PreAuthorize("hasRole('platform.super_admin')")` on the grant/revoke method when `role == "platform.super_admin"`.
  - `@BreakGlassRequired` annotation on the same method — rejects without a valid `X-Break-Glass-Cosigner` header (different admin with `platform.super_admin`).
  - `@RequestSignatureRequired` annotation on the same method — rejects without a valid HMAC-SHA256 `X-Signature` header.
  - Off-hours check (TIME_OF_DAY_RESTRICTION) — defaults to `09:00–18:00 UTC`; outside that window the co-signer is mandatory even for non-super roles (mirrors `SECURITY_ARCHITECTURE.md` §14).
- **Network**: admin port (`8081`) is reachable only from the
  `admin-service`, `platform-ops`, and `platform-engineering`
  namespaces + bastion. Public ingress is not routed to it.
- **mTLS**: linkerd sidecar on every admin call.

### 10.6 Local admin (dev only)

- **Run with admin port**: ./gradlew bootRun --admin.port=8081
- **Test admin endpoints**: ./gradlew test --tests *AdminController*
- **Mint a dev admin JWT**: `make admin-jwt ROLE=platform.admin`

### 10.7 Super Admin preset membership

This service is included in the platform's `SUPER_ADMIN` permission
preset. An operator granted the preset receives:

- The platform-wide guard role `platform.super_admin`.
- This service's per-service admin role `identity.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` §14) and
emit `audit.admin.identity.v1` (per §10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Business core — Kotlin / Spring Boot 4.

**External vendor SDK.** Keycloak (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) §7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) §3 *Kotlin / Spring Boot OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: Spring Data JPA · Spring Security 7 (Keycloak) · Spring Cache.

**Extractability.** This service can be lifted out of the platform and run as a
standalone project without code changes. The minimum dependency manifest
that demonstrates this is [`SKELETON.gradle.kts`](./SKELETON.gradle.kts)
(doc-only stub; not a runnable build). The split between platform-required
and swappable dependencies is:

| Dependency class | Platform-required | Swappable |
|---|---|---|
| Language runtime | — | JDK 25 / Go 1.25 / Python 3.14 (use whatever your env needs) |
| Web/framework | `platform-spring-boot-starter` (Kotlin) / `net/http` + `chi` (Go) / FastAPI (Python) | Replace with your preferred framework |
| Database | PostgreSQL 18 (per-service schema) | H2 (in tests) / any PostgreSQL 14+ compatible |
| Migrations | Flyway 11 (Kotlin) / `golang-migrate` v4 (Go) / Alembic (Python) | Any tool that produces the same SQL |
| Cache | Redis 7 (cluster) | Caffeine (in-process) / no cache |
| Messaging | Apache Kafka 3.9 | In-process `BlockingQueue` for tests |
| Identity | Keycloak | Stub JWT verifier (JWKS = a static fixture) |
| Observability | OpenTelemetry SDK → OTLP | Logback / logrus / structlog direct to stdout |
| External vendor SDK | (per the "External" column of [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §2) | Swap or stub at the driver boundary (`PaymentGatewayDriver`, `MapProvider`, etc.) |

**Single source of truth.** The full licence catalogue (SPDX IDs,
license-text URLs, NOTICE / THIRD-PARTY-LICENSES generation tooling,
license compatibility matrix) is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
The version pin for every library is in [`../RECOMMENDATIONS.md` §5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

---

> All pinned versions are in [`../RECOMMENDATIONS.md` §5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
> Admin endpoints, roles, and audit conventions are pinned in
> [`../RECOMMENDATIONS.md` §6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac).
> To bump versions or change the admin pattern, open a PR against the
> corresponding section — never pin versions directly in this file.
