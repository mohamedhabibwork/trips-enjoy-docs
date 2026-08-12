---
description: Polymorphic request_id + workflow_process_id refactor — master plan. Phase A→E sequence; Option B (replicated per owning service) + Full rename (no compat window).
created: 2026-08-12
---

# Polymorphic `request_id` + `workflow_process_id` Refactor

## 1. Goal

Replace the parallel `order_id` / `trip_id` (and `ride_id` / `food_order_id`) identifier conventions with a single polymorphic `request_id` column everywhere, plus a `service` discriminator on the request entity. Add `workflow_process_id` to bind every request to an orchestrator (Conductor / Camunda / Temporal) process instance ID so the saga root is first-class.

## 2. Decisions (locked)

- **Option B** — `requests` table is **replicated per owning service**: `trip.requests`, `food_order.requests`, `courier.requests`. Each is a 1:1 shadow of its concrete aggregate with the same `id` shape, the `service` enum discriminator, and `workflow_process_id`. Honors `main.md:1305-1307` (no shared DB, no cross-service FKs).
- **Full rename, no compat window** — drop `trip_id` / `order_id` / `food_order_id` / `ride_id` columns from cross-service payloads; drop old idempotency-key formats; drop old REST URL paths (`/v1/ride-payment/sagas/{trip_id}/...`, `/v1/food-payment/sagas/{food_order_id}/...`). Single cutover.

## 3. Naming conventions (locked)

| Concept | Value |
|---|---|
| Polymorphic parent PK | `request_id UUID` (UUIDv7) |
| Discriminator | `service TEXT CHECK (service IN ('trip','food_order','courier_delivery'))` — extensible |
| Orchestrator linkage | `workflow_process_id TEXT` — Conductor workflow instance ID, format `wf.process.{service}.{request_id}.v1` |
| Service-specific PKs | `trips.id`, `orders.id`, `deliveries.id` — **unchanged** (kept as concrete PKs) |
| Cross-service FK | `request_id UUID NOT NULL` (replaces `trip_id`, `order_id`, `food_order_id`, `ride_id`) |
| Intra-service FK | `request_id UUID NOT NULL UNIQUE REFERENCES <schema>.requests(id)` (within the same service's schema) |
| Idempotency-key namespace | `request:{request_id}:{purpose}:{scope}` (replaces `trip:{trip_id}:...`, `order:<order_id>:...`, `food:<id>:...`, `ride:<id>:...`) |
| State enum | `request_status TEXT CHECK (status IN ('requested','matched','in_progress','completed','cancelled','failed'))` — captured by the polymorphic `requests` table; concrete aggregates keep their own richer state column |
| Event naming | **Keep existing** `domain.entity.event.v1` (e.g. `trip.started.v1`, `food.order.placed.v1`). **Add** parent events: `request.created.v1`, `request.matched.v1`, `request.in_progress.v1`, `request.completed.v1`, `request.cancelled.v1`, `request.failed.v1` — emitted by the owning service, fanned out via the request topic. |
| REST URL | `/{v1}/requests/{request_id}` is the canonical read endpoint. Concrete aggregates continue to expose `/{v1}/trips/{id}`, `/{v1}/orders/{id}`, `/{v1}/deliveries/{id}` for owner reads; cross-service callers use `request_id`. |

## 4. Schema pattern (Option B)

Each owning service's `requests` table is the same shape:

```sql
CREATE TABLE <schema>.requests (
    id              UUID PRIMARY KEY,                                -- = request_id (UUIDv7)
    service         TEXT NOT NULL CHECK (service IN
                      ('trip','food_order','courier_delivery')),
    workflow_process_id TEXT NOT NULL,                              -- Conductor/Camunda/Temporal instance ID
    status          TEXT NOT NULL CHECK (status IN
                      ('requested','matched','in_progress',
                       'completed','cancelled','failed')),
    status_reason   TEXT,
    customer_id     UUID NOT NULL,                                   -- cross-service ref
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    correlation_id  UUID NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_requests_customer ON <schema>.requests (customer_id, created_at DESC);
CREATE INDEX idx_requests_status   ON <schema>.requests (status, created_at);
CREATE INDEX idx_requests_workflow ON <schema>.requests (workflow_process_id);
```

The concrete aggregate gets a `request_id NOT NULL UNIQUE` FK:

```sql
-- trip.trips
ALTER TABLE trip.trips
    ADD COLUMN request_id UUID NOT NULL UNIQUE REFERENCES trip.requests(id);

-- food_order.orders
ALTER TABLE food_order.orders
    ADD COLUMN request_id UUID NOT NULL UNIQUE REFERENCES food_order.requests(id);

-- courier.deliveries
ALTER TABLE courier.deliveries
    ADD COLUMN request_id UUID NOT NULL UNIQUE REFERENCES courier.requests(id);
```

The request row is created **first** in the same transaction as the concrete aggregate are created. For example, in `trip-service`: customer posts `POST /v1/rides` → trip-service inserts `trip.requests` (service='trip', status='requested', workflow_process_id=`wf.process.trip.<uuid>.v1`) → inserts `trip.trips` (request_id=<same uuid>). The UNIQUE constraint makes the request → aggregate relation 1:1.

## 5. Per-file edit list

### Phase A — Foundation

| File | Edits |
|---|---|
| `docs/architecture/adrs/0020-polymorphic-request-id.md` | **NEW ADR.** Codifies Option B + Full rename; defines `service` enum, `workflow_process_id` format, request idempotency-key namespace, request event taxonomy. |
| `main.md` | Lines 69-78: add `request_id` to cross-service ID list. Lines 719-731: add `request.{lifecycle}.v1` events. New paragraph at the top of section 21 (Required Global Diagrams) listing a "Request lifecycle" diagram. |
| `docs/shared/TYPE_CATALOG.md` | New section "Request types" defining `service` enum (`trip`, `food_order`, `courier_delivery`, extensible). New section "Workflow process id" defining `wf.process.{service}.{request_id}.v1` format. |
| `docs/shared/CONDUCTOR_WORKFLOWS.md` | Section 3.1 generic-ified: `wf.phase7.reward_grant.v1` accepts `request_id` parameter; idempotency key becomes `request:{request_id}:reward:{role}:...`. New section 3.6 "Request orchestration pattern" defining `wf.process.{service}.{request_id}.v1` parameter shape. Section 3.5 explicitly marked "NOT customer-facing, separate admin concept". |

### Phase B-1 — Owning-service ERDs

| File | Edits |
|---|---|
| `docs/services/trip-service/ERD.md` | Add `trip.requests` table (DDL above). Add `trip.trips.request_id UUID NOT NULL UNIQUE REFERENCES trip.requests(id)` column. Replace every `trip_id` FK in `trip_stops`, `trip_location_points`, `trip_state_history`, `trip_reward`, `trip_reward_reversal` with intra-service FK declarations (kept as `trip_id`) + comment "the request_id on the parent trip is the polymorphic cross-service reference". Update Mermaid ERD. |
| `docs/services/food-order-service/ERD.md` | Add `food_order.requests` table. Add `food_order.orders.request_id UUID NOT NULL UNIQUE REFERENCES food_order.requests(id)`. Replace `order_id` FK declarations in `order_items`, `order_state_history` with "FK to orders.id (intra-schema)". Update Mermaid. |
| `docs/services/courier-service/ERD.md` | Add `courier.requests` table. Add `courier.deliveries.request_id UUID NOT NULL UNIQUE REFERENCES courier.requests(id)`. Replace `food_order_id` FK on `courier.dispatches` with `request_id` (the polymorphic identifier; the order itself is still queryable via `request_id` lookup through `food_order-service`). Update Mermaid. |

### Phase B-2 — Downstream schemas (heavy)

| File | Edits |
|---|---|
| `docs/services/payment-service/ERD.md` | `payment.payment_intents` — COLLAPSE three nullable FKs (`food_order_id`, `ride_id`, `trip_id`) → ONE `request_id UUID NOT NULL`. Drop three partial indexes; add one index on `request_id`. `payment.ride_sagas` and `payment.food_sagas` collapse to `payment.request_sagas` keyed by `request_id`. New `payment.request_saga_steps` table with `(request_id, step)`. |
| `docs/services/notification-service/ERD.md` | `notification.deliveries` — COLLAPSE two nullable FKs (`trip_id`, `order_id`) → ONE `request_id UUID NOT NULL`. Add `service` column for routing. |
| `docs/services/notification-service/seeds/templates.v1.json` | For every template that has `trip_id` AND `order_id` variants, collapse to ONE template with `request_id` (and `service` for conditional rendering of deeplinks / URL buttons). Body text: `{{request_id}}` replaces `{{trip_id}}` and `{{order_id}}`. Required_variables: drop trip_id / order_id; add request_id. Deeplinks: add `{{service}}` so the template can render `uber://trip/{{request_id}}` vs `uber://order/{{request_id}}` via a tiny switch. |

### Phase B-3 — Other downstream ERDs

| File | Edits |
|---|---|
| `docs/services/customer-service/ERD.md` | `customer.customer_ltv_history` line 117 — formalize the existing `source_id` polymorphism: add `service TEXT NOT NULL CHECK (service IN (...))` column; rename `source_id` → `request_id` (with note "the polymorphic request identifier; the concrete aggregate is resolved via the owning service's REST API"). Update lines 220, 350 similarly. |
| `docs/services/driver-service/ERD.md` | `driver.match_attempts.ride_request_id` (line 546) → `request_id UUID NOT NULL`. Update `POST /v1/match` body (README:389). |
| `docs/services/fraud-risk-service/ERD.md` | `risk_signals.trip_id` and `risk_decisions.trip_id` (lines 20, 39, 288, 354) → `request_id UUID`. |
| `docs/services/pricing-service/ERD.md` + `TECH.md` + `INTEGRATION.md` | Quote payload's `ride_request_id` / `trip_id` → `request_id`. `/admin/v1/pricing/quote/recalculate/{ride_request_id}` → `/admin/v1/pricing/quote/recalculate/{request_id}`. |

### Phase C-1 — Workflow docs (Mermaid updates)

| File | Edits |
|---|---|
| `docs/workflows/RIDE_WORKFLOWS.md` | Add `request.created.v1` → `request.matched.v1` → `request.in_progress.v1` → `request.completed.v1` parent events to Mermaid sequence diagrams. Include `workflow_process_id` on steps. |
| `docs/workflows/FOOD_ORDER_WORKFLOWS.md` | Same. `request.created.v1` on order placement, `request.matched.v1` on courier assignment, `request.completed.v1` on delivery. |
| `docs/workflows/COURIER_WORKFLOWS.md` | Same. |
| `docs/workflows/DRIVER_WORKFLOWS.md` | Add request lifecycle events. |
| `docs/workflows/SAFETY_WORKFLOWS.md` | Replace `trip_id` with `request_id` in Mermaid. |

### Phase C-2 — Workflow idempotency keys

| File | Edits |
|---|---|
| `docs/workflows/PAYMENT_WORKFLOWS.md` | Replace every `trip:{trip_id}:...` and `order:<order_id>:...` with `request:{request_id}:...`. Lines 108, 233, 274-281. |
| `docs/workflows/REFUND_WORKFLOWS.md` | Replace `order:<order_id>:...` with `request:{request_id}:...`. Lines 62, 64, 88, 90, 183-184. |
| `docs/workflows/ACCOUNTING_WORKFLOWS.md` | Replace `trip:<trip_id>:...`, `order:<order_id>:...`, `journal:<admin_id>:<request_id>` with `request:{request_id}:...` and `journal:<admin_id>:<request_id>`. Lines 298, 540-550. |

### Phase C-3 — Service INTEGRATION files

| File | Edits |
|---|---|
| `docs/services/payment-service/INTEGRATION.md` | Replace all `food:<order_id>:...` / `ride:<ride_id>:...` idempotency keys with `request:{request_id}:...`. Replace `food_order_id` / `ride_request_id` fields in payload schemas with `request_id`. Replace saga endpoint paths `/v1/ride-payment/sagas/{trip_id}/...` / `/v1/food-payment/sagas/{food_order_id}/...` with `/v1/payment/sagas/{request_id}/...`. Lines 10, 21, 74, 96, 112, 269-303, 803-804. |
| `docs/services/trip-service/INTEGRATION.md` | Replace `trip_id` / `ride_request_id` in event payloads with `request_id`. Replace idempotency keys `trip:{trip_id}:...` with `request:{request_id}:...`. Lines 11, 15, 230, 281, 297, 305, 307, 320, 327, 335, 345, 347, 364, 372, 385, 393, 410-414, 424-426, 468-477, 498-503, 641, 663, 717-718. |
| `docs/services/food-order-service/INTEGRATION.md` | Replace `order_id` references with `request_id` in event payloads. Replace `deal:<order_id>:...` with `request:{request_id}:deal:...`. |
| `docs/services/ledger-service/INTEGRATION.md` | Replace `trip:{trip_id}:reward:ledger:...` with `request:{request_id}:reward:ledger:...`. Lines 444-445. Add equivalent rows for order-side rewards (currently missing). |
| `docs/services/audit-service/INTEGRATION.md` | Replace `trip:{trip_id}:reward:audit:...` with `request:{request_id}:reward:audit:...`. Lines 602-603. Add order-side rows. |
| `docs/services/reporting-service/INTEGRATION.md` | Replace `trip:{trip_id}:reward:reporting:...` with `request:{request_id}:reward:reporting:...`. Lines 354-355. Add order-side rows. |
| `docs/services/notification-service/INTEGRATION.md` | Replace `trip:{trip_id}:reward:...` with `request:{request_id}:reward:...`. Lines 21, 70, 126-127, 145, 157-158, 461, 530, 966-967. |

### Phase D — Architecture docs

| File | Edits |
|---|---|
| `docs/architecture/DATA_OWNERSHIP.md` | Add `Request` row to the source-of-truth matrix (lines 88-141). `Request` is owned by the same service as its concrete aggregate (trip-service / food-order-service / courier-service). Add explicit "request → concrete aggregate" 1:1 column. |
| `docs/architecture/CONSISTENCY_STRATEGY.md` | New section "Workflow process id as saga root" — explains that `workflow_process_id` is the canonical root for cross-service saga correlation. |
| `docs/architecture/FAILURE_HANDLING.md` | New section "Compensation by request_id" — compensation handlers are scoped to a request, not to a trip or order. |
| `docs/architecture/EVENT_ARCHITECTURE.md` | Add `request.{lifecycle}.v1` to the event catalog. Define the new request topic / partition key (key = `request_id`). |

### Phase E — Per-service SRS / BRD / README (narrative)

| File | Edits |
|---|---|
| `docs/services/trip-service/{SRS,BRD,README}.md` | Replace `trip_id` / `ride_request_id` references in narrative with `request_id`. Update end-to-end flow sections to show request creation → request.matched.v1 → request.in_progress.v1 → request.completed.v1. |
| `docs/services/food-order-service/{SRS,BRD,README}.md` | Same. |
| `docs/services/courier-service/{SRS,BRD,README}.md` | Same. |
| `docs/services/payment-service/{SRS,BRD,README}.md` | Same. |
| `docs/services/customer-service/{SRS,BRD,README}.md` | Update `customer_ltv_history` description. |
| `docs/services/driver-service/{SRS,BRD,README}.md` | Update `match_attempts` description. |
| `docs/services/fraud-risk-service/{SRS,BRD,README}.md` | Update `risk_signals` / `risk_decisions` description. |
| `docs/services/pricing-service/{SRS,BRD,README}.md` | Update quote payload description. |
| `docs/services/notification-service/{SRS,BRD,README}.md` | Update template system description. |
| `docs/services/ledger-service/{SRS,BRD,README}.md` | Update idempotency-key namespace. |
| `docs/services/audit-service/{SRS,BRD,README}.md` | Update idempotency-key namespace. |
| `docs/services/reporting-service/{SRS,BRD,README}.md` | Update idempotency-key namespace. |

## 6. Idempotency-key migration table

| Old | New |
|---|---|
| `trip:{trip_id}:reward:{role}:grant` | `request:{request_id}:reward:{role}:grant` |
| `trip:{trip_id}:reward:{role}:reversal` | `request:{request_id}:reward:{role}:reversal` |
| `trip:{trip_id}:earn` | `request:{request_id}:earn` |
| `trip:{trip_id}:cap` | `request:{request_id}:cap` |
| `trip:{trip_id}:saga:ride:{step}` | `request:{request_id}:saga:ride:{step}` |
| `trip:{trip_id}:penalty:driver:{penalty_id}` | `request:{request_id}:penalty:driver:{penalty_id}` |
| `ride:{ride_id}:auth`/`capture`/`void`/`refund:{reason}` | `request:{request_id}:payment:auth`/`capture`/`void`/`refund:{reason}` |
| `food:{order_id}:auth`/`capture`/`void`/`refund:{reason}` | `request:{request_id}:payment:auth`/`capture`/`void`/`refund:{reason}` |
| `order:{order_id}:refund:cancel` | `request:{request_id}:refund:cancel` |
| `order:{order_id}:refund:reject` | `request:{request_id}:refund:reject` |
| `order:{order_id}:tip:{request_id}` | `request:{request_id}:tip:{request_id}` |
| `wallet:{wallet_id}:topup:{request_id}` | `request:{request_id}:wallet:topup` |
| `delivery:{delivery_id}:cod:{request_id}` | `request:{request_id}:cod` |
| `journal:{admin_id}:{request_id}` | `journal:{admin_id}:{request_id}` (unchanged — admin journal is a separate concept) |
| `driver:{driver_id}:incentive:{trip_id}` | `request:{request_id}:incentive` |
| `deal:{order_id}:open` | `request:{request_id}:deal:open` |

## 7. Event catalog additions

```
request.created.v1      — emitted by owning service when the request row is inserted
request.matched.v1      — emitted when the request is matched (driver for trip, courier for delivery)
request.in_progress.v1  — emitted when the request enters its active phase (trip started, order placed)
request.completed.v1    — emitted when the request reaches its terminal-success state
request.cancelled.v1    — emitted on cancellation
request.failed.v1       — emitted on terminal failure
```

Each event payload contains:
- `request_id` (UUID)
- `service` (enum: `trip` | `food_order` | `courier_delivery`)
- `workflow_process_id`
- `customer_id`
- `status` (current)
- `previous_status` (current → new transition)
- `correlation_id`
- `occurred_at`
- `actor_id` + `actor_type` (where applicable)

Domain-specific events (`trip.started.v1`, `food.order.placed.v1`, `delivery.courier.assigned.v1`, etc.) continue to exist with their concrete-aggregate payloads. The `request.*.v1` events are the **parent** events; the domain events are the **children**. Consumers that only need request-level state can subscribe to `request.*.v1`; consumers that need detail subscribe to the domain events.

## 8. Verification checklist

After all phase edits:

- [ ] `grep -r "trip:{trip_id}" docs/` returns zero matches
- [ ] `grep -r "order:<order_id>" docs/` returns zero matches
- [ ] `grep -r "food:<order_id>" docs/` returns zero matches
- [ ] `grep -r "ride:<ride_id>" docs/` returns zero matches
- [ ] `grep -r "{{trip_id}}" docs/` returns zero matches (notification templates)
- [ ] `grep -r "{{order_id}}" docs/` returns zero matches (notification templates)
- [ ] `grep -r "request_id" docs/services/payment-service/ERD.md` shows ONE column on `payment_intents`, not three
- [ ] `grep -r "request_id" docs/services/notification-service/ERD.md` shows ONE column on `deliveries`, not two
- [ ] Every service `README.md` has a "Cross-cutting" or "See also" section pointing to ADR-0020
- [ ] `docs/MIGRATION_HUB.md` has a new entry for the 2026-08-12 refactor mirroring the precedent from the `ride_request → trip` merger
- [ ] ADR-0020 exists and is comprehensive
- [ ] Each owning service `README.md` lists `<service>.requests` table ownership alongside its concrete aggregate

## 9. Roll-back plan

Because the user chose **Full rename, no compat window**, there is no backward-compatibility path. Roll-back is via git revert of the commit(s) that land this refactor. To enable a clean revert, the refactor MUST be landed in a single commit (or a small set of commits) with a clear commit message:

```
refactor!(docs): polymorphic request_id + workflow_process_id per ADR-0020

- Each owning service (trip, food_order, courier) gains a `requests` shadow table
  with `service` enum and `workflow_process_id`.
- Concrete aggregates (trips, orders, deliveries) gain `request_id UNIQUE FK`.
- All cross-service payload columns trip_id / order_id / food_order_id / ride_id
  collapse to `request_id`.
- All idempotency keys switch to `request:{request_id}:...` namespace.
- New request.*.v1 parent events added to event catalog.
- No compat window per architect decision.
```

## 10. Source-of-truth files

- `docs/architecture/adrs/0020-polymorphic-request-id.md` — the decision
- `docs/shared/TYPE_CATALOG.md` — the type vocabulary
- `docs/shared/CONDUCTOR_WORKFLOWS.md` — the orchestrator pattern
- `docs/architecture/DATA_OWNERSHIP.md` — the source-of-truth matrix
- `docs/architecture/EVENT_ARCHITECTURE.md` — the event catalog
- `docs/MIGRATION_HUB.md` — the migration record

Sub-agents working on this refactor MUST read this plan before editing.
