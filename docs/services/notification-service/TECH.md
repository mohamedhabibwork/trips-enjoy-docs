# notification-service — Technology Profile

> One-page technology reference for `notification-service`. The platform-wide
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
| **Framework** | Spring Boot 4.x + Spring Kafka 4.x |
| **Build** | Gradle 9 (Kotlin DSL) |
| **Container** | `eclipse-temurin:25-jre-jammy` (multi-stage build, JRE-only final stage, non-root user) |

## 2. Key libraries

- Spring Data JPA (Hibernate 7) — templates + preferences + delivery state
- Spring Kafka 4 (outbox + transactional producer)
- Spring Cache (Caffeine + Redis)
- Flyway 11

## 3. Data layer

- **Database**: PostgreSQL 19, schema `notification` (monthly RANGE partitioned on `created_at`)
- **Migrations**: Flyway 11.x
- **ORM / DSL**: Hibernate 7 (Spring Data JPA)

## 4. Cache

Redis — dedup window, suppression rules, quiet hours

## 5. External integrations

communication-gateway (push/SMS/email fan-out)

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

- **HPA signal**: CPU 60% + Kafka lag, 3–20 replicas, p99 < 500ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: ./gradlew bootRun
- **Test**: ./gradlew test
- **Compose profile**: `docker compose --profile notification up`

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
- `platform.support`
- `notification.admin`
- `notification.support`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.notification.v1`
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
| Templates (i18n) | ✓ | ✓ | ✓ | — | — | — | read |
| Delivery state | ✓ | ✓ | ✓ | read+reason | — | — | ✓ |
| User preferences (PII) | ✓ | ✓ | scrubbed | scrubbed+reason | — | — | scrubbed |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/notifications/{id}/cancel` | `platform.admin` | Cancel a notification before it's been delivered |
| `POST` | `/admin/v1/templates/{id}/publish` | `notification.admin` | Publish a new template version (atomic across i18n locales) |
| `POST` | `/admin/v1/templates/{id}/submit-for-approval` | `notification.admin` | Submit a WhatsApp structured template to the configured provider for approval |
| `POST` | `/admin/v1/templates/{id}/approve` | `notification.admin` | Record the provider's `approved` decision (HMAC; idempotent on `(template_id, locale, provider_template_id)`) |
| `GET`  | `/admin/v1/templates/{id}/history` | `notification.admin` / `platform.support` | Full publication history (powers "what was actually sent?" support workflow) |
| `GET`  | `/admin/v1/notifications/{user_id}/recent` | `platform.support` | List recent notifications sent to a user (PII scrubbed unless `?reason=...`) |

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
- This service's per-service admin role `notification.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` 14) and
emit `audit.admin.notification.v1` (per 10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Business core — Kotlin / Spring Boot 4 + Spring Kafka.

**External vendor SDK.** communication-gateway (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 3 *Kotlin / Spring Boot OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: Spring Data JPA · Spring Kafka 4 · Spring Cache · Flyway.

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

## 12. Make a Deal

This service participates in the platform's
[Make a Deal](../../shared/DEAL_FEATURE.md) negotiation kernel as the
**outbound channel** that notifies the rider/customer and the
driver/courier of every deal state change.

**Deal participation.** Notification-service consumes all
`*.deal.*.v1` events (ride + food families) and fans them out across
the 5 deal templates
([`deal.opened` / `deal.bid_received` / `deal.counter_received` /
`deal.accepted` / `deal.expired`](../notification-service/INTEGRATION.md#templates-make-a-deal--phase-75)).
Each notification binds to a `template_version_snapshot_id` per the
existing immutable-template audit chain (see
[`../../shared/NOTIFICATION_TEMPLATES.md`](../../shared/NOTIFICATION_TEMPLATES.md)
or the 10 of this TECH.md).

**Events.** Consumes `ride.deal.opened.v1`,
`ride.deal.bid.submitted.v1`, `ride.deal.countered.v1`,
`ride.deal.accepted.v1`, `ride.deal.rejected.v1`,
`ride.deal.expired.v1`, `food.deal.opened.v1`,
`food.deal.bid.submitted.v1`, `food.deal.countered.v1`,
`food.deal.accepted.v1`, `food.deal.rejected.v1`,
`food.deal.expired.v1`. Produces no deal events (downstream only).

**Idempotency.** Inbound `Idempotency-Key` on every state-changing
notification write; consumer-side inbox dedup on `event_id` per
[`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md).
A duplicate deal event is a no-op (the notification was already
sent).

**Single source of truth.** The deal model, state machine,
fare-band rules, and participation matrix live in
[`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md). The
config keys live in
[`../RECOMMENDATIONS.md` 6.2b](../RECOMMENDATIONS.md). Do not
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
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — WhatsApp structured template model
- [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) — immutable audit table
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — delivery-side audit chain
- [`PLAN.md`](./PLAN.md) — implementation tracker

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
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).

- **SDK**: `io.conductor:conductor-client:3.x (Kotlin/Spring)`
- **License**: Apache-2.0 (Netflix Conductor OSS)
- **Worker registration model**: workers are colocated in this service's binary; each task implementation is annotated `@ConductorTask(<task_name>)` and registers at startup with the Conductor server via `ConductorClient.startWorkers(...)`.
- **Connection settings** (Helm-injected, per env):
  - `conductor.server.url` — e.g. `https://conductor.prod.uber.io`
  - `conductor.task.<task_name>.timeout_seconds` — default 30s
  - `conductor.task.<task_name>.retry_count` — default 3
  - `conductor.worker.heartbeat_interval_seconds` — default 5s
  - `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration
- **Operational references**: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 8 (runbook), 7 (observability); [`MASTER_TASK.md`](../../MASTER_TASK.md) 7-9 for per-service task IDs.
