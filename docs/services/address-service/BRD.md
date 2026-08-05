# address-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's product team, the
notification team, and the SRE on-call rotation. It
captures *why* the `address-service` exists, the
business capabilities it provides, the business rules
it enforces, and the KPIs against which it is
evaluated. It is the input to the SRS, ERD, and
INTEGRATION docs in this folder.

## 2. Business Context

Every platform user — customer, driver, courier —
has saved addresses (home, work, gym, etc.) that they
use as ride pickup points, food delivery addresses,
and so on. Without a single canonical store, each
persona service would have to grow its own address
table, and the user would have to manage addresses
separately. The `address-service`:

- **Single source of truth** for saved addresses
  across all personas and contexts.
- **Single address UX** — a user manages their
  addresses in one place, and every channel honors
  them.
- **Geocoding consistency** — every saved address is
  geocoded once, cached, and reused.
- **Default address per context** — a user can
  set a default `home` for `ride_pickup` and a
  different default for `food_delivery`.
- **GDPR right-to-erasure** is owned centrally.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable `address_id` (UUIDv7) for every saved address. | 100% of `POST /v1/addresses` result in a row within 1 second. |
| BR--002 | Geocode every saved address within 2 seconds (P99). | P99 geocode latency. |
| BR--003 | Honor default address per context. | 100% of consumers see the correct default. |
| BR--004 | Meet the Tier-2 SLO of 99.9% availability and P99 ≤ 30 ms on the read path. | SLO burn rate. |
| BR--005 | Support GDPR right-to-erasure. | 100% of `POST /v1/addresses/{id}/erase` complete within SLA. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product team | owner | address UX, tags, defaults |
| Notification team | consumer | address change events |
| Mobile / web channel teams | consumer | address management API |
| `customer-service` | consumer | default ride_pickup, default food_delivery |
| `cart-service`, `checkout-service` | consumer | default food_delivery |
| Compliance | reviewer | GDPR |
| SRE on-call | operator | alerts, MTTR |

## 5. Actors / Personas

- **Customer / driver / courier (any persona)**
  (human) — manages their saved addresses.
- **Internal admin / support** (human) — GDPR
  erasure.
- **Downstream services** (system) — read the
  default address for ride / order / checkout.

## 6. Business Capabilities

- **Address save** — create an address with a
  free-form input; geocode and normalize.
- **Address update** — re-geocode on change.
- **Address delete** — soft-delete; the address is
  not physically removed (audit + referential
  integrity).
- **Tag** — `home`, `work`, `gym`, `other`,
  custom.
- **Default address per context** — a user can
  set a default for `ride_pickup`,
  `food_delivery`, etc.
- **Geocoding** — delegated to
  `geolocation-service`; cached locally for fast
  reads.
- **GDPR erasure** — anonymize and emit
  `address.deleted.v1`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only writer of the `address` schema. | MUST | data ownership |
| BR--011 | The service MUST emit `address.created.v1` on creation. | MUST | event architecture |
| BR--012 | The service MUST emit `address.updated.v1` on update. | MUST | event architecture |
| BR--013 | The service MUST emit `address.deleted.v1` on delete (manual or GDPR). | MUST | event architecture |
| BR--014 | The service MUST geocode every address via `geolocation-service`. | MUST | product |
| BR--015 | The service MUST support a default address per context. | MUST | product |
| BR--016 | The service MUST support address tags. | MUST | product |
| BR--017 | The service MUST anonymize PII on GDPR erasure. | MUST | GDPR |
| BR--018 | The service MUST support per-country address formats. | SHOULD | product |
| BR--019 | The service MUST enforce `address.max_per_user` per user. | MUST | hygiene |
| BR--020 | The service MUST use PostGIS for the geometry column. | MUST | geospatial |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | An address has at most one `default_for` per context per user. | Unique constraint. |
| BR--031 | A `street_line1` is required. | Validation. |
| BR--032 | A `country` MUST be in `address.supported_countries`. | Validation. |
| BR--033 | A `geocode_status` is `pending`, `success`, or `failed`. | On `failed`, the user is prompted to fix the address. |
| BR--034 | The number of addresses per user MUST NOT exceed `address.max_per_user`. | 409 `LIMIT_REACHED`. |
| BR--035 | `address_id` is never recycled, even on erasure. | Stability. |
| BR--036 | A `default` setting MUST propagate to dependent services within 10 seconds (P99). | Event-driven. |

## 9. Assumptions

- `geolocation-service` is reachable for geocoding.
- The platform's `address.supported_countries` list
  is configured per environment.
- Mobile clients send a free-form address; the
  service normalizes and geocodes.

## 10. Constraints

- The service MUST NOT call other services'
  databases directly.
- The service MUST use the standard event and error
  envelopes.
- The service MUST NOT store the user's name /
  email / phone; only the `identity_id` reference.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `geolocation-service` | service | geocoding |
| `identity-service` | service | claims validation |
| `configuration-service` | service | config hot-reload |
| `customer-service` | consumer | `address.*.v1` |
| `cart-service`, `checkout-service` | consumer | `address.*.v1` |
| `notification-service` | consumer | `address.*.v1` |
| `audit-service` | consumer | `address.*.v1` |
| `analytics-service` | consumer | `address.*.v1` |
| Redis | infra | address hot-cache |
| Kafka | infra | event bus |
| Vault | infra | DB credentials |

## 12. Business Workflows

- **Address save** (detailed in `WORKFLOWS.md`).
- **Default address per context** (detailed in
  `WORKFLOWS.md`).
- **Geocoding retry** (detailed in `WORKFLOWS.md`).
- **GDPR right-to-erasure** (detailed in
  `WORKFLOWS.md`).

## 13. Exception Workflows

- **`geolocation-service` unreachable** — the
  service accepts the address but marks it
  `geocode_status='pending'`; a backfill job
  retries.
- **Geocoding returns no result** — the user is
  prompted to fix the address.
- **Address limit reached** — 409
  `ADDRESS_LIMIT_REACHED`.
- **Country not supported** — 400 `VALIDATION_FAILED`.

## 14. Success Criteria

- 100% of saved addresses have a `address_id`.
- 100% of `address.*.v1` events are observed by
  all declared consumers within 10 seconds (P99).
- A default address change propagates to consumers
  within 10 seconds (P99).
- A GDPR erasure completes end-to-end in ≤ 24
  hours (expedited) and is auditable.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Availability | ≥ 99.9% per 30d | uptime / total time per region |
| P99 read latency | ≤ 30 ms | request duration histogram |
| P99 geocode latency | ≤ 2 s | request duration histogram |
| P99 propagation lag | ≤ 10 s | event time → consumer ack |
| Geocode success rate | ≥ 95% | (success / total) |
| Erasure SLA | 100% within 24 h expedited | support ticket resolution time |

## 16. Acceptance Criteria

- A `POST /v1/addresses` creates a row and emits
  `address.created.v1` within 1 second.
- A successful geocode updates the `location`
  column and sets `geocode_status='success'`.
- A failed geocode sets `geocode_status='failed'`
  and the user is prompted to fix.
- A `PUT /v1/addresses/{id}/default` sets the
  default for the context and emits
  `address.updated.v1`.
- A `DELETE /v1/addresses/{id}` soft-deletes the
  row and emits `address.deleted.v1`.
- A `POST /v1/addresses/{id}/erase` (admin) anonymizes
  the row and emits `address.deleted.v1` with
  `reason='gdpr'`.
- The address count per user is enforced.

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

