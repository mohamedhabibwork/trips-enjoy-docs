# Service Documentation Template

> **Companion documents:** [`HLD.md`](HLD.md) (system-level
> architecture) and [`LLD.md`](LLD.md) (component-level patterns).
> This document is the canonical contract for every service's
> 8-doc set.

This template is the contract for every `services/<service>/` folder.
Every active service in the **21-service catalog** (20 per
[ADR-0017](adrs/0017-20-service-architecture.md) + `chat-service` per
Phase 7.7 / [ADR-0021](adrs/0021-in-app-chat-phase-7-7.md)) MUST
produce these **nine** files following this structure exactly. The
level of detail is "a backend team can begin implementation from the
documentation."

Services that participate in **Conductor workflows** (the 15 of 20
per [ADR-0018](adrs/0018-workflow-engine-conductor.md)) additionally
include an `### Conductor Workers` section in `INTEGRATION.md` and
a `### Phase 7.6 — Conductor Workers` block in `PLAN.md` per
[[trips-enjoy-conductor-workflow-engine-adoption]].

## File List and Purpose

| File | Purpose | Required sections |
|------|---------|-------------------|
| `README.md` | Overview, context, ownership, dependencies | see below |
| `BRD.md` | Business Requirements Document | BR--IDs |
| `SRS.md` | Software Requirements Specification | FR--/NFR--/SEC--/DATA-- IDs |
| `ERD.md` | Data model (PostgreSQL) | Mermaid ER + DDL |
| `INTEGRATION.md` | APIs and event contracts | Inbound/Outbound APIs, Events |
| `WORKFLOWS.md` | State machines and end-to-end flows | Mermaid sequence + state diagrams |
| `TECH.md` | Technology profile (runtime, libs, data layer, cache, integrations, admin/RBAC) | numbered sections §1–§11 |
| `PLAN.md` | Implementation plan with Phase 1–10 task backbone + applicable Phase 7.0 / 7.5 / 7.6 / 7.7 blocks + a `Hard service-to-service dependencies` callout | per-phase task tables |
| `SKELETON.<ext>` | Extractability skeleton (minimum dependency manifest proving the service can run as a standalone project) | `SKELETON.gradle.kts` / `SKELETON.go.mod` / `SKELETON.pyproject.toml` |
| `STATUS.md` | **Composition-only** status snapshot (identity, tech profile, implementation lifecycle, documentation completeness, contract snapshot, security/RBAC, plan snapshot) | 8 numbered sections; values are pointers to the canonical sources, never duplicated |

> **STATUS.md is a reader-rendered composition, not a source
> of truth.** Every field points at its canonical source
> (e.g. lifecycle → `DEPLOYMENT_ORDER.md` §8.2; contract
> counts → `INTEGRATION.md` §1–4). The doc-QA invariant is
> in `docs/PLAN_INDEX.md` "STATUS.md composition contract".

---

## README.md template

```markdown
# <Service Name>

## 1. Purpose

<2-4 sentences: what this service is responsible for, in plain English.>

## 2. Bounded Context

<Name of the bounded context. What is in scope, what is out of scope.>

## 3. Responsibilities

- <responsibility 1>
- <responsibility 2>
- ...

## 4. Explicitly NOT Owned

- <thing 1 — and who owns it instead>
- <thing 2>

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| <name> | human / system | <read/write/admin> |

## 6. Dependencies

### Synchronous (REST)

- `<downstream-service>` — `<purpose>` — `<SLO>` — circuit breaker: yes/no

### Asynchronous (events consumed)

- `<event-name>` from `<producer>` — `<purpose>` — duplicate handling: <inbox dedup>

## 7. Technology Assumptions

- Runtime: `<Go 1.25.x (edge / hot path) / Kotlin 2.2.x + Spring Boot 4.x (business core) / Python 3.14 (streaming / ML)>` — **no Node.js / TypeScript on the backend**; the platform's backend apps are Go, Kotlin, or Python only.
- Database: PostgreSQL 19 (per-service schema `<schema_name>`)
- Cache: Redis (per-service)
- Event broker: Kafka

## 8. Database Ownership

- Schema: `<schema_name>`
- Migrations: `<path>` (versioned, forward-only)
- Soft delete: yes/no
- Partitioning: yes/no (which tables)

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/<resource> | bearer | list |
| POST | /v1/<resource> | bearer | create |
| ... | ... | ... | ... |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| <name> | <trigger> | <consumers> |

(Full contracts in INTEGRATION.md.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| <name> | <producer> | <why> | <what we do> |

(Full contracts in INTEGRATION.md.)

## 12. External Integrations

- <provider> — <purpose> — credentials in Vault at <path>

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| <key> | <type> | configuration-service | <notes> |

## 14. Security

- AuthN: <JWT, service-account, mTLS>
- AuthZ: <RBAC roles, scopes, resource ownership checks>
- Secrets: <Vault paths>
- PII: <what is stored, encryption>

## 15. Observability

- Logs: <JSON to stdout, fields>
- Metrics: <RED + business KPIs>
- Traces: <OpenTelemetry, propagation>
- Health: /health, /ready, /started

## 16. Scalability

- Replicas: <default N>
- HPA: <CPU, custom metrics>
- Hot path: <identify and explain>

## 17. Local Development

- <how to run locally>
- <docker compose snippet or pointer>
- <seed data>

## 18. Deployment

- Image: <registry path>
- Replicas: <N>
- Resource limits: see deployment-arch
- Migrations: <how migrations run>
```

---

## BRD.md template

```markdown
# <Service Name> — Business Requirements Document

## 1. Document Purpose

<Who reads this, when, and what decisions it informs.>

## 2. Business Context

<Why this service exists, what business problem it solves.>

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | <objective> | <how measured> |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| <name> | <role> | <interest> |

## 5. Actors / Personas

<Description of each persona that interacts with this service.>

## 6. Business Capabilities

- <capability 1>
- <capability 2>

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | <requirement> | MUST | <source> |
| BR--011 | <requirement> | SHOULD | <source> |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | <rule> | <notes> |

## 9. Assumptions

- <assumption 1>
- <assumption 2>

## 10. Constraints

- <constraint 1>
- <constraint 2>

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| <name> | service / provider / config | <notes> |

## 12. Business Workflows

<List of high-level workflows; details in WORKFLOWS.md.>

## 13. Exception Workflows

<List of failure / edge workflows.>

## 14. Success Criteria

- <criterion 1>
- <criterion 2>

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| <kpi> | <target> | <how> |

## 16. Acceptance Criteria

- <criterion 1>
- <criterion 2>
```

---

## SRS.md template

```markdown
# <Service Name> — Software Requirements Specification

## 1. Introduction

<Brief overview of this document.>

## 2. Scope

<In scope and out of scope.>

## 3. System Context

<Mermaid context diagram showing this service, its callers, and its dependencies.>

## 4. Actors

<List with technical description (system actors, persona types).>

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | <requirement> | MUST |
| FR--002 | <requirement> | SHOULD |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P99 latency | <Xms> |
| NFR--002 | availability | uptime | 99.X% |
| NFR--003 | scalability | concurrent users | <N> |
| NFR--004 | maintainability | MTTR | <Xmin> |

## 7. API Requirements

<Summary; full contract in INTEGRATION.md.>

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | <requirement> | <notes> |

(Full schema in ERD.md.)

## 9. Validation Rules

- <rule 1>
- <rule 2>

## 10. State Transitions

<Pointer to WORKFLOWS.md; brief summary here.>

## 11. Authorization Requirements

- <rule 1>
- <rule 2>

## 12. Configuration Requirements

<List of configuration keys consumed.>

## 13. Error Handling

- <error 1> — <response>
- <error 2> — <response>

## 14. Concurrency Requirements

- <requirement 1>

## 15. Idempotency Requirements

- <requirement 1>

## 16. Performance

- Dominant path: <X>
- P50/P95/P99: <targets>

## 17. Scalability

- Horizontal scaling: <strategy>
- Vertical scaling: <strategy>

## 18. Availability

- SLO: 99.X%
- Error budget: <Y minutes / 30d>
- Maintenance window: <none / weekly Sun 04-06 UTC>

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | <requirement> | <notes> |

## 20. Privacy

- PII stored: <list>
- Retention: <period>
- Erasure: <process>

## 21. Auditability

- <event 1>
- <event 2>

## 22. Observability

- Logs: <format, fields>
- Metrics: <list>
- Traces: <OpenTelemetry; sample rate>
- Alerts: <list>

## 23. Maintainability

- Code style: <standard>
- Test coverage: <target>
- Documentation: <where>

## 24. Disaster Recovery

- RPO: <X>
- RTO: <Y>

## 25. Acceptance Criteria

- <criterion 1>
- <criterion 2>
```

---

## ERD.md template

```markdown
# <Service Name> — Entity-Relationship Diagram

## 1. Database

- Engine: PostgreSQL 19
- Schema: `<schema_name>` (owned exclusively by this service)
- Migrations: <path>

## 2. Cross-Service References

<List every cross-service ID stored as a column WITHOUT a database FK.>

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `<col>` | UUID | `<entity>` in `<service>` | `<service>` |

## 3. Entities

### `<Entity 1>`

<Description>

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `created_by` | UUID | NOT NULL | identity |
| `updated_by` | UUID | NOT NULL | identity |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |
| ... | ... | ... | ... |

#### Indexes

- PK on `id`
- Index on `<column>` (reason)
- Partial index on `<column>` WHERE `deleted_at IS NULL` (reason)

#### Constraints

- CHECK: `<column> IN (...)`
- UNIQUE: `<natural_key>`

### `<Entity 2>`

... (repeat)

## 4. Mermaid ER Diagram

\`\`\`mermaid
erDiagram
    ENTITY1 ||--o{ ENTITY2 : has
    ENTITY1 {
        uuid id PK
        string name
        timestamptz created_at
    }
    ENTITY2 {
        uuid id PK
        uuid entity1_id FK
        timestamptz created_at
    }
\`\`\`

## 5. DDL Sketch

\`\`\`sql
CREATE TABLE <schema>.<table> (
    id UUID PRIMARY KEY,
    ...
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX ... ON <schema>.<table> (...);
ALTER TABLE <schema>.<table> ADD CONSTRAINT ... CHECK (...);
\`\`\`

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`, `created_by`,
`updated_by`. `deleted_at` for soft delete.

## 7. Soft Delete

<Yes / No. List tables that use it.>

## 8. JSONB Usage

<List tables with JSONB columns and what is stored. Justify each.>

## 9. Partitioning

<List partitioned tables, partition strategy, retention.>

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| ... | ... | ... |

## 11. Migration Considerations

- <note 1>
- <note 2>
```

---

## INTEGRATION.md template

```markdown
# <Service Name> — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/<resource>`

- **Purpose**: <purpose>
- **Auth**: Bearer JWT (required roles: `<roles>`; scopes: `<scopes>`)
- **Idempotency**: `Idempotency-Key` header required
- **Request**:
  \`\`\`json
  { ... }
  \`\`\`
- **Response (201)**:
  \`\`\`json
  { ... }
  \`\`\`
- **Errors**:
  - 400 VALIDATION_FAILED
  - 401 UNAUTHENTICATED
  - 403 FORBIDDEN
  - 409 CONFLICT
  - 422 BUSINESS_RULE_VIOLATION
- **Validation**: <rules>

### 1.2 `GET /v1/<resource>/{id}`

... (repeat for each endpoint)

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `<service>` | POST | /v1/... | <purpose> | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `<event.name.v1>`

- **Producer**: <this-service>
- **Topic**: `<domain.entity.event>`
- **Trigger**: <when>
- **Schema version**: 1
- **Partition key**: `<aggregate_id>`
- **Consumers**: <list>
- **Schema**:
  \`\`\`json
  {
    "event_id": "...",
    "occurred_at": "...",
    "aggregate_id": "...",
    "data": { ... }
  }
  \`\`\`
- **Retry**: outbox pattern, 3 attempts
- **DLQ**: `<topic>.dlq`

## 4. Consumed Events

### 4.1 `<event.name.v1>`

- **Producer**: <producer>
- **Reason**: <why we consume>
- **Handler**: <what we do>
- **Deduplication**: inbox on `event_id`
- **Retry**: 3 with backoff
- **Failure**: DLQ

## 5. Reliability

- **Timeouts**: <defaults>
- **Retries**: <strategy>
- **Circuit breakers**: <where>
- **Bulkheads**: <where>
- **Outbox**: <yes, schema>
- **Inbox**: <yes, schema>
- **DLQ**: <list>
- **Reconciliation**: <jobs>

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the same
in the envelope. Logs and traces are correlated.

> **Note (ADR-0019, request id at the edge).** The `X-Correlation-Id`
> referenced in this template is the **alias form** of the
> API-gateway-generated `X-Request-Id` (the canonical contract is
> [ADR-0019](adrs/0019-request-id-at-the-edge.md) and the runtime
> is in [`shared/CONVENTIONS.md` 2](../shared/CONVENTIONS.md)).
> Services accept **either** `X-Request-Id` or `X-Correlation-Id`
> inbound, set **both** as outbound HTTP headers and Kafka headers,
> write the value to the MDC under `requestId`, bind it to the OTel
> root span as the attribute `platform.request_id`, and put it in
> the event envelope's `correlation_id` field. The W3C `traceparent`
> is propagated **separately** and is the OTel trace id, distinct
> from the request id.

## 7. Distributed Tracing

OpenTelemetry; one root span per request; propagated through Kafka.
```

---

## WORKFLOWS.md template

```markdown
# <Service Name> — Workflows

## 1. `<Workflow 1 name>`

### 1.1 Objective

<What this workflow achieves.>

### 1.2 Initiating Actor

<Who/what starts it.>

### 1.3 Participating Services

<All services involved.>

### 1.4 Prerequisites

<What must be true before this workflow starts.>

### 1.5 Happy Path

\`\`\`mermaid
sequenceDiagram
    participant A as <actor>
    participant S as <this-service>
    participant X as <external>
    A->>S: <action>
    S-->>A: <result>
\`\`\`

### 1.6 Alternate Paths

- <alt 1>
- <alt 2>

### 1.7 Failure Paths

- <failure 1>
- <failure 2>

### 1.8 Business Rules

- <rule 1>

### 1.9 State Transitions

\`\`\`mermaid
stateDiagram-v2
    [*] --> state1
    state1 --> state2
    state2 --> [*]
\`\`\`

### 1.10 Events

| Event | Direction | When |
|-------|-----------|------|
| <event> | produced / consumed | <when> |

### 1.11 APIs Involved

| API | Direction | When |
|-----|-----------|------|
| <api> | inbound / outbound | <when> |

### 1.12 Compensation / Rollback

<What we do if we need to undo.>

### 1.13 Final State

<Where the system ends up.>

## 2. `<Workflow 2 name>`

... (repeat)
```

---

## STATUS.md template

> **Read this section alongside the canonical sources**
> listed below. Every value in `STATUS.md` is a pointer to
> a canonical source — never restate a value that already
> lives elsewhere. The doc-QA invariant is documented in
> [`PLAN_INDEX.md`](../PLAN_INDEX.md) "STATUS.md composition
> contract".

```markdown
# <service> — Status Snapshot

> **Composition page.** This file is a reader-rendered
> composition of fields from the canonical sources below.
> When any source changes, regenerate this file (see
> `docs/PLAN_INDEX.md` "STATUS.md composition contract" for
> the contract and the doc-QA invariants).
>
> **Canonical sources for each field** (in order of
> preference; never duplicate the value — link to it):
>
> | Field group | Source of truth |
> |---|---|
> | Identity | `docs/services/README.md` + `<service>/README.md` §1–2 |
> | Tech profile | `<service>/TECH.md` + `docs/services/RECOMMENDATIONS.md` §2 |
> | Implementation lifecycle | `docs/DEPLOYMENT_ORDER.md` §8.2 |
> | Documentation completeness | filesystem scan (`docs/services/<service>/`) |
> | Contract snapshot | `<service>/INTEGRATION.md` + `docs/SERVICE_INTEGRATION_MATRIX.md` |
> | Security / RBAC | `<service>/TECH.md` §10 + `docs/services/RECOMMENDATIONS.md` §6.2a |
> | Plan snapshot | `<service>/PLAN.md` |

## 1. Identity

| Field | Value |
|---|---|
| Service name (kebab-case) | `<service>` |
| Bounded context | <from README §2> |
| Domain | <from MICROSERVICES_MAP> |
| Tier (deployment) | `T<n>` (position `<n>` of 21; `DEPLOYMENT_ORDER.md` §2) |
| Criticality / SLO | T1 (99.95%) / T2 (99.9%) / T3 (99.5%) |
| Owner team | <if documented> |

## 2. Tech profile

| Field | Value | Source |
|---|---|---|
| Language | Kotlin / Go / Python | `TECH.md` §1 |
| Framework | Spring Boot 4 / chi / FastAPI | `TECH.md` §1 |
| Profile | Edge / Business core / Math-ML / Streaming | `RECOMMENDATIONS.md` §1 |
| DB schema | `<schema>` (per-service) | `services/README.md` env-var table |
| Cache | Redis / none / per-pattern | `TECH.md` §4 |
| HPA signal | `<signal>` | `TECH.md` §8 |
| Replicas (default) | N–M | `TECH.md` §8 |
| p99 latency target | <Xms> | `TECH.md` §8 |
| Image | `registry.trips-enjoy.com/<service>:<sha>` | `README.md` §18 |
| Container port | `8080` (default) | `TECH.md` §1 |
| Health endpoints | `/actuator/health/liveness`, `/actuator/health/readiness` | `TECH.md` §7 |
| `.env.example` | `apps/<service>/.env.example` ✅ | filesystem |

## 3. Implementation lifecycle

> Source of truth: [`DEPLOYMENT_ORDER.md` §8.2](../../DEPLOYMENT_ORDER.md).

| Field | Value |
|---|---|
| Status | ✅ Graduated / ⏳ Stub |
| `apps/<service>/` source count | <int> |
| `apps/<service>/Dockerfile` | ✅ present / — |
| `apps/<service>/k8s/` (flat kustomize overlays) | ✅ present / — |
| `apps/<service>/monitoring/` (ServiceMonitor + PrometheusRule) | ✅ present / — |
| Local test suite | <e.g. "50 / 50 unit tests"> / "—" |
| Implementation memory | `uber-<service>-implementation-<date>.md` (project memory) / "—" |

## 4. Documentation completeness

> Source: filesystem scan of `docs/services/<service>/`. The
> "last updated" column is the git `HEAD` mtime of each file
> (rounded to the day).

| File | Required? | Present? | Last updated |
|---|---|---|---|
| `README.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `BRD.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `SRS.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `ERD.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `INTEGRATION.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `WORKFLOWS.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `TECH.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `PLAN.md` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `SKELETON.<ext>` | ✅ mandatory | ✅ | YYYY-MM-DD |
| `STATUS.md` (this file) | ✅ mandatory (new) | ✅ | YYYY-MM-DD |

## 5. Contract snapshot

> Sources: `<service>/INTEGRATION.md` §1–4 and
> [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md).

| Field | Count / Value |
|---|---|
| Inbound REST APIs | <int> (full contract in INTEGRATION.md §1) |
| Outbound REST APIs | <int> (INTEGRATION.md §2) |
| Produced events | <int> — topics: <comma-separated> (INTEGRATION.md §3) |
| Consumed events | <int> — topics: <comma-separated> (INTEGRATION.md §4) |
| Sync deps | <list, per SERVICE_INTEGRATION_MATRIX.md row> |
| Workflows participated | <list, per services/README.md "By workflow participation"> |

## 6. Security / RBAC

| Field | Value |
|---|---|
| AuthN | Bearer JWT (Keycloak, realm `<realm>`) per `TECH.md` §10 |
| AuthZ | RBAC; admin role `<service>.admin` per `TECH.md` §10.1 |
| SUPER_ADMIN preset | ✅ member of the 22-role preset (`platform.super_admin` + 21 × `<service>.admin`) per `services/RECOMMENDATIONS.md` §6.2a |
| Time-bounded alias | `platform-internal` realm `service-claims` scope mappers (per identity-service per-service claim contract); `<service>.scopes` / `<service>.level` / `<service>.tenant` claims available |

## 7. Plan snapshot

> Source: `<service>/PLAN.md` (header lines 3–9 + phase blocks).

| Field | Value |
|---|---|
| Plan header | Domain: <...> / Tier: T<n> / Technology: <...> / Criticality: <...> / DB Schema: <schema> / Cache: <...> / HPA: <...> |
| Phase 7.0 (cross-cutting) block | ✅ present / — (block IDs `T-<SVC>-NN`) |
| Phase 7.5 (Make-a-Deal kernel) block | ✅ present / — |
| Phase 7.6 (Conductor workers) block | ✅ present / — (15 of 20 services per ADR-0018) |
| Phase 7.7 (in-app chat) block | ✅ present / — (chat-service + 6 others) |
| Plan task total | <int> |
| Plan task status | pending: <int> · in_progress: <int> · done: <int> · blocked: <int> |

## 8. Cross-links

- **Sibling docs**: README · BRD · SRS · ERD · INTEGRATION · WORKFLOWS · TECH · PLAN · SKELETON
- **Platform-wide**: [`services/README.md`](../README.md) · [`MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md) · [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) · [`DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md) §8 · [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a
- **Implementation memory** (graduates only): `uber-<service>-implementation-<date>.md` (project memory index)
```

> **Last updated column.** Use `git log -1 --format=%ci -- <path>`
> (rounded to the day) — not the filesystem mtime, which is
> not preserved across clones.

---

## Style Rules

- Use the **template headings** verbatim. Add additional `###` sections
  as needed under each.
- Tables for any list of structured data.
- Mermaid diagrams for state machines and sequences.
- IDs: `BR--NNN`, `FR--NNN`, `NFR--NNN`, `SEC--NNN`, `DATA--NNN`.
- Currency in minor units with `currency` field.
- Time in RFC3339 UTC.
- Cross-service IDs as `uuid` columns WITHOUT database FKs.
- One database per service; one schema per service; this service is
  the source of truth for its data.
- Use `kebab-case` for service names, `snake_case` for tables/columns,
  `PascalCase` for logical entities, `domain.entity.event.vN` for
  events.
- Every event payload conforms to the envelope in
  `docs/architecture/EVENT_ARCHITECTURE.md`.
- Every error response conforms to `docs/architecture/API_STANDARDS.md`.
- Every PII field is marked and its retention/encryption documented.

## Minimum Content per Service

For a service to be considered "documented" by the platform's
contract:

- `README.md`: ≥ 200 lines, all 18 sections filled.
- `BRD.md`: ≥ 5 business requirements with IDs.
- `SRS.md`: ≥ 10 functional, ≥ 5 non-functional, ≥ 3 security.
- `ERD.md`: ≥ 1 entity with full DDL and Mermaid ER.
- `INTEGRATION.md`: ≥ 3 inbound APIs, ≥ 3 events produced, ≥ 3 events
  consumed.
- `WORKFLOWS.md`: ≥ 1 workflow with happy + failure + compensation
  paths and Mermaid diagrams.

## Related architecture docs

- [`SYSTEM_OVERVIEW.md`](SYSTEM_OVERVIEW.md) — plain-English platform summary
- [`MICROSERVICES_MAP.md`](MICROSERVICES_MAP.md) — service catalog
- [`DATA_OWNERSHIP.md`](DATA_OWNERSHIP.md) — source-of-truth matrix
- [`EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md) — event catalog and delivery semantics
- [`ADR_INDEX.md`](ADR_INDEX.md) — architecture decision records
- [`../DEPLOYMENT_ORDER.md`](../DEPLOYMENT_ORDER.md) §8 — canonical implementation lifecycle registry (Graduated / Stub per service); the per-service `STATUS.md` "Implementation lifecycle" section is composed from this
