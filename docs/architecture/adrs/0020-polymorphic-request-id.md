# ADR-0020: Polymorphic `request_id` + `workflow_process_id`

- Status: Accepted
- Date: 2026-08-12
- Authors: Platform Architecture
- Deciders: Architecture Review Board
- Tags: architecture, request, saga, orchestrator, polymorphic, cross-service

## Context and Problem Statement

The platform currently carries four parallel concrete identifiers for what is
conceptually the same lifecycle: a request for a ride or a delivery.

| Concrete identifier | Owning service | Used where |
|---|---|---|
| `trip_id` | `trip-service` | `trip.trips`, `trip_stops`, `trip_location_points`, reward fan-out |
| `order_id` / `food_order_id` | `food-order-service` | `food_order.orders`, `order_items`, `order_state_history` |
| `ride_id` | historical (absorbed into `trip-service` per ADR-0017) | payment-service REST paths, idempotency keys |
| `delivery_id` | `courier-service` | `courier.deliveries`, `courier.dispatches` |

These concrete identifiers are embedded in:
- Cross-service payload columns (`payment.payment_intents` has three nullable FK
  columns — `food_order_id`, `ride_id`, `trip_id` — side by side)
- Idempotency-key namespaces (`request:{request_id}:reward:...` alongside
  `request:{request_id}:refund:...`)
- REST URL paths (`/v1/ride-payment/sagas/{trip_id}/...` vs
  `/v1/food-payment/sagas/{food_order_id}/...`)
- Notification template variables (`{{trip_id}}` vs `{{order_id}}`)
- Conductor workflow signal mappings (separate topics per vertical)

The result is N+1 branching logic whenever a downstream service needs to
correlate a payment, notification, or ledger entry back to its origin request.
There is no polymorphic parent — the "request" concept exists only as a
concrete `trip` or `order` aggregate, not as a first-class entity.

Additionally, the platform lacks a first-class saga root: the Phase 7 reward
fan-out and the payment sagas are triggered by domain events
(`trip.reward.granted.v1`, `payment.captured.v1`) but have no explicit
workflow-process ID stamped on the originating entity. Correlation across
services relies on idempotency-key conventions, not on a shared process ID.

## Decision Drivers

- **Blast radius of change**: adding a new vertical (e.g. courier delivery)
  requires adding new FK columns, new idempotency-key prefixes, and new
  template variables in every downstream service.
- **Saga observability**: there is no canonical workflow instance ID visible on
  the originating request entity that would let an operator correlate a payment
  failure back to the workflow run that authorized it.
- **Cross-service joins are prohibited** by the platform's hard constraint
  (`main.md:1305-1307`: no shared business DB, no cross-service FKs, no
  cross-service DB joins). The existing nullable-FK pattern in
  `payment.payment_intents` violates this intent — three nullable columns are
  not a real FK but they model the same relationship three times.
- **Option B (replicated per owning service)** honors the no-shared-DB
  constraint: instead of one central `requests` table, each owning service gets
  its own `requests` shadow table in its own schema, with the same shape and
  the same `id` (UUIDv7 = request_id) as the concrete aggregate.

## Considered Options

### Option A — New `requests-service`

A dedicated service owning a single global `requests` table. All other services
reference it via REST or events.

- Good: one canonical home for the request concept.
- Bad: creates a new distributed-system hop on every request creation.
- Bad: a separate service is overkill for what is fundamentally a
  1:1 shadow of an aggregate; it would require a dedicated deployment,
  database, and on-call rotation for a table that is always consistent with
  the concrete aggregate in the same transaction.
- Bad: violates the no-cross-service-FK constraint (the `requests` table
  would hold the PK but the owning service holds the aggregate).

**Rejected** — too heavy for a 1:1 shadow; operational overhead without
benefit.

### Option B — Replicated per owning service (chosen)

Each owning service (`trip-service`, `food-order-service`, `courier-service`)
gets a `requests` table in its own schema, 1:1 with its concrete aggregate
(the `trips`, `orders`, `deliveries` table respectively). The concrete
aggregate gets a `request_id NOT NULL UNIQUE` FK referencing its own
schema's `requests` table. No shared database; no cross-service FK.

- Good: honors the no-shared-DB constraint absolutely.
- Good: request creation is a single local transaction (insert `requests`
  and the concrete aggregate in the same DB, same schema, same transaction).
- Good: each service owns its request projection; no distributed hop.
- Good: adding a new vertical means adding a new `requests` table in the
  new service's schema — zero blast radius on existing services.
- Bad: the `request_id` value is replicated (stored in both the concrete
  aggregate's PK and the `requests` table row); this is unavoidable without a
  shared DB and is the same pattern already used for the concrete aggregate's
  PK vs its REST representation.
- Bad: a read of "the request" from another service requires a REST call
  to the owning service (consistent with the rest of the platform's
  cross-service data access pattern).

### Option C — Single `requests` table in `trip-service`

Put the unified `requests` table in `trip-service` and let `food-order-service`
and `courier-service` reference it via REST or events.

- Bad: `trip-service` becomes the implicit owner of all request lifecycles;
  the food vertical and courier vertical should not depend on `trip-service`
  for their core entity.
- Bad: `trip-service` is a ride-hailing bounded context; housing the food
  vertical's request entity there violates bounded-context boundaries per
  ADR-0017.
- Bad: the same blast-radius problem as Option A for any downstream that
  needs to cross-correlate.

**Rejected** — violates bounded-context ownership per ADR-0017.

## Decision Outcome

Chosen option: **Option B — replicated per owning service**.

Each owning service's `requests` table is created in the **same transaction**
as the concrete aggregate and carries the same UUIDv7 `id` value (which is
the `request_id`). The concrete aggregate adds a `request_id NOT NULL UNIQUE`
column referencing its own schema's `requests` table, making the relationship
1:1 and intra-service.

The `service` discriminator on the `requests` table decides routing logic at
the workflow and fan-out layer, replacing the current static `if trip else if
order` branching.

The `workflow_process_id` column is stamped on the `requests` row at creation
time by the Conductor / Camunda / Temporal workflow started as part of request
creation — it is the **saga root** and appears in every downstream
idempotency-key, event correlation header, and ledger postings.

### Schema (per owning service)

```sql
CREATE TABLE <schema>.requests (
    id                  UUID PRIMARY KEY,          -- = request_id (UUIDv7)
    service             TEXT NOT NULL CHECK (service IN
                        ('trip','food_order','courier_delivery')),
    workflow_process_id TEXT NOT NULL,             -- Conductor/Camunda/Temporal instance ID
    status              TEXT NOT NULL CHECK (status IN
                        ('requested','matched','in_progress',
                         'completed','cancelled','failed')),
    status_reason       TEXT,
    customer_id         UUID NOT NULL,            -- cross-service ref (no FK)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    correlation_id      UUID NOT NULL,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- concrete aggregate (e.g. trip.trips):
ALTER TABLE <schema>.<aggregate>
    ADD COLUMN request_id UUID NOT NULL UNIQUE
    REFERENCES <schema>.requests(id);
```

### Naming conventions (locked)

| Concept | Format |
|---|---|
| Polymorphic parent PK | `request_id UUIDv7` |
| Discriminator | `service TEXT CHECK (service IN ('trip','food_order','courier_delivery'))` |
| Orchestrator linkage | `workflow_process_id TEXT` — format `wf.process.{service}.{request_id}.v1` |
| Idempotency-key namespace | `request:{request_id}:{purpose}:{scope}` |
| Request events | `request.created.v1`, `request.matched.v1`, `request.in_progress.v1`, `request.completed.v1`, `request.cancelled.v1`, `request.failed.v1` |
| REST read endpoint | `/{v1}/requests/{request_id}` (cross-service); concrete aggregates continue to expose `/{v1}/trips/{id}`, `/{v1}/orders/{id}` for owner reads |

## Consequences

### Structural

- `payment.payment_intents` collapses three nullable FKs (`food_order_id`,
  `ride_id`, `trip_id`) → one `request_id UUID NOT NULL` column, with
  `service TEXT NOT NULL CHECK (service IN (...))` to drive routing.
- `notification.deliveries` collapses two nullable FKs (`trip_id`,
  `order_id`) → one `request_id UUID NOT NULL` column.
- `payment.ride_sagas` / `payment.food_sagas` collapse to
  `payment.request_sagas` keyed by `request_id`.
- Downstream services (`ledger-service`, `audit-service`,
  `reporting-service`, `notification-service`) update idempotency-key
  prefixes from `trip:{trip_id}:...` / `order:{order_id}:...` to
  `request:{request_id}:...`.
- REST URL paths for saga initiation collapse from
  `/v1/ride-payment/sagas/{trip_id}/...` and
  `/v1/food-payment/sagas/{food_order_id}/...` to a single
  `/v1/payment/sagas/{request_id}/...`.

### Event catalog

Domain events (`trip.started.v1`, `food.order.placed.v1`) continue to exist
with their concrete-aggregate payloads. New parent events fan out from the
`requests` table via the owning service's outbox:

```
request.created.v1      — emitted when the request row is inserted
request.matched.v1     — emitted when the request is matched (driver/courier assigned)
request.in_progress.v1  — emitted when the request enters its active phase
request.completed.v1   — emitted on terminal success
request.cancelled.v1   — emitted on cancellation
request.failed.v1      — emitted on terminal failure
```

Each `request.*.v1` event payload contains:
`request_id`, `service`, `workflow_process_id`, `customer_id`,
`status`, `previous_status`, `correlation_id`, `occurred_at`,
`actor_id` + `actor_type`.

Consumers that need only request-level state subscribe to `request.*.v1`;
consumers that need domain detail subscribe to the concrete events.

### Conductor workflow

The canonical request orchestrator is `wf.process.{service}.{request_id}.v1`
started synchronously at request creation. Its input is
`{request_id, service, customer_id}` and its `workflow_process_id` is
persisted on the `requests` row. This workflow ID is the saga root carried
in every downstream idempotency key and event correlation header.

### Confirmation

- `grep -r "trip_id" docs/services/payment-service/ERD.md` returns only
  comments and the concrete `trip.trips` table reference.
- `grep -r "request_id" docs/services/notification-service/ERD.md` shows
  ONE column on `notification.deliveries`.
- `grep -r "request:{request_id}:" docs/` returns hits in all downstream
  service INTEGRATION files and workflow docs.
- Every owning service `ERD.md` documents its `requests` table and the
  `request_id UNIQUE FK` on the concrete aggregate.

## Pros and Cons of the Options (summary)

| | Option A (requests-service) | Option B (replicated per service) | Option C (requests in trip-service) |
|---|---|---|---|
| Honors no-shared-DB | No | **Yes** | No |
| Bounded-context clean | Yes | **Yes** | No |
| No distributed hop on creation | No | **Yes** | No |
| Zero blast radius for new verticals | **Yes** | **Yes** | Yes |
| Operational overhead | High | **Low** | Medium |
| Saga root first-class | **Yes** | **Yes** | Yes |

## References

- [`ADR-0017`](0017-20-service-architecture.md) — the 20-service catalog and
  per-service aggregate ownership (the "38 → 20" merger that established the
  pattern of one owning service per aggregate).
- [`ADR-0019`](0019-request-id-at-the-edge.md) — `request_id` as the HTTP
  correlation id at the api-gateway edge (distinct from this ADR's polymorphic
  business-entity `request_id`; both coexist: ADR-0019 is the correlation
  header, ADR-0020 is the polymorphic business-entity parent).
- [`main.md`](../../../main.md) — hard constraint: no shared business DB,
  no cross-service FKs, no cross-service DB joins (`main.md:1305-1307`).
- [`docs/shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md)
  — `wf.process.{service}.{request_id}.v1` canonical orchestrator shape
  (§3.6).
- [`docs/shared/TYPE_CATALOG.md`](../../shared/TYPE_CATALOG.md) — `service` enum and
  `workflow_process_id` format (§9, §10).
- [`docs/workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md)
  — collapsed payment saga paths.
- [`docs/workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) —
  request lifecycle events added to ride sequence diagrams.
- [`docs/workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md)
  — request lifecycle events added to food-order sequence diagrams.
- [`docs/MIGRATION_HUB.md`](../../MIGRATION_HUB.md) — migration record for the
  `trip_id/order_id/food_order_id/ride_id` → `request_id` rename.
