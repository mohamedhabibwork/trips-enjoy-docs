# cart-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and
rules for the `cart-service` — the canonical owner of the
**shopping cart aggregate** (the customer's in-progress order
before checkout). It is read by:

- Product managers scoping cart UX.
- Engineering leads planning the service's roadmap.
- Customer experience teams when designing the cart flow.
- Marketing teams when designing promo application.

It informs decisions on cart lifecycle, item management,
promotion application, re-quote on price / availability
change, and abandonment.

## 2. Business Context

A **cart** is the customer's in-progress order before checkout.
It holds:

- The customer id.
- The branch id (the kitchen that will prepare the order).
- Items (product, quantity, modifiers, add-ons).
- Applied promotions.
- The subtotal (sub-quote from `pricing-service`).
- A delivery address reference.
- A tip.
- Lifecycle state: `active`, `abandoned`, `checked_out`.

The cart is the bridge between the customer browsing the menu
and the checkout session. It is the source of truth for "what
the customer intends to order" until they check out.

Without this service, the platform could not support
re-quoting on price / availability change, promotion
application, or cart abandonment.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a customer to add an item in < 1 second | `cart_add_item_seconds` (P99) < 1 s |
| BR--002 | Re-quote on price / availability change within 5 s | `cart_re_quote_seconds` (P95) < 5 s |
| BR--003 | Detect abandonment within 30 minutes of last activity | `cart_abandonment_seconds` (P95) < 1,800 s |
| BR--004 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--005 | Block checkout when the restaurant is offline in 100% of cases | `offline_block_rate` = 1.00 |
| BR--006 | Apply promotions with 100% accuracy (no double-application) | `promo_double_application_rate` = 0.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Customer | End user | fast, accurate cart; re-quote |
| Merchant (indirect) | Seller | cart abandonment data |
| Marketing | Owner | promo application |
| Customer Service | Support | cart history |

## 5. Actors / Personas

- **Customer**: adds items, applies promos, edits the cart,
  checks out.
- **Customer (indirect via events)**: receives notifications
  on price / availability change.
- **Marketing (indirect)**: defines promotions; the cart
  applies them.

## 6. Business Capabilities

- **Cart CRUD**: create, read, update, abandon.
- **Item management**: add, update, remove items with
  modifiers and add-ons.
- **Promotion application**: apply and remove promo codes.
- **Sub-quote**: request a quote from `pricing-service` and
  cache.
- **Re-quote**: on price or availability change.
- **Abandonment detection**: cron job marks carts as
  abandoned after 30 min idle.
- **Checkout handoff**: `POST /v1/carts/{id}/checkout` creates
  a checkout session in `checkout-service` (the cart is
  marked `checked_out`).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow a customer to create a cart | MUST | Product |
| BR--011 | The service MUST allow adding items with modifiers and add-ons | MUST | Product |
| BR--012 | The service MUST support promotion application with idempotency | MUST | Product |
| BR--013 | The service MUST re-quote on `menu.item.price.changed.v1` | MUST | Product |
| BR--014 | The service MUST remove items on `menu.item.unavailable.v1` | MUST | Product |
| BR--015 | The service MUST block checkout when the restaurant is offline | MUST | Operations |
| BR--016 | The service MUST mark carts as abandoned after 30 min idle | MUST | Product |
| BR--017 | The service MUST emit `cart.*.v1` events for every state change | MUST | Event architecture |
| BR--018 | The service MUST hard-delete abandoned carts after 30 days | MUST | Retention |
| BR--019 | The service MUST support a max of 50 items per cart | SHOULD | Product |
| BR--020 | The service MUST support a max of 20 quantity per item | SHOULD | Product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A cart belongs to exactly one customer and one branch. | enforced |
| BR--031 | Items can only be added to an `active` cart. | enforced |
| BR--032 | A promotion is applied with an idempotency key; double-application is prevented. | enforced |
| BR--033 | A cart with a `checkout_blocked = true` flag cannot proceed to checkout. | enforced |
| BR--034 | The subtotal is recomputed on any item / promo / address change. | enforced |
| BR--035 | Re-quote on price change: the new subtotal replaces the old; the customer is notified of the diff. | enforced |
| BR--036 | Re-quote on unavailability: the item is removed; the subtotal is recomputed; the customer is notified. | enforced |
| BR--037 | Cart abandonment is detected by a cron job that runs every 5 minutes. | scheduled |
| BR--038 | The customer's `id` is held for ownership checks; the cart is otherwise anonymous. | enforced |

## 9. Assumptions

- The customer has a verified Keycloak identity.
- The branch is `open` and the restaurant is `online` when
  items are added (a soft warning is shown, not a hard block).
- The `pricing-service`, `promotion-service`, `menu-service`,
  and `restaurant-service` are operational.
- The customer app sends an `Idempotency-Key` on all writes.

## 10. Constraints

- The service is the source of truth for the cart only. It
  MUST NOT store order, payment, or prep state.
- The service MUST be deployable independently of
  `checkout-service` and `food-order-service`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A); no card data is ever stored.
- The service MUST respect GDPR — only the customer's id is
  stored; the cart contents are short-lived.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `customer-service` | service | verify customer |
| `menu-service` | service | read products, prices, modifiers |
| `restaurant-service` | service | read online status |
| `promotion-service` | service | validate / apply promo |
| `pricing-service` | service | sub-quote |
| `checkout-service` | service | checkout handoff |
| `food-order-service` | service | read after checkout |
| `configuration-service` | service | limits, idle timeout |
| `notification-service` | service | notify customer |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Cart Creation and Item Add**: customer creates a cart,
  adds items.
- **Promotion Application**: customer applies a promo code.
- **Re-quote on Price Change**: menu event triggers re-quote.
- **Re-quote on Unavailability**: menu event triggers item
  removal.
- **Checkout Handoff**: `POST /checkout` creates a checkout
  session; the cart is marked `checked_out`.
- **Abandonment**: cron job marks idle carts as abandoned.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Restaurant goes offline mid-cart**: the cart is flagged
  `checkout_blocked`; the customer is notified.
- **All items removed due to unavailability**: the cart is
  empty but remains `active`; the customer is notified.
- **Promotion validation failure**: 422 `PROMO_INVALID`.

## 14. Success Criteria

- 100% of state changes are emitted as events.
- 100% of re-quotes are applied within 5 s.
- 100% of offline restaurants block checkout.
- P99 add-item latency < 1 s.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Add-item latency (P99) | ≤ 1 s | `cart_add_item_seconds` |
| Re-quote latency (P95) | ≤ 5 s | `cart_re_quote_seconds` |
| Abandonment detection (P95) | ≤ 30 min | `cart_abandonment_seconds` |
| Cart-to-checkout conversion | (varies) | analytics |
| Average cart size | (varies) | `cart_items_total` |
| Re-quote rate (P95) | ≤ 5% of carts | `cart_re_quote_total / cart_updated_total` |

## 16. Acceptance Criteria

- AC-1: A customer can add an item in < 1 s.
- AC-2: A price change is reflected in the cart within 5 s.
- AC-3: An unavailable item is removed from the cart within
  5 s.
- AC-4: An offline restaurant blocks checkout.
- AC-5: A cart idle for 30 min is marked abandoned.
- AC-6: All state changes are emitted as events.
- AC-7: The service meets its 99.9% SLO.
- AC-8: The service stores no card data.
- AC-9: The service supports a max of 50 items per cart.
- AC-10: Abandoned carts are hard-deleted after 30 days.

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

