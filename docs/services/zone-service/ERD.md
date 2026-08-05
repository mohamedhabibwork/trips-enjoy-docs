# zone-service — Entity-Relationship Diagram

## 1. Database

- **Engine**: PostgreSQL 18 with PostGIS 3.4 extension.
- **Schema**: `zone` — owned exclusively by this service.
- **Migrations**: `services/zone-service/migrations/` (versioned,
  forward-only, golang-migrate; reviewed in PR; no destructive
  migrations without a multi-step plan).

The schema is the **canonical** source of truth for cities,
service zones, surge zones, restricted zones, and zone hours.
No other service writes here; other services read via REST or
consume `zone.*.updated.v1` events.

## 2. Cross-Service References

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `tenant_id` | UUID | `Tenant` in `identity-service` | `identity-service` |
| `actor_sub` (audit) | UUID | Keycloak `sub` of admin actor | `identity-service` (Keycloak) |
| `correlation_id` (audit) | UUID | per request | gateway / caller |
| `idempotency_key` (audit) | TEXT | client-supplied | caller |

## 3. Entities

### `City`

The top-level administrative unit. Every zone belongs to a city.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `tenant_id` | UUID | NOT NULL | cross-ref |
| `name` | TEXT | NOT NULL | display name (en) |
| `name_i18n` | JSONB | NOT NULL | localized names; e.g. `{"en":"Riyadh","ar":"الرياض"}` |
| `country_code` | CHAR(2) | NOT NULL | ISO 3166-1 alpha-2 |
| `timezone` | TEXT | NOT NULL | IANA tz |
| `currency` | CHAR(3) | NOT NULL | ISO 4217 |
| `polygon` | `geometry(Polygon, 4326)` | NOT NULL | city boundary |
| `supported_verticals` | TEXT[] | NOT NULL | e.g. `{ride, food}` |
| `status` | TEXT | NOT NULL | `active` \| `suspended` \| `retired` |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Indexes

- PK on `id`
- GIST on `polygon`
- BTree on `country_code`
- BTree on `status` WHERE `deleted_at IS NULL`
- BTree on `tenant_id`

#### Constraints

- CHECK: `status IN ('active', 'suspended', 'retired')`
- CHECK: `country_code ~ '^[A-Z]{2}$'`
- CHECK: `currency ~ '^[A-Z]{3}$'`
- CHECK: `ST_IsValid(polygon)` — enforced via trigger

### `ServiceZone`

A polygon inside a city where a vertical is allowed.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `city_id` | UUID | NOT NULL | cross-ref (UUID, no FK) |
| `name` | TEXT | NOT NULL | display name |
| `vertical` | TEXT | NOT NULL | `ride` \| `food` \| `parcel` |
| `polygon` | `geometry(Polygon, 4326)` | NOT NULL | |
| `allowed_ride_types` | TEXT[] | NULL | nullable; null = all |
| `max_concurrent_rides` | INT | NULL | nullable; null = unlimited |
| `metadata` | JSONB | NULL | free-form metadata (e.g. "airport_zone", "downtown") |
| `status` | TEXT | NOT NULL | `draft` \| `active` \| `suspended` \| `retired` |
| `activate_at` | TIMESTAMPTZ | NULL | for draft promotion |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- BTree on `city_id` WHERE `deleted_at IS NULL`
- GIST on `polygon`
- BTree on `status` WHERE `deleted_at IS NULL`
- BTree on `vertical` WHERE `deleted_at IS NULL`

#### Constraints

- CHECK: `vertical IN ('ride', 'food', 'parcel')`
- CHECK: `status IN ('draft', 'active', 'suspended', 'retired')`
- CHECK: `ST_IsValid(polygon)` and `ST_Within(polygon, city.polygon)` — enforced via trigger on INSERT/UPDATE; city polygon is loaded in the trigger via a subquery

### `SurgeZone`

A polygon inside a city where a multiplier applies.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `city_id` | UUID | NOT NULL | |
| `name` | TEXT | NOT NULL | |
| `polygon` | `geometry(Polygon, 4326)` | NOT NULL | |
| `multiplier` | NUMERIC(4,2) | NOT NULL CHECK (multiplier >= 1.00 AND multiplier <= 99.99) | |
| `time_windows` | JSONB | NOT NULL | `[{weekday, opens_at, closes_at}, …]` |
| `priority` | INT | NOT NULL DEFAULT 100 | lower = higher priority for overlap resolution |
| `status` | TEXT | NOT NULL | `active` \| `suspended` \| `retired` |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- BTree on `city_id` WHERE `deleted_at IS NULL`
- GIST on `polygon`
- BTree on `status` WHERE `deleted_at IS NULL`

#### Constraints

- CHECK: `status IN ('active', 'suspended', 'retired')`
- CHECK: `ST_IsValid(polygon)`
- App-level: `multiplier <= zone.surge.max_multiplier` (config-driven; default 10)

### `RestrictedZone`

A polygon inside a city with a restriction type.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `city_id` | UUID | NOT NULL | |
| `name` | TEXT | NOT NULL | |
| `polygon` | `geometry(Polygon, 4326)` | NOT NULL | |
| `type` | TEXT | NOT NULL | `no_pickup` \| `no_dropoff` \| `no_idle` \| `surge_only` |
| `reason` | TEXT | NOT NULL | free text, but not empty |
| `legal_hold` | BOOLEAN | NOT NULL DEFAULT false | |
| `time_windows` | JSONB | NOT NULL | `[{weekday, opens_at, closes_at}, …]`; null times = always |
| `status` | TEXT | NOT NULL | `active` \| `suspended` \| `retired` |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- BTree on `city_id` WHERE `deleted_at IS NULL`
- GIST on `polygon`
- BTree on `type` WHERE `deleted_at IS NULL AND status = 'active'`

#### Constraints

- CHECK: `type IN ('no_pickup', 'no_dropoff', 'no_idle', 'surge_only')`
- CHECK: `status IN ('active', 'suspended', 'retired')`
- CHECK: `ST_IsValid(polygon)`
- CHECK: `legal_hold = true → reason != ''` (reason is required for legal hold)

### `ZoneHours`

Per-weekday operating hours for a service zone.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `zone_id` | UUID | NOT NULL | FK to `service_zones.id` (within the same schema) |
| `weekday` | INT | NOT NULL | 0=Sun .. 6=Sat |
| `opens_at` | TIME | NOT NULL | local time (city timezone) |
| `closes_at` | TIME | NOT NULL CHECK (closes_at > opens_at) | |

#### Indexes

- PK on `id`
- BTree on `(zone_id, weekday)`

#### Constraints

- CHECK: `weekday BETWEEN 0 AND 6`

### `ZoneHolidayOverride`

Holiday calendar overrides for a service zone.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `zone_id` | UUID | NOT NULL | |
| `holiday_date` | DATE | NOT NULL | in the city timezone |
| `is_closed` | BOOLEAN | NOT NULL DEFAULT false | |
| `opens_at` | TIME | NULL | if not closed |
| `closes_at` | TIME | NULL | if not closed |

#### Indexes

- PK on `id`
- UNIQUE on `(zone_id, holiday_date)`

### `Region`

A grouping of cities (e.g. MENA, EU, US).

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `name` | TEXT | NOT NULL UNIQUE | e.g. `mena`, `eu`, `us-east` |
| `display_name` | TEXT | NOT NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | |

#### Indexes

- PK on `id`
- UNIQUE on `name`

### `CityRegion`

Many-to-many between cities and regions.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `city_id` | UUID | NOT NULL | PK (composite) |
| `region_id` | UUID | NOT NULL | PK (composite) |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |

#### Indexes

- PK on `(city_id, region_id)`
- BTree on `region_id`

### `ZoneAudit` (append-only, monthly partitioned)

Every zone edit is recorded here. **No UPDATE / DELETE allowed**
at the application layer; enforced by a row-level policy and
grants.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | NOT NULL | UUIDv7 |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `entity_type` | TEXT | NOT NULL | `City` \| `ServiceZone` \| `SurgeZone` \| `RestrictedZone` |
| `entity_id` | UUID | NOT NULL | the affected row |
| `action` | TEXT | NOT NULL | `create` \| `update` \| `delete` \| `retire` |
| `actor_sub` | UUID | NOT NULL | |
| `actor_role` | TEXT | NOT NULL | |
| `tenant_id` | UUID | NULL | |
| `before` | JSONB | NULL | snapshot before |
| `after` | JSONB | NULL | snapshot after |
| `correlation_id` | UUID | NOT NULL | |
| `request_idempotency_key` | TEXT | NULL | |
| `signature` | TEXT | NOT NULL | HMAC-SHA256 hex |

#### Indexes

- BTree on `(occurred_at DESC, entity_id)`
- BTree on `correlation_id`
- BTree on `actor_sub`

#### Partitioning

- Range-partitioned by `occurred_at`, monthly.
- Retention 7y; drop partitions older than 7y.

### `Outbox` and `Inbox`

Standard outbox and inbox tables per `EVENT_ARCHITECTURE.md`.
Schemas match the platform's standard pattern; see
`geolocation-service/ERD.md` for the canonical DDL.

## 4. Mermaid ER Diagram

```mermaid
erDiagram
    Region ||--o{ CityRegion : "groups"
    City ||--o{ CityRegion : "in"
    City ||--o{ ServiceZone : "has"
    City ||--o{ SurgeZone : "has"
    City ||--o{ RestrictedZone : "has"
    ServiceZone ||--o{ ZoneHours : "open"
    ServiceZone ||--o{ ZoneHolidayOverride : "override"
    ServiceZone ||--o{ ZoneAudit : "audited"
    SurgeZone ||--o{ ZoneAudit : "audited"
    RestrictedZone ||--o{ ZoneAudit : "audited"
    City ||--o{ ZoneAudit : "audited"

    City {
        uuid id PK
        text name
        text country_code
        text timezone
        char currency
        geometry polygon
        text_array supported_verticals
        text status
        uuid tenant_id FK_ref
        int version
    }
    ServiceZone {
        uuid id PK
        uuid city_id FK_ref
        text name
        text vertical
        geometry polygon
        text_array allowed_ride_types
        int max_concurrent_rides
        text status
        int version
    }
    SurgeZone {
        uuid id PK
        uuid city_id FK_ref
        text name
        geometry polygon
        numeric multiplier
        jsonb time_windows
        int priority
        text status
    }
    RestrictedZone {
        uuid id PK
        uuid city_id FK_ref
        text name
        geometry polygon
        text type
        text reason
        bool legal_hold
        jsonb time_windows
        text status
    }
    ZoneHours {
        uuid id PK
        uuid zone_id FK
        int weekday
        time opens_at
        time closes_at
    }
    ZoneHolidayOverride {
        uuid id PK
        uuid zone_id FK
        date holiday_date
        bool is_closed
        time opens_at
        time closes_at
    }
    Region {
        uuid id PK
        text name UK
        text display_name
    }
    CityRegion {
        uuid city_id PK_FK
        uuid region_id PK_FK
    }
    ZoneAudit {
        uuid id PK
        timestamptz occurred_at
        text entity_type
        uuid entity_id
        text action
        uuid actor_sub
        jsonb before
        jsonb after
        uuid correlation_id
    }
```

## 5. DDL Sketch

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS zone;
SET search_path = zone, public;

CREATE TABLE zone.cities (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name TEXT NOT NULL,
    name_i18n JSONB NOT NULL,
    country_code CHAR(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    timezone TEXT NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    polygon geometry(Polygon, 4326) NOT NULL,
    supported_verticals TEXT[] NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('active','suspended','retired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX cities_polygon_gist ON zone.cities USING GIST (polygon);
CREATE INDEX cities_country_code_idx ON zone.cities (country_code);
CREATE INDEX cities_status_active_idx ON zone.cities (status) WHERE deleted_at IS NULL;
CREATE INDEX cities_tenant_id_idx ON zone.cities (tenant_id);

CREATE TABLE zone.regions (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE zone.city_regions (
    city_id UUID NOT NULL,
    region_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (city_id, region_id)
);
CREATE INDEX city_regions_region_idx ON zone.city_regions (region_id);

CREATE TABLE zone.service_zones (
    id UUID PRIMARY KEY,
    city_id UUID NOT NULL,
    name TEXT NOT NULL,
    vertical TEXT NOT NULL CHECK (vertical IN ('ride','food','parcel')),
    polygon geometry(Polygon, 4326) NOT NULL,
    allowed_ride_types TEXT[],
    max_concurrent_rides INT,
    metadata JSONB,
    status TEXT NOT NULL CHECK (status IN ('draft','active','suspended','retired')),
    activate_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX service_zones_city_idx ON zone.service_zones (city_id) WHERE deleted_at IS NULL;
CREATE INDEX service_zones_polygon_gist ON zone.service_zones USING GIST (polygon);
CREATE INDEX service_zones_status_active_idx ON zone.service_zones (status) WHERE deleted_at IS NULL;
CREATE INDEX service_zones_vertical_active_idx ON zone.service_zones (vertical) WHERE deleted_at IS NULL;

CREATE TABLE zone.surge_zones (
    id UUID PRIMARY KEY,
    city_id UUID NOT NULL,
    name TEXT NOT NULL,
    polygon geometry(Polygon, 4326) NOT NULL,
    multiplier NUMERIC(4,2) NOT NULL CHECK (multiplier >= 1.00 AND multiplier <= 99.99),
    time_windows JSONB NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    status TEXT NOT NULL CHECK (status IN ('active','suspended','retired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX surge_zones_city_idx ON zone.surge_zones (city_id) WHERE deleted_at IS NULL;
CREATE INDEX surge_zones_polygon_gist ON zone.surge_zones USING GIST (polygon);
CREATE INDEX surge_zones_status_active_idx ON zone.surge_zones (status) WHERE deleted_at IS NULL;

CREATE TABLE zone.restricted_zones (
    id UUID PRIMARY KEY,
    city_id UUID NOT NULL,
    name TEXT NOT NULL,
    polygon geometry(Polygon, 4326) NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('no_pickup','no_dropoff','no_idle','surge_only')),
    reason TEXT NOT NULL CHECK (length(reason) > 0),
    legal_hold BOOLEAN NOT NULL DEFAULT false,
    time_windows JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('active','suspended','retired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX restricted_zones_city_idx ON zone.restricted_zones (city_id) WHERE deleted_at IS NULL;
CREATE INDEX restricted_zones_polygon_gist ON zone.restricted_zones USING GIST (polygon);
CREATE INDEX restricted_zones_type_active_idx ON zone.restricted_zones (type) WHERE deleted_at IS NULL AND status = 'active';

CREATE TABLE zone.zone_hours (
    id UUID PRIMARY KEY,
    zone_id UUID NOT NULL REFERENCES zone.service_zones(id) ON DELETE CASCADE,
    weekday INT NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL CHECK (closes_at > opens_at)
);
CREATE INDEX zone_hours_zone_weekday_idx ON zone.zone_hours (zone_id, weekday);

CREATE TABLE zone.zone_holiday_overrides (
    id UUID PRIMARY KEY,
    zone_id UUID NOT NULL REFERENCES zone.service_zones(id) ON DELETE CASCADE,
    holiday_date DATE NOT NULL,
    is_closed BOOLEAN NOT NULL DEFAULT false,
    opens_at TIME,
    closes_at TIME,
    UNIQUE (zone_id, holiday_date)
);

CREATE TABLE zone.zone_audit (
    id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    entity_type TEXT NOT NULL CHECK (entity_type IN ('City','ServiceZone','SurgeZone','RestrictedZone')),
    entity_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('create','update','delete','retire')),
    actor_sub UUID NOT NULL,
    actor_role TEXT NOT NULL,
    tenant_id UUID,
    before JSONB,
    after JSONB,
    correlation_id UUID NOT NULL,
    request_idempotency_key TEXT,
    signature TEXT NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Idempotent pre-creation; safe to rerun as part of the maintenance job.
CREATE TABLE IF NOT EXISTS zone.zone_audit_2026_07
    PARTITION OF zone.zone_audit
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE INDEX zone_audit_entity_idx ON zone.zone_audit (occurred_at DESC, entity_id);
CREATE INDEX zone_audit_correlation_idx ON zone.zone_audit (correlation_id);
CREATE INDEX zone_audit_actor_idx ON zone.zone_audit (actor_sub);
```

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`, `created_by`,
`updated_by`, `deleted_at`, `version`. `zone_audit` is
append-only.

## 7. Soft Delete

`City`, `ServiceZone`, `SurgeZone`, `RestrictedZone`,
`Region` all use `deleted_at`. Reads filter
`WHERE deleted_at IS NULL` (enforced by the repository
pattern). `ZoneHours` and `ZoneHolidayOverride` are
cascaded on hard delete only — soft-deleted zones keep
their hours for historical "what were the hours when the
zone was active" queries.

## 8. JSONB Usage

| Table | Column | Justification |
|-------|--------|---------------|
| `cities` | `name_i18n` | localized names; rare read with locale filter |
| `service_zones` | `metadata` | free-form operational tags |
| `surge_zones` | `time_windows` | structured but vendor-specific shape |
| `restricted_zones` | `time_windows` | same |
| `zone_audit` | `before`, `after` | audit snapshots |

No JSONB column is used in a hot `WHERE` clause.

## 9. Partitioning

| Table | Partition strategy | Retention |
|-------|--------------------|-----------|
| `zone_audit` | RANGE by `occurred_at`, monthly | 7y, then drop |

Other tables are not partitioned.

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| `cities`, `service_zones`, `surge_zones`, `restricted_zones`, `regions` | indefinite (soft delete only) | hard delete after legal review, 7y |
| `zone_hours`, `zone_holiday_overrides` | while zone is alive; cascading on hard delete | cascade |
| `zone_audit` | 7y | partition drop |
| `outbox` | 24h after publish | partition drop |
| `inbox` | 7 days | hard delete |

## 11. Migration Considerations

- **First migration** must `CREATE EXTENSION postgis` and
  `CREATE SCHEMA zone`.
- **Polygon triggers** are added in a follow-up migration
  after the cities table exists, so the trigger can
  `ST_Within(zone, city)` against the city row.
- **Adding a new polygon column** is fine; just remember
  to add a GIST index in the same migration.
- **GIST index creation** is non-blocking (`CREATE INDEX
  CONCURRENTLY`) but must run outside a transaction; the
  migration tool's `golang-migrate` with `IF NOT EXISTS`
  handles this.
- **Removing a city** is a multi-step plan: mark as retired,
  wait 30 days, then hard delete (cascades to all zones).
- **Holiday calendar data** is loaded from a fixture file
  via a one-off migration script, not a runtime API.

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

