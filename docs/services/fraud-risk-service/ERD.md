# fraud-risk-service — Entity-Relationship Diagram

## 1. Database

- **Engine**: PostgreSQL 19.
- **Schema**: `fraud_risk` — owned exclusively by this service.
- **Migrations**: `services/fraud-risk-service/migrations/`
  (versioned, forward-only).

The schema is the canonical source of truth for scores,
blocklists, device fingerprints, and the model registry.
Model artifacts live in S3; the schema stores only metadata.

## 2. Cross-Service References

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `user_id` | UUID | `Customer` / `Driver` / `Courier` / `Merchant` | each owner service |
| `payment_id` | UUID | `PaymentIntent` in `payment-service` | `payment-service` |
| `trip_id` | UUID | `Trip` in `trip-service` | `trip-service` |
| `actor_sub` (audit) | UUID | Keycloak `sub` of admin | `identity-service` (Keycloak) |
| `correlation_id` | UUID | per request | gateway / caller |
| `tenant_id` | UUID | multi-tenant blocklist isolation | `identity-service` |

## 3. Entities

### `Score`

A single risk score and its decision.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `event_type` | TEXT | NOT NULL | `login`, `payment`, `dispatch` |
| `user_id` | UUID | NULL | cross-ref |
| `payment_id` | UUID | NULL | cross-ref |
| `trip_id` | UUID | NULL | cross-ref |
| `tenant_id` | UUID | NULL | for multi-tenant |
| `score` | NUMERIC(4,3) | NOT NULL CHECK (score >= 0 AND score <= 1) | |
| `decision` | TEXT | NOT NULL | `allow`, `challenge`, `block` |
| `model_id` | UUID | NOT NULL | FK to models (within schema) |
| `model_version` | INT | NOT NULL | the model version |
| `reason_codes` | TEXT[] | NOT NULL DEFAULT '{}' | e.g. `["blocklist_hit:ip", "velocity_breach:payment"]` |
| `context` | JSONB | NOT NULL | the input context (encrypted PII) |
| `latency_ms` | INT | NOT NULL | model + feature fetch |
| `correlation_id` | UUID | NOT NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `deleted_at` | TIMESTAMPTZ | NULL | for right-to-erasure |

#### Indexes

- PK on `id`
- BTree on `(event_type, created_at DESC)`
- BTree on `(user_id, created_at DESC)` WHERE `user_id IS NOT NULL`
- BTree on `payment_id` WHERE `payment_id IS NOT NULL`
- BTree on `model_id, created_at DESC`
- BTree on `correlation_id`

#### Constraints

- CHECK: `event_type IN ('login','payment','dispatch')`
- CHECK: `decision IN ('allow','challenge','block')`

#### Partitioning

- Range-partitioned by `created_at`, monthly.
- Retention: 1y; partition dropped.
- Right-to-erasure: `UPDATE … SET deleted_at = now() WHERE user_id = ?`.

### `DeviceFingerprint`

A cached device fingerprint.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `fingerprint_hash` | TEXT | NOT NULL UNIQUE | SHA-256 of the device attributes |
| `user_id` | UUID | NULL | the most recent user (denormalized) |
| `device_attributes` | JSONB | NOT NULL | OS, browser, screen, etc. (PII; encrypted) |
| `first_seen_at` | TIMESTAMPTZ | NOT NULL | |
| `last_seen_at` | TIMESTAMPTZ | NOT NULL | |
| `seen_count` | INT | NOT NULL DEFAULT 1 | |
| `trust_score` | NUMERIC(4,3) | NULL | derived; e.g. consistent vs. inconsistent |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | right-to-erasure |

#### Indexes

- PK on `id`
- UNIQUE on `fingerprint_hash`
- BTree on `user_id` WHERE `user_id IS NOT NULL`
- BTree on `last_seen_at` (LRU eviction)

#### Constraints

- CHECK: `seen_count >= 1`
- CHECK: `trust_score IS NULL OR (trust_score >= 0 AND trust_score <= 1)`

### `Blocklist`

A blocklist entry (email, phone, IP, device, card BIN,
region).

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `type` | TEXT | NOT NULL | `email`, `phone`, `ip`, `device`, `card_bin`, `region` |
| `value_hash` | TEXT | NOT NULL | SHA-256 hex (for fast lookup) |
| `value_encrypted` | BYTEA | NOT NULL | `pgcrypto` ciphertext (PII) |
| `reason` | TEXT | NOT NULL | |
| `severity` | TEXT | NOT NULL | `low`, `medium`, `high`, `critical` |
| `tenant_id` | UUID | NULL | multi-tenant |
| `source` | TEXT | NOT NULL | `admin`, `auto`, `analyst` |
| `expires_at` | TIMESTAMPTZ | NULL | null = permanent |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete (allowlist override) |

#### Indexes

- PK on `id`
- UNIQUE on `(type, value_hash, tenant_id) WHERE deleted_at IS NULL`
- BTree on `expires_at` WHERE `expires_at IS NOT NULL`

#### Constraints

- CHECK: `type IN ('email','phone','ip','device','card_bin','region')`
- CHECK: `severity IN ('low','medium','high','critical')`
- CHECK: `length(reason) > 0`

### `Model`

A scoring model.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `name` | TEXT | NOT NULL | `login_v3`, `payment_v2` |
| `event_type` | TEXT | NOT NULL | `login`, `payment`, `dispatch` |
| `version` | INT | NOT NULL | |
| `artifact_s3_path` | TEXT | NOT NULL | |
| `artifact_sha256` | TEXT | NOT NULL | for integrity check |
| `signature` | TEXT | NOT NULL | HMAC-SHA256 with Vault key |
| `metrics` | JSONB | NULL | precision, recall, F1, AUC, FPR |
| `status` | TEXT | NOT NULL | `draft`, `staging`, `active`, `retired` |
| `traffic_percentage` | INT | NOT NULL DEFAULT 0 CHECK (traffic_percentage BETWEEN 0 AND 100) | for A/B |
| `deployed_at` | TIMESTAMPTZ | NULL | |
| `retired_at` | TIMESTAMPTZ | NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |

#### Indexes

- PK on `id`
- UNIQUE on `(name, version)`
- BTree on `(event_type, status)` WHERE `deleted_at IS NULL`

#### Constraints

- CHECK: `event_type IN ('login','payment','dispatch')`
- CHECK: `status IN ('draft','staging','active','retired')`
- CHECK: `length(artifact_sha256) = 64`

### `Evaluation`

A model evaluation result (offline or shadow).

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `model_id` | UUID | NOT NULL | |
| `event_type` | TEXT | NOT NULL | |
| `kind` | TEXT | NOT NULL | `offline`, `shadow`, `ab` |
| `dataset` | TEXT | NOT NULL | `train`, `test`, `holdout`, `production_sample` |
| `metrics` | JSONB | NOT NULL | precision, recall, F1, AUC, FPR, etc. |
| `sample_size` | INT | NOT NULL | |
| `evaluated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |

#### Indexes

- PK on `id`
- BTree on `(model_id, evaluated_at DESC)`

#### Constraints

- CHECK: `kind IN ('offline','shadow','ab')`
- CHECK: `event_type IN ('login','payment','dispatch')`
- CHECK: `sample_size > 0`

### `Action` (audit, partitioned)

Every block, every allowlist override, every model deploy
is recorded here. **Append-only** at the application layer.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | NOT NULL | UUIDv7 |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `action` | TEXT | NOT NULL | `block`, `allowlist`, `blocklist_add`, `blocklist_remove`, `model_deploy`, `model_retire` |
| `target_type` | TEXT | NULL | `user`, `card`, `device`, `ip`, `email`, `phone`, `model` |
| `target_id` | UUID | NULL | cross-ref or model_id |
| `actor_sub` | UUID | NOT NULL | |
| `co_signer_sub` | UUID | NULL | |
| `co_signer_signature` | TEXT | NULL | |
| `payload` | JSONB | NOT NULL | |
| `result` | TEXT | NOT NULL | `success`, `failure` |
| `correlation_id` | UUID | NOT NULL | |
| `request_idempotency_key` | TEXT | NULL | |

#### Indexes

- BTree on `(occurred_at DESC, action)`
- BTree on `target_id` WHERE `target_id IS NOT NULL`
- BTree on `actor_sub`

#### Constraints

- CHECK: `action IN (...)` (allowed list)
- CHECK: `result IN ('success','failure')`

#### Partitioning

- Range-partitioned by `occurred_at`, monthly.
- Retention: 1y; partition dropped.

### `VelocityCounter`

A velocity counter (per IP, phone, email, card, device per
window). Maintained in Redis in the hot path; this table
is a periodic snapshot for analytics.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `key` | TEXT | NOT NULL | `payment:card:<bin>:<last4>` |
| `window` | TEXT | NOT NULL | `minute`, `hour`, `day` |
| `count` | INT | NOT NULL | |
| `window_started_at` | TIMESTAMPTZ | NOT NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |

#### Indexes

- PK on `id`
- BTree on `(key, window, window_started_at DESC)`

### `Outbox` and `Inbox`

Standard outbox and inbox tables per `EVENT_ARCHITECTURE.md`.
See `geolocation-service/ERD.md` for the canonical DDL.

## 4. Mermaid ER Diagram

```mermaid
erDiagram
    Model ||--o{ Score : "produced by"
    Model ||--o{ Evaluation : "evaluated"
    Blocklist ||--o{ Score : "matched"
    VelocityCounter ||--o{ Score : "checked"
    Score {
        uuid id PK
        text event_type
        uuid user_id FK_ref
        uuid payment_id FK_ref
        uuid trip_id FK_ref
        numeric score
        text decision
        uuid model_id FK
        text_array reason_codes
        jsonb context
        timestamptz created_at
    }
    DeviceFingerprint {
        uuid id PK
        text fingerprint_hash UK
        uuid user_id FK_ref
        jsonb device_attributes
        numeric trust_score
    }
    Blocklist {
        uuid id PK
        text type
        text value_hash
        bytea value_encrypted
        text reason
        text severity
        uuid tenant_id FK_ref
    }
    Model {
        uuid id PK
        text name
        text event_type
        int version
        text artifact_s3_path
        text artifact_sha256
        text signature
        text status
        int traffic_percentage
    }
    Evaluation {
        uuid id PK
        uuid model_id FK
        text event_type
        text kind
        text dataset
        jsonb metrics
        int sample_size
    }
    Action {
        uuid id PK
        timestamptz occurred_at
        text action
        text target_type
        uuid target_id FK_ref
        uuid actor_sub
        text result
    }
```

## 5. DDL Sketch

```sql
CREATE SCHEMA IF NOT EXISTS fraud_risk;
SET search_path = fraud_risk, public;

CREATE TABLE fraud_risk.scores (
    id UUID NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('login','payment','dispatch')),
    user_id UUID,
    payment_id UUID,
    trip_id UUID,
    tenant_id UUID,
    score NUMERIC(4,3) NOT NULL CHECK (score >= 0 AND score <= 1),
    decision TEXT NOT NULL CHECK (decision IN ('allow','challenge','block')),
    model_id UUID NOT NULL,
    model_version INT NOT NULL,
    reason_codes TEXT[] NOT NULL DEFAULT '{}',
    context JSONB NOT NULL,
    latency_ms INT NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX scores_event_type_created_idx
    ON fraud_risk.scores (event_type, created_at DESC);
CREATE INDEX scores_user_idx
    ON fraud_risk.scores (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;
CREATE INDEX scores_payment_idx
    ON fraud_risk.scores (payment_id)
    WHERE payment_id IS NOT NULL;
CREATE INDEX scores_model_idx
    ON fraud_risk.scores (model_id, created_at DESC);
CREATE INDEX scores_correlation_idx
    ON fraud_risk.scores (correlation_id);

CREATE TABLE IF NOT EXISTS fraud_risk.scores_2026_07
    PARTITION OF fraud_risk.scores
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

-- Verify the child is actually attached to the correct parent with
-- the expected bounds. IF NOT EXISTS only guards the name; it does
-- not verify bounds.
DO $$
DECLARE
    v_parent   REGCLASS := 'fraud_risk.scores'::REGCLASS;
    v_child    REGCLASS := 'fraud_risk.scores_2026_07'::REGCLASS;
    v_expected TSTZRANGE := tstzrange('2026-07-01 00:00:00+00',
                                      '2026-08-01 00:00:00+00',
                                      '[)');
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
    IF NOT (SELECT relpartbound FROM pg_class WHERE oid = v_child)
              = v_expected THEN
        RAISE EXCEPTION 'partition % has unexpected bounds', v_child::text;
    END IF;
END $$;

CREATE TABLE fraud_risk.device_fingerprints (
    id UUID PRIMARY KEY,
    fingerprint_hash TEXT NOT NULL UNIQUE,
    user_id UUID,
    device_attributes JSONB NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    seen_count INT NOT NULL DEFAULT 1 CHECK (seen_count >= 1),
    trust_score NUMERIC(4,3) CHECK (trust_score IS NULL OR (trust_score >= 0 AND trust_score <= 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX device_user_idx
    ON fraud_risk.device_fingerprints (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX device_last_seen_idx
    ON fraud_risk.device_fingerprints (last_seen_at);

CREATE TABLE fraud_risk.blocklists (
    id UUID PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('email','phone','ip','device','card_bin','region')),
    value_hash TEXT NOT NULL,
    value_encrypted BYTEA NOT NULL,
    reason TEXT NOT NULL CHECK (length(reason) > 0),
    severity TEXT NOT NULL CHECK (severity IN ('low','medium','high','critical')),
    tenant_id UUID,
    source TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX blocklists_type_value_tenant_uk
    ON fraud_risk.blocklists (type, value_hash, tenant_id)
    WHERE deleted_at IS NULL;
CREATE INDEX blocklists_expires_idx
    ON fraud_risk.blocklists (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE fraud_risk.models (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('login','payment','dispatch')),
    version INT NOT NULL,
    artifact_s3_path TEXT NOT NULL,
    artifact_sha256 TEXT NOT NULL CHECK (length(artifact_sha256) = 64),
    signature TEXT NOT NULL,
    metrics JSONB,
    status TEXT NOT NULL CHECK (status IN ('draft','staging','active','retired')),
    traffic_percentage INT NOT NULL DEFAULT 0 CHECK (traffic_percentage BETWEEN 0 AND 100),
    deployed_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    UNIQUE (name, version)
);
CREATE INDEX models_event_status_idx
    ON fraud_risk.models (event_type, status) WHERE deleted_at IS NULL;

CREATE TABLE fraud_risk.evaluations (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL REFERENCES fraud_risk.models(id),
    event_type TEXT NOT NULL CHECK (event_type IN ('login','payment','dispatch')),
    kind TEXT NOT NULL CHECK (kind IN ('offline','shadow','ab')),
    dataset TEXT NOT NULL,
    metrics JSONB NOT NULL,
    sample_size INT NOT NULL CHECK (sample_size > 0),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL
);
CREATE INDEX evaluations_model_idx
    ON fraud_risk.evaluations (model_id, evaluated_at DESC);

CREATE TABLE fraud_risk.actions (
    id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    action TEXT NOT NULL,
    target_type TEXT,
    target_id UUID,
    actor_sub UUID NOT NULL,
    co_signer_sub UUID,
    co_signer_signature TEXT,
    payload JSONB NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('success','failure')),
    correlation_id UUID NOT NULL,
    request_idempotency_key TEXT,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX actions_action_idx ON fraud_risk.actions (occurred_at DESC, action);
CREATE INDEX actions_target_idx ON fraud_risk.actions (target_id) WHERE target_id IS NOT NULL;
CREATE INDEX actions_actor_idx ON fraud_risk.actions (actor_sub);

CREATE TABLE IF NOT EXISTS fraud_risk.actions_2026_07
    PARTITION OF fraud_risk.actions
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
```

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`, `created_by`,
`updated_by`. `actions` is append-only.

## 7. Soft Delete

`models`, `blocklists`, `device_fingerprints` use
`deleted_at`. `scores` uses `deleted_at` for right-to-erasure
(soft delete of per-user data).

## 8. JSONB Usage

| Table | Column | Justification |
|-------|--------|---------------|
| `scores` | `context` | the input context (PII; encrypted) |
| `scores` | `reason_codes` | (actually TEXT[], not JSONB) |
| `models` | `metrics` | precision, recall, F1, AUC, FPR; rare read |
| `evaluations` | `metrics` | same |
| `device_fingerprints` | `device_attributes` | OS, browser, screen, etc. (PII; encrypted) |
| `actions` | `payload` | the request body |

## 9. Partitioning

| Table | Partition strategy | Retention |
|-------|--------------------|-----------|
| `scores` | RANGE by `created_at`, monthly | 1y |
| `actions` | RANGE by `occurred_at`, monthly | 1y |

See [`DATABASE_ARCHITECTURE.md` "Table Partitioning — Canonical Template"](../../architecture/DATABASE_ARCHITECTURE.md) for the idempotent `CREATE TABLE IF NOT EXISTS … PARTITION OF …` pattern, naming convention, and the service-owned maintenance-job contract (advisory lock, verification, retention/mixed-retention handling).

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| `scores` | 1y | partition drop; right-to-erasure sets `deleted_at` |
| `device_fingerprints` | 1y (LRU eviction) | background job |
| `blocklists` | indefinite (soft delete) | hard delete after expiry + 30d |
| `models` | indefinite | retired models soft-deleted |
| `evaluations` | indefinite | hard delete after 2y |
| `actions` | 1y | partition drop |
| `velocity_counters` | 1y | partition drop |
| `outbox` | 24h after publish | partition drop |
| `inbox` | 7d | hard delete |

## 11. Migration Considerations

- **Adding a new event type** is a config + schema change
  (update CHECK constraints; add a column to `scores`
  for the event-type-specific context if needed).
- **Adding a new model** is a S3 upload + a row in `models`;
  the deploy endpoint hot-swaps.
- **Right-to-erasure** must update `scores.deleted_at`
  (and `blocklists.deleted_at` if user-specific) — this
  is a multi-row update; the user is anonymized, not
  removed.
- **Blocklist hot path** uses Redis; PostgreSQL is the
  source of truth. A migration that changes the
  `value_hash` algorithm requires a re-hash of all rows
  (background job).

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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

## Related docs

- [`../../architecture/DATA_OWNERSHIP.md`](../../architecture/DATA_OWNERSHIP.md) — full source-of-truth matrix
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — how this service handles a downstream outage
- [`../../architecture/DATABASE_ARCHITECTURE.md`](../../architecture/DATABASE_ARCHITECTURE.md) — PostgreSQL-per-service rules
- [`../../architecture/CONSISTENCY_STRATEGY.md`](../../architecture/CONSISTENCY_STRATEGY.md) — strong vs eventual consistency per context

