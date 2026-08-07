# restaurant-service — Software Requirements Specification

## 1. Introduction

This SRS specifies the software behavior of `restaurant-service`. It
covers functional requirements, non-functional requirements, data
requirements, API contract summaries, validation, state transitions,
authorization, idempotency, performance, availability, security, and
disaster recovery. The service is the source of truth for the
`Restaurant` aggregate.

## 2. Scope

In scope:

- Restaurant creation, profile, and lifecycle.
- Online / offline toggling.
- Admin actions: approve, reject, suspend, reinstate, close.
- Cascade handling from parent merchant events.
- Rating denormalization from ``trip-service` / `food-order-service` / `search-service` (review projections)`.
- Search projection via `restaurant.updated.v1`.

Out of scope:

- Merchant legal entity (owned by ``restaurant-service` (merchant)`).
- Branches (owned by ``restaurant-service` (branch)`).
- Menus (owned by ``restaurant-service` (menu)`).
- Staff (owned by ``restaurant-service` (staff)`).
- Orders and prep state (owned by `food-order-service` and
  ``food-order-service` (queue)`).

## 3. System Context

```mermaid
flowchart LR
    OWN[Merchant Owner] -->|HTTPS| GW[api-gateway]
    ADM[Platform Admin] -->|HTTPS| GW
    GW --> RES[restaurant-service]
    RES -->|REST| MER["`restaurant-service` (merchant)]
    RES -->|REST| ID[identity-service]
    RES -->|REST| CFG[configuration-service]
    RES -->|REST| FS[file-service]
    RES -->|REST| NOT[notification-service]
    RES -->|Kafka| K[(Kafka)]
    K --> BRH["`restaurant-service` (branch)]
    K --> MN["`restaurant-service` (menu)]
    K --> SR[search-service]
    K --> CRT["`food-order-service` (cart)]
    K --> CHK["`food-order-service` (checkout)]
    K --> CDP["`courier-service` (dispatch)]
    K --> AUD[audit-service]
    MER -->|events| K
    RR["`trip-service` / `food-order-service` / `search-service` (review projections)] -->|events| K
```

## 4. Actors

- **Merchant Owner (human)** — Keycloak subject with role
  `merchant_owner`.
- **Merchant Ops (human)** — Keycloak subject with role
  `merchant_ops`; read/write profile.
- **Restaurant Operator (human)** — Keycloak subject with role
  `restaurant_staff`; can toggle online/offline.
- **Platform Admin (human)** — full access.
- **``restaurant-service` (merchant)` (system)** — parent merchant; source of
  cascade events.
- **``restaurant-service` (branch)` (system)** — child branches; emits hours
  events.
- **``restaurant-service` (menu)` (system)** — child menus.
- **``food-order-service` (cart)` / ``food-order-service` (checkout)` (system)** — read online
  status.
- **`search-service` (system)** — consumes update events.
- **``trip-service` / `food-order-service` / `search-service` (review projections)` (system)** — emits aggregated ratings.
- **`audit-service` (system)** — receives audit events.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | The service MUST accept a `POST /v1/restaurants` with `merchant_id`, `name`, `type`, `cuisines`, `description`, `logo_file_id`. | MUST |
| FR--002 | The service MUST verify the parent merchant is `approved` via ``restaurant-service` (merchant)` before allowing creation. | MUST |
| FR--003 | The service MUST support `POST /v1/restaurants/{id}/submit` (transition `draft → pending_review`). | MUST |
| FR--004 | The service MUST allow admins to `POST /v1/restaurants/{id}/approve` (transition `pending_review → approved`). | MUST |
| FR--005 | The service MUST allow admins to `POST /v1/restaurants/{id}/reject` with `reason_code` (transition `pending_review → rejected`). | MUST |
| FR--006 | The service MUST allow `POST /v1/restaurants/{id}/online` (transition `approved|offline → online`) by the operator. | MUST |
| FR--007 | The service MUST allow `POST /v1/restaurants/{id}/offline` (transition `online → offline`) by the operator. | MUST |
| FR--008 | The service MUST allow admins to `POST /v1/restaurants/{id}/suspend` with `reason_code` (transition `approved|online|offline → suspended`). | MUST |
| FR--009 | The service MUST allow admins to `POST /v1/restaurants/{id}/reinstate` with `reason_code` (transition `suspended → approved`). | MUST |
| FR--010 | The service MUST allow admins to `POST /v1/restaurants/{id}/close` with `reason_code` (transition `approved|offline|online|suspended → closed`; `closed` is terminal). | MUST |
| FR--011 | The service MUST support `POST /v1/restaurants/{id}/resubmit` (transition `rejected → pending_review`) and preserve the prior reason in the audit log. | SHOULD |
| FR--012 | The service MUST support `PATCH /v1/restaurants/{id}` for profile fields. | MUST |
| FR--013 | The service MUST expose `GET /v1/restaurants/{id}/online` (cached, P99 < 30 ms). | MUST |
| FR--014 | The service MUST support cursor pagination on `GET /v1/restaurants` with filters (`state`, `merchant_id`, `cuisine`, `type`, `q`, `cursor`, `limit`). | MUST |
| FR--015 | The service MUST cascade parent merchant `suspended` to all its `approved|online|offline` restaurants. | MUST |
| FR--016 | The service MUST cascade parent merchant `reinstated` to all its `suspended` restaurants, but leave them `offline` if the operator had set them offline. | MUST |
| FR--017 | The service MUST cascade parent merchant `closed` to all its restaurants. | MUST |
| FR--018 | The service MUST auto-set the restaurant `offline` when no branch is open (configurable; default enabled). | SHOULD |
| FR--019 | The service MUST update the denormalized `avg_rating` and `review_count` on `review.aggregated.v1`. | MUST |
| FR--020 | The service MUST publish a `restaurant.*.v1` event for every state transition. | MUST |
| FR--021 | The service MUST reject any write on a restaurant in `closed` state with 410 `RESTAURANT_CLOSED`. | MUST |
| FR--022 | The service MUST reject restaurant creation if the parent merchant is not `approved` with 409 `MERCHANT_NOT_APPROVED`. | MUST |
| FR--023 | The service MUST emit `admin.audit.restaurant.*` events for every admin action. | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P99 latency `GET /v1/restaurants/{id}/online` | < 30 ms (cache hit) |
| NFR--002 | performance | P99 latency `GET /v1/restaurants/{id}` | < 150 ms |
| NFR--003 | performance | P99 latency `POST /v1/restaurants` | < 1 s |
| NFR--004 | availability | service uptime | 99.95% over 30 days |
| NFR--005 | scalability | `online` lookups | ≥ 10,000 RPS via Redis |
| NFR--006 | scalability | concurrent writes | ≥ 200 RPS sustained, 1,000 RPS burst |
| NFR--007 | maintainability | MTTR for P1 | < 30 min |
| NFR--008 | data-integrity | zero event loss | outbox + 24 h ack |
| NFR--009 | latency | rating refresh P95 | < 5 min after `review.aggregated.v1` |
| NFR--010 | observability | every state change queryable in audit | 100% |

## 7. API Requirements

REST API under `/v1/restaurants[...]` per
[`API_STANDARDS.md`](../../architecture/API_STANDARDS.md). All write
endpoints require an `Idempotency-Key` header. Cursor pagination by
default. OpenAPI 3.1 spec at `/openapi.json`.

(Full contracts in `INTEGRATION.md`.)

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | Restaurants are uniquely identified by `id UUIDv7`. | primary key |
| DATA--002 | Every mutable table has `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`. | audit |
| DATA--003 | `state` is a CHECK-constrained enum. | lifecycle |
| DATA--004 | `merchant_id` is a UUID column with no DB FK. | cross-service ref |
| DATA--005 | Cuisines are stored as a normalized many-to-many (`restaurant_cuisines`). | taxonomy |
| DATA--006 | Tags are stored as a normalized many-to-many (`restaurant_tags`). | taxonomy |
| DATA--007 | Rating fields (`avg_rating`, `review_count`) are denormalized; the source of truth is ``trip-service` / `food-order-service` / `search-service` (review projections)`. | read-side |
| DATA--008 | Logo `file_id` is a UUID column with no FK. | cross-service ref |
| DATA--009 | The `online` flag is computed at write time and cached. | hot path |

(Full schema in `ERD.md`.)

## 9. Validation Rules

- `name` — 1..120 chars, Unicode NFC.
- `type` — drawn from `restaurant.type.list` in
  `configuration-service`.
- `cuisines` — non-empty array, each value drawn from
  `restaurant.cuisine.list`.
- `description` — 0..1000 chars.
- `logo_file_id` — must reference an existing file (validated via
  `file-service` if needed; soft validation at write time).
- `reason_code` on admin actions — drawn from
  `restaurant.suspension.reason_codes`.

## 10. State Transitions

| From | To | Trigger |
|------|----|---------|
| `draft` | `pending_review` | `POST /submit` |
| `pending_review` | `approved` | admin `POST /approve` |
| `pending_review` | `rejected` | admin `POST /reject` |
| `rejected` | `pending_review` | owner `POST /resubmit` |
| `approved` | `online` | `POST /online` |
| `online` | `offline` | `POST /offline` or auto |
| `offline` | `online` | `POST /online` |
| `approved` | `suspended` | admin or cascade |
| `online` | `suspended` | admin or cascade |
| `offline` | `suspended` | admin or cascade |
| `suspended` | `approved` | admin `POST /reinstate` |
| `approved` | `closed` | admin or cascade |
| `online` | `closed` | admin or cascade |
| `offline` | `closed` | admin or cascade |
| `suspended` | `closed` | admin or cascade |
| `rejected` | `closed` | admin |
| `closed` | — | terminal |

State transitions are described in detail in `WORKFLOWS.md`.

## 11. Authorization Requirements

- `merchant_owner` of the parent merchant may create, submit, and
  re-submit restaurants; may update profile; may toggle
  online/offline; may not transition to `approved` or `suspended`.
- `merchant_ops` may update profile and toggle online/offline.
- `restaurant_staff` may toggle online/offline (read access to
  profile).
- `platform_admin` has full read/write and may perform all
  lifecycle transitions.
- Cascade handlers act as the system actor; the source of truth
  for the reason is the originating event.

## 12. Configuration Requirements

- `restaurant.cuisine.list` — array of allowed cuisines.
- `restaurant.type.list` — array of allowed types.
- `restaurant.suspension.reason_codes` — enum.
- `restaurant.suspension.grace_period_hours` — int.
- `restaurant.online.required_branches` — int (default 1).
- `restaurant.feature.auto_offline_on_no_open_branch` — bool.
- `restaurant.rate_limit.create_per_hour` — int.
- `feature_flag.restaurant.auto_approve_enabled` — bool.

## 13. Error Handling

| Error | Response |
|-------|----------|
| Body validation failure | 400 `VALIDATION_FAILED` with `details[]` |
| Missing/invalid JWT | 401 `UNAUTHENTICATED` |
| Insufficient role | 403 `FORBIDDEN` |
| Parent merchant not approved | 409 `MERCHANT_NOT_APPROVED` |
| Parent merchant suspended | 409 `MERCHANT_SUSPENDED` |
| Illegal state transition | 409 `STATE_INVALID` |
| Write on `closed` | 410 `RESTAURANT_CLOSED` |
| Idempotency key reused | 422 `IDEMPOTENCY_KEY_REUSED` |
| Rate limited | 429 `RATE_LIMITED` |
| Downstream (merchant) timeout | 503 `DEPENDENCY_TIMEOUT` |
| Circuit open | 503 `CIRCUIT_OPEN` |
| Other | 500 `INTERNAL_ERROR` |

## 14. Concurrency Requirements

- Two concurrent `online`/`offline` calls MUST be serialized via
  row-level lock; the second one receives 409 `STATE_INVALID` if
  the first changed the state.
- Two concurrent admin lifecycle actions on the same restaurant
  MUST be serialized.
- Cascade handlers must be idempotent: a duplicate
  `merchant.suspended.v1` for the same merchant MUST NOT
  double-suspend (inbox dedup).

## 15. Idempotency Requirements

- `POST /v1/restaurants` requires `Idempotency-Key`.
- All admin actions (`approve`, `reject`, `suspend`, `reinstate`,
  `close`) require `Idempotency-Key`.
- All state transitions use the outbox pattern with
  `event_id` deduplication on the consumer side.

## 16. Performance

- Dominant path: `GET /v1/restaurants/{id}/online`. P50 < 5 ms
  (cache hit), P99 < 30 ms.
- `GET /v1/restaurants/{id}`: P50 < 30 ms, P99 < 150 ms.
- `POST /v1/restaurants`: P50 < 500 ms, P99 < 1 s.
- Cascade suspension: P95 < 60 s from event received to
  `restaurant.suspended.v1` published.

## 17. Scalability

- Horizontal: HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Vertical: up to 4 CPU / 8 GiB.
- DB: 1 primary + 1 read replica in each region.
- Cache: Redis cluster, key `restaurant:online:{id}` TTL 30 s.

## 18. Availability

- SLO: 99.95% over 30 days.
- Error budget: ~22 min / 30 days.
- Maintenance: Sunday 04:00–06:00 UTC.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require a valid JWT; service-to-service uses `client_credentials`. | gateway enforced |
| SEC--002 | Admin actions require `X-Audit-Reason` and HMAC-SHA256 signature. | `API_STANDARDS.md` 14 |
| SEC--003 | `suspend` and `close` require a second admin's co-signature (break-glass). | `SECURITY_ARCHITECTURE.md` 3 |
| SEC--004 | Resource-level ownership checks at the service layer. | `restaurant.merchant.owner_kc_sub == sub` |
| SEC--005 | All cross-service calls use mTLS + `client_credentials` JWT. | defense in depth |
| SEC--006 | Secrets only in Vault; never in source control. | pre-commit enforced |
| SEC--007 | Rate limiting at gateway and service. | `API_STANDARDS.md` 12 |
| SEC--008 | No PII (the brand profile is public); only audit columns hold PII refs. | minimal |
| SEC--009 | Admin actions emit `admin.audit.restaurant.*` events. | `audit-service` |
| SEC--010 | The service stores no card data; PCI scope is none. | SAQ-A |

## 20. Privacy

- PII stored: none at the brand level. The owner Keycloak subject
  is held as `created_by` for audit; classified `internal`.
- Retention: 7 years (soft delete on `close`; hard delete after
  retention).
- Erasure: not applicable (no merchant PII stored here).

## 21. Auditability

- Every state transition emits a `restaurant.*.v1` event.
- Every admin action emits an `admin.audit.restaurant.*` event.
- Audit retention: 7 years.

## 22. Observability

- Logs: JSON to stdout with `correlation_id`, `trace_id`,
  `merchant_id`, `restaurant_id`, `state`, `from_state`,
  `to_state`, `actor`, `reason_code`.
- Metrics:
  - RED: standard.
  - Business: `restaurants_created_total{country,cuisine}`,
    `restaurants_online_total`,
    `restaurants_offline_total{reason}`,
    `restaurant_suspension_propagation_seconds`,
    `restaurant_search_lookups_total{cache_hit}`,
    `restaurant_rating_freshness_seconds`.
- Traces: OpenTelemetry.
- Alerts: SLO burn rate, outbox lag, online propagation lag.

## 23. Maintainability

- TypeScript strict, ESLint, Prettier.
- Coverage: ≥ 85% lines.
- Documentation: this folder is the source of truth.

## 24. Disaster Recovery

- RPO: 5 min (PITR 30 days for Tier-1).
- RTO: 30 min.
- Quarterly restore drill.

## 25. Acceptance Criteria

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
  ms.
- AC-7: All admin actions are recorded with reason and actor.
- AC-8: The service meets its 99.95% SLO.
- AC-9: All state changes are emitted as events.
- AC-10: Soft delete preserves data for 7 years.

---

## Appendix A — Predecessor SRS absorbed (restaurant-staff)

The functional and non-functional requirements below were migrated
from ``restaurant-service` (staff)/SRS.md` as part of
[ADR-0016](../../architecture/adrs/0016-service-domain-consolidation.md).
The canonical source is [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.10.

### A.1 Functional requirements (from restaurant-staff)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-RS-001 | Invite a staff member by email; issue an invitation token. | MUST |
| FR-RS-002 | Activate a staff member after Keycloak sign-up with the invitation token. | MUST |
| FR-RS-003 | Assign roles per restaurant or per branch (`manager`, `cashier`, `kitchen`, `dispatcher`). | MUST |
| FR-RS-004 | Manage per-device login state (allow-list of device IDs). | MUST |
| FR-RS-005 | Deactivate a staff member (admin or owner action). | MUST |
| FR-RS-006 | Emit `staff.invited.v1`, `staff.activated.v1`, `staff.deactivated.v1`. | MUST |

### A.2 Validation rules (predecessor)

- An invitation token MUST expire after 168 hours (7 days).
- A staff member MUST NOT have more than 5 active devices.
- A role assignment MUST belong to an existing restaurant or
  branch.

### A.3 Non-functional requirements (predecessor)

| ID | Category | Target |
|----|----------|--------|
| NFR-RS-001 | performance | Invite latency ≤ 100 ms |
| NFR-RS-002 | performance | Activate latency ≤ 100 ms |
| NFR-RS-003 | availability | 99.9% / 30 d |

### A.4 Acceptance criteria (predecessor)

- Invitation token works exactly once.
- Deactivation propagates to the operator console within 5 s.

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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

