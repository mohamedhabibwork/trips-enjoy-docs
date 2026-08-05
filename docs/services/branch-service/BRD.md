# branch-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and rules
for the `branch-service` — the canonical owner of the **branch
aggregate** (a physical location of a restaurant). It is read by:

- Product managers scoping branch onboarding and operations.
- Engineering leads planning the service's roadmap.
- Trust & Safety teams designing suspension cascades.
- Operations teams when planning hours and capacity.
- Dispatch and search teams when surfacing branches to customers
  and couriers.

It informs decisions on branch onboarding, hours, prep capacity,
busy state, temporary closures, and the relationship to
restaurants, menus, and orders.

## 2. Business Context

A **branch** is a physical location from which food is prepared
and (optionally) served. It has:

- A real-world address and geographic point.
- Weekly hours and special hours / holidays.
- A prep capacity (max concurrent orders the kitchen can handle).
- A busy state (a signal that the kitchen is overwhelmed and new
  orders should be delayed or routed elsewhere).

A branch belongs to exactly one restaurant. A restaurant may have
many branches (e.g. a chain). This separation exists so that:

- Hours and capacity are managed per-location (a downtown branch
  closes at 23:00, a suburban branch at 22:00).
- A restaurant can be open in one city and not another.
- Operational metrics (busy, prep capacity) are location-specific.

Without this service, the platform could not support chains or
multi-location restaurants, and could not gracefully degrade
service during peak hours.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a merchant to onboard a branch in < 15 minutes (operator time) | `branch_creation_seconds` (P90) < 900 s |
| BR--002 | Ensure 100% of branches are searchable within 30 s of creation | `search_indexing_propagation_seconds` (P95) < 30 s |
| BR--003 | Reflect hours changes in cart and checkout within 30 s | `hours_propagation_seconds` (P95) < 30 s |
| BR--004 | Reflect busy state in dispatch within 10 s | `busy_propagation_seconds` (P95) < 10 s |
| BR--005 | Propagate temporary closures to downstream services within 30 s | `temp_closure_propagation_seconds` (P95) < 30 s |
| BR--006 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--007 | Block checkouts for closed / temp-closed branches in 100% of cases | `closed_block_rate` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchant Owner | Operator | clean onboarding, low friction |
| Restaurant Operator (staff) | Day-to-day | quick hours and busy toggling |
| Platform Admin | Reviewer | ability to close a branch in violation |
| Platform Trust & Safety | Quality | ability to temporarily close fast |
| Customer (indirect) | End user | sees correct open/closed status |
| Courier (indirect) | End user | sees correct busy state |
| Search Service (system) | Consumer | up-to-date search index |
| Cart / Checkout (system) | Consumer | blocks orders when closed |

## 5. Actors / Personas

- **Merchant Owner**: creates branches under their approved
  restaurants. They set the initial hours and capacity.
- **Restaurant Operator (staff)**: a delegated user who can
  toggle busy state, set temporary closures, and update hours
  during the day.
- **Platform Admin**: can close a branch permanently (e.g. for
  health-code violations).
- **Customer (indirect)**: sees the branch's open status in the
  app and cannot place orders when it's closed.
- **Courier (indirect)**: sees the branch's busy state and may
  be routed elsewhere if it's busy.

## 6. Business Capabilities

- **Branch onboarding**: create a branch under an approved
  restaurant; geocode the address; set default hours.
- **Hours management**: weekly hours (per day of week, with
  open/close time) and special hours (holidays, special events).
- **Temporary closure**: operator can close a branch for a
  period (e.g. equipment failure, staff shortage) without
  permanent closure.
- **Permanent closure**: admin action; terminal.
- **Busy state**: operator signals kitchen overwhelmed; affects
  dispatch.
- **Prep capacity**: max concurrent orders; affects dispatch.
- **Cascade handling**: parent restaurant suspended → all
  branches temporarily closed; parent restaurant closed → all
  branches permanently closed.
- **Zone check**: if a zone is updated and the branch falls out
  of a serving zone, the branch is temporarily closed.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow an approved restaurant to create a branch | MUST | Product |
| BR--011 | The service MUST geocode the branch address on creation | MUST | Operations |
| BR--012 | The service MUST support weekly hours per branch | MUST | Operations |
| BR--013 | The service MUST support special hours (holidays) per branch | MUST | Operations |
| BR--014 | The service MUST support temporary closure by the operator | MUST | Operations |
| BR--015 | The service MUST support permanent closure by the admin | MUST | Lifecycle |
| BR--016 | The service MUST support a busy state toggle | MUST | Operations |
| BR--017 | The service MUST support a prep capacity setting per branch | MUST | Operations |
| BR--018 | The service MUST emit `branch.*.v1` events for every state change | MUST | Event architecture |
| BR--019 | The service MUST cascade parent restaurant suspension to all branches (as temporary closures) | MUST | Trust & Safety |
| BR--020 | The service MUST cascade parent restaurant closure to all branches (as permanent closure) | MUST | Lifecycle |
| BR--021 | The service MUST auto-temporarily-close a branch that falls out of a serving zone | MUST | Operations |
| BR--022 | The service MUST expose fast `open` and `busy` lookups for `cart-service`, `checkout-service`, `courier-dispatch-service` | MUST | Latency |
| BR--023 | The service MUST soft-delete branches on closure | MUST | Retention |
| BR--024 | The service MUST support multiple branches per restaurant | MUST | Product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A branch can be created only if its parent restaurant is `approved`. | enforced server-side |
| BR--031 | A branch must be inside a serving zone to be created. | enforced via `zone-service` |
| BR--032 | A branch's address must be geocodable. | enforced via `geolocation-service` |
| BR--033 | A branch can be permanently closed only by an admin (or via cascade from a parent restaurant closure). | enforced server-side |
| BR--034 | A branch's `state` is `open`, `temporarily_closed`, or `closed`; `closed` is terminal. | state machine |
| BR--035 | Hours are interpreted in the branch's local timezone (IANA). | timezone-aware |
| BR--036 | Temporary closures have a start and end time; auto-clear after the end time. | scheduled job |
| BR--037 | The `busy` state is a soft signal; it does not block orders but affects dispatch prioritization. | dispatch decision |
| BR--038 | Prep capacity is the maximum number of concurrent orders; dispatch MUST NOT exceed it. | dispatch rule |
| BR--039 | Permanent closure of a parent restaurant is the only path to branch permanent closure via cascade. | inheritance |

## 9. Assumptions

- The parent restaurant is `approved` (verified by
  `restaurant-service`).
- A serving zone exists at the branch's location
  (`zone-service`).
- The branch's address is real and geocodable.
- The operator has a verified Keycloak identity
  (`restaurant_staff` or `merchant_owner`).
- `geolocation-service` and `zone-service` are operational.

## 10. Constraints

- The service is the source of truth for branches only. It MUST
  NOT store order, payment, or prep state.
- The service MUST be deployable independently of
  `restaurant-service` and `menu-service`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A).
- All admin actions are subject to HMAC-SHA256 request signing
  and break-glass co-signature per
  [`SECURITY_ARCHITECTURE.md`](../../architecture/SECURITY_ARCHITECTURE.md).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `restaurant-service` | service | parent; emits cascade events |
| `geolocation-service` | service | geocode (synchronous on create) |
| `zone-service` | service | zone validation |
| `configuration-service` | service | hours / capacity defaults |
| `identity-service` | service | Keycloak subject |
| `notification-service` | service | lifecycle messages |
| `menu-service` | service | menu (read; menus are keyed at restaurant level) |
| `cart-service` | service | consumes `open` / `closed` events |
| `checkout-service` | service | consumes `open` / `closed` events |
| `courier-dispatch-service` | service | consumes `open`, `busy`, `temp_closure` |
| `food-order-service` | service | reads branch ref |
| `restaurant-order-mgmt-service` | service | reads branch ref |
| `search-service` | service | consumes update events |
| `audit-service` | service | receives audit events |
| `file-service` | service | branch photo (optional) |
| Vault | infra | secrets |

## 12. Business Workflows

- **Branch Onboarding**: create, geocode, set hours.
- **Hours Change**: operator updates hours; downstream services
  re-evaluate.
- **Temporary Closure**: operator sets a window during which the
  branch is closed.
- **Permanent Closure**: admin closes the branch; terminal.
- **Busy Toggle**: operator signals busy; dispatch adapts.
- **Cascade Suspension**: parent restaurant suspended → all
  branches temporarily closed.
- **Cascade Closure**: parent restaurant closed → all branches
  permanently closed.
- **Zone Drift**: zone changes; branch auto-closed if outside.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Address not geocodable**: branch creation fails with
  422 `GEOCODE_FAILED`; the operator is asked to correct the
  address.
- **Outside serving zone**: branch creation fails with
  422 `OUT_OF_ZONE`; the operator is told the supported zones.
- **Auto-busy threshold exceeded**: when the dispatch observes
  that in-flight orders exceed a threshold, it may signal the
  branch as busy; the operator can also toggle it manually.

## 14. Success Criteria

- 100% of branches are searchable within 30 s of creation.
- 100% of state changes are emitted as events.
- 100% of cascade temporary closures reach downstream services
  within 30 s.
- 100% of `closed` branches block checkouts.
- P90 branch creation < 15 minutes operator time.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Branch creation time (P90) | ≤ 15 min | operator UI timing |
| Geocode success rate | ≥ 99% | `branch_geocode_success_total / branch_geocode_total` |
| Hours propagation (P95) | ≤ 30 s | synthetic probe |
| Busy propagation (P95) | ≤ 10 s | synthetic probe |
| Temp-closure propagation (P95) | ≤ 30 s | synthetic probe |
| Zone-drift detection (P95) | ≤ 5 min | reconciliation job |
| Open-lookup cache hit rate | ≥ 95% | `branch_open_lookups_total{cache_hit}` |

## 16. Acceptance Criteria

- AC-1: A merchant can create a branch and have it searchable
  in < 15 min.
- AC-2: The branch's open status is reflected in cart and
  checkout within 30 s of an hours change.
- AC-3: A busy toggle is reflected in dispatch within 10 s.
- AC-4: A temporary closure blocks checkouts.
- AC-5: A permanent closure is terminal; all writes return 410.
- AC-6: A suspended restaurant's branches are all temporarily
  closed within 30 s.
- AC-7: A closed restaurant's branches are all permanently
  closed.
- AC-8: A branch outside a serving zone is auto-temporarily
  closed within 5 min of the zone change.
- AC-9: All admin actions are recorded with reason and actor.
- AC-10: The service meets its 99.95% SLO.

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

