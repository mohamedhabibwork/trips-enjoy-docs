# driver-service

## 1. Purpose

The `driver-service` is the platform's source of truth for
the **driver profile** — the data attached to a Keycloak
identity that has been onboarded as a driver. It owns the
driver's profile, KYC documents, document expiry tracking,
eligibility per city, ratings (read model), and the driver
state machine (`pending_review`, `approved`, `rejected`,
`suspended`, `inactive`, `erased`). It is the only writer
of the `driver` schema and the canonical source of
`driver_id` for the platform.

## 2. Bounded Context

**Driver profile + KYC.** In scope: driver profile, KYC
documents and expiry, eligibility per city, ratings (read
model), driver state machine, document expiry warnings,
suspension / disable / erasure, GDPR. Out of scope:
authentication (`identity-service`), location
(``driver-service` (location)`), availability
(``driver-service` (availability)`), earnings / withdrawals
(``payment-service` (driver earnings)`), ride history.

## 3. Responsibilities

- Create and maintain the `driver.drivers` row for every
  platform driver.
- Maintain the driver state machine
  (`pending_review`, `approved`, `rejected`, `suspended`,
  `inactive`, `erased`).
- Track KYC documents (license, vehicle registration,
  insurance, selfie, background check) with expiry dates.
- Send document expiry warnings (30, 7, 1 day).
- Auto-suspend a driver when a critical document expires
  (after a grace period).
- Maintain eligibility per city: a driver is eligible in
  the cities where they are registered and approved.
- Maintain a read-model rating (aggregated from
  ``trip-service` / `food-order-service` / `search-service` (review projections)`).
- React to `identity.*.v1` events.
- React to `vehicle.registered.v1` and
  `vehicle.insurance.expired.v1` to update
  driver-vehicle associations.
- Emit `driver.created.v1`, `driver.approved.v1`,
- `driver.rejected.v1`, `driver.suspended.v1`,
  `driver.reinstated.v1`, `driver.disabled.v1`,
- `driver.erased.v1`, `driver.document.expired.v1`,
  `driver.document.expiring.v1`.
- Provide the driver-facing profile API and the
  driver-availability check API.

## 4. Explicitly NOT Owned

- **Authentication.** `identity-service` (via Keycloak).
- **Location.** ``driver-service` (location)`.
- **Availability (online/offline/busy).** ``driver-service` (availability)`.
- **Earnings / withdrawals.** ``payment-service` (driver earnings)`.
- **Incentives / quests.** ``driver-service` (incentives)`.
- **Reviews / ratings aggregation.** ``trip-service` / `food-order-service` / `search-service` (review projections)`
  is the source of truth for the aggregated rating; this
  service holds a read-model snapshot.
- **Vehicle data.** ``driver-service` (vehicles)` (this service
  stores a reference to the primary vehicle).
- **Common user preferences.** ``customer-service` (cross-persona profile)`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver | human | read/write on their own profile |
| `identity-service` | service (producer) | emits `identity.*.v1` |
| ``driver-service` (vehicles)` | service (producer) | emits `vehicle.registered.v1`, `vehicle.approved.v1`, `vehicle.insurance.expired.v1`, `vehicle.inspection.expired.v1` |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | service (producer) | emits `review.aggregated.v1` |
| `admin-service` | service | admin actions (approve, reject, suspend, reinstate, disable) |
| ``driver-service` (availability)` | service (consumer) | reads `driver.approved.v1`, `driver.suspended.v1` |
| ``driver-service` (dispatch)` | service (consumer) | reads `driver.approved.v1`, `driver.suspended.v1` |
| `notification-service` | service (consumer) | reads `driver.*.v1` for expiry warnings, state changes |
| `fraud-risk-service` | service (consumer) | reads `driver.suspended.v1` |
| `audit-service` | consumer | reads `driver.*.v1` |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — read claims on creation — SLO
  99.95% — circuit breaker: yes.
- ``driver-service` (vehicles)` — read vehicle metadata on
  primary-vehicle reference — SLO 99.9% — circuit
  breaker: yes.
- `geolocation-service` — read city for eligibility —
  SLO 99.95% — circuit breaker: yes.
- ``geolocation-service` (zones)` — validate that a driver is registered
  in a city they want to operate in — SLO 99.95% —
  circuit breaker: yes.

### Asynchronous (events consumed)

- `identity.user.created.v1` from `identity-service` —
  back-channel: ensure a `drivers` row exists. Duplicate
  handling: idempotent on `identity_id`.
- `identity.user.updated.v1` — refresh cached claims.
- `identity.user.suspended.v1` — mark driver suspended.
- `identity.user.disabled.v1` — mark driver disabled.
- `identity.user.reinstated.v1` — clear suspension.
- `identity.user.erased.v1` — GDPR erasure.
- `vehicle.registered.v1` from ``driver-service` (vehicles)` — link
  to primary vehicle.
- `vehicle.approved.v1` — link confirmed.
- `vehicle.insurance.expired.v1` — auto-suspend if
  no replacement.
- `vehicle.inspection.expired.v1` — auto-suspend if
  no replacement.
- `review.aggregated.v1` from ``trip-service` / `food-order-service` / `search-service` (review projections)` —
  update the rating read-model.
- `configuration.updated.v1` from
  `configuration-service` — reload KYC rules, document
  expiry windows, city eligibility. Duplicate handling:
  configuration version stamp.

## 7. Technology Assumptions

- Runtime: **Java 21** (Spring Boot) — fits the rest
  of the platform's financial-adjacent services.
- Database: PostgreSQL 18 (per-service schema
  `driver`).
- Cache: Redis (per-service logical DB).
- Event broker: Kafka.
- Document expiry: a nightly job (cron) scans
  `driver.documents` for upcoming expiries and emits
  `driver.document.expiring.v1` (30, 7, 1 day) and
  `driver.document.expired.v1` (after grace period).

## 8. Database Ownership

- Schema: `driver`.
- Migrations: `services/driver-service/migrations/`
  (versioned, forward-only, Flyway).
- Soft delete: yes (`drivers` use `deleted_at`).
- Partitioning: no. The `drivers` table is one row
  per driver; `driver_documents` is small per driver;
  `driver_rating_history` is range-partitioned by
  month (volume of `review.aggregated.v1` events is
  the driver).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/drivers/{driver_id}` | bearer (self or service) | get a driver |
| POST | `/v1/drivers` | bearer (service) | create a driver (idempotent on `identity_id`) |
| PATCH | `/v1/drivers/{driver_id}` | bearer (self or admin) | update profile |
| GET | `/v1/drivers/{driver_id}/documents` | bearer (self or service) | list documents |
| POST | `/v1/drivers/{driver_id}/documents` | bearer (self) | upload a document |
| DELETE | `/v1/drivers/{driver_id}/documents/{document_id}` | bearer (self) | remove a document |
| GET | `/v1/drivers/{driver_id}/eligibility` | bearer (self or service) | get eligibility per city |
| POST | `/v1/drivers/{driver_id}/eligibility/cities/{city_id}` | bearer (self or admin) | request eligibility in a city |
| GET | `/v1/drivers/{driver_id}/rating` | bearer (self or service) | get rating |
| POST | `/v1/drivers/{driver_id}/approve` | bearer (admin) | approve |
| POST | `/v1/drivers/{driver_id}/reject` | bearer (admin) | reject |
| POST | `/v1/drivers/{driver_id}/suspend` | bearer (admin) | suspend |
| POST | `/v1/drivers/{driver_id}/reinstate` | bearer (admin) | re-instate |
| POST | `/v1/drivers/{driver_id}/disable` | bearer (admin) | disable |
| POST | `/v1/drivers/{driver_id}/erase` | bearer (admin) | GDPR erasure |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `driver.created.v1` | A new driver row is created | `audit-service`, ``reporting-service` (data lake)`, `identity-service` (back-channel) |
| `driver.approved.v1` | A driver is approved (after KYC review) | ``driver-service` (availability)`, ``driver-service` (dispatch)`, `notification-service`, `audit-service` |
| `driver.rejected.v1` | A driver is rejected | `notification-service`, `audit-service` |
| `driver.suspended.v1` | A driver is suspended | ``driver-service` (availability)`, ``driver-service` (dispatch)`, ``trip-service` (ride-request)`, `notification-service`, `fraud-risk-service`, `audit-service` |
| `driver.reinstated.v1` | A suspended driver is re-instated | same as suspended |
| `driver.disabled.v1` | A driver is disabled (permanent) | same as suspended, plus ``admin-service` (support module)` |
| `driver.erased.v1` | GDPR erasure | `audit-service`, ``reporting-service` (data lake)`, every service that owns a profile |
| `driver.document.expiring.v1` | Document is expiring (30, 7, 1 day) | `notification-service`, `audit-service` |
| `driver.document.expired.v1` | Document has expired (after grace period) | ``driver-service` (availability)`, ``driver-service` (dispatch)`, `notification-service`, `audit-service` |
| `driver.inactive.v1` | Driver has been offline for `inactive_after_days` | `audit-service` |

## 11. Events Consumed

Listed in 6 (asynchronous).

## 12. External Integrations

- **Background-check provider** (e.g. Checkr) — driver
  background checks. The service sends the driver's
  info to the provider and stores the
  `verification_id`. Credentials in Vault at
  `vault://platform/driver/background-check`.
- **KYC document verification provider** (e.g.
  Onfido) — license / ID verification. Credentials
  in Vault.
- **Vault** — provider credentials, DB credentials.
- **Redis** — claim hot-cache, city eligibility
  projection.
- **Kafka** — event bus.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `driver.kyc.required_documents` | string[] | configuration-service | e.g. `["license", "vehicle_reg", "insurance", "selfie"]` |
| `driver.documents.expiry_warning_days` | int[] | configuration-service | default `[30, 7, 1]` |
| `driver.documents.expiry_grace_days` | int | configuration-service | default 7 (after expiry before auto-suspend) |
| `driver.eligibility.min_rating` | decimal | configuration-service | default 4.0 (below this, driver is ineligible) |
| `driver.eligibility.min_trips` | int | configuration-service | default 0 (no minimum) |
| `driver.inactive_after_days` | int | configuration-service | default 30 (offline → inactive) |
| `driver.erasure.keep_financial_years` | int | configuration-service | default 7 |

## 14. Security

- **AuthN**: every endpoint requires a JWT bearer
  token. Self-service endpoints accept the
  gateway-injected `X-User-Id`. Service endpoints
  require `client_credentials` with the
  `driver.read` / `driver.write` / `driver.read.any`
  client role.
- **AuthZ**: resource-level check — a driver can only
  read/write their own profile; cross-driver reads
  require `driver.read.any` admin scope.
- **Secrets**: Vault; rotated quarterly.
- **PII**: the `drivers` row contains `name`, `email`,
  `phone` (PII), and document references (sensitive).
  PII columns are column-level encrypted. KYC
  documents are stored in `file-service`; this
  service holds only the `file_id`.
- **Background-check data**: the
  `background_check_verification_id` is the only
  reference to the provider; the provider holds the
  full record.
- **GDPR**: `POST /v1/drivers/{id}/erase` anonymizes
  PII; the `driver_id` is preserved for referential
  integrity; financial records retain the
  `driver_id` reference but their PII fields are
  redacted.
- **mTLS**: in-cluster mTLS via sidecar.

## 15. Observability

- **Logs**: JSON to stdout. Fields: `ts`, `level`,
  `service=driver-service`, `version`, `env`,
  `region`, `correlation_id`, `request_id`,
  `trace_id`, `user_id` (`driver_id`), `action`,
  `result`, `msg`.
- **Metrics**: RED per endpoint. Plus:
  - `driver_state_distribution{state}`
  - `driver_kyc_documents_expiring_total{type,days}`
  - `driver_kyc_documents_expired_total{type}`
  - `driver_eligibility_count{city_id}`
  - `driver_rating_histogram{band}`
  - `driver_suspension_reasons_total{reason}`
- **Traces**: OpenTelemetry. Sample 100% on errors,
  10% on success.
- **Health**: `/health` (process up), `/ready` (DB +
  Redis + Kafka reachable), `/started` (initial
  config loaded).

## 16. Scalability

- **Replicas**: default 6 per region; minimum 3.
- **HPA**: CPU 60% target; custom metric
  `driver_lookups_per_second` (target 5k/replica).
- **Hot path**: driver read by `driver_id` (PK index
  hit) → return row. P99 ≤ 30 ms.

## 17. Local Development

- Run with `make up-driver` (the platform's
  docker-compose v2 starts Postgres, Redis, Kafka,
  Keycloak dev realm, and stub background-check
  + KYC providers).
- A dev KYC provider returns a fixed result for
  testing.

## 18. Deployment

- **Image**: `registry.example.com/services/driver-service:{semver}`.
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
  ``geolocation-service` (zones)`, background-check / KYC
  providers, the DB, Redis, Kafka, Vault.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``driver-service` (dispatch)` (ride
matching, match-attempt ledger, offer/accept/expire flow, fairness),
``driver-service` (availability)` (driver online state, current shift,
accepted ride types, current zone),
``driver-service` (location)` (high-frequency driver location stream),
``driver-service` (incentives)` (quests, bonuses, surge guarantees,
eligibility), and ``driver-service` (vehicles)` (vehicles, registration,
insurance, inspection) is now absorbed into this service. The
canonical source for these sections is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.3
(driver-availability), 3.4 (driver-location), 3.5 (dispatch),
3.6 (driver-incentive), 3.7 (vehicle). Section numbering is
preserved so deep links into the predecessor READMEs continue to
resolve.

### A.1 Bounded context (post-merger)

Driver profile + KYC + online state + high-frequency location +
match attempts + assignment ledger + quests / bonuses / surge
guarantees + incentive accruals + vehicles (plates, registration,
insurance, inspection). The service is the **only** writer of the
`driver` schema. Out of scope: authentication (`identity-service`),
earnings / withdrawals (`payment-service`), trip aggregate
(`trip-service`), pricing engine (`pricing-service`).

### A.2 Absorbed responsibilities (from ``driver-service` (dispatch)`)

- Consume `ride.request.created.v1` and begin a match attempt.
- Query the embedded available-driver pool (online + last-known
  location).
- Sort candidates by ETA, fairness score, and recent activity.
- Send a ride offer to the top candidate via push.
- Hold a 15 s offer timer; on expiration emit
  `dispatch.offer.expired.v1` and try the next candidate.
- On accept, emit `dispatch.matched.v1` and stop the search.
- After N attempts with no driver, emit `dispatch.no_driver.v1`.
- Persist the match attempt for audit and fairness analysis.

### A.3 Absorbed responsibilities (from ``driver-service` (availability)`)

- Accept `online` and `offline` requests from the driver app.
- Track which ride types and which zone the driver accepts.
- Cooperate with `trip-service` to mark a driver `busy` when a
  match lands, and back to `available` when the trip completes.
- Refuse to take a driver offline if they have an active trip.
- Emit `driver.availability.online.v1`, `…offline.v1`,
  `…busy.v1`, `…zone.changed.v1`.

### A.4 Absorbed responsibilities (from ``driver-service` (location)`)

- Accept GPS points from the driver app at up to 5 Hz per driver.
- Maintain the **last known location** per driver
  (`current_location` table, keyed by `driver_id`).
- Maintain a **recent trail** of points per driver (`locations`
  table, partitioned by day).
- Publish a curated `driver.location.updated.v1` event stream
  at 1 Hz per driver.
- Expose a REST read for "where is driver X right now?" and
  "where were they in the last N minutes?".

### A.5 Absorbed responsibilities (from ``driver-service` (incentives)`)

- Define quests / bonuses / guarantees (admin-configured).
- Evaluate eligibility for each driver on each completed trip.
- Calculate the earned amount.
- Post the earned amount to `payment-service` with an
  idempotency key.
- Emit `driver.incentive.earned.v1` for the driver app and the
  earnings ledger.
- Cooperate with `pricing-service` (which owns surge **pricing**;
  this capability only consumes the resulting surge value).

### A.6 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/locations` | bearer (driver) | ingest a GPS ping |
| GET  | `/v1/drivers/{id}/location` | bearer | last-known location |
| GET  | `/v1/drivers/{id}/locations/recent?minutes=N` | bearer | recent trail |
| POST | `/v1/drivers/{id}/online` | bearer (driver) | go online |
| POST | `/v1/drivers/{id}/offline` | bearer (driver) | go offline |
| POST | `/v1/drivers/{id}/shift` | bearer (driver) | open / close shift |
| POST | `/v1/drivers/{id}/accepted-types` | bearer (driver) | set accepted ride types |
| GET  | `/v1/drivers/{id}/availability` | bearer | read current availability |
| POST | `/v1/match` | bearer (service) | start a match for a `ride_request_id` |
| GET  | `/v1/match/{id}` | bearer | read a match attempt |
| POST | `/v1/match/{id}/accept` | bearer (driver) | driver accepts an offer |
| POST | `/v1/match/{id}/reject` | bearer (driver) | driver rejects an offer |
| POST | `/v1/match/{id}/cancel` | bearer (service / admin) | cancel a match |
| POST | `/v1/match/{id}/reassign` | bearer (admin) | force reassignment |
| GET  | `/v1/match/metrics` | bearer (admin) | operational counters |
| POST | `/v1/incentives/quests` | bearer (admin) | create a quest |
| POST | `/v1/incentives/bonuses` | bearer (admin) | create a bonus |
| POST | `/v1/incentives/guarantees` | bearer (admin) | create a guarantee |
| GET  | `/v1/drivers/{id}/incentives` | bearer | list eligible / earned |
| GET  | `/v1/incentives/metrics` | bearer (admin) | operational counters |

### A.7 Absorbed events

**Produced** (same topic + schema version, by this service):

- `driver.availability.online.v1`, `…offline.v1`, `…busy.v1`,
  `…zone.changed.v1`.
- `driver.location.updated.v1` (curated 1 Hz per driver).
- `dispatch.matched.v1`, `dispatch.no_driver.v1`,
  `dispatch.offer.expired.v1`.
- `driver.incentive.earned.v1`.

**Consumed**:

- `ride.request.created.v1` (from ``trip-service` (ride-request)`).
- `driver.approved.v1`, `driver.suspended.v1` (own producer).
- `trip.completed.v1`, `trip.cancelled.v1` (from `trip-service`).
- `configuration.updated.v1` (from `configuration-service`).

### A.8 Absorbed configuration keys

- `driver.match.offer_window_seconds` (int, default 15).
- `driver.match.max_attempts` (int, default 6).
- `driver.match.fairness_window_minutes` (int, default 60).
- `driver.location.stale_seconds` (int, default 60).
- `driver.incentive.bonus_eligible_min_rating` (numeric, default 4.5).

### A.9 Absorbed state machines

`MatchAttempt`:

```
initiated → offered → accepted → committed
                  ↘ expired ↗
                  ↘ rejected ↗
initiated → no_driver (terminal after max_attempts)
```

`DriverAvailability`:

```
offline ⇄ online ⇄ busy
            ⇅
          paused
```

### A.10 Absorbed non-functional targets

- P50 time-to-match from `ride.request.created.v1` ≤ 30 s.
- P95 ≤ 60 s.
- 5 Hz ingestion per driver; 1 Hz curated outbound.
- 99.95% / 30 days SLO.
- Test coverage ≥ 80% line, ≥ 70% branch; 100% on matching and state machine.

### A.11 Degraded mode (post-merger)

- If the embedded location stream is unreachable (no cross-service
  hop; sub-call within this service), matching continues with stale
  locations and a wider radius (×1.5).
- If ``geolocation-service` (ETA/routing)` is unreachable, matching uses
  straight-line distance and a fixed 30 km/h estimate.
- If `trip-service` is unreachable, the busy flag is set
  optimistically and reconciled on `trip.completed.v1`.

### A.12 Compatibility window

For at least six calendar months from 2026-08-05:

- `dispatch.*.v1`, `driver.availability.*.v1`,
  `driver.location.updated.v1`, `driver.incentive.earned.v1` are
  published under the same topic names and schema versions.
- `/v1/match`, `/v1/drivers/{id}/locations*`,
  `/v1/drivers/{id}/availability*`, `/v1/drivers/{id}/incentives`,
  `/v1/incentives/*` continue to be served from this service.
- Old schema names `dispatch.*`, `driver_availability.*`,
  `driver_location.*`, `driver_incentive.*` remain readable as
  views in the `driver` schema.

### A.13 Absorbed responsibilities (from `driver-service` (vehicles))

- Maintain `driver.vehicles` + `driver.vehicle_insurance` +
  `driver.vehicle_inspections`.
- Emit `vehicle.registered.v1`, `vehicle.approved.v1`,
  `vehicle.insurance.expired.v1`,
  `vehicle.inspection.expired.v1`.

### A.14 Absorbed REST endpoints (vehicle)

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/vehicles` | bearer (driver) | register a vehicle |
| GET  | `/v1/drivers/{id}/vehicles` | bearer (driver) | list vehicles |
| PATCH | `/v1/vehicles/{vehicle_id}` | bearer (driver) | update |
| POST | `/v1/vehicles/{vehicle_id}/insurance` | bearer (driver) | upload insurance |
| POST | `/v1/vehicles/{vehicle_id}/inspections` | bearer (driver) | upload inspection |

### A.15 Compatibility window (vehicle)

For at least six calendar months from 2026-08-05:

- `vehicle.*.v1` are published under the same topic names and
  schema versions by this service.
- `/v1/drivers/{id}/vehicles`, `/v1/vehicles/{vehicle_id}*` continue
  to be served from this service.
- Old schema name `vehicle.*` remains readable as a view in the
  `driver` schema.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`api-gateway`](../api-gateway/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`file-service`](../file-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`file-service`](../file-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/DRIVER_WORKFLOWS.md`](../../workflows/DRIVER_WORKFLOWS.md) — onboarding, shifts, earnings
