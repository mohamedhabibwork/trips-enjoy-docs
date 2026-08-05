# menu-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and
rules for the `menu-service` — the canonical owner of the
**menu aggregate** (categories, products, modifiers, add-ons).
It is read by:

- Product managers scoping menu management.
- Engineering leads planning the service's roadmap.
- Restaurant operators when designing the operator console.
- Search and discovery teams when surfacing items to customers.

It informs decisions on menu onboarding, draft / published
lifecycle, price changes, per-item unavailability, and the
relationship to inventory, taxes, and orders.

## 2. Business Context

A **menu** is the hierarchical catalog of items a restaurant
offers. It is distinct from the restaurant (brand) and the
branch (location). The menu hierarchy is:

- **Menu** (one or more per restaurant; e.g. "Lunch" and
  "Dinner").
- **Category** (e.g. "Starters", "Mains", "Desserts").
- **Product** (e.g. "Margherita Pizza").
- **Modifier** (e.g. "Size", "Spice Level") with options (e.g.
  "Small", "Medium", "Large").
- **Add-on** (e.g. "Extra Cheese", "Avocado").

The menu has a draft / published lifecycle; only published items
are visible to customers and orderable. Prices can change over
time, and the platform tracks price history. Items can be
temporarily unavailable (the "86" operation in restaurant slang).

Without this service, the platform could not support a
hierarchical menu, modifiers, add-ons, or 86 logic; the
cart and checkout services would have no canonical source of
truth for products and prices.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a merchant owner to create and publish a menu in < 30 minutes (operator time) | `menu_publish_seconds` (P90) < 1,800 s |
| BR--002 | Ensure 100% of published menus are searchable within 30 s of publication | `search_indexing_propagation_seconds` (P95) < 30 s |
| BR--003 | Reflect price changes in active carts within 30 s | `price_change_propagation_seconds` (P95) < 30 s |
| BR--004 | Reflect 86 in active carts within 10 s | `unavailable_propagation_seconds` (P95) < 10 s |
| BR--005 | Propagate restaurant suspension to unpublish menus within 60 s | `unpublish_propagation_seconds` (P95) < 60 s |
| BR--006 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--007 | Block ordering of unavailable items in 100% of cases | `unavailable_block_rate` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchant Owner | Operator | clean menu onboarding |
| Restaurant Manager (staff) | Day-to-day | quick 86, price changes |
| Restaurant Kitchen (staff) | Operator | 86 items when out of ingredients |
| Platform Admin | Reviewer | full access |
| Customer (indirect) | End user | sees correct menu and prices |
| Cart / Checkout (system) | Consumer | accurate menu state for quoting |
| Search (system) | Consumer | index |

## 5. Actors / Personas

- **Merchant Owner**: creates and publishes menus under their
  approved restaurant.
- **Restaurant Manager**: edits menus, changes prices, 86s
  items.
- **Kitchen Staff**: can 86 items (e.g. out of an ingredient)
  but cannot edit prices.
- **Platform Admin**: full access.

## 6. Business Capabilities

- **Menu CRUD**: create menus under an approved restaurant.
- **Category / Product CRUD**: hierarchical CRUD with
  validation.
- **Modifier and Add-on CRUD**: per-product, with prices.
- **Draft / Published lifecycle**: only published items are
  visible to customers.
- **Price change with effective date**: a new price can be
  scheduled to take effect at a future time.
- **86 (unavailability)**: per-item unavailability with a
  reason.
- **Cascade handling**: parent restaurant suspension /
  closure → unpublish menus.
- **Stock-driven 86**: an out-of-stock event from
  `inventory-service` can auto-86 a product (configurable).
- **Tax integration**: tax codes are denormalized from
  `tax-service` and cached.
- **Photo management**: product photos via `file-service` refs.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow an approved restaurant to create a menu | MUST | Product |
| BR--011 | The service MUST support draft / published lifecycle | MUST | Product |
| BR--012 | The service MUST support hierarchical categories and products | MUST | Product |
| BR--013 | The service MUST support per-product modifiers with options | MUST | Product |
| BR--014 | The service MUST support per-product add-ons with prices | MUST | Product |
| BR--015 | The service MUST support price changes with optional effective date | MUST | Product |
| BR--016 | The service MUST support per-item unavailability (`86`) with a reason | MUST | Operations |
| BR--017 | The service MUST cascade parent restaurant suspension to unpublish menus | MUST | Trust & Safety |
| BR--018 | The service MUST cascade parent restaurant closure to unpublish menus | MUST | Lifecycle |
| BR--019 | The service MUST auto-86 a product on `inventory.item.out_of_stock.v1` (configurable) | SHOULD | Operations |
| BR--020 | The service MUST emit `menu.*.v1` events for every state change | MUST | Event architecture |
| BR--021 | The service MUST expose fast menu lookups for `cart-service`, `checkout-service`, `restaurant-order-mgmt-service` | MUST | Latency |
| BR--022 | The service MUST track price history (current and previous) | MUST | Financial |
| BR--023 | The service MUST soft-delete menus, categories, products on deletion | MUST | Retention |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A menu can be created only if its parent restaurant is `approved`. | enforced server-side |
| BR--031 | A menu can be published only if it has at least 1 category and 1 product. | enforced server-side |
| BR--032 | A published product with price `≤ 0` is invalid; publish is rejected. | enforced |
| BR--033 | Price changes are recorded in `product_price_history`; the current price is the latest. | audit |
| BR--034 | A 86'd product is not orderable; the cart removes it on `menu.item.unavailable.v1`. | enforced |
| BR--035 | A 86 is recorded with a reason code (`out_of_ingredient`, `quality`, `manual`, `stock`). | enum |
| BR--036 | A product can be 86'd by a kitchen staff (limited role) without editing prices. | RBAC |
| BR--037 | Unpublishing a menu transitions all its `published` products to `draft`; cart items are flagged stale. | state machine |
| BR--038 | Re-publishing a menu requires admin or owner review. | enforced |
| BR--039 | Modifiers and add-ons have a price modifier (in minor units); the cart computes the final price. | computed |

## 9. Assumptions

- The parent restaurant is `approved` (verified by
  `restaurant-service`).
- The operator has a verified Keycloak identity
  (`merchant_owner`, `restaurant_manager`, or kitchen staff).
- A `file-service` file id is provided for product photos.
- `tax-service` is operational and provides tax codes.
- `inventory-service` is operational and emits
  `inventory.item.out_of_stock.v1`.
- The merchant's primary currency is the same as the menu's
  currency (multi-currency is out of scope for v1).

## 10. Constraints

- The service is the source of truth for menu data only. It
  MUST NOT store order, payment, or prep state.
- The service MUST be deployable independently of
  `restaurant-service` and `inventory-service`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A); no card data is ever stored.
- All admin actions are subject to HMAC-SHA256 request signing
  per
  [`SECURITY_ARCHITECTURE.md`](../../architecture/SECURITY_ARCHITECTURE.md).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `restaurant-service` | service | parent; cascade events |
| `tax-service` | service | tax codes (cached) |
| `inventory-service` | service | stock state; emits `inventory.item.out_of_stock.v1` |
| `file-service` | service | product photos |
| `configuration-service` | service | menu config, limits, policies |
| `identity-service` | service | Keycloak subject |
| `notification-service` | service | lifecycle messages |
| `cart-service` | service | consumes menu events |
| `checkout-service` | service | reads menu |
| `food-order-service` | service | reads menu (line items) |
| `restaurant-order-mgmt-service` | service | reads menu (kitchen view) |
| `search-service` | service | consumes update events |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Menu Onboarding and Publication**: create menu, add
  categories and products, publish.
- **Price Change**: operator changes a price (with optional
  effective date); cart re-quotes.
- **86 an Item**: operator 86s an item; cart removes it.
- **Un-86 an Item**: operator un-86s; cart may re-add (subject
  to re-validation).
- **Cascade Unpublish**: parent restaurant suspended / closed
  → all menus unpublished.
- **Stock-Driven 86**: inventory event auto-86s a product.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Publish without categories**: 422 `MENU_EMPTY`.
- **Product with zero / negative price**: 422 `PRICE_INVALID`.
- **86 by kitchen staff**: allowed; the audit log records the
  role.
- **Auto-86 conflict**: if the product is already 86'd manually
  with a different reason, the auto-86 does not overwrite; the
  manual reason wins.

## 14. Success Criteria

- 100% of published menus are searchable within 30 s.
- 100% of state changes are emitted as events.
- 100% of cascade unpublish reach downstream services within
  60 s.
- 100% of 86'd items are removed from active carts within 10 s.
- P90 menu publish < 30 minutes operator time.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Menu publish time (P90) | ≤ 30 min | operator UI timing |
| Search indexing lag (P95) | ≤ 30 s | `menu.published.v1` → search index |
| Price change propagation (P95) | ≤ 30 s | synthetic probe |
| 86 propagation (P95) | ≤ 10 s | synthetic probe |
| Unpublish propagation (P95) | ≤ 60 s | synthetic probe |
| Menu lookup cache hit rate | ≥ 90% | `menu_lookups_total{cache_hit}` |

## 16. Acceptance Criteria

- AC-1: A merchant can create, populate, and publish a menu in
  < 30 min.
- AC-2: A published menu is searchable within 30 s.
- AC-3: A price change is reflected in active carts within 30
  s.
- AC-4: A 86 is reflected in active carts within 10 s.
- AC-5: A suspended restaurant's menus are all unpublished
  within 60 s.
- AC-6: A closed restaurant's menus are all unpublished.
- AC-7: All admin actions are recorded with reason and actor.
- AC-8: The service meets its 99.95% SLO.
- AC-9: All state changes are emitted as events.
- AC-10: Soft delete preserves data for 7 years.

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

