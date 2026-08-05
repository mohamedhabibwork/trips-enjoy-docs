# checkout-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and
rules for the `checkout-service` — the canonical owner of the
**checkout session aggregate** (the customer's pre-payment
state). It is read by:

- Product managers scoping the checkout UX.
- Engineering leads planning the service's roadmap.
- Payment teams when designing the authorization flow.
- Trust & Safety teams when designing the failure path.

It informs decisions on session lifecycle, address and slot
selection, payment method selection, final quote freezing,
and idempotency.

## 2. Business Context

A **checkout session** is the customer's pre-payment state. It
holds:

- The cart id (and a snapshot of the cart contents at session
  creation).
- The customer id.
- The delivery address id.
- The delivery slot.
- The payment method id.
- The final quote (frozen for the session).
- Lifecycle state: `pending`, `completed`, `failed`,
  `expired`.

The session is the bridge between the cart and the payment
authorization. It freezes the quote so that the customer is
charged exactly what they agreed to (modulo tax re-calculation
on address change).

Without this service, the platform could not guarantee
quote stability, idempotent retries, or clean session
expiration.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a customer to start checkout in < 1 second | `checkout_create_seconds` (P99) < 1 s |
| BR--002 | Authorize payment in < 3 seconds (P99) | `checkout_authorize_seconds` (P99) < 3 s |
| BR--003 | Reflect payment failure within 5 s | `checkout_failure_seconds` (P95) < 5 s |
| BR--004 | Expire stale sessions within 5 minutes of TTL | `checkout_expiration_seconds` (P95) < 300 s |
| BR--005 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--006 | Block payment when the restaurant is offline in 100% of cases | `offline_block_rate` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Customer | End user | fast, accurate checkout |
| Payment (system) | Consumer | authorization flow |
| Restaurant (indirect) | Seller | accurate quote |

## 5. Actors / Personas

- **Customer**: starts checkout, selects address and slot,
  selects payment method, pays.
- **Customer (indirect via events)**: receives notifications on
  payment success / failure.

## 6. Business Capabilities

- **Session CRUD**: create, read, update, cancel.
- **Address selection**: pick from saved addresses.
- **Slot selection**: pick a delivery slot (or "as soon as
  possible").
- **Payment method selection**: pick a saved method.
- **Final quote**: request a frozen quote from
  `pricing-service`; the session stores it.
- **Payment authorization**: call `payment-service` to
  authorize; on success, create the food order.
- **Session expiration**: sessions expire after TTL if not
  paid.
- **Idempotency**: retries on `POST /pay` are safe.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow a customer to create a checkout session from a cart | MUST | Product |
| BR--011 | The service MUST snapshot the cart contents at session creation | MUST | Product |
| BR--012 | The service MUST support address, slot, and payment method selection | MUST | Product |
| BR--013 | The service MUST freeze the final quote for the session | MUST | Financial |
| BR--014 | The service MUST authorize payment on `POST /pay` and create the food order on success | MUST | Product |
| BR--015 | The service MUST expire sessions after TTL (default 15 min) | MUST | Operations |
| BR--016 | The service MUST block `POST /pay` when the restaurant is offline | MUST | Operations |
| BR--017 | The service MUST emit `checkout.completed.v1` on success and `checkout.failed.v1` on failure | MUST | Event architecture |
| BR--018 | The service MUST be idempotent on `POST /pay` retries | MUST | Financial |
| BR--019 | The service MUST hard-delete expired sessions after 7 days | MUST | Retention |
| BR--020 | The service MUST support a saved payment method flow | MUST | Product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A session is created from an `active` cart only. | enforced |
| BR--031 | The cart contents are snapshotted at session creation and frozen for the session. | enforced |
| BR--032 | The final quote is the result of the most recent `pricing-service` call. | enforced |
| BR--033 | `POST /pay` is idempotent: a retry with the same `Idempotency-Key` returns the prior result. | enforced |
| BR--034 | A session in `completed` state is terminal. | enforced |
| BR--035 | A session that expires transitions to `expired`; the cart is re-enabled. | enforced |
| BR--036 | A `pay_blocked = true` flag prevents `POST /pay` from succeeding. | enforced |
| BR--037 | The session is created in the same DB transaction as the cart snapshot; atomicity is critical. | enforced |

## 9. Assumptions

- The customer has a verified Keycloak identity.
- The cart is `active` and has at least one item.
- The customer has at least one saved payment method (or
  `payment-service` supports a "new card" flow that returns a
  `payment_method_id`).
- The address is in a serving zone.
- `pricing-service` and `payment-service` are operational.

## 10. Constraints

- The service is the source of truth for the session only. It
  MUST NOT store payment intent state, order state, or cart
  state (the latter is read-only).
- The service MUST be deployable independently of
  `payment-service` and `food-order-service`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A); no card data is ever stored.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `cart-service` | service | cart contents (read) |
| `pricing-service` | service | final quote |
| `address-service` | service | saved addresses |
| `payment-service` | service | authorization |
| `customer-service` | service | default payment method |
| `food-order-service` | service | create order |
| `restaurant-service` | service | online check |
| `branch-service` | service | open check |
| `notification-service` | service | customer notifications |
| `configuration-service` | service | TTL, slot lead time |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Session Creation**: customer starts checkout; cart is
  snapshotted; the final quote is frozen.
- **Session Update**: customer changes address / slot / tip /
  payment method; the quote is re-frozen.
- **Payment Authorization and Order Creation**: customer pays;
  the service authorizes; on success, the food order is
  created.
- **Session Expiration**: cron job expires stale sessions.
- **Payment Failure**: payment fails; the session is marked
  `failed`; the customer is notified; the cart is re-enabled.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Restaurant goes offline mid-session**: `pay_blocked = true`;
  `POST /pay` returns 409.
- **Payment fails**: the session is `failed`; the customer
  can update the payment method and retry.
- **Session expires**: the cart is re-enabled; the customer
  can re-checkout.

## 14. Success Criteria

- 100% of state changes are emitted as events.
- 100% of `POST /pay` retries are idempotent.
- 100% of offline restaurants block payment.
- P99 authorize latency < 3 s.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Session creation (P99) | ≤ 1 s | `checkout_create_seconds` |
| Authorize (P99) | ≤ 3 s | `checkout_authorize_seconds` |
| Failure propagation (P95) | ≤ 5 s | `checkout_failure_seconds` |
| Expiration detection (P95) | ≤ 5 min | `checkout_expiration_seconds` |
| Conversion rate (session → order) | (varies) | analytics |
| Cart abandonment rate | (varies) | analytics |

## 16. Acceptance Criteria

- AC-1: A customer can start checkout in < 1 s.
- AC-2: The final quote is frozen for the session.
- AC-3: `POST /pay` is idempotent on retries.
- AC-4: An offline restaurant blocks payment.
- AC-5: A session idle for 15 min is marked expired.
- AC-6: All state changes are emitted as events.
- AC-7: The service meets its 99.95% SLO.
- AC-8: The service stores no card data.
- AC-9: A session in `completed` is terminal.
- AC-10: Expired sessions are hard-deleted after 7 days.

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

