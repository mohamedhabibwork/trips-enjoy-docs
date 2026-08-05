# inventory-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and
rules for the `inventory-service` — the canonical owner of
**inventory stock** (per-product stock counts, 86 list,
time-bound availability, auto-restock). It is read by:

- Product managers scoping inventory management.
- Engineering leads planning the service's roadmap.
- Restaurant operators when designing the operator console.
- Cart / checkout teams when designing availability checks.

It informs decisions on stock tracking, 86 logic, time-bound
availability, and the relationship to menu products and
orders.

## 2. Business Context

An **inventory item** represents the stock of a specific
product at a specific restaurant or branch. It tracks:

- The current stock count.
- A 86 flag (per-item, with reason).
- Time-bound availability windows (e.g. "only available
  11:00–14:00").
- Auto-restock schedules.

The inventory item is linked to a `product_id` in
`menu-service` (via an `inventory_item_id` cross-reference).
Stock is decremented when an order is placed and re-credited
when an order is cancelled.

Without this service, the platform could not accurately track
stock, automatically 86 items when out of stock, or support
time-bound availability.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a merchant owner to create an inventory item in < 5 minutes (operator time) | `inventory_item_creation_seconds` (P90) < 300 s |
| BR--002 | Decrement stock on order placement within 5 s | `stock_decrement_propagation_seconds` (P95) < 5 s |
| BR--003 | Emit `out_of_stock` when stock reaches 0 within 1 s of the change | `out_of_stock_emission_seconds` (P95) < 1 s |
| BR--004 | Auto-restock at scheduled times within 60 s of the scheduled time | `auto_restock_drift_seconds` (P95) < 60 s |
| BR--005 | Propagate parent restaurant suspension to 86 all items within 60 s | `cascade_86_propagation_seconds` (P95) < 60 s |
| BR--006 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--007 | Block ordering of out-of-stock items in 100% of cases | `oos_block_rate` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchant Owner | Operator | accurate stock |
| Restaurant Manager (staff) | Day-to-day | restock, 86, low-stock alerts |
| Kitchen Staff | Operator | 86 items when out of ingredient |
| Platform Admin | Reviewer | full access |
| Cart / Checkout (system) | Consumer | accurate availability |
| `food-order-service` (system) | Consumer | decrements / re-credits stock |

## 5. Actors / Personas

- **Merchant Owner**: creates inventory items, sets initial
  stock, configures auto-restock.
- **Restaurant Manager**: restocks, adjusts (waste, count
  correction), 86s items.
- **Kitchen Staff**: 86s items when an ingredient runs out.
- **Platform Admin**: full access.

## 6. Business Capabilities

- **Inventory item CRUD**: create, read, update, soft delete.
- **Stock tracking**: current count, history of movements.
- **Restock**: add stock with a quantity and a reason.
- **Adjust**: admin-only stock adjustment (waste, count
  correction).
- **86 / un-86**: per-item unavailability with reason.
- **Time-bound availability**: time windows during which the
  item is available.
- **Auto-restock**: scheduled restocks (e.g. "every morning at
  06:00, add 100").
- **Cascade handling**: parent restaurant suspension / closure
  → 86 all items.
- **Low-stock alerts**: notify owner when stock falls below a
  threshold.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow an approved restaurant to create inventory items | MUST | Product |
| BR--011 | The service MUST track current stock per item | MUST | Product |
| BR--012 | The service MUST decrement stock on `food.order.placed.v1` | MUST | Operations |
| BR--013 | The service MUST re-credit stock on `food.order.cancelled.v1` | MUST | Operations |
| BR--014 | The service MUST emit `out_of_stock` when stock reaches the threshold | MUST | Operations |
| BR--015 | The service MUST emit `restocked` when stock crosses the threshold upward | MUST | Operations |
| BR--016 | The service MUST support per-item 86 with a reason code | MUST | Operations |
| BR--017 | The service MUST support time-bound availability windows | SHOULD | Product |
| BR--018 | The service MUST support auto-restock schedules | SHOULD | Operations |
| BR--019 | The service MUST emit low-stock alerts when stock falls below the threshold | MUST | Product |
| BR--020 | The service MUST cascade parent restaurant suspension to 86 all items | MUST | Trust & Safety |
| BR--021 | The service MUST emit `inventory.*.v1` events for every state change | MUST | Event architecture |
| BR--022 | The service MUST expose fast availability lookups for `cart-service`, `checkout-service` | MUST | Latency |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Stock cannot be negative; the service rejects decrements that would make stock negative. | enforced |
| BR--031 | A 86'd item is not orderable; the cart removes it. | enforced via event |
| BR--032 | Stock is decremented atomically with the order event (saga step); re-credit on cancellation. | saga |
| BR--033 | A 86 by kitchen staff records the role in the audit log. | audit |
| BR--034 | An auto-restock adds the scheduled quantity at the scheduled time. | cron |
| BR--035 | Time-bound availability windows are interpreted in the restaurant's timezone. | timezone |
| BR--036 | A `menu.item.unavailable.v1` mirrored to inventory sets the 86 flag with `reason_code = "menu_86"`. | mirror |

## 9. Assumptions

- The parent restaurant is `approved` (verified by
  `restaurant-service`).
- The operator has a verified Keycloak identity.
- The product exists in `menu-service` and has an
  `inventory_item_id` cross-reference.
- `food-order-service` emits `food.order.placed.v1` and
  `food.order.cancelled.v1` reliably.

## 10. Constraints

- The service is the source of truth for stock data only. It
  MUST NOT store order, payment, or prep state.
- The service MUST be deployable independently of
  `menu-service` and `food-order-service`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `menu-service` | service | product (cross-service ref) |
| `food-order-service` | service | order events (decrement / re-credit) |
| `restaurant-service` | service | parent; cascade events |
| `configuration-service` | service | thresholds, defaults |
| `notification-service` | service | low-stock alerts |
| `cart-service` | service | consumes availability events |
| `checkout-service` | service | consumes availability events |
| `search-service` | service | consumes availability events |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Inventory Item Creation**: operator creates an item, sets
  initial stock.
- **Restock**: operator restocks (adds quantity).
- **Adjust**: admin adjusts (e.g. waste).
- **86 / Un-86**: operator 86s an item.
- **Order-Driven Decrement / Re-credit**: order events drive
  stock changes.
- **Auto-Restock**: scheduled cron restocks.
- **Cascade 86**: parent restaurant suspended / closed → 86
  all items.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Decrement that would make stock negative**: 422
  `INSUFFICIENT_STOCK`; the order is rejected at the
  `food-order-service` level.
- **Auto-restock job failure**: the job retries with backoff;
  a `support.ticket` is opened after 3 failures.
- **Low-stock threshold reached**: a `notification-service`
  message is sent to the owner.

## 14. Success Criteria

- 100% of order-driven stock changes are applied within 5 s.
- 100% of out-of-stock events are emitted within 1 s.
- 100% of cascade 86s reach downstream services within 60 s.
- P90 inventory item creation < 5 min operator time.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Inventory item creation (P90) | ≤ 5 min | operator UI timing |
| Stock decrement propagation (P95) | ≤ 5 s | synthetic probe |
| Out-of-stock emission (P95) | ≤ 1 s | synthetic probe |
| Auto-restock drift (P95) | ≤ 60 s | `auto_restock_drift_seconds` |
| Cascade 86 propagation (P95) | ≤ 60 s | synthetic probe |
| Low-stock alert latency (P95) | ≤ 30 s | `notification-service` lag |
| Availability lookup cache hit rate | ≥ 90% | `inventory_lookups_total{cache_hit}` |

## 16. Acceptance Criteria

- AC-1: A merchant owner can create an inventory item in <
  5 min.
- AC-2: Stock is decremented on order placement within 5 s.
- AC-3: Stock is re-credited on order cancellation within 5 s.
- AC-4: An out-of-stock event is emitted within 1 s of stock
  reaching 0.
- AC-5: A 86 is reflected in the cart within 10 s.
- AC-6: A suspended restaurant's items are all 86'd within
  60 s.
- AC-7: All admin actions are recorded with reason and actor.
- AC-8: The service meets its 99.9% SLO.
- AC-9: All state changes are emitted as events.
- AC-10: Stock is never negative.

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

