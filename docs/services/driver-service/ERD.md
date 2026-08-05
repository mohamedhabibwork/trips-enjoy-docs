# driver-service — Entity-Relationship Diagram

## 1. Database

- **Engine**: PostgreSQL 18.
- **Schema**: `driver`.
- **Migrations**: `services/driver-service/migrations/`
  (versioned, forward-only, Flyway).

## 2. Cross-Service References

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `identity_id` (in `drivers`) | UUID | `Identity` in `identity-service` | `identity-service` |
| `primary_vehicle_id` | UUID | `Vehicle` in ``driver-service` (vehicles)` | ``driver-service` (vehicles)` |
| `kyc_verification_id` | UUID | KYC provider's verification | KYC provider |
| `background_check_verification_id` | UUID | Background-check provider's verification | Background-check provider |
| `document_file_id` (in `driver_documents`) | UUID | `File` in `file-service` | `file-service` |

All stored as UUID columns WITHOUT database FKs.

## 3. Entities

### `drivers`

The platform's driver aggregate. One row per driver.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `identity_id` | UUID | NOT NULL, UNIQUE | cross-service ref |
| `name` | TEXT | NULL (PII, column-level encrypted) | cached |
| `email` | TEXT | NULL (PII, column-level encrypted) | cached |
| `phone` | TEXT | NULL (PII, column-level encrypted) | cached |
| `primary_vehicle_id` | UUID | NULL | cross-service ref to ``driver-service` (vehicles)` |
| `kyc_verification_id` | UUID | NULL | provider's id |
| `kyc_verified_at` | TIMESTAMPTZ | NULL | when the current KYC was completed |
| `background_check_verification_id` | UUID | NULL | provider's id |
| `background_check_verified_at` | TIMESTAMPTZ | NULL | when the current background check was completed |
| `rating` | DECIMAL(3,2) | NOT NULL DEFAULT 0.0 | read-model |
| `rating_count` | INT | NOT NULL DEFAULT 0 | read-model |
| `rating_updated_at` | TIMESTAMPTZ | NULL | when the rating was last updated |
| `status` | TEXT | NOT NULL DEFAULT 'pending_review' | `pending_review` / `approved` / `rejected` / `suspended` / `inactive` / `erased` |
| `rejected_reason` | TEXT | NULL | reason for rejection |
| `suspended_reason` | TEXT | NULL | reason for suspension |
| `suspended_at` | TIMESTAMPTZ | NULL | when suspended |
| `suspended_by` | UUID | NULL | actor's identity_id |
| `disabled_at` | TIMESTAMPTZ | NULL | when disabled |
| `erased_at` | TIMESTAMPTZ | NULL | when GDPR-erased |
| `documents_warn` | BOOLEAN | NOT NULL DEFAULT false | true if any document in grace period |
| `last_online_at` | TIMESTAMPTZ | NULL | for `inactive` detection |
| `row_version` | BIGINT | NOT NULL DEFAULT 1 | optimistic-lock |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `created_by` | UUID | NOT NULL | identity |
| `updated_by` | UUID | NOT NULL | identity |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |

#### Indexes

- PK on `id`.
- UNIQUE on `identity_id` (partial, `WHERE deleted_at IS NULL`).
- Index on `status` (partial, `WHERE status IN ('pending_review', 'approved', 'suspended')`).
- Index on `primary_vehicle_id` (partial, `WHERE primary_vehicle_id IS NOT NULL`).
- Index on `documents_warn` (partial, `WHERE documents_warn = true`).

#### Constraints

- CHECK: `status IN ('pending_review', 'approved', 'rejected', 'suspended', 'inactive', 'erased')`.
- CHECK: `rating >= 0 AND rating <= 5`.

### `driver_documents`

The driver's KYC documents. Multiple rows per driver.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `driver_id` | UUID | NOT NULL | FK to `drivers.id` |
| `type` | TEXT | NOT NULL | `license` / `vehicle_reg` / `insurance` / `selfie` / `background_check` / `medical` / `permit` |
| `file_id` | UUID | NOT NULL | cross-service ref to `file-service` |
| `verification_id` | UUID | NULL | provider's id |
| `verified_at` | TIMESTAMPTZ | NULL | when the provider verified |
| `expiry_date` | TIMESTAMPTZ | NULL | nullable for non-expiring docs (selfie) |
| `critical` | BOOLEAN | NOT NULL DEFAULT true | whether auto-suspend applies on expiry |
| `status` | TEXT | NOT NULL DEFAULT 'pending' | `pending` / `verified` / `rejected` / `expired` |
| `rejected_reason` | TEXT | NULL | provider's reason |
| `row_version` | BIGINT | NOT NULL DEFAULT 1 | optimistic-lock |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `created_by` | UUID | NOT NULL | identity |
| `updated_by` | UUID | NOT NULL | identity |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |

#### Indexes

- PK on `id`.
- Index on `driver_id` (partial, `WHERE deleted_at IS NULL`).
- Index on `expiry_date` (partial, `WHERE deleted_at IS NULL AND expiry_date IS NOT NULL`) for the nightly expiry job.
- Index on `status` (partial, `WHERE status = 'verified' AND deleted_at IS NULL`).

#### Constraints

- CHECK: `type IN ('license', 'vehicle_reg', 'insurance', 'selfie', 'background_check', 'medical', 'permit')`.
- CHECK: `status IN ('pending', 'verified', 'rejected', 'expired')`.

### `driver_city_eligibility`

The driver's eligibility per city. Many-to-many.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `driver_id` | UUID | NOT NULL | FK to `drivers.id` |
| `city_id` | UUID | NOT NULL | cross-service ref to ``geolocation-service` (zones)` |
| `status` | TEXT | NOT NULL DEFAULT 'eligible' | `eligible` / `ineligible` / `pending_review` |
| `min_rating` | DECIMAL(3,2) | NULL | per-city override |
| `granted_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | when granted |
| `granted_by` | UUID | NULL | actor's identity_id |
| `revoked_at` | TIMESTAMPTZ | NULL | when revoked |
| `revoked_by` | UUID | NULL | actor's identity_id |
| `revoked_reason` | TEXT | NULL | reason |
| `row_version` | BIGINT | NOT NULL DEFAULT 1 | optimistic-lock |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |

#### Indexes

- PK on `id`.
- UNIQUE on `(driver_id, city_id)`.
- Index on `city_id` (for the dispatch eligibility check).
- Index on `status` (partial, `WHERE status = 'eligible'`).

#### Constraints

- CHECK: `status IN ('eligible', 'ineligible', 'pending_review')`.

### `driver_rating_history`

Append-only history of rating updates. Range-partitioned
by month.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | NOT NULL | UUIDv7 |
| `driver_id` | UUID | NOT NULL | FK to `drivers.id` |
| `rating` | DECIMAL(3,2) | NOT NULL | new rating |
| `rating_count` | INT | NOT NULL | new count |
| `delta` | DECIMAL(3,2) | NULL | change |
| `source` | TEXT | NOT NULL | `review.aggregated.v1` |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |

#### Partitioning

- Range partition by `occurred_at` (monthly).
- Pre-create next 30 days.
- Drop partitions older than 1 year (after archive).

### `driver_audit_log`

Append-only audit of every state change. Immutable.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `driver_id` | UUID | NOT NULL | FK to `drivers.id` |
| `action` | TEXT | NOT NULL | `create` / `update` / `approve` / `reject` / `suspend` / `reinstate` / `disable` / `erase` / `document_upload` / `document_verified` / `document_expired` / `eligibility_change` / `rating_update` |
| `actor` | UUID | NULL | actor's identity_id |
| `actor_type` | TEXT | NOT NULL | `user` / `admin` / `service` / `system` |
| `before` | JSONB | NULL | snapshot before |
| `after` | JSONB | NULL | snapshot after |
| `reason` | TEXT | NULL | reason code |
| `correlation_id` | UUID | NULL | request correlation id |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | when the action happened |

#### Constraints

- No `UPDATE` or `DELETE`.
- Retention 7 years.

### `outbox`

Outbox table for the outbox pattern. Same shape as
`identity-service.outbox`.

## 4. Mermaid ER Diagram

```mermaid
erDiagram
    DRIVERS ||--o{ DRIVER_DOCUMENTS : "has"
    DRIVERS ||--o{ DRIVER_CITY_ELIGIBILITY : "eligible in"
    DRIVERS ||--o{ DRIVER_RATING_HISTORY : "rating changes"
    DRIVERS ||--o{ DRIVER_AUDIT_LOG : "audited by"
    OUTBOX }o..o| DRIVERS : "aggregate_id -> id"

    DRIVERS {
        uuid id PK
        uuid identity_id
        text name
        text email
        text phone
        uuid primary_vehicle_id
        uuid kyc_verification_id
        uuid background_check_verification_id
        decimal rating
        int rating_count
        text status
        timestamptz suspended_at
        timestamptz erased_at
        boolean documents_warn
        timestamptz last_online_at
    }

    DRIVER_DOCUMENTS {
        uuid id PK
        uuid driver_id FK
        text type
        uuid file_id
        uuid verification_id
        timestamptz verified_at
        timestamptz expiry_date
        boolean critical
        text status
    }

    DRIVER_CITY_ELIGIBILITY {
        uuid id PK
        uuid driver_id FK
        uuid city_id
        text status
        decimal min_rating
        timestamptz granted_at
        timestamptz revoked_at
    }

    DRIVER_RATING_HISTORY {
        uuid id PK
        uuid driver_id FK
        decimal rating
        int rating_count
        text source
        timestamptz occurred_at
    }

    DRIVER_AUDIT_LOG {
        uuid id PK
        uuid driver_id FK
        text action
        uuid actor
        text actor_type
        jsonb before
        jsonb after
        text reason
        uuid correlation_id
        timestamptz occurred_at
    }

    OUTBOX {
        uuid id PK
        text aggregate_type
        uuid aggregate_id
        text topic
        text event_name
        jsonb payload
        jsonb headers
        timestamptz created_at
        timestamptz claimed_at
        timestamptz published_at
        int attempts
        text last_error
    }
```

## 5. DDL Sketch

```sql
CREATE SCHEMA IF NOT EXISTS driver;

CREATE TABLE driver.drivers (
    id UUID PRIMARY KEY,
    identity_id UUID NOT NULL,
    name TEXT,
    email TEXT,
    phone TEXT,
    primary_vehicle_id UUID,
    kyc_verification_id UUID,
    kyc_verified_at TIMESTAMPTZ,
    background_check_verification_id UUID,
    background_check_verified_at TIMESTAMPTZ,
    rating DECIMAL(3,2) NOT NULL DEFAULT 0.0,
    rating_count INT NOT NULL DEFAULT 0,
    rating_updated_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'pending_review',
    rejected_reason TEXT,
    suspended_reason TEXT,
    suspended_at TIMESTAMPTZ,
    suspended_by UUID,
    disabled_at TIMESTAMPTZ,
    erased_at TIMESTAMPTZ,
    documents_warn BOOLEAN NOT NULL DEFAULT false,
    last_online_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT drivers_status_check
        CHECK (status IN ('pending_review','approved','rejected','suspended','inactive','erased')),
    CONSTRAINT drivers_rating_check
        CHECK (rating >= 0 AND rating <= 5)
);

CREATE UNIQUE INDEX drivers_identity_id_uniq
    ON driver.drivers (identity_id)
    WHERE deleted_at IS NULL;

CREATE INDEX drivers_status_idx
    ON driver.drivers (status)
    WHERE status IN ('pending_review','approved','suspended');

CREATE INDEX drivers_primary_vehicle_id_idx
    ON driver.drivers (primary_vehicle_id)
    WHERE primary_vehicle_id IS NOT NULL;

CREATE INDEX drivers_documents_warn_idx
    ON driver.drivers (documents_warn)
    WHERE documents_warn = true;

CREATE TABLE driver.driver_documents (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL REFERENCES driver.drivers(id),
    type TEXT NOT NULL,
    file_id UUID NOT NULL,
    verification_id UUID,
    verified_at TIMESTAMPTZ,
    expiry_date TIMESTAMPTZ,
    critical BOOLEAN NOT NULL DEFAULT true,
    status TEXT NOT NULL DEFAULT 'pending',
    rejected_reason TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT driver_documents_type_check
        CHECK (type IN ('license','vehicle_reg','insurance','selfie','background_check','medical','permit')),
    CONSTRAINT driver_documents_status_check
        CHECK (status IN ('pending','verified','rejected','expired'))
);

CREATE INDEX driver_documents_driver_id_idx
    ON driver.driver_documents (driver_id)
    WHERE deleted_at IS NULL;

CREATE INDEX driver_documents_expiry_date_idx
    ON driver.driver_documents (expiry_date)
    WHERE deleted_at IS NULL AND expiry_date IS NOT NULL;

CREATE TABLE driver.driver_city_eligibility (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL REFERENCES driver.drivers(id),
    city_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'eligible',
    min_rating DECIMAL(3,2),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by UUID,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID,
    revoked_reason TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT driver_city_eligibility_status_check
        CHECK (status IN ('eligible','ineligible','pending_review'))
);

CREATE UNIQUE INDEX driver_city_eligibility_uniq
    ON driver.driver_city_eligibility (driver_id, city_id);

CREATE INDEX driver_city_eligibility_city_id_idx
    ON driver.driver_city_eligibility (city_id);

CREATE INDEX driver_city_eligibility_status_idx
    ON driver.driver_city_eligibility (status)
    WHERE status = 'eligible';

CREATE TABLE driver.driver_rating_history (
    id UUID NOT NULL,
    driver_id UUID NOT NULL REFERENCES driver.drivers(id),
    rating DECIMAL(3,2) NOT NULL,
    rating_count INT NOT NULL,
    delta DECIMAL(3,2),
    source TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Idempotent pre-creation; safe to rerun as part of the maintenance job.
CREATE TABLE IF NOT EXISTS driver.driver_rating_history_2026_07
    PARTITION OF driver.driver_rating_history
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE driver.driver_audit_log (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL REFERENCES driver.drivers(id),
    action TEXT NOT NULL,
    actor UUID,
    actor_type TEXT NOT NULL,
    before JSONB,
    after JSONB,
    reason TEXT,
    correlation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER driver_audit_log_no_update
    BEFORE UPDATE OR DELETE ON driver.driver_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION raise_exception();

CREATE TABLE driver.outbox (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    topic TEXT NOT NULL,
    event_name TEXT NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX outbox_unpublished_idx
    ON driver.outbox (created_at)
    WHERE published_at IS NULL;

CREATE INDEX outbox_aggregate_id_idx
    ON driver.outbox (aggregate_id);
```

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`,
`created_by`, `updated_by`. The `drivers`,
`driver_documents`, and `driver_city_eligibility`
tables also have `row_version` for optimistic
locking. The `driver_audit_log` is the source of
truth for audit; every state change writes there
AND emits the corresponding `driver.*.v1` event.

## 7. Soft Delete

- The `drivers` table uses soft delete (`deleted_at`).
- The `driver_documents` table uses soft delete;
  re-uploading a document of the same type is
  idempotent on the type (the old row is soft-deleted,
  the new row is created).

## 8. JSONB Usage

- `driver_audit_log.before` / `after` — snapshots.
- `outbox.payload` / `outbox.headers` — event
  envelope.

## 9. Partitioning

- `driver_rating_history` is range-partitioned by
  `occurred_at` (monthly).
- Pre-create the next 30 days of partitions.
- Drop partitions older than 1 year (after archive).

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| `drivers` | until erasure + 7 years (tombstone) | background job |
| `driver_documents` | until driver erased + 7 years | background job |
| `driver_city_eligibility` | until driver erased | background job |
| `driver_rating_history` | 1 year hot, then archived; 7 years total | partition drop after archive |
| `driver_audit_log` | 7 years (audit) | background job |
| `outbox` | 24 h after `published_at` | background job |

## 11. Migration Considerations

- Adding a new document type: add the value to the
  `driver_documents_type_check` constraint; add it
  to `driver.kyc.required_documents` in
  configuration; the nightly job picks it up.
- Renaming a `status` value: deprecated alias
  stored alongside; old code path reads the alias;
  new code reads the new value. Drop after a
  deprecation window.
- Cross-service references (`identity_id`,
  `primary_vehicle_id`) are added as nullable
  columns; the back-channel consumer populates
  them.

---

## Appendix A — Predecessor tables absorbed (dispatch + driver-availability + driver-location + driver-incentive)

The tables below were migrated from `dispatch.*`,
`driver_availability.*`, `driver_location.*`, and `driver_incentive.*`
as part of [ADR-0016](../../architecture/adrs/0016-service-domain-consolidation.md).
The canonical source is [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md)
§3.4, §3.5, §3.6, §3.7. The old schema names remain readable as views
in the `driver` schema for at least six months from 2026-08-05.

### A.1 Tables absorbed

| Old schema.table | New schema.table | Notes |
|------------------|------------------|-------|
| `dispatch.match_attempts` | `driver.match_attempts` | state machine `initiated → offered → accepted → committed \| no_driver` |
| `dispatch.match_offers` | `driver.match_offers` | append-only |
| `dispatch.city_config` | `driver.dispatch_city_config` | configuration snapshot |
| `driver_availability.online_state` | `driver.online_state` | state: `offline\|online\|busy\|paused` |
| `driver_availability.shifts` | `driver.shifts` | planned / actual start, planned end, break intervals |
| `driver_availability.accepted_ride_types` | `driver.accepted_ride_types` | per-driver accepted types |
| `driver_location.current_location` | `driver.current_location` | UPSERT by `driver_id` |
| `driver_location.locations` | `driver.location_trail` | RANGE on `recorded_at`, monthly |
| `driver_incentive.quests` | `driver.quests` | |
| `driver_incentive.bonuses` | `driver.bonuses` | |
| `driver_incentive.guarantees` | `driver.guarantees` | |
| `driver_incentive.eligibility_rules` | `driver.incentive_eligibility` | |
| `driver_incentive.accruals` | `driver.incentive_accruals` | RANGE on `accrued_at`, monthly |

### A.2 DDL sketch (migrated entities)

```sql
CREATE TABLE driver.match_attempts (
    id UUID PRIMARY KEY,
    ride_request_id UUID NOT NULL,
    driver_id UUID,
    city_id UUID NOT NULL,
    state TEXT NOT NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    CONSTRAINT match_attempts_state_chk CHECK (state IN
        ('initiated','offered','accepted','committed','no_driver','cancelled','failed'))
);

CREATE TABLE driver.match_offers (
    id UUID PRIMARY KEY,
    match_attempt_id UUID NOT NULL REFERENCES driver.match_attempts(id),
    driver_id UUID NOT NULL,
    sequence INT NOT NULL,
    outcome TEXT NOT NULL,
    offered_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    distance_meters INT NOT NULL,
    eta_seconds INT NOT NULL,
    CONSTRAINT match_offers_outcome_chk CHECK (outcome IN
        ('offered','accepted','rejected','expired'))
) PARTITION BY RANGE (offered_at);

CREATE TABLE driver.online_state (
    driver_id UUID PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('offline','online','busy','paused')),
    zone_id UUID,
    current_match_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE driver.shifts (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    planned_start TIMESTAMPTZ NOT NULL,
    planned_end TIMESTAMPTZ NOT NULL,
    actual_start TIMESTAMPTZ,
    actual_end TIMESTAMPTZ,
    break_intervals JSONB
);

CREATE TABLE driver.current_location (
    driver_id UUID PRIMARY KEY,
    city_id UUID NOT NULL,
    zone_id UUID,
    last_lat NUMERIC(9,6) NOT NULL,
    last_lng NUMERIC(9,6) NOT NULL,
    last_ping_at TIMESTAMPTZ NOT NULL,
    is_stale BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE driver.location_trail (
    driver_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    lat NUMERIC(9,6) NOT NULL,
    lng NUMERIC(9,6) NOT NULL,
    bearing NUMERIC(5,2),
    speed_mps NUMERIC(5,2),
    accuracy_m NUMERIC(6,2),
    PRIMARY KEY (driver_id, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE TABLE driver.quests (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    zone_id UUID NOT NULL,
    criteria JSONB NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    reward_minor BIGINT NOT NULL,
    currency TEXT NOT NULL
);

CREATE TABLE driver.incentive_accruals (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    trip_id UUID NOT NULL,
    type TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency TEXT NOT NULL,
    accrued_at TIMESTAMPTZ NOT NULL,
    idempotency_key TEXT UNIQUE NOT NULL
) PARTITION BY RANGE (accrued_at);
```

### A.3 Partitioning (predecessor)

| Table | Strategy | Cadence | Pre-create | Retention |
|-------|----------|---------|------------|-----------|
| `driver.match_offers` | RANGE on `offered_at` | monthly | 12 months | 3 years |
| `driver.location_trail` | RANGE on `recorded_at` | monthly | 12 months | 30 d hot; 1 y cold |
| `driver.incentive_accruals` | RANGE on `accrued_at` | monthly | 12 months | 3 years |

### A.4 Compatibility views (≥ 6 months)

```sql
CREATE VIEW dispatch.match_attempts AS TABLE driver.match_attempts;
CREATE VIEW dispatch.match_offers AS TABLE driver.match_offers;
CREATE VIEW driver_availability.online_state AS TABLE driver.online_state;
CREATE VIEW driver_availability.shifts AS TABLE driver.shifts;
CREATE VIEW driver_location.current_location AS TABLE driver.current_location;
CREATE VIEW driver_location.locations AS TABLE driver.location_trail;
CREATE VIEW driver_incentive.quests AS TABLE driver.quests;
CREATE VIEW driver_incentive.accruals AS TABLE driver.incentive_accruals;
```

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

