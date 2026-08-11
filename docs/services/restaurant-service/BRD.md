# restaurant-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and rules
for the `restaurant-service` — the canonical owner of the
**restaurant aggregate** (the operational brand under a merchant).
It is read by:

- Product managers scoping restaurant onboarding and lifecycle.
- Engineering leads planning the service's roadmap.
- Trust & Safety teams designing suspension flows.
- Search and discovery teams when surfacing restaurants to
  customers.
- Admin and support teams when designing operator consoles.

It informs decisions on restaurant onboarding, online/offline
behavior, suspension, and the relationship to menus, branches,
and orders.

## 2. Business Context

A **restaurant** is the operational brand that a customer sees and
orders from. It is distinct from the **merchant** (the legal
entity) and the **branch** (the physical location). This separation
exists so that:

- A merchant (legal entity) can operate multiple restaurant brands
  (e.g. a holding company running "Pizza Palace" and "Burger
  Barn").
- A restaurant can have multiple physical locations (branches).
- The customer-facing brand, cuisine, and rating are managed at
  the restaurant level; the legal, tax, and banking are managed
  at the merchant level.

Without this service, the platform would conflate operational and
legal concerns and could not support multi-brand merchants.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a merchant to onboard a restaurant in < 30 minutes (operator time) | `restaurant_creation_seconds` (P90) < 1,800 s |
| BR--002 | Ensure 100% of approved restaurants are searchable within 30 s of approval | `search_indexing_propagation_seconds` (P95) < 30 s |
| BR--003 | Propagate suspension to all downstream services within 60 s | `suspension_propagation_seconds` (P95) < 60 s |
| BR--004 | Reflect online/offline changes in cart and checkout within 30 s | `online_propagation_seconds` (P95) < 30 s |
| BR--005 | Display an accurate average rating for 100% of approved restaurants | `rating_freshness_seconds` (P95) < 300 s |
| BR--006 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--007 | Block checkouts for offline restaurants in 100% of cases | `offline_block_rate` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchant Owner | Operator | clean onboarding, low friction |
| Restaurant Operator (staff) | Day-to-day | quick online/offline toggle |
| Platform Admin | Reviewer | clear review queue, audit trail |
| Platform Trust & Safety | Quality | ability to suspend / reinstate fast |
| Customer (indirect) | End user | finds the restaurant; sees accurate rating |
| Search Service (system) | Consumer | up-to-date search index |
| Cart / Checkout (system) | Consumer | blocks orders when offline |

## 5. Actors / Personas

- **Merchant Owner**: creates and manages restaurants under their
  approved merchant. They submit the restaurant for review and
  toggle it online.
- **Restaurant Operator (staff)**: a delegated user (via
  ``restaurant-service` (staff)`) who can toggle online/offline and
  read but not change the profile.
- **Platform Admin**: reviews submitted restaurants, approves or
  rejects, and handles suspensions.
- **Customer (indirect)**: the end user who browses and orders
  from the restaurant. They do not interact with this service
  directly; their experience is mediated by the customer app.

## 6. Business Capabilities

- **Restaurant onboarding**: create a new restaurant under an
  approved merchant; submit for review.
- **Profile management**: name, description, cuisines, type, logo
  ref, brand color.
- **Lifecycle management**: drive the restaurant through
  `draft → pending_review → approved → online|offline → suspended
  → approved|closed → closed`.
- **Online / offline toggling**: by the operator (manual) or
  automatically (no open branch).
- **Admin review queue**: list view and per-restaurant review
  view.
- **Rating aggregation**: denormalize the average rating from
  ``trip-service` / `food-order-service` / `search-service` (review projections)` for fast reads.
- **Search projection**: emit `restaurant.updated.v1` for the
  search service.
- **Cascade suspension**: when the parent merchant is suspended,
  all `approved|online` restaurants of that merchant are
  suspended.
- **Audit and reporting**: every state change and admin action
  is recorded.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow an approved merchant to create a restaurant | MUST | Product |
| BR--011 | The service MUST store the restaurant's brand profile (name, type, cuisines, logo, description) | MUST | Product |
| BR--012 | The service MUST support an admin approval flow before a restaurant goes online | MUST | Trust & Safety |
| BR--013 | The service MUST support online / offline toggling by the operator | MUST | Operations |
| BR--014 | The service MUST support admin suspension with a required reason code | MUST | Trust & Safety |
| BR--015 | The service MUST support admin re-instatement with a required reason | MUST | Trust & Safety |
| BR--016 | The service MUST support permanent closure with reason | MUST | Lifecycle |
| BR--017 | The service MUST cascade parent merchant suspension to all approved restaurants | MUST | Trust & Safety |
| BR--018 | The service MUST cascade parent merchant closure to all restaurants | MUST | Lifecycle |
| BR--019 | The service MUST emit `restaurant.*.v1` events for every state change | MUST | Event architecture |
| BR--020 | The service MUST expose a fast `online` lookup for ``food-order-service` (cart)` and ``food-order-service` (checkout)` | MUST | Latency |
| BR--021 | The service MUST maintain a denormalized average rating from ``trip-service` / `food-order-service` / `search-service` (review projections)` | MUST | Product |
| BR--022 | The service MUST support re-submission after rejection | SHOULD | Product |
| BR--023 | The service MUST soft-delete restaurants on closure | MUST | Retention |
| BR--024 | The service MUST auto-set the restaurant offline when no branch is open (configurable) | SHOULD | Operations |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A restaurant can be created only if its parent merchant is `approved`. | enforced server-side |
| BR--031 | A restaurant can be approved only if it has: a logo, a name, ≥ 1 cuisine, a type. | enforced in approve |
| BR--032 | A restaurant can be `online` only if it is `approved` and at least one branch is open. | configurable |
| BR--033 | A restaurant can be suspended only if it is `approved` (online or offline). | enforced server-side |
| BR--034 | A `closed` restaurant cannot transition to any other state. | terminal |
| BR--035 | Suspension of the parent merchant overrides any operator-set online state. | cascade has priority |
| BR--036 | Re-instatement of the parent merchant restores the restaurant to `approved` but NOT to `online` (operator must re-enable). | safe default |
| BR--037 | Admin `suspend` and `close` require a `reason_code` from the platform enum and (for break-glass) a second admin's co-signature. | security |
| BR--038 | Re-submission preserves the prior rejection reason in the audit log. | audit |
| BR--039 | The average rating is denormalized; the source of truth is ``trip-service` / `food-order-service` / `search-service` (review projections)`. | read-side projection |

## 9. Assumptions

- A merchant is approved (verified by ``restaurant-service` (merchant)`) before
  creating a restaurant.
- The operator has a verified Keycloak identity (`merchant_owner`
  or `merchant_ops`).
- A `file-service` file id is provided for the logo (the operator
  uploads the logo via the file service).
- ``trip-service` / `food-order-service` / `search-service` (review projections)` is operational and emits
  `review.aggregated.v1`.
- `search-service` consumes `restaurant.updated.v1` to keep the
  search index fresh.
- The restaurant's parent merchant may operate other restaurants
  with overlapping brand or menu; the service does not enforce
  uniqueness across merchants.

## 10. Constraints

- The service is the source of truth for the restaurant brand
  only. It MUST NOT store order, payment, or prep state.
- The service MUST be deployable independently of ``restaurant-service` (merchant)`
  and ``restaurant-service` (branch)`. No shared migrations.
- The service MUST remain within the platform's PCI scope
  (SAQ-A); no card data is ever stored.
- The service MUST respect GDPR — only the minimum PII is stored
  (none, in fact — the brand profile is public).
- All admin actions are subject to HMAC-SHA256 request signing
  and break-glass co-signature per
  [`SECURITY_ARCHITECTURE.md`](../../architecture/SECURITY_ARCHITECTURE.md).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| ``restaurant-service` (merchant)` | service | parent merchant; emits cascade events |
| ``restaurant-service` (branch)` | service | branches under the restaurant; hours feed |
| ``restaurant-service` (menu)` | service | menu keyed by `restaurant_id` |
| `configuration-service` | service | cuisine list, type list, reason enums |
| `identity-service` | service | Keycloak subject verification |
| `geolocation-service` | service | service zone derivation |
| `notification-service` | service | lifecycle messages |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | service | denormalized rating source |
| `search-service` | service | consumes `restaurant.updated.v1` |
| ``food-order-service` (cart)` | service | consumes `restaurant.online/offline.v1` |
| ``food-order-service` (checkout)` | service | consumes `restaurant.online/offline.v1` |
| ``courier-service` (dispatch)` | service | consumes `restaurant.online/offline.v1` |
| `audit-service` | service | receives audit events |
| `file-service` | service | logo storage |
| Vault | infra | secrets |

## 12. Business Workflows

- **Restaurant Onboarding**: create, submit, admin review,
  approve.
- **Online / Offline**: operator toggles; auto-offline if no
  branch is open.
- **Cascade Suspension**: parent merchant suspended → restaurants
  suspended.
- **Cascade Re-instatement**: parent merchant reinstated →
  restaurants reinstated (but stay offline until operator
  enables).
- **Cascade Closure**: parent merchant closed → restaurants
  closed.
- **Admin Suspension**: directly suspend a restaurant.
- **Admin Re-instatement**: restore a suspended restaurant.
- **Permanent Closure**: terminal.
- **Re-submission after Rejection**.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Cuisine not in allowed list**: owner is asked to choose from
  the configured list; no rejection.
- **Logo missing**: `approve` is rejected with 422
  `LOGO_MISSING`.
- **No branch under the restaurant at approval time**: not
  blocking; the operator can create a branch later and toggle
  online.
- **Auto-offline if no branch open**: configurable; default
  enabled.
- **Admin accidental suspension**: re-instate within grace period;
  reason recorded.

## 14. Success Criteria

- 100% of approved restaurants are searchable within 30 s.
- 100% of state changes are emitted as events.
- 100% of suspension cascades reach downstream services within
  60 s.
- 100% of offline restaurants block checkouts.
- P90 restaurant creation < 30 minutes operator time.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Restaurant creation time (P90) | ≤ 30 min | operator UI timing |
| Search indexing lag (P95) | ≤ 30 s | `restaurant.updated.v1` → search index |
| Suspension propagation (P95) | ≤ 60 s | synthetic probe |
| Online propagation (P95) | ≤ 30 s | synthetic probe |
| Auto-offline accuracy | ≥ 99% | branch state vs. restaurant online |
| Rating freshness (P95) | ≤ 5 min | `review.aggregated.v1` → denormalized field |
| Admin review SLA (P90) | ≤ 48 h | `restaurant_approval_seconds` |

## 16. Acceptance Criteria

- AC-1: A merchant can create, submit, and have a restaurant
  approved in < 48 h.
- AC-2: An approved restaurant can be toggled online by an
  operator.
- AC-3: A suspended merchant's restaurants are all suspended
  within 60 s.
- AC-4: A `closed` merchant's restaurants are all closed.
- AC-5: The average rating field is updated within 5 min of
  `review.aggregated.v1`.
- AC-6: The service exposes a fast `online` lookup with P99 < 30
  ms (cache hit).
- AC-7: All admin actions are recorded with reason and actor.
- AC-8: The service meets its 99.95% SLO.
- AC-9: All state changes are emitted as events with
  `correlation_id`.
- AC-10: Soft delete preserves data for 7 years; hard delete only
  after retention.

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

