# dispatch-service — Technology Profile

> One-page technology reference for `dispatch-service`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`INTEGRATION.md`](./INTEGRATION.md) · [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Math / scoring (hot path) |
| **Language** | Kotlin 2.2.x |
| **Framework** | Spring Boot 4.x (WebFlux coroutines) |
| **Build** | Gradle 9 (Kotlin DSL) |
| **Container** | `eclipse-temurin:25-jre-jammy` (multi-stage build, JRE-only final stage, non-root user) |

## 2. Key libraries

- Spring WebFlux (coroutines, virtual threads)
- Spring Statemachine 5 (match state machine)
- Spring Data R2DBC (reactive PostgreSQL)
- MapStruct

## 3. Data layer

- **Database**: PostgreSQL 18, schema `dispatch` (R2DBC — non-blocking, monthly RANGE partitioned on `assigned_at`)
- **DB extras**: monthly partitions; pre-create 12 future months
- **Migrations**: Flyway 11.x
- **ORM / DSL**: Spring Data R2DBC (NOT JPA — blocking not allowed on hot path)

## 4. Cache

Redis — match attempts, driver eligibility ring

## 5. External integrations

driver-location · eta-routing

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

- **HPA signal**: RPS + queue depth, 3–30 replicas, p99 < 100ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: ./gradlew bootRun
- **Test**: ./gradlew test
- **Compose profile**: `docker compose --profile dispatch up`

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
- `platform.ops`
- `dispatch.admin`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.dispatch.v1`
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
| Match attempts | ✓ | ✓ | ✓ | — | — | — | ✓ |
| Driver eligibility ring | ✓ | ✓ | ✓ | — | — | — | — |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md §6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `GET` | `/admin/v1/dispatch/matches/recent` | `platform.ops` | List recent match attempts with scoring breakdown + reason code |
| `POST` | `/admin/v1/dispatch/matches/{id}/replay` | `dispatch.admin` | Replay a specific match attempt (e.g. after model update) |
| `POST` | `/admin/v1/dispatch/eligibility/rebuild` | `dispatch.admin` | Rebuild the driver eligibility ring for a city/zone |

### 10.5 Admin enforcement

- **Pattern**: Spring Security 7 method security (`@PreAuthorize("hasRole('platform.admin')")`) on `@RestController` mounted at `/admin/v1`
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
- This service's per-service admin role `dispatch.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` §14) and
emit `audit.admin.dispatch.v1` (per §10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Math / scoring — Kotlin / Spring Boot 4 (WebFlux).

**External vendor SDK.** driver-location · eta-routing (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) §7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) §3 *Kotlin / Spring Boot OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: Spring WebFlux · Spring Statemachine 5 · Spring Data R2DBC · MapStruct.

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

## 12. Make a Deal

This service participates in the platform's
[Make a Deal](../../shared/DEAL_FEATURE.md) negotiation kernel as the
**driver-side boundary** for ride deals.

**Deal participation.** Dispatch-service owns the per-deal `DealBid`
and `DealAttempt` rows. On `ride.deal.opened.v1` it fans out the deal
to eligible drivers (mirroring the existing `MatchAttempt` flow with
`deal.broadcast.radius_m` and `deal.broadcast.max_concurrent_drivers`),
persists each bid, enforces the bid TTL (`deal.bid.ttl_seconds`), and
emits `dispatch.deal.bid.submitted.v1`. When a driver accepts a
rider counter, it emits `dispatch.deal.accepted.v1`. The hybrid
push-pull model adds `GET /v1/dispatch/drivers/{id}/open-deals` for
driver-initiated discovery.

**Events.** Produces `dispatch.deal.bid.submitted.v1`,
`dispatch.deal.bid.expired.v1`, `dispatch.deal.accepted.v1`,
`dispatch.deal.rejected.v1` (driver-side reject). Consumes
`ride.deal.opened.v1`, `ride.deal.countered.v1`,
`ride.deal.accepted.v1`, `ride.deal.rejected.v1`,
`ride.deal.expired.v1` (see
[`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §4 for
the deal event catalog).

**Idempotency.** `Idempotency-Key: deal:<deal_id>:<action>` on every
state-changing POST; consumer-side inbox dedup on `event_id` per
[`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md).
The bid-TTL sweeper uses the same Redis sorted-set primitive as the
existing `dispatch.offer.*` TTL handling.

**Single source of truth.** The deal model, state machine,
fare-band rules, and participation matrix live in
[`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md). The
config keys live in
[`../RECOMMENDATIONS.md` §6.2b](../RECOMMENDATIONS.md). Do not
duplicate the deal spec here.

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
