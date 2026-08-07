# fraud-risk-service — Technology Profile

> One-page technology reference for `fraud-risk-service`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`INTEGRATION.md`](./INTEGRATION.md) · [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Math / scoring / ML |
| **Language** | Python 3.14 |
| **Framework** | FastAPI 0.115+ |
| **Build** | `uv` + `pyproject.toml` |
| **Container** | `python:3.14-slim` (multi-stage build, non-root user) |

## 2. Key libraries

- FastAPI 0.115+ (async REST)
- Pydantic 2
- scikit-learn 1.6+ (real-time scoring)
- NumPy 2 (vectorised features)
- MLflow client (model registry)
- `asyncpg` (PostgreSQL 18 async)

## 3. Data layer

- **Database**: PostgreSQL 18, schema `fraud_risk` (monthly RANGE partitioned on `created_at`)
- **Migrations**: Alembic 1.13+
- **ORM / DSL**: SQLAlchemy 2.0 (async) + `asyncpg`

## 4. Cache

Redis — device fingerprint cache, blocklists (email/phone/IP/device/card BIN)

## 5. External integrations

device fingerprint provider · threat intel feed(s)

## 6. Security

- **AuthN**: Keycloak resource server (Spring Security 7 for Kotlin, `coreos/go-oidc` v3 for Go, `authlib` for Python)
- **AuthZ**: RBAC (JWT scopes / roles)
- **Secrets**: external secret manager (Kubernetes External Secrets / Vault — no secret in env)
- **mTLS**: linkerd sidecar, all intra-cluster traffic

## 7. Observability

- **Tracing**: OpenTelemetry SDK 1.40+ → OTLP
- **Metrics**: `prometheus-client` (FastAPI instrumentation via `starlette-prometheus`) → Prometheus
- **Logs**: structured JSON to stdout (Loki)
- **Health**: `/healthz` (custom handler)

## 8. Scaling

- **HPA signal**: RPS + model latency, 3–20 replicas, p99 < 100ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: `uvicorn main:app --reload --port 8080`
- **Test**: `pytest`
- **Compose profile**: `docker compose --profile fraud-risk up`

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
- `platform.ops`
- `fraud_risk.admin`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.fraud_risk.v1`
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
| Risk scores | ✓ | ✓ | ✓ | — | — | — | ✓ |
| Blocklists | ✓ | ✓ | ✓ | — | — | — | scrubbed |
| Model registry | ✓ | ✓ | read | — | — | — | read |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/blocklists/{type}/{value}` | `fraud_risk.admin` | Add to a blocklist (email / phone / IP / device / card BIN) |
| `DELETE` | `/admin/v1/blocklists/{type}/{value}` | `fraud_risk.admin` | Remove from a blocklist |
| `GET` | `/admin/v1/scores/{event_id}` | `platform.ops` | Full score breakdown for a specific event (model + features + reason) |
| `POST` | `/admin/v1/models/{id}/promote` | `fraud_risk.admin` | Promote a model version from staging to production |

### 10.5 Admin enforcement

- **Pattern**: FastAPI `APIRouter(prefix='/admin/v1')` with `Depends(require_role('platform.admin'))` per route; the dependency also emits the audit event
- **Network**: admin port (`8081`) is reachable only from the
  `admin-service`, `platform-ops`, and `platform-engineering`
  namespaces + bastion. Public ingress is not routed to it.
- **mTLS**: linkerd sidecar on every admin call.

### 10.6 Local admin (dev only)

- **Run with admin port**: `ADMIN_PORT=8081 uvicorn main:app --reload --port 8080`
- **Test admin endpoints**: `pytest -k admin`
- **Mint a dev admin JWT**: `make admin-jwt ROLE=platform.admin`

### 10.7 Super Admin preset membership

This service is included in the platform's `SUPER_ADMIN` permission
preset. An operator granted the preset receives:

- The platform-wide guard role `platform.super_admin`.
- This service's per-service admin role `fraud_risk.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` 14) and
emit `audit.admin.fraud_risk.v1` (per 10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Math / scoring / ML — Python / FastAPI + scikit-learn.

**External vendor SDK.** device fingerprint · threat intel (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 5 *Python OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: FastAPI 0.115+ · Pydantic 2 · scikit-learn 1.6 · NumPy 2 · MLflow client · `asyncpg`.

**Extractability.** This service can be lifted out of the platform and run as a
standalone project without code changes. The minimum dependency manifest
that demonstrates this is [`SKELETON.pyproject.toml`](./SKELETON.pyproject.toml)
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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

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

- **SDK**: `conductor-python:1.x (Python/FastAPI)`
- **License**: Apache-2.0 (Netflix Conductor OSS)
- **Worker registration model**: workers are colocated in this service's binary; each task implementation is annotated `@ConductorTask(<task_name>)` and registers at startup with the Conductor server via `ConductorClient.startWorkers(...)`.
- **Connection settings** (Helm-injected, per env):
  - `conductor.server.url` — e.g. `https://conductor.prod.uber.io`
  - `conductor.task.<task_name>.timeout_seconds` — default 30s
  - `conductor.task.<task_name>.retry_count` — default 3
  - `conductor.worker.heartbeat_interval_seconds` — default 5s
  - `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration
- **Operational references**: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 8 (runbook), 7 (observability); [`MASTER_TASK.md`](../MASTER_TASK.md) 7-9 for per-service task IDs.
