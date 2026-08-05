# driver-incentive-service — Business Requirements Document

## 1. Document Purpose

Read by product, growth, driver operations, and engineering to
align on what `driver-incentive-service` does. Incentives drive
supply; getting the rules and the timing right means a healthy
marketplace.

## 2. Business Context

A driver is more likely to be online during a high-demand window
if the platform offers an incentive. Quests, bonuses, and surge
guarantees are the levers. This service is the system of record
for those levers and for the calculation of the earned amount.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for driver incentives | 100% of incentive earnings originate here |
| BR--002 | Evaluate eligibility within 500ms p99 of `trip.completed.v1` | `driver_incentive_evaluation_seconds` p99 |
| BR--003 | Post the earned amount within 5 minutes of trip completion | p99 ≤ 5min |
| BR--004 | Be idempotent: replaying the same event does not double-earn | 100% |
| BR--005 | Respect the eligibility rules (rating, trip count) | 100% |
| BR--006 | Allow admin CRUD on quests / bonuses | always |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | match rate during surge |
| Growth | owner | quest design, conversion |
| Driver Operations | reviewer | bonus spend, fairness |
| Finance | reviewer | incentive spend, ROI |
| Engineering (Rides) | builder | correctness, performance |

## 5. Actors / Personas

- **`trip-service`** — emits the trigger event.
- **`driver-earnings-service`** — receives the earned amount.
- **Driver** — sees quest progress; opts in / out.
- **Admin** — CRUDs quests / bonuses.
- **Configuration** — defines the default rules.

## 6. Business Capabilities

- Define quests / bonuses / guarantees.
- Evaluate eligibility on each completed trip.
- Calculate the earned amount.
- Post the earned amount to `driver-earnings-service`.
- Emit `driver.incentive.earned.v1`.
- Surface quest progress to the driver.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST consume `trip.completed.v1` and evaluate eligibility. | MUST | Product |
| BR--011 | The service MUST compute the earned amount per the active incentive's rule. | MUST | Finance |
| BR--012 | The service MUST post the earned amount to `driver-earnings-service` with `Idempotency-Key=trip:{trip_id}:incentive`. | MUST | Finance |
| BR--013 | The service MUST respect the eligibility rules (rating, trip count). | MUST | Driver Operations |
| BR--014 | The service MUST emit `driver.incentive.earned.v1` on earning. | MUST | Platform Event Standards |
| BR--015 | The service MUST allow admin CRUD on quests / bonuses. | MUST | Product |
| BR--016 | The service MUST allow driver opt-in / opt-out for opted-in quests. | MUST | Product |
| BR--017 | The service MUST be idempotent: replaying the same event does not double-earn. | MUST | Finance |
| BR--018 | The service MUST record an audit event for every state transition. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A quest is active in a window (start, end). | Configurable. |
| BR--031 | A quest has a target (e.g. "20 trips") and a reward (e.g. "100 AED"). | |
| BR--032 | A bonus has a trigger (e.g. "5 trips in 2 hours") and a reward. | |
| BR--033 | A surge guarantee has a floor (e.g. "100 AED/hour") and a window. | |
| BR--034 | Eligibility is computed at evaluation time (rating, trip count). | |
| BR--035 | A driver who opts in to a quest is committed for the quest's duration. | |

## 9. Assumptions

- The trip's `final_fare` and `city_id` are known.
- The driver's rating and trip count are queryable via
  `driver-service`.
- The `driver-earnings-service` is the canonical ledger; we only
  post.

## 10. Constraints

- The evaluation must complete within 500ms p99 (the trip
  completion is on the hot path).
- The incentive ledger entries are append-only; corrections are
  negative entries with a reason.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `trip-service` | service | trigger event |
| `driver-service` | service | rating, trip count |
| `driver-earnings-service` | service | post earned amount |
| `configuration-service` | service | default rules |

## 12. Business Workflows

- **Evaluation on trip completed** — see `WORKFLOWS.md`.
- **Quest opt-in** — see `WORKFLOWS.md`.
- **Admin creates a quest** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- `driver-service` down: skip the rating check; fall back to the
  cached value; if no cache, skip the incentive.
- `driver-earnings-service` down: retry; on persistent failure,
  the trip is unearned; reconciliation catches.

## 14. Success Criteria

- Incentives are paid accurately and on time.
- Driver participation in quests is high.
- The platform's supply is responsive to surge.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Evaluation latency P99 | ≤ 500ms | `driver_incentive_evaluation_seconds` |
| Quest completion rate | > 30% | reporting |
| Incentive spend | within budget | reporting |
| Driver satisfaction with incentives | ≥ 4.0/5 | survey |

## 16. Acceptance Criteria

- Replaying `trip.completed.v1` for the same trip id does not
  double-earn.
- An ineligible driver does not earn.
- A driver who opts in to a quest sees the progress in the app.

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

