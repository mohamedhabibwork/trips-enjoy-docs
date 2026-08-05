# eta-routing-service — Software Requirements Specification

## 1. Introduction

This document specifies the requirements for `eta-routing-service`.
The service is a stateless adapter to a map provider; the
implementation must be cache-aware, fail-over-aware, and stable.

## 2. Scope

In scope:

- ETA computation.
- Route computation.
- Alternative routes.
- Caching.
- Provider failover.

Out of scope:

- Geocoding.
- Driver location.
- Trip state.
- Pricing.

## 3. System Context

```mermaid
flowchart LR
    PRC[pricing-service] --> ER[eta-routing-service]
    DSP[dispatch-service] --> ER
    TR[trip-service] --> ER
    RR[ride-request-service] --> ER
    DLV[delivery-service] --> ER
    ER --> MP[Map provider (primary)]
    ER --> MF[Map provider (failover)]
    ER --> RD[(Redis)]
    ER --> PG[(PostgreSQL 18, cache only)]
    ER -. eta.computed.v1 / route.computed.v1 .-> K[(Kafka)]
    K --> AN[analytics-service]
```

## 4. Actors

- **System callers** — JWT role `internal_service`.
- **Map provider** — external system.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | `POST /v1/routing/eta` with `{origin, destination, mode, departure_time?}`; return `{eta_seconds, distance_meters, traffic_multiplier, polyline?, source}`. | MUST |
| FR--002 | `POST /v1/routing/route` with `{origin, destination, mode, alternatives?}`; return `{polyline, eta_seconds, distance_meters, alternatives[]}`. | MUST |
| FR--003 | Round origin / destination to 4 decimal places (~11m) before caching to improve hit ratio. | MUST |
| FR--004 | Hash the request into a cache key. | MUST |
| FR--005 | On cache hit, return the cached value with `source=cache`. | MUST |
| FR--006 | On cache miss, call the primary provider; on success, cache and return. | MUST |
| FR--007 | On primary failure (timeout, 5xx), call the failover provider; on success, cache and return with `source=failover`. | MUST |
| FR--008 | On both providers failing, return 503 `DEPENDENCY_TIMEOUT`. | MUST |
| FR--009 | Emit `eta.computed.v1` on every successful compute. | MUST |
| FR--010 | Emit `route.computed.v1` on every successful route compute. | MUST |
| FR--011 | Support per-city provider configuration. | MUST |
| FR--012 | Reject invalid requests (bad lat/lon, unknown mode). | MUST |
| FR--013 | Do not log precise lat/lon in plain text. | MUST |
| FR--014 | All events go through the transactional outbox (lightweight; outbox row in `eta_routing.outbox`). | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P50 ETA compute (cache hit) | ≤ 10ms |
| NFR--002 | performance | P99 ETA compute (cache miss) | ≤ 800ms |
| NFR--003 | performance | P99 route compute (cache miss) | ≤ 1.5s |
| NFR--004 | availability | uptime | 99.95% (Tier-1) |
| NFR--005 | scalability | concurrent requests | 10k/s per region |
| NFR--006 | maintainability | MTTR for a bad deploy | ≤ 15 minutes |
| NFR--007 | observability | tracing coverage | 100% |

## 7. API Requirements

REST per `architecture/API_STANDARDS.md`. The endpoints are
system-only; rate limited per caller. Errors use the standard
envelope. Full contract in `INTEGRATION.md`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | `cache` table keyed by hash of the request | no PII |
| DATA--002 | All timestamps `timestamptz` UTC | RFC3339 at the wire |
| DATA--003 | `created_at`, `expires_at` for cache rows | TTL |
| DATA--004 | `source` field on the response (cache / provider / failover) | observability |

## 9. Validation Rules

- `lat ∈ [-90, 90]`, `lon ∈ [-180, 180]`.
- `mode` must be in `{car, motorcycle, bicycle, walking}`.
- `departure_time` (if present) must be within the next 24h.

## 10. State Transitions

N/A (stateless).

## 11. Authorization Requirements

- All endpoints require a JWT with role `internal_service`.
- The provider credentials are not exposed to callers.

## 12. Configuration Requirements

Consumed from `configuration-service` and refreshed on
`configuration.updated.v1`. See `README.md` §13.

## 13. Error Handling

| Error | Response | Recovery |
|-------|----------|----------|
| Bad request | 400 `VALIDATION_FAILED` | caller corrects |
| Provider timeout | retry once → failover | |
| Both providers down | 503 `DEPENDENCY_TIMEOUT` | caller decides |
| Provider returned garbage | treat as provider error; retry → failover | |

## 14. Concurrency Requirements

- The cache lookup and the provider call are not transactional
  (the provider is external). A race between two callers for the
  same key results in two provider calls; one of them is wasted.
  Acceptable.

## 15. Idempotency Requirements

- ETA / route reads are inherently idempotent.
- The cache write uses an UPSERT keyed on the hash; no duplicate
  rows.

## 16. Performance

- Dominant path: ETA / route compute.
- P50 / P95 / P99: 10ms / 200ms / 800ms (cache miss).

## 17. Scalability

- Horizontal: stateless, scale by HPA on CPU and on
  `eta_compute_seconds_p99`.
- The provider is the bottleneck; we cache aggressively.

## 18. Availability

- SLO: 99.95% over 30 days.
- Error budget: ~22 minutes per 30 days.
- Maintenance window: weekly Sun 04:00–06:00 UTC.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require a valid JWT bearer token | gateway validates |
| SEC--002 | Provider credentials are in Vault only | rotated per policy |
| SEC--003 | Do not log precise coordinates | enforced by a custom log filter |
| SEC--004 | mTLS to the provider if supported | defense in depth |
| SEC--005 | TLS 1.3 at edge; mTLS in cluster | platform standard |

## 20. Privacy

- PII stored: none. Lat/lon are rounded before caching; the cache
  key is a hash.

## 21. Auditability

- Every failover is logged at `warn`.
- Every `eta.computed.v1` / `route.computed.v1` is emitted for
  audit and analytics.

## 22. Observability

- Logs: JSON to stdout with `correlation_id`, `caller_service`,
  `latency_ms`, `cache_hit`, `provider`, `status`. No lat/lon.
- Metrics: see `README.md` §15.
- Traces: OpenTelemetry.
- Alerts: SLO burn-rate, failover rate spike, cache hit ratio
  drop.

## 23. Maintainability

- Code style: TypeScript with `strict: true`; ESLint + Prettier.
- Test coverage: ≥ 80% line / branch.
- Documentation: this folder.

## 24. Disaster Recovery

- RPO: N/A (stateless).
- RTO: ≤ 15 minutes. The cache is rebuilt on TTL.

## 25. Acceptance Criteria

- ETA compute with cache hit returns within 50ms.
- ETA compute with cache miss returns within 800ms.
- Primary provider failure triggers a failover within 5 seconds.
- Both providers down returns 503.
- The response shape is identical regardless of provider.

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

