# courier-service

## 1. Purpose

The `courier-service` is the platform's source of truth
for the **courier profile** — the data attached to a
Keycloak identity that has been onboarded as a courier
for the food delivery marketplace. It owns the courier's
profile, KYC documents, vehicle type, shift schedule, and
the courier state machine (`pending_review`, `approved`,
`rejected`, `suspended`, `inactive`, `erased`). It is the
only writer of the `courier` schema and the canonical
source of `courier_id` for the platform.

## 2. Bounded Context

**Courier profile + KYC.** In scope: courier profile,
KYC documents, vehicle type, shift schedule, ratings
(read model), city-level eligibility, courier state
machine, suspension / disable / erasure, GDPR. Out of
scope: authentication (`identity-service`), location
(``courier-service` (tracking)`), availability
(`courier-service` online flag), earnings / withdrawals
(``payment-service` (courier earnings)`), delivery assignments
(``courier-service` (dispatch)`).

## 3. Responsibilities

- Create and maintain the `courier.couriers` row for
  every platform courier.
- Maintain the courier state machine
  (`pending_review`, `approved`, `rejected`,
  `suspended`, `inactive`, `erased`).
- Track KYC documents (ID, vehicle doc, selfie, bag
  photo) with expiry dates.
- Send document expiry warnings (30, 7, 1 day).
- Auto-suspend a courier when a critical document
  expires (after a grace period).
- Track vehicle type (`bicycle`, `motorcycle`,
  `car`, `scooter`).
- Track shift schedule (planned vs. actual shifts).
- Maintain city-level eligibility.
- Maintain a read-model rating (aggregated from
  ``trip-service` / `food-order-service` / `search-service` (review projections)`).
- React to `identity.*.v1` events.
- React to `vehicle.registered.v1` and
  `vehicle.insurance.expired.v1`.
- Emit `courier.created.v1`, `courier.approved.v1`,
- `courier.rejected.v1`, `courier.suspended.v1`,
  `courier.reinstated.v1`, `courier.disabled.v1`,
- `courier.erased.v1`, `courier.shift.scheduled.v1`,
  `courier.shift.started.v1`, `courier.shift.ended.v1`.

## 4. Explicitly NOT Owned

- **Authentication.** `identity-service` (via Keycloak).
- **Location.** ``courier-service` (tracking)`.
- **Earnings / withdrawals.** ``payment-service` (courier earnings)`.
- **Delivery assignments.** ``courier-service` (dispatch)`.
- **Delivery aggregate.** ``courier-service` (delivery)`.
- **Reviews / ratings aggregation.**
  ``trip-service` / `food-order-service` / `search-service` (review projections)` (this service holds a
  read-model snapshot).
- **Vehicle data.** ``driver-service` (vehicles)` (this service
  stores a reference to the courier's vehicle).
- **Common user preferences.** ``customer-service` (cross-persona profile)`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Courier | human | read/write on their own profile |
| `identity-service` | service (producer) | emits `identity.*.v1` |
| ``driver-service` (vehicles)` | service (producer) | emits `vehicle.*.v1` |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | service (producer) | emits `review.aggregated.v1` |
| `admin-service` | service | admin actions |
| ``courier-service` (dispatch)` | service (consumer) | reads `courier.approved.v1`, `courier.suspended.v1` |
| ``courier-service` (tracking)` | service (consumer) | reads `courier.approved.v1` |
| `notification-service` | service (consumer) | reads `courier.*.v1` |
| `fraud-risk-service` | service (consumer) | reads `courier.suspended.v1` |
| `audit-service` | consumer | reads `courier.*.v1` |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — read claims on creation — SLO
  99.95% — circuit breaker: yes.
- ``driver-service` (vehicles)` — read vehicle metadata — SLO
  99.9% — circuit breaker: yes.
- `geolocation-service`, ``geolocation-service` (zones)` — read city
  for eligibility — SLO 99.95% — circuit breaker:
  yes.

### Asynchronous (events consumed)

- `identity.user.created.v1` from `identity-service` —
  back-channel: ensure a `couriers` row exists.
- `identity.user.updated.v1` — refresh cached claims.
- `identity.user.suspended.v1` — mark courier
  suspended.
- `identity.user.disabled.v1` — mark courier disabled.
- `identity.user.reinstated.v1` — clear suspension.
- `identity.user.erased.v1` — GDPR erasure.
- `vehicle.registered.v1` — link to primary vehicle.
- `vehicle.insurance.expired.v1` — auto-suspend if no
  replacement.
- `review.aggregated.v1` — update rating.
- `configuration.updated.v1` from
  `configuration-service` — reload KYC rules, document
  expiry windows, city eligibility, shift rules.

## 7. Technology Assumptions

- Runtime: **Java 21** (Spring Boot) — fits the rest
  of the platform's financial-adjacent services.
- Database: PostgreSQL 19 (per-service schema
  `courier`).
- Cache: Redis (per-service logical DB).
- Event broker: Kafka.
- Document expiry: a nightly job (cron) scans
  `courier.documents` for upcoming expiries and emits
  warnings; auto-suspends after grace period.

## 8. Database Ownership

- Schema: `courier`.
- Migrations: `services/courier-service/migrations/`
  (versioned, forward-only, Flyway).
- Soft delete: yes (`couriers` use `deleted_at`).
- Partitioning: no. The `couriers` table is one row
  per courier; `courier_documents` and
  `courier_shifts` are small per courier;
  `courier_rating_history` is range-partitioned by
  month.

The `requests` shadow table (`courier.requests`) is owned by this service per [ADR-0020](../architecture/adrs/0020-polymorphic-request-id.md). It stores the polymorphic `service` discriminator (one of `trip`, `food_order`, `courier_delivery`) and the `workflow_process_id` orchestrator linkage. The concrete aggregate (`courier.deliveries`) carries a `request_id UUID NOT NULL UNIQUE` FK to the local `courier.requests` table. Cross-service references use `request_id` rather than the concrete aggregate's PK.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/couriers/{courier_id}` | bearer (self or service) | get a courier |
| POST | `/v1/couriers` | bearer (service) | create (idempotent on `identity_id`) |
| PATCH | `/v1/couriers/{courier_id}` | bearer (self or admin) | update profile |
| GET | `/v1/couriers/{courier_id}/documents` | bearer (self or service) | list documents |
| POST | `/v1/couriers/{courier_id}/documents` | bearer (self) | upload a document |
| DELETE | `/v1/couriers/{courier_id}/documents/{document_id}` | bearer (self) | remove |
| GET | `/v1/couriers/{courier_id}/vehicle-type` | bearer (self or service) | get vehicle type |
| PUT | `/v1/couriers/{courier_id}/vehicle-type` | bearer (self) | set vehicle type |
| GET | `/v1/couriers/{courier_id}/eligibility` | bearer (self or service) | get per-city eligibility |
| POST | `/v1/couriers/{courier_id}/eligibility/cities/{city_id}` | bearer (self or admin) | request eligibility |
| GET | `/v1/couriers/{courier_id}/rating` | bearer (self or service) | get rating |
| GET | `/v1/couriers/{courier_id}/shifts` | bearer (self or service) | list shifts |
| POST | `/v1/couriers/{courier_id}/shifts` | bearer (self) | schedule a shift |
| DELETE | `/v1/couriers/{courier_id}/shifts/{shift_id}` | bearer (self) | cancel a shift |
| POST | `/v1/couriers/{courier_id}/approve` | bearer (admin) | approve |
| POST | `/v1/couriers/{courier_id}/reject` | bearer (admin) | reject |
| POST | `/v1/couriers/{courier_id}/suspend` | bearer (admin) | suspend |
| POST | `/v1/couriers/{courier_id}/reinstate` | bearer (admin) | re-instate |
| POST | `/v1/couriers/{courier_id}/disable` | bearer (admin) | disable |
| POST | `/v1/couriers/{courier_id}/erase` | bearer (admin) | GDPR erasure |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `courier.created.v1` | A new courier row is created | `audit-service`, ``reporting-service` (data lake)`, `identity-service` (back-channel) |
| `courier.approved.v1` | A courier is approved | ``courier-service` (dispatch)`, ``courier-service` (tracking)`, `notification-service`, `audit-service` |
| `courier.rejected.v1` | A courier is rejected | `notification-service`, `audit-service` |
| `courier.suspended.v1` | A courier is suspended | ``courier-service` (dispatch)`, ``courier-service` (delivery)`, `notification-service`, `fraud-risk-service`, `audit-service` |
| `courier.reinstated.v1` | A suspended courier is re-instated | same as suspended |
| `courier.disabled.v1` | A courier is disabled (permanent) | same as suspended, plus ``admin-service` (support module)` |
| `courier.erased.v1` | GDPR erasure | `audit-service`, ``reporting-service` (data lake)`, every service that owns a profile |
| `courier.shift.scheduled.v1` | A shift is scheduled | `notification-service`, `audit-service` |
| `courier.shift.started.v1` | A shift starts (courier goes online) | ``courier-service` (dispatch)`, `notification-service` |
| `courier.shift.ended.v1` | A shift ends (courier goes offline) | ``courier-service` (dispatch)`, `notification-service` |
| `courier.document.expiring.v1` | Document is expiring (30, 7, 1 day) | `notification-service`, `audit-service` |
| `courier.document.expired.v1` | Document has expired (after grace period) | ``courier-service` (dispatch)`, `notification-service`, `audit-service` |

## 11. Events Consumed

Listed in 6 (asynchronous).

## 12. External Integrations

- **KYC document verification provider** (e.g.
  Onfido) — ID verification. Credentials in Vault.
- **Background-check provider** (e.g. Checkr).
- **Vault** — provider credentials, DB credentials.
- **Redis** — claim hot-cache, eligibility projection.
- **Kafka** — event bus.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `courier.kyc.required_documents` | string[] | configuration-service | e.g. `["id", "vehicle_doc", "selfie", "bag_photo"]` |
| `courier.documents.expiry_warning_days` | int[] | configuration-service | default `[30, 7, 1]` |
| `courier.documents.expiry_grace_days` | int | configuration-service | default 7 |
| `courier.vehicle_types` | string[] | configuration-service | default `["bicycle", "motorcycle", "car", "scooter", "walking"]` |
| `courier.eligibility.min_rating` | decimal | configuration-service | default 4.0 |
| `courier.inactive_after_days` | int | configuration-service | default 30 |
| `courier.shifts.min_duration_minutes` | int | configuration-service | default 60 |
| `courier.shifts.max_duration_hours` | int | configuration-service | default 12 |
| `courier.erasure.keep_financial_years` | int | configuration-service | default 7 |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer
  token. Self-service endpoints accept the
  gateway-injected `X-User-Id`. Service endpoints
  require `client_credentials` with the
  `courier.read` / `courier.write` / `courier.read.any`
  client role.
- **AuthZ**: resource-level check — a courier can
  only read/write their own profile; cross-courier
  reads require `courier.read.any` admin scope.
- **Secrets**: Vault; rotated quarterly.
- **PII**: the `couriers` row contains `name`,
  `email`, `phone` (PII), and document references
  (sensitive). PII columns are column-level encrypted.
- **GDPR**: `POST /v1/couriers/{id}/erase` anonymizes
  PII; the `courier_id` is preserved for referential
  integrity; financial records retain the
  `courier_id` reference but their PII fields are
  redacted.
- **mTLS**: in-cluster mTLS via sidecar.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=courier-service`, `version`, `env`,
  `region`, `correlation_id`, `request_id`,
  `trace_id`, `user_id` (`courier_id`), `action`,
  `result`, `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `courier_state_distribution{state}`
  - `courier_vehicle_type_distribution{type}`
  - `courier_shifts_active_count`
  - `courier_kyc_documents_expiring_total{type,days}`
  - `courier_kyc_documents_expired_total{type}`
  - `courier_rating_histogram{band}`
  - `courier_suspension_reasons_total{reason}`
- **Traces**: OpenTelemetry. Sample 100% on errors,
  10% on success.
- **Health**: `/health`, `/ready`, `/started`.

## 16. Scalability

- **Replicas**: default 6 per region; minimum 3.
- **HPA**: CPU 60% target; custom metric
  `courier_lookups_per_second` (target 5k/replica).
- **Hot path**: courier read by `courier_id` (PK
  index hit) → return row. P99 ≤ 30 ms.

## 17. Local Development

- Run with `make up-courier` (the platform's
  docker-compose v2 starts Postgres, Redis, Kafka,
  Keycloak dev realm, and stub KYC provider).

## 18. Deployment

- **Image**: `registry.example.com/services/courier-service:{semver}`.
- **Replicas**: 6 (prod, per region), 3 (staging),
  1 (dev).
- **Resource limits**: 1 vCPU / 1 GiB RAM per pod.
- **Migrations**: Kubernetes Job before the
  deployment's pods start; same image with the
  `migrate` subcommand.
- **Pod disruption budget**: `minAvailable: 3` in
  production.
- **Network policy**: ingress from `api-gateway`,
  `admin-service`; egress to `identity-service`,
  ``driver-service` (vehicles)`, `geolocation-service`,
  ``geolocation-service` (zones)`, KYC / background-check
  providers, the DB, Redis, Kafka, Vault.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``courier-service` (dispatch)`
(courier matching, assignment ledger, batched offers, no-courier
handling), ``courier-service` (tracking)` (high-frequency courier
location stream), and ``courier-service` (delivery)` (delivery aggregate) is
now absorbed into this service. The documentation below is the
migrated content; the canonical source for these sections is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.24
(courier-dispatch), 3.25 (courier-tracking), and 3.26
(delivery). Section numbering is preserved so deep links into
the predecessor READMEs continue to resolve.

### A.1 Bounded context (post-merger)

Courier profile + KYC + online state + high-frequency location +
matching + assignment ledger. The service is the **only** writer of
the `courier` schema. Out of scope: authentication (`identity-service`),
earnings / withdrawals (`payment-service`), delivery aggregate
(``courier-service` (delivery)`), customer-facing notifications
(`notification-service`).

### A.2 Absorbed responsibilities (from ``courier-service` (dispatch)`)

- Maintain the live pool of *available* couriers in the current
  city / zone, joined with their last-known location.
- Evaluate match attempts for a `food.order.ready.v1` event and
  produce a `delivery.courier.assigned.v1` (or `no_courier`).
- Run the offer flow: push to couriers, wait for acceptance,
  time-out and re-offer.
- Persist the assignment ledger (`courier.assignments`) so the
  decision is auditable and replayable.
- Support batched offers (multiple orders from the same
  restaurant).
- Handle reassignment: when a courier cancels or fails,
  re-dispatch the same delivery to a new courier.
- Surface `delivery.dispatch.no_courier.v1` when no courier
  accepts within the offer window, and re-dispatch on a
  configurable interval.

### A.3 Absorbed responsibilities (from ``courier-service` (tracking)`)

- Ingest location pings at up to 5 Hz per courier (target 1 Hz).
- Persist the **current location** in a hot table (UPSERT by
  `courier_id`).
- Persist a **trail** of recent pings (range-partitioned by day,
  monthly pre-create, 12-month pre-create) for replay and analytics.
- Emit `courier.location.updated.v1` at a curated rate (default
  1 Hz per courier) for downstream consumers.
- Serve `GET /v1/couriers/{id}/location` for synchronous reads
  (dispatch, delivery ETA, safety).
- Detect a "stale" courier (no ping in 60 s) and mark them
  accordingly.

### A.4 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/couriers/{id}/locations` | bearer (courier) | ingest a GPS ping (up to 5 Hz, target 1 Hz) |
| GET  | `/v1/couriers/{id}/location` | bearer | last-known location |
| GET  | `/v1/couriers/{id}/locations/recent?minutes=N` | bearer | recent trail |
| POST | `/v1/dispatches` | bearer (service) | start a dispatch for a `food_order_id` |
| GET  | `/v1/dispatches/{id}` | bearer | read a dispatch attempt |
| GET  | `/v1/dispatches?order_id=…` | bearer | list attempts for an order |
| POST | `/v1/dispatches/{id}/offers` | bearer (internal) | record an offer attempt |
| POST | `/v1/dispatches/{id}/accept` | bearer (courier) | courier accepts an offer |
| POST | `/v1/dispatches/{id}/reject` | bearer (courier) | courier rejects an offer |
| POST | `/v1/dispatches/{id}/cancel` | bearer (service / admin) | cancel a dispatch (compensates) |
| POST | `/v1/dispatches/{id}/reassign` | bearer (service / admin) | force reassignment |
| GET  | `/v1/dispatches/metrics` | bearer (admin) | operational counters |

### A.5 Absorbed events

**Produced** (same topic + schema version, by this service):

- `courier.location.updated.v1` (curated 1 Hz per courier).
- `delivery.courier.assigned.v1`.
- `delivery.dispatch.no_courier.v1`.
- `delivery.dispatch.offer.expired.v1`.
- `delivery.dispatch.reassigned.v1`.
- `courier_dispatch.audit.assignment_committed.v1`.

**Consumed**:

- `food.order.ready.v1` (from `food-order-service`).
- `courier.availability.online.v1` / `offline.v1` (own producer —
  the courier's online flag is part of the courier profile).
- `courier.shift.ended.v1` (own producer).
- `delivery.courier.cancelled.v1` (from ``courier-service` (delivery)`).
- `configuration.updated.v1` (from `configuration-service`).

### A.6 Absorbed configuration keys

- `courier.offer_window_seconds` (int, default 30, per-city override).
- `courier.max_offer_attempts` (int, default 6, per-zone override).
- `courier.batch_max_size` (int, default 3).
- `courier.no_courier_backoff_seconds` (int, default 60).
- `courier.pool_max_radius_meters` (int, default 3000).
- `courier.feature.batched_dispatch` (bool).
- `courier.feature.zone_surge_aware` (bool).
- `courier.location.stale_seconds` (int, default 60).

### A.7 Absorbed state machines

`Dispatch` (matches the prior ``courier-service` (dispatch)`):

```
initiated → offered → accepted → committed
                  ↘ expired ↗
                  ↘ rejected ↗
initiated → no_courier → re_offered (loop) → no_courier
```

CHECK `state IN ('initiated','offered','accepted','committed','no_courier','cancelled','failed')`;
`attempt_number BETWEEN 1 AND 50`;
`offer_window_seconds BETWEEN 1 AND 120`;
`max_offer_attempts BETWEEN 1 AND 20`.

`Assignment` rows are never modified once `committed=true`; a
cancellation inserts a *new* `released` row that points back to the
original. CHECK `outcome IN ('offered','accepted','rejected','expired','cancelled','no_courier')`;
`responded_at IS NULL OR responded_at >= offered_at`;
`sequence BETWEEN 1 AND 50`.

### A.8 Absorbed non-functional targets

- P50 time-to-assignment from `food.order.ready.v1` ≤ 45 s.
- P95 time-to-assignment ≤ 90 s.
- P95 pool-search latency ≤ 200 ms.
- P95 accept-pipeline latency ≤ 300 ms.
- 5 Hz ingestion per courier; 1 Hz curated outbound.
- 50 dispatches / second / region sustained; 200 rps burst.
- 99.95% / 30 days SLO.
- RPO 5 min; RTO 30 min.
- Test coverage ≥ 80% line, ≥ 70% branch; 100% on matching and state machine.

### A.9 Degraded mode (post-merger)

- If the embedded location stream is unreachable (no cross-service
  hop; sub-call within this service), the service continues with
  stale locations and a wider radius (×1.5).
- If ``geolocation-service` (ETA/routing)` is unreachable, the matching algorithm
  uses straight-line distance and a fixed 8 km/h estimate.
- If `notification-service` is unreachable, the offer push is
  retried with backoff (handled by the embedded notification
  client; not the responsibility of this service).

### A.10 Compatibility window

For at least six calendar months from 2026-08-05:

- `courier.location.updated.v1`, `delivery.courier.assigned.v1`,
  `delivery.dispatch.*.v1` are published under the same topic
  names and schema versions.
- `/v1/dispatches`, `/v1/couriers/{id}/locations*` continue to be
  served from this service.
- Old schema names `courier_dispatch.*` and `courier_tracking.*`
  remain readable as views in the `courier` schema.

### A.11 Absorbed responsibilities (from `courier-service` (delivery))

- Maintain the delivery aggregate
  (`courier.deliveries`, state: `assigned`, `en_route_pickup`,
  `arrived_pickup`, `picked_up`, `en_route_dropoff`, `delivered`,
  `failed`).
- Allow the courier to transition the delivery state.
- Emit `delivery.pickup.v1`, `delivery.in_transit.v1`,
  `delivery.completed.v1`, `delivery.failed.v1`.
- Consume `delivery.courier.assigned.v1` (own producer) and
  `courier.location.updated.v1` (own producer).

### A.12 Absorbed REST endpoints (delivery)

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/deliveries/{id}` | bearer | read |
| POST | `/v1/deliveries/{id}/arrive-pickup` | bearer (courier) | arrived pickup |
| POST | `/v1/deliveries/{id}/pickup` | bearer (courier) | picked up |
| POST | `/v1/deliveries/{id}/in-transit` | bearer (courier) | in transit |
| POST | `/v1/deliveries/{id}/complete` | bearer (courier) | complete |
| POST | `/v1/deliveries/{id}/fail` | bearer (courier / admin) | fail |

### A.13 Compatibility window (delivery)

For at least six calendar months from 2026-08-05:

- `delivery.pickup.v1`, `delivery.in_transit.v1`,
  `delivery.completed.v1`, `delivery.failed.v1` are published
  under the same topic names and schema versions by this service.
- `/v1/deliveries/{id}/*` continue to be served from this
  service.
- Old schema name `delivery.*` remains readable as a view in the
  `courier` schema.

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

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`chat-service`](../chat-service/README.md) *(Phase 7.7 — customer ↔ courier chat thread; consumes `chat.message.reported.v1`)*, [`configuration-service`](../configuration-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-service`](../restaurant-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`chat-service`](../chat-service/README.md) *(Phase 7.7 — producer of `delivery.*.v1` events that create / close the `delivery_chat` thread)*, [`customer-service`](../customer-service/README.md), [`file-service`](../file-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-service`](../restaurant-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
- [`../../shared/TYPE_CATALOG.md`](../../shared/TYPE_CATALOG.md) — **platform-wide type vocabulary** — courier vehicle types (bicycle / scooter / motorcycle / car / walking) catalogued in [4](../../shared/TYPE_CATALOG.md#4-courier-vehicle-types); CHECK at `courier.couriers.vehicle_type` plus the `courier.vehicle_types` configuration key.

### Workflows this service participates in

- [`../../workflows/COURIER_WORKFLOWS.md`](../../workflows/COURIER_WORKFLOWS.md) — courier shifts, dispatch, delivery
