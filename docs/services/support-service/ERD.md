# support-service — Entity-Relationship Diagram

## 1. Database

- **Engine**: PostgreSQL 18.
- **Schema**: `support` — owned exclusively by this service.
- **Migrations**: `services/support-service/migrations/`
  (versioned, forward-only, golang-migrate).

The schema is the canonical source of truth for tickets,
conversations, escalations, and the agent action audit log.
Refunds and re-instatements are *initiated* here but
*executed* by other services.

## 2. Cross-Service References

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `user_id` | UUID | `Customer` / `Driver` / `Courier` / `Merchant` | each owner service |
| `customer_id` | UUID | `Customer` in `customer-service` | `customer-service` |
| `driver_id` | UUID | `Driver` in `driver-service` | `driver-service` |
| `courier_id` | UUID | `Courier` in `courier-service` | `courier-service` |
| `merchant_id` | UUID | `Merchant` in `merchant-service` | `merchant-service` |
| `trip_id` | UUID (nullable) | `Trip` in `trip-service` | `trip-service` |
| `order_id` | UUID (nullable) | `FoodOrder` in `food-order-service` | `food-order-service` |
| `payment_id` | UUID (nullable) | `PaymentIntent` in `payment-service` | `payment-service` |
| `delivery_id` | UUID (nullable) | `Delivery` in `delivery-service` | `delivery-service` |
| `file_id` | UUID (nullable) | `File` in `file-service` | `file-service` |
| `actor_sub` (audit) | UUID | Keycloak `sub` of agent | `identity-service` (Keycloak) |
| `correlation_id` (audit) | UUID | per request | gateway / caller |
| `co_signer_sub` (audit) | UUID | Keycloak `sub` of co-signer | `identity-service` (Keycloak) |

## 3. Entities

### `Ticket`

A support ticket.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `subject` | TEXT | NOT NULL | short summary |
| `body_encrypted` | BYTEA | NOT NULL | `pgcrypto` ciphertext (PII) |
| `category` | TEXT | NOT NULL | `safety`, `payment`, `trip`, `food`, `account`, `other` |
| `severity` | TEXT | NOT NULL | `P1` \| `P2` \| `P3` \| `P4` |
| `status` | TEXT | NOT NULL | `open`, `triaged`, `in_progress`, `awaiting_customer`, `awaiting_internal`, `escalated`, `resolved`, `closed` |
| `source` | TEXT | NOT NULL | `user`, `event`, `admin` |
| `user_id` | UUID | NULL | the user the ticket is about (nullable for event-driven tickets) |
| `user_type` | TEXT | NULL | `customer`, `driver`, `courier`, `merchant_staff` |
| `customer_id` | UUID | NULL | cross-ref |
| `driver_id` | UUID | NULL | cross-ref |
| `courier_id` | UUID | NULL | cross-ref |
| `merchant_id` | UUID | NULL | cross-ref |
| `trip_id` | UUID | NULL | cross-ref |
| `order_id` | UUID | NULL | cross-ref |
| `payment_id` | UUID | NULL | cross-ref |
| `delivery_id` | UUID | NULL | cross-ref |
| `assignee_sub` | UUID | NULL | the agent's Keycloak sub |
| `assignee_role` | TEXT | NULL | `support_agent_l1`, `support_agent_l2`, etc. |
| `sla_first_response_due_at` | TIMESTAMPTZ | NULL | computed from severity matrix |
| `first_responded_at` | TIMESTAMPTZ | NULL | when the first agent message was sent |
| `resolved_at` | TIMESTAMPTZ | NULL | |
| `closed_at` | TIMESTAMPTZ | NULL | 7 days after `resolved_at` |
| `reopen_until` | TIMESTAMPTZ | NULL | `resolved_at + 7 days` |
| `fraud_hold` | BOOLEAN | NOT NULL DEFAULT false | |
| `legal_hold` | BOOLEAN | NOT NULL DEFAULT false | |
| `metadata` | JSONB | NULL | free-form |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete (rare; usually retained) |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Indexes

- PK on `id`
- BTree on `(status, severity)` WHERE `deleted_at IS NULL`
- BTree on `(assignee_sub, status)` WHERE `assignee_sub IS NOT NULL AND deleted_at IS NULL`
- BTree on `user_id` WHERE `user_id IS NOT NULL`
- BTree on `customer_id` WHERE `customer_id IS NOT NULL`
- BTree on `trip_id` WHERE `trip_id IS NOT NULL`
- BTree on `order_id` WHERE `order_id IS NOT NULL`
- BTree on `payment_id` WHERE `payment_id IS NOT NULL`
- BTree on `sla_first_response_due_at` WHERE `status NOT IN ('resolved', 'closed') AND deleted_at IS NULL`
- BTree on `correlation_id` WHERE `source = 'event'`

#### Constraints

- CHECK: `severity IN ('P1','P2','P3','P4')`
- CHECK: `status IN ('open','triaged','in_progress','awaiting_customer','awaiting_internal','escalated','resolved','closed')`
- CHECK: `source IN ('user','event','admin')`
- CHECK: `user_type IS NULL OR user_type IN ('customer','driver','courier','merchant_staff')`
- CHECK: `legal_hold = false OR fraud_hold = false` (a ticket is either on legal hold or fraud hold, not both — both block action)

### `Conversation`

A message in a ticket conversation.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `ticket_id` | UUID | NOT NULL | FK to tickets (within schema) |
| `kind` | TEXT | NOT NULL | `user`, `agent`, `internal_note`, `system` |
| `author_sub` | UUID | NULL | Keycloak sub of the author (null for `system`) |
| `author_role` | TEXT | NULL | `customer`, `driver`, `support_agent_l1`, etc. |
| `body_encrypted` | BYTEA | NOT NULL | `pgcrypto` ciphertext |
| `attachment_ids` | UUID[] | NOT NULL DEFAULT '{}' | cross-ref to `file-service` |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- BTree on `(ticket_id, created_at)` for thread display
- BTree on `correlation_id` (the request that produced the
  message)

#### Constraints

- CHECK: `kind IN ('user','agent','internal_note','system')`
- CHECK: `(kind IN ('user','agent','internal_note') AND author_sub IS NOT NULL) OR (kind = 'system' AND author_sub IS NULL)`

### `Escalation`

A record of an escalation event.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `ticket_id` | UUID | NOT NULL | FK to tickets |
| `from_role` | TEXT | NOT NULL | `support_agent_l1` |
| `to_role` | TEXT | NOT NULL | `support_agent_l2`, `support_agent_l3`, `safety`, `fraud`, `finance` |
| `reason` | TEXT | NOT NULL | `sla_breach`, `manual`, `complexity` |
| `actor_sub` | UUID | NOT NULL | the agent who escalated |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |

#### Indexes

- PK on `id`
- BTree on `(ticket_id, created_at)`

#### Constraints

- CHECK: `from_role <> to_role`

### `Action` (audit, partitioned)

Every agent action (refund, reinstate, escalate, resolve,
etc.) is recorded here. **Append-only** at the application
layer; enforced by grants.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | NOT NULL | UUIDv7 |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `ticket_id` | UUID | NOT NULL | |
| `action` | TEXT | NOT NULL | `open`, `assign`, `message`, `escalate`, `refund_initiated`, `refund_completed`, `refund_failed`, `reinstate_initiated`, `reinstate_completed`, `reinstate_failed`, `resolve`, `reopen`, `close`, `fraud_hold`, `legal_hold` |
| `actor_sub` | UUID | NOT NULL | |
| `actor_role` | TEXT | NOT NULL | |
| `co_signer_sub` | UUID | NULL | for co-signed actions |
| `co_signer_signature` | TEXT | NULL | HMAC-SHA256 hex |
| `request_idempotency_key` | TEXT | NULL | |
| `payload` | JSONB | NOT NULL | the request body |
| `result` | TEXT | NOT NULL | `success`, `failure` |
| `error_code` | TEXT | NULL | |
| `correlation_id` | UUID | NOT NULL | |
| `refund_id` | UUID | NULL | cross-ref to the refund |
| `amount_minor` | BIGINT | NULL | for refund actions |
| `currency` | CHAR(3) | NULL | for refund actions |

#### Indexes

- BTree on `(ticket_id, occurred_at DESC)`
- BTree on `(actor_sub, occurred_at DESC)`
- BTree on `correlation_id`
- BTree on `refund_id` WHERE `refund_id IS NOT NULL`

#### Constraints

- CHECK: `action IN (...)` (allowed list)
- CHECK: `result IN ('success','failure')`
- CHECK: `(amount_minor IS NULL AND currency IS NULL) OR (amount_minor IS NOT NULL AND currency IS NOT NULL)`
- CHECK: `currency ~ '^[A-Z]{3}$'`

#### Partitioning

- Range-partitioned by `occurred_at`, monthly.
- Retention: 7y for financial, 1y for others.
- Drop partitions older than retention.

### `Refund`

A refund initiated from a ticket. The actual execution
happens in the payment integration services; this table
records the *decision* and the *result*.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `ticket_id` | UUID | NOT NULL | FK to tickets |
| `user_id` | UUID | NOT NULL | |
| `payment_id` | UUID | NOT NULL | cross-ref to `payment-service` |
| `amount_minor` | BIGINT | NOT NULL CHECK (amount_minor > 0) | |
| `currency` | CHAR(3) | NOT NULL | |
| `reason` | TEXT | NOT NULL | |
| `idempotency_key` | TEXT | NOT NULL UNIQUE | `ticket:<ticket_id>:refund:<N>` |
| `status` | TEXT | NOT NULL | `initiated`, `completed`, `failed` |
| `provider_reference` | TEXT | NULL | the payment provider's refund id |
| `failure_reason` | TEXT | NULL | |
| `actor_sub` | UUID | NOT NULL | |
| `co_signer_sub` | UUID | NULL | |
| `correlation_id` | UUID | NOT NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `completed_at` | TIMESTAMPTZ | NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- UNIQUE on `idempotency_key`
- BTree on `ticket_id`
- BTree on `payment_id`
- BTree on `user_id`

#### Constraints

- CHECK: `status IN ('initiated','completed','failed')`
- CHECK: `currency ~ '^[A-Z]{3}$'`

### `Reinstation`

An account re-instatement initiated from a ticket.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `ticket_id` | UUID | NOT NULL | |
| `user_id` | UUID | NOT NULL | |
| `user_type` | TEXT | NOT NULL | `customer`, `driver`, `courier`, `merchant_staff` |
| `reason` | TEXT | NOT NULL | |
| `idempotency_key` | TEXT | NOT NULL UNIQUE | `ticket:<ticket_id>:reinstate:<N>` |
| `status` | TEXT | NOT NULL | `initiated`, `completed`, `failed` |
| `failure_reason` | TEXT | NULL | |
| `actor_sub` | UUID | NOT NULL | |
| `co_signer_sub` | UUID | NOT NULL | co-signature required |
| `co_signer_signature` | TEXT | NOT NULL | HMAC-SHA256 hex |
| `correlation_id` | UUID | NOT NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `completed_at` | TIMESTAMPTZ | NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- UNIQUE on `idempotency_key`
- BTree on `ticket_id`

#### Constraints

- CHECK: `status IN ('initiated','completed','failed')`
- CHECK: `user_type IN ('customer','driver','courier','merchant_staff')`

### `DSAR`

A data subject access / erasure request.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `kind` | TEXT | NOT NULL | `access` \| `erasure` |
| `user_id` | UUID | NOT NULL | |
| `user_type` | TEXT | NOT NULL | |
| `idempotency_key` | TEXT | NOT NULL UNIQUE | |
| `status` | TEXT | NOT NULL | `received`, `in_progress`, `completed`, `failed` |
| `data_package_url` | TEXT | NULL | for `access` (signed URL, encrypted) |
| `completed_at` | TIMESTAMPTZ | NULL | |
| `deadline_at` | TIMESTAMPTZ | NOT NULL | `created_at + 30 days` |
| `actor_sub` | UUID | NOT NULL | the agent / system that handled it |
| `correlation_id` | UUID | NOT NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `notes` | JSONB | NULL | per-service progress notes |

#### Indexes

- PK on `id`
- UNIQUE on `idempotency_key`
- BTree on `user_id`
- BTree on `(status, deadline_at)` WHERE `status NOT IN ('completed','failed')`

#### Constraints

- CHECK: `kind IN ('access','erasure')`
- CHECK: `status IN ('received','in_progress','completed','failed')`
- CHECK: `(kind = 'access' AND data_package_url IS NOT NULL) OR (kind = 'erasure' AND data_package_url IS NULL)` on completion

### `Outbox` and `Inbox`

Standard outbox and inbox tables per `EVENT_ARCHITECTURE.md`.
See `geolocation-service/ERD.md` for the canonical DDL.

## 4. Mermaid ER Diagram

```mermaid
erDiagram
    Ticket ||--o{ Conversation : "has"
    Ticket ||--o{ Escalation : "escalated via"
    Ticket ||--o{ Action : "audited"
    Ticket ||--o{ Refund : "initiates"
    Ticket ||--o{ Reinstatement : "initiates"
    DSAR {
        uuid id PK
        text kind
        uuid user_id
        text status
        timestamptz deadline_at
    }
    Ticket {
        uuid id PK
        text subject
        bytea body_encrypted
        text category
        text severity
        text status
        text source
        uuid user_id FK_ref
        uuid customer_id FK_ref
        uuid trip_id FK_ref
        uuid order_id FK_ref
        uuid payment_id FK_ref
        uuid assignee_sub
        bool fraud_hold
        bool legal_hold
        int version
    }
    Conversation {
        uuid id PK
        uuid ticket_id FK
        text kind
        uuid author_sub
        bytea body_encrypted
        uuid_array attachment_ids
    }
    Escalation {
        uuid id PK
        uuid ticket_id FK
        text from_role
        text to_role
        text reason
        uuid actor_sub
    }
    Action {
        uuid id PK
        timestamptz occurred_at
        uuid ticket_id FK
        text action
        uuid actor_sub
        text result
        uuid correlation_id
    }
    Refund {
        uuid id PK
        uuid ticket_id FK
        uuid payment_id FK_ref
        bigint amount_minor
        text currency
        text status
        text idempotency_key UK
    }
    Reinstatement {
        uuid id PK
        uuid ticket_id FK
        uuid user_id FK_ref
        text user_type
        text status
        text idempotency_key UK
        uuid co_signer_sub
    }
```

## 5. DDL Sketch

```sql
CREATE SCHEMA IF NOT EXISTS support;
SET search_path = support, public;

CREATE TABLE support.tickets (
    id UUID PRIMARY KEY,
    subject TEXT NOT NULL,
    body_encrypted BYTEA NOT NULL,
    category TEXT NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('P1','P2','P3','P4')),
    status TEXT NOT NULL CHECK (status IN ('open','triaged','in_progress','awaiting_customer','awaiting_internal','escalated','resolved','closed')),
    source TEXT NOT NULL CHECK (source IN ('user','event','admin')),
    user_id UUID,
    user_type TEXT CHECK (user_type IS NULL OR user_type IN ('customer','driver','courier','merchant_staff')),
    customer_id UUID,
    driver_id UUID,
    courier_id UUID,
    merchant_id UUID,
    trip_id UUID,
    order_id UUID,
    payment_id UUID,
    delivery_id UUID,
    assignee_sub UUID,
    assignee_role TEXT,
    sla_first_response_due_at TIMESTAMPTZ,
    first_responded_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    reopen_until TIMESTAMPTZ,
    fraud_hold BOOLEAN NOT NULL DEFAULT false,
    legal_hold BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1,
    CHECK (legal_hold = false OR fraud_hold = false)
);
CREATE INDEX tickets_status_severity_idx
    ON support.tickets (status, severity) WHERE deleted_at IS NULL;
CREATE INDEX tickets_assignee_idx
    ON support.tickets (assignee_sub, status) WHERE assignee_sub IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX tickets_user_idx ON support.tickets (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX tickets_customer_idx ON support.tickets (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX tickets_trip_idx ON support.tickets (trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX tickets_order_idx ON support.tickets (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX tickets_payment_idx ON support.tickets (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX tickets_sla_due_idx
    ON support.tickets (sla_first_response_due_at)
    WHERE status NOT IN ('resolved','closed') AND deleted_at IS NULL;
CREATE INDEX tickets_correlation_idx
    ON support.tickets (correlation_id) WHERE source = 'event';

CREATE TABLE support.conversations (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support.tickets(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('user','agent','internal_note','system')),
    author_sub UUID,
    author_role TEXT,
    body_encrypted BYTEA NOT NULL,
    attachment_ids UUID[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1,
    CHECK ((kind IN ('user','agent','internal_note') AND author_sub IS NOT NULL)
        OR (kind = 'system' AND author_sub IS NULL))
);
CREATE INDEX conversations_ticket_created_idx
    ON support.conversations (ticket_id, created_at);
CREATE INDEX conversations_correlation_idx
    ON support.conversations (correlation_id);

CREATE TABLE support.escalations (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support.tickets(id) ON DELETE CASCADE,
    from_role TEXT NOT NULL,
    to_role TEXT NOT NULL,
    reason TEXT NOT NULL,
    actor_sub UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_role <> to_role)
);
CREATE INDEX escalations_ticket_created_idx
    ON support.escalations (ticket_id, created_at);

CREATE TABLE support.actions (
    id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ticket_id UUID NOT NULL,
    action TEXT NOT NULL,
    actor_sub UUID NOT NULL,
    actor_role TEXT NOT NULL,
    co_signer_sub UUID,
    co_signer_signature TEXT,
    request_idempotency_key TEXT,
    payload JSONB NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('success','failure')),
    error_code TEXT,
    correlation_id UUID NOT NULL,
    refund_id UUID,
    amount_minor BIGINT,
    currency CHAR(3) CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Idempotent pre-creation; safe to rerun as part of the maintenance job.
CREATE TABLE IF NOT EXISTS support.actions_2026_07
    PARTITION OF support.actions
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE INDEX actions_ticket_idx ON support.actions (ticket_id, occurred_at DESC);
CREATE INDEX actions_actor_idx ON support.actions (actor_sub, occurred_at DESC);
CREATE INDEX actions_correlation_idx ON support.actions (correlation_id);
CREATE INDEX actions_refund_idx ON support.actions (refund_id) WHERE refund_id IS NOT NULL;

CREATE TABLE support.refunds (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support.tickets(id),
    user_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reason TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('initiated','completed','failed')),
    provider_reference TEXT,
    failure_reason TEXT,
    actor_sub UUID NOT NULL,
    co_signer_sub UUID,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX refunds_ticket_idx ON support.refunds (ticket_id);
CREATE INDEX refunds_payment_idx ON support.refunds (payment_id);
CREATE INDEX refunds_user_idx ON support.refunds (user_id);

CREATE TABLE support.reinstatements (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support.tickets(id),
    user_id UUID NOT NULL,
    user_type TEXT NOT NULL CHECK (user_type IN ('customer','driver','courier','merchant_staff')),
    reason TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('initiated','completed','failed')),
    failure_reason TEXT,
    actor_sub UUID NOT NULL,
    co_signer_sub UUID NOT NULL,
    co_signer_signature TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX reinstatements_ticket_idx ON support.reinstatements (ticket_id);

CREATE TABLE support.dsars (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('access','erasure')),
    user_id UUID NOT NULL,
    user_type TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('received','in_progress','completed','failed')),
    data_package_url TEXT,
    completed_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    actor_sub UUID NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes JSONB
);
CREATE INDEX dsars_user_idx ON support.dsars (user_id);
CREATE INDEX dsars_deadline_idx ON support.dsars (status, deadline_at) WHERE status NOT IN ('completed','failed');
```

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`, `created_by`,
`updated_by`. `actions` is append-only.

## 7. Soft Delete

`tickets`, `conversations`, `escalations` use `deleted_at`.
Reads filter `WHERE deleted_at IS NULL`. Refunds,
reinstatements, DSARs, and actions are append-mostly.

## 8. JSONB Usage

| Table | Column | Justification |
|-------|--------|---------------|
| `tickets` | `metadata` | free-form operational tags |
| `conversations` | (n/a) | — |
| `actions` | `payload` | the request that triggered the action |
| `dsars` | `notes` | per-service progress notes |
| `outbox` / `inbox` | `payload` / `headers` | event body |

## 9. Partitioning

| Table | Partition strategy | Retention |
|-------|--------------------|-----------|
| `actions` | RANGE by `occurred_at`, monthly | 7y financial, 1y others |

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| `tickets` | 7y (financial) / 1y (others) | hard delete after retention |
| `conversations` | cascading on ticket hard delete | cascade |
| `escalations` | cascading | cascade |
| `actions` | 7y / 1y | partition drop |
| `refunds` | 7y (financial) | hard delete after retention |
| `reinstatements` | 7y (financial) | hard delete after retention |
| `dsars` | 7y (financial) / 1y (others) | hard delete after retention |
| `outbox` | 24h after publish | partition drop |
| `inbox` | 7d | hard delete |

## 11. Migration Considerations

- **Adding a new severity level** is a config change (no
  schema change).
- **Adding a new role** is a config change.
- **Adding a new action type** requires a migration to
  update the CHECK constraint.
- **Adding a new ticket category** is a config change.
- **Refund table** stores the decision; the actual refund
  is in the payment integration service. The two are
  reconciled via `payment.refund.*.v1` events.

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

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

