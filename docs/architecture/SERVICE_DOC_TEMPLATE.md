# Service Documentation Template

This template is the contract for every `services/<service>/` folder.
Every active service in the **20-service catalog** (per
[ADR-0017](adrs/0017-20-service-architecture.md)) MUST produce these
six files following this structure exactly. The level of detail is
"a backend team can begin implementation from the documentation."

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

- Runtime: <Node 20 / Go 1.22 / Java 21 / etc.>
- Database: PostgreSQL 18 (per-service schema `<schema_name>`)
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

- Engine: PostgreSQL 18
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
