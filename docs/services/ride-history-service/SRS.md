# ride-history-service — Software Requirements Specification

## 1. Introduction

This document specifies the requirements for
`ride-history-service`. The service is a read model; correctness
is defined by eventual consistency with the source of truth.

## 2. Scope

In scope:

- Projection of `trip.completed.v1`,
  `ride.payment.completed.v1`, `review.submitted.v1`.
- Per-customer / per-driver / admin reads.
- Pagination and filtering.
- Caching.
- Retention.

Out of scope:

- The trip aggregate.
- Payment capture.
- Reviews.

## 3. System Context

```mermaid
flowchart LR
    TR[trip-service] -. trip.completed.v1 .-> RH[ride-history-service]
    RPI[ride-payment-integration-service] -. ride.payment.completed.v1 .-> RH
    REV[review-rating-service] -. review.submitted.v1 .-> RH
    RH --> PG[(PostgreSQL 18)]
    RH --> RD[(Redis)]
    C[Customer app] --> RH
    DR[Driver app] --> RH
    ADM[Admin] --> RH
```

## 4. Actors

- **Customer app** — JWT role `customer`. Read own.
- **Driver app** — JWT role `driver`. Read own.
- **Admin / support** — JWT roles. Read all.
- **`trip-service`**, **`ride-payment-integration-service`**,
  **`review-rating-service`** — system actors via events.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | On `trip.completed.v1`, upsert a `ride_history.entries` row. | MUST |
| FR--002 | On `ride.payment.completed.v1`, update the entry's `fare`, `payment_status=paid`. | MUST |
| FR--003 | On `review.submitted.v1`, update the entry's `rating`, `review_comment`. | MUST |
| FR--004 | `GET /v1/history/trips` returns the caller's entries, paginated, with filters. | MUST |
| FR--005 | `GET /v1/history/trips/{id}` returns one entry. | MUST |
| FR--006 | `GET /v1/drivers/{driver_id}/trips` returns the driver's entries, paginated, with filters. | MUST |
| FR--007 | `GET /v1/admin/trips` returns all entries, paginated, with filters. | MUST |
| FR--008 | Cursor-based pagination. | MUST |
| FR--009 | Filters: `date_from`, `date_to`, `ride_type`, `status`. | MUST |
| FR--010 | 7-year retention. | MUST |
| FR--011 | Idempotent projection (replaying the same event does not double-project). | MUST |
| FR--012 | All projection events go through the inbox + dedup. | MUST |
| FR--013 | The entry shows `payment_status=pending` until `ride.payment.completed.v1`. | MUST |
| FR--014 | Caching: per-customer list cached for 60s; per-driver list cached for 60s. | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P99 read latency | ≤ 200ms |
| NFR--002 | performance | P99 projection lag | ≤ 30s |
| NFR--003 | availability | uptime | 99.9% (Tier-2) |
| NFR--004 | scalability | concurrent reads | 10k/s per region |
| NFR--005 | maintainability | MTTR for a bad deploy | ≤ 15 minutes |
| NFR--006 | observability | tracing coverage | 100% |

## 7. API Requirements

REST per `architecture/API_STANDARDS.md`. Pagination is cursor-based
with `next_cursor` and `has_more`. Errors use the standard
envelope. Full contract in `INTEGRATION.md`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | All PKs are UUIDv7 | |
| DATA--002 | All timestamps `timestamptz` UTC | RFC3339 at the wire |
| DATA--003 | Money in `amount_minor BIGINT` with `currency CHAR(3)` | no floats |
| DATA--004 | Cross-service refs (`trip_id`, `customer_id`, `driver_id`) as UUID without FKs | |
| DATA--005 | Audit columns on every mutable table | platform standard |
| DATA--006 | Partition by `trip_completed_at` (year) | |

## 9. Validation Rules

- `cursor` must be a valid opaque token.
- `date_from <= date_to`.
- `limit` between 1 and 100.

## 10. State Transitions

N/A (the entry is upserted on each event; no explicit state
machine).

## 11. Authorization Requirements

- Customer can read own entries (`customer_id == sub`).
- Driver can read own entries (`driver_id == sub`).
- Admin / support can read all.

## 12. Configuration Requirements

Consumed from `configuration-service` and refreshed on
`configuration.updated.v1`. See `README.md` §13.

## 13. Error Handling

| Error | Response | Recovery |
|-------|----------|----------|
| Bad cursor | 400 `VALIDATION_FAILED` | client retries without cursor |
| Date range invalid | 400 `VALIDATION_FAILED` | client corrects |
| DB down | 503 | client retries |

## 14. Concurrency Requirements

- The projection is idempotent; concurrent events for the same
  trip are safe (UPSERT on `trip_id`).

## 15. Idempotency Requirements

- All event handlers are idempotent by `event_id`.
- The UPSERT on `trip_id` is the second line of defense.

## 16. Performance

- Dominant path: the per-customer / per-driver read.
- P50 / P95 / P99: 50ms / 100ms / 200ms.

## 17. Scalability

- Horizontal: stateless, scale by HPA on CPU and on
  `ride_history_read_seconds_p99`.
- Read replicas: 2 for the high-traffic reads.

## 18. Availability

- SLO: 99.9% over 30 days.
- Error budget: ~44 minutes per 30 days.
- Maintenance window: weekly Sun 04:00–06:00 UTC.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require a valid JWT bearer token | gateway validates |
| SEC--002 | Customer / driver ownership is enforced | `customer_id / driver_id == sub` |
| SEC--003 | PII (pickup/dropoff) encrypted at rest | disk-level KMS |
| SEC--004 | Idempotency keys are opaque UUIDs | |
| SEC--005 | TLS 1.3 at edge; mTLS in cluster | platform standard |

## 20. Privacy

- PII stored: pickup/dropoff, customer name, driver name,
  payment amount, rating comment.
- Retention: 7 years.
- Erasure: per GDPR, identifiers are erased; financial records
  retained de-identified.

## 21. Auditability

- Every projection is logged with `correlation_id`, `trip_id`,
  `event_id`.
- Every admin read is logged at `info`.

## 22. Observability

- Logs: JSON to stdout with `correlation_id`, `service`,
  `version`, `route`, `latency_ms`, `status`.
- Metrics: see `README.md` §15.
- Traces: OpenTelemetry.
- Alerts: SLO burn-rate, projection lag, cache hit ratio.

## 23. Maintainability

- Code style: TypeScript with `strict: true`; ESLint + Prettier.
- Test coverage: ≥ 80% line / branch.
- Documentation: this folder.

## 24. Disaster Recovery

- RPO: ≤ 5 minutes (the read model is reproducible from the
  events).
- RTO: ≤ 30 minutes. A rebuild replays the events.

## 25. Acceptance Criteria

- A trip.completed.v1 leads to an entry within 30 seconds.
- A ride.payment.completed.v1 updates the entry within 30
  seconds.
- A review.submitted.v1 updates the entry within 30 seconds.
- The customer's "my trips" is paginated and fast.
- The 7-year retention is applied.

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

