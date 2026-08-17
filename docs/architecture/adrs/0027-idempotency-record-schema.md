# ADR-0027: Idempotency record schema (canonical `(actor_id, idempotency_key)`)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: idempotency, contracts, data-model

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide canonical schema for the
> `idempotency` table. Every service's idempotency record MUST use
> the unique key `(actor_id, idempotency_key)` and the canonical
> column set. The 5 services that ship a local `Idempotency` or
> `IdempotencyRecord` entity adopt the platform entity in
> `platform-spring-boot-data`; their local tables are migrated
> forward via a per-service V__ Flyway migration.

## Context and Problem Statement

5 of 14 Kotlin services ship a local `Idempotency` entity with one
of three distinct schemas:

| Service | Table name | Schema | PK | Unique key |
|---|---|---|---|---|
| `configuration-service` | `idempotency` | `configuration` | `id UUID` | `(idempotency_key)` (UUID) |
| `customer-service` | `idempotency` | `customer` | `id UUID` | `(actor_id, idempotency_key)` |
| `notification-service` | `idempotency_records` | `notification` | `id UUID` | `(actor_id, idempotency_key)` |
| `payment-service` | `idempotency_keys` | `payment` | `id UUID` | `(scope, idem_key)` |
| `driver-service` | `idempotency_keys` | `driver` | `id UUID` | `(actor_id, idempotency_key)` |
| `restaurant-service` | `idempotency_keys` | `restaurant` | `id UUID` | `(actor_id, idempotency_key)` |

The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0023](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this as schema drift that must be resolved before
deleting the 5 redundant entities. The platform
`IdempotencyRecord` in `platform-spring-boot-data` already declares
the canonical schema with `(actor_id, idempotency_key)` unique
key — but 1 of 5 services uses `(scope, idem_key)` and 1 uses
`(idempotency_key)` alone.

The contract is load-bearing: every `@IdempotencyKey` annotation
or `Idempotency-Key` HTTP header request must resolve to a
single canonical row. If `payment-service` accepts a key under
`(scope, idem_key)` but `customer-service` rejects it because
`scope` is not a column, cross-service idempotency breaks.

## Decision Drivers

- **Single canonical schema.** All 21 services must agree on the
  idempotency table column set and unique key.
- **Per-actor isolation.** The `(actor_id, idempotency_key)`
  composite key isolates one user's keys from another; a key
  without `actor_id` is global and can collide.
- **Hash-based replay detection.** The `request_hash` column
  detects when the same key is replayed with a different payload
  (returning `422 IDEMPOTENCY_KEY_REUSED`).

## Considered Options

1. **`(actor_id, idempotency_key)`** + canonical columns
   (platform default; 3 of 5 services already use this)
2. **`(scope, idem_key)`** (payment-service only)
3. **`(idempotency_key)` alone** (configuration-service only)
4. **Per-service columns; no canonical schema** (rejected —
   defeats the purpose of a shared library)

## Decision Outcome

**Chosen option: option 1, `(actor_id, idempotency_key)`.**

Canonical column set:

```sql
CREATE TABLE <schema>.idempotency (
    id              UUID         PRIMARY KEY,
    actor_id        UUID         NOT NULL,
    idempotency_key UUID         NOT NULL,
    request_hash    CHAR(64)     NOT NULL,           -- SHA-256 of canonical request bytes
    response_status INT,                            -- NULL while pending; HTTP status once committed
    response_body   JSONB,                          -- NULL while pending; response once committed
    state           VARCHAR(16)  NOT NULL,           -- PENDING | COMPLETED | RELEASED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,           -- 24h default; configurable per service
    CONSTRAINT uq_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expires_at ON <schema>.idempotency (expires_at);
```

Migration path per service (V__ Flyway migration, one per
service):

1. `ALTER TABLE <schema>.idempotency RENAME TO idempotency_old;`
2. Create new `<schema>.idempotency` table with canonical columns.
3. `INSERT INTO <schema>.idempotency (id, actor_id, idempotency_key, request_hash, response_status, response_body, state, created_at, expires_at) SELECT id, COALESCE(actor_id, '00000000-0000-0000-0000-000000000000'::UUID), COALESCE(idem_key, idempotency_key), COALESCE(request_hash, repeat('0', 64)), response_status, response_body, COALESCE(state, 'COMPLETED'), created_at, COALESCE(expires_at, created_at + INTERVAL '24 hours') FROM <schema>.idempotency_old;`
4. `DROP TABLE <schema>.idempotency_old;`

### Consequences

**Good:**
- Single canonical idempotency schema across 5 services
- `(actor_id, idempotency_key)` composite key provides per-user
  isolation
- `request_hash` enables `422 IDEMPOTENCY_KEY_REUSED` detection
- 5 redundant `Idempotency` entity files deleted (~400 LOC)
- 8 redundant `IdempotencyService.kt` files adopt
  `platform-spring-boot-data`'s `PlatformIdempotencyService` helper
  (~400 LOC deleted in Phase D)

**Bad:**
- 1 service (`payment-service`) must migrate `(scope, idem_key)`
  → `(actor_id, idempotency_key)`; the `scope` column is dropped
  and existing keys get `actor_id = '00000000-0000-0000-0000-000000000000'`
  (system actor) — verified safe because `payment-service` does
  not currently support per-user actor keys
- 1 service (`configuration-service`) must migrate
  `(idempotency_key)` alone → `(actor_id, idempotency_key)`;
  same system-actor fallback
- Existing idempotency keys are preserved (migrated, not
  invalidated)

### Follow-up

- [ ] Update `shared/CONVENTIONS.md` §idempotency to declare the
  canonical column set and unique key.
- [ ] Update `services/RECOMMENDATIONS.md` to declare the 24h
  default `expires_at`.
- [ ] Add `PlatformIdempotencyService` to
  `platform-spring-boot-data` (Phase D) so the 8 service-local
  `IdempotencyService.kt` files can be deleted.

## Pros and Cons of the Options

### `(actor_id, idempotency_key)` (chosen)

Per-actor isolation; matches 3 of 5 services; matches the
platform `IdempotencyRecord` entity; supports
`422 IDEMPOTENCY_KEY_REUSED` via the `request_hash` column.

### `(scope, idem_key)`

Used by 1 service. Adds an extra dimension (`scope`) without
providing isolation (multiple users can share a `scope`).
Rejected because the canonical schema already provides the
necessary namespacing.

### `(idempotency_key)` alone

Used by 1 service. No per-actor isolation. Rejected because
two users could collide on the same UUIDv7 key.

## References

- [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#idempotency) —
  the canonical idempotency contract
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0023](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [`shared/MODULES.md`](../shared/MODULES.md) — where the
  platform `IdempotencyRecord` is declared
- [ADR-0015](0015-uuidv7-for-ids.md) — UUIDv7 for new identifiers
  (the `idempotency_key` mint source)
