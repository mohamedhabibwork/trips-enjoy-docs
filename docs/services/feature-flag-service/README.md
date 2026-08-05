# Feature Flag Service

## 1. Purpose

`feature-flag-service` is the platform's single source of truth for
**feature flags** — boolean, multivariate, percentage-rolled-out,
segment-targeted, and time-windowed switches. It separates "what is
the value of this flag for this caller?" from the underlying service
so that feature rollouts, kill switches, and A/B experiments are
managed centrally, with attribution, audit, and one-click rollback.

## 2. Bounded Context

**Bounded context**: Feature flags / rollouts. A flag has a key, a
default, and zero or more rules; rules can match on user, segment,
region, percentage, or time window. The service evaluates a flag
against an evaluation context and returns the resolved value plus the
matched rule id.

In scope:

- Flag definitions (key, type, default, description, owner).
- Override rules (user, segment, region, percentage, time window).
- Percentage rollouts (consistent hashing on a stable id).
- Experiment tracking (variant assignment is sticky on a stable id).
- Flag evaluation API (synchronous) for server-side callers and
  for edge clients (filtered subset).
- Long-poll and event push delivery of changes.
- Audit log of every write.
- Targeting on a stable id (e.g. `customer_id`, `driver_id`,
  `session_id`).

Out of scope:

- Business rule values (owned by `configuration-service`).
- Experiment result analysis (owned by `analytics-service`).
- Permission gating (roles are owned by Keycloak).

## 3. Responsibilities

- CRUD on flag definitions and rules.
- Evaluation of a flag against an evaluation context.
- Sticky assignment for percentage rollouts (a stable id always
  resolves to the same variant for the duration of the experiment).
- Long-poll delivery and event push.
- Per-channel filtered subset for edge clients.
- Audit log of every write.
- Type-safe SDK that fails to start on misconfiguration.

## 4. Explicitly NOT Owned

- **Business rule values** — `configuration-service`.
- **Permissions / roles** — Keycloak.
- **A/B result analysis** — `analytics-service`.
- **Customer preferences** — `user-profile-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Operator (admin) | human | write (gated by RBAC + reason) |
| Experiment owner (data) | human | write (specific flag keys) |
| Mobile / web client | system | read filtered subset |
| Internal service | system | read + subscribe |
| Reconciliation job | system | read |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — admin token validation.

### Asynchronous (events consumed)

- `customer.segment.changed.v1` (from `customer-service`) — used to
  invalidate per-segment evaluation caches; the next evaluation
  reads the latest segment.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript) for the REST + SDK; Go 1.22 for the
  evaluation hot path.
- Database: PostgreSQL 18 (per-service schema `feature_flag`).
- Cache: Redis cluster.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `feature_flag`.
- Migrations: `services/feature-flag-service/migrations/`.
- Soft delete: yes (`flags.deleted_at`).
- Partitioning: `feature_flag.evaluation_log` partitioned by day.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/flags` | bearer | list flags |
| GET | `/v1/flags/{key}` | bearer | read flag definition |
| POST | `/v1/flags` | bearer (admin) | create flag |
| PUT | `/v1/flags/{key}` | bearer (admin) | update flag |
| POST | `/v1/flags/{key}/rules` | bearer (admin) | add rule |
| DELETE | `/v1/flags/{key}/rules/{rule_id}` | bearer (admin) | remove rule |
| POST | `/v1/flags/{key}/evaluate` | bearer | evaluate against context |
| GET | `/v1/flags/stream` | bearer | long-poll |
| GET | `/v1/channels/{channel}/flags` | bearer | filtered subset |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `feature_flag.updated.v1` | any change to a flag or its rules | every service |
| `feature_flag.disabled.v1` | flag is globally disabled (kill switch) | every service |
| `feature_flag.experiment.started.v1` | an experiment begins | `analytics-service` |
| `feature_flag.experiment.stopped.v1` | an experiment ends | `analytics-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `customer.segment.changed.v1` | `customer-service` | segment-based rules may now match | invalidate evaluation cache for that segment |
| `customer.created.v1` | `customer-service` | pre-warm the per-user evaluation cache | optional |

## 12. External Integrations

- **HashiCorp Vault** — DB credentials, signing key at
  `secret/feature-flag-service/<env>`.
- **AWS S3** — daily experiment assignment snapshot to
  `s3://trips-enjoy-platform-audit/feature-flag/assignments/<yyyy>/<mm>/<dd>/`.

## 13. Configuration

Operational parameters are loaded from environment variables:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | Per-env database URL |
| `KAFKA_BROKERS` | string | env | Bootstrap servers |
| `REDIS_URL` | string | env | Evaluation cache |
| `ADMIN_REALM` | string | env | `platform-internal` |
| `STICKY_HASH_ALGO` | string | env | `murmur3` (default) |
| `EVAL_LOG_TTL_DAYS` | int | env | 30 (default) |
| `LONGPOLL_MAX_WAIT_SECONDS` | int | env | 25 |

## 14. Security

- AuthN: JWT bearer (RS256, Keycloak).
- AuthZ: RBAC at gateway; per-endpoint scopes inside. Writes require
  `flag.admin`; experiment writes additionally require
  `flag.experiment`.
- Secrets: Vault paths `secret/feature-flag-service/{env}/{resource}`.
- PII: evaluation context may carry customer / driver IDs; not
  stored in evaluation log; logged only in access logs.
- Request signing: high-value mutations (kill switches, mass rule
  changes) require `X-Signature`.

## 15. Observability

- Logs: structured JSON to stdout; standard fields.
- Metrics: RED per route + `flag_evaluations_total{key, variant,
  matched_rule_id}`, `flag_cache_hit_ratio`, `flag_longpoll_connections`.
- Traces: OpenTelemetry; one root span per request; evaluation is
  a child span.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 6; HPA on CPU > 60% and
  `flag_longpoll_connections > 1000`.
- Hot path: `POST /v1/flags/{key}/evaluate` (read from in-memory
  cache; Redis as fallback).

## 17. Local Development

```bash
docker compose -f deploy/compose/feature-flag-service.yml up -d db
make -C services/feature-flag-service migrate-up
pnpm --filter @platform/feature-flag-service dev
pnpm --filter @platform/feature-flag-service seed
```

Seed data: sample release, operational, and experiment flags.

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/feature-flag-service:<sha>`.
- Replicas: 6 in production (3 per AZ).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: `pre-upgrade` Job.
- Rollback: re-deploy prior image; flag definitions and rules are
  in the DB, no code references values by version.

## 19. Disaster Recovery

- RPO: 5 minutes (PITR + WAL).
- RTO: 30 minutes (warm standby).

## 20. References

- Architecture: `docs/architecture/CONFIGURATION_ARCHITECTURE.md`.
- Event spec: `docs/architecture/EVENT_ARCHITECTURE.md`.


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

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`identity-service`](../identity-service/README.md), [`user-profile-service`](../user-profile-service/README.md)
- **Depended on by**: [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`delivery-service`](../delivery-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`inventory-service`](../inventory-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`restaurant-staff-service`](../restaurant-staff-service/README.md), [`search-service`](../search-service/README.md)

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
