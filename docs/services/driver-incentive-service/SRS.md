# driver-incentive-service — Software Requirements Specification

## 1. Introduction

This document specifies the requirements for
`driver-incentive-service`. The service is on the trip-completion
hot path; the evaluation must be fast and correct.

## 2. Scope

In scope:

- Quest / bonus / guarantee definitions.
- Evaluation on `trip.completed.v1`.
- Posting the earned amount to `driver-earnings-service`.
- Driver opt-in / opt-out.
- Quest progress.

Out of scope:

- The trip aggregate.
- The driver earnings ledger.
- Surge pricing.

## 3. System Context

```mermaid
flowchart LR
    TR[trip-service] -. trip.completed.v1 .-> DI[driver-incentive-service]
    DI --> DRV[driver-service]
    DI --> DE[driver-earnings-service]
    DI -. driver.incentive.earned.v1 .-> K[(Kafka)]
    K --> DE
    K --> NOT[notification-service]
    K --> RP[reporting-service]
```

## 4. Actors

- **Driver app** — JWT role `driver`. Read progress; opt in / out.
- **`trip-service`** — system actor via events.
- **`driver-service`** — system actor via REST.
- **`driver-earnings-service`** — system actor via REST and events.
- **Admin** — CRUD.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | On `trip.completed.v1`, evaluate the active quests / bonuses / guarantees for the driver. | MUST |
| FR--002 | Reject duplicate evaluation (same idempotency key) with no-op. | MUST |
| FR--003 | Post the earned amount to `driver-earnings-service` with `Idempotency-Key=trip:{trip_id}:incentive`. | MUST |
| FR--004 | Emit `driver.incentive.earned.v1` on earning. | MUST |
| FR--005 | `GET /v1/incentives/quests` returns the active quests for the driver. | MUST |
| FR--006 | `GET /v1/incentives/quests/{id}/progress` returns the driver's progress. | MUST |
| FR--007 | `POST /v1/incentives/quests/{id}/opt-in` and `POST /v1/incentives/quests/{id}/opt-out`. | MUST |
| FR--008 | Admin CRUD on quests / bonuses via `POST /v1/incentives`, `PATCH /v1/incentives/{id}`, `POST /v1/incentives/{id}/disable`. | MUST |
| FR--009 | All events go through the transactional outbox. | MUST |
| FR--010 | All eligibility rules are evaluated at evaluation time. | MUST |
| FR--011 | The eligibility rules include rating, trip count, opt-in status, time window, zone. | MUST |
| FR--012 | An incentive can be disabled (soft delete). | MUST |
| FR--013 | Surge guarantee: the driver earns at least the floor for the window, regardless of trip count. | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P99 evaluation latency | ≤ 500ms |
| NFR--002 | performance | P95 posting latency | ≤ 1s |
| NFR--003 | availability | uptime | 99.5% (Tier-3) |
| NFR--004 | scalability | concurrent evaluations | 50k/s per region |
| NFR--005 | maintainability | MTTR for a bad deploy | ≤ 15 minutes |
| NFR--006 | observability | tracing coverage | 100% |

## 7. API Requirements

REST per `architecture/API_STANDARDS.md`. `Idempotency-Key` required
on `POST /v1/incentives/quests/{id}/opt-in` and
`POST /v1/incentives/quests/{id}/opt-out`. Admin endpoints require
`X-Audit-Reason`. Errors use the standard envelope. Full contract
in `INTEGRATION.md`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | All PKs are UUIDv7 | |
| DATA--002 | All timestamps `timestamptz` UTC | RFC3339 at the wire |
| DATA--003 | Money in `amount_minor BIGINT` with `currency CHAR(3)` | no floats |
| DATA--004 | Cross-service refs (`driver_id`, `trip_id`) as UUID without FKs | |
| DATA--005 | Quest / bonus / guarantee definitions stored as JSONB (rules) | flexible |
| DATA--006 | Audit columns on every mutable table | platform standard |
| DATA--007 | Soft delete for incentives (`disabled_at`) | |

## 9. Validation Rules

- A quest's window must be valid (start < end).
- A quest's target and reward must be positive.
- An opt-in is allowed only if the driver is eligible for the
  quest's eligibility rules.

## 10. State Transitions

N/A (the service is largely event-driven; the
`IncentiveEarning` row is append-only).

## 11. Authorization Requirements

- Driver can read own quests; opt in / out.
- Admin can CRUD.
- The evaluation endpoint is system-only (consumed via events).

## 12. Configuration Requirements

Consumed from `configuration-service` and refreshed on
`configuration.updated.v1`. See `README.md` §13.

## 13. Error Handling

| Error | Response | Recovery |
|-------|----------|----------|
| Invalid quest | 422 `INVALID_QUEST` | none |
| Opt-in already | 409 `ALREADY_OPTED_IN` | none |
| Driver ineligible | 422 `INELIGIBLE` | none |
| `driver-service` down | skip rating check | fall back to cache |
| `driver-earnings-service` down | retry | backoff; on persistent, page |

## 14. Concurrency Requirements

- The driver's opt-in is unique per quest; the row lock prevents
  double opt-in.
- The evaluation is idempotent by `(trip_id, incentive_id)`.

## 15. Idempotency Requirements

- `Idempotency-Key=trip:{trip_id}:incentive:{incentive_id}` on the
  earning.
- All event handlers are idempotent by `event_id`.

## 16. Performance

- Dominant path: evaluation on `trip.completed.v1`.
- P50 / P95 / P99: 50ms / 200ms / 500ms.

## 17. Scalability

- Horizontal: stateless, scale by HPA on
  `driver_incentive_evaluation_seconds_p99` and CPU.
- The active quests are cached in Redis for 5 minutes.

## 18. Availability

- SLO: 99.5% over 30 days (Tier-3 — incentives are nice-to-have,
  not revenue-critical).
- Error budget: ~3h 36m per 30 days.
- Maintenance window: weekly Sun 04:00–06:00 UTC.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require a valid JWT bearer token | gateway validates |
| SEC--002 | Driver ownership is enforced | `driver_id == sub` |
| SEC--003 | Admin actions require `X-Audit-Reason` | |
| SEC--004 | Idempotency keys are opaque UUIDs | |
| SEC--005 | TLS 1.3 at edge; mTLS in cluster | platform standard |

## 20. Privacy

- PII stored: none beyond driver_id and trip_id.
- Retention: 7 years for incentive earnings (financial).
- Erasure: per GDPR, identifiers are erased; financial records
  retained de-identified.

## 21. Auditability

- Every incentive creation / update / disable is logged at `warn`
  and emitted to `audit-service`.
- Every earning is logged at `info` with the rule that fired.

## 22. Observability

- Logs: JSON to stdout with `correlation_id`, `service`,
  `version`, `route`, `latency_ms`, `status`.
- Metrics: see `README.md` §15.
- Traces: OpenTelemetry.
- Alerts: SLO burn-rate, evaluation lag, posting failure rate.

## 23. Maintainability

- Code style: TypeScript with `strict: true`; ESLint + Prettier.
- Test coverage: ≥ 80% line / branch.
- Documentation: this folder.

## 24. Disaster Recovery

- RPO: ≤ 5 minutes.
- RTO: ≤ 30 minutes. The incentive earnings are reproducible from
  the `trip.completed.v1` events.

## 25. Acceptance Criteria

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

