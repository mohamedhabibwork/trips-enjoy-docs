# vehicle-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's ride-hailing and
food marketplace operations teams, the compliance team,
and the SRE on-call. It captures *why* the
`vehicle-service` exists, the business capabilities it
provides, the business rules it enforces, and the KPIs
against which it is evaluated. It is the input to the
SRS, ERD, and INTEGRATION docs in this folder.

## 2. Business Context

The platform needs a canonical, single source of truth
for **vehicles** — cars, motorcycles, scooters —
registered by drivers and couriers. Without it, every
consumer (driver, courier, dispatch, trip) would have
to grow its own vehicle table, and plate, insurance,
and inspection would be inconsistent across the
platform. The `vehicle-service`:

- **Single source of truth** for vehicle data.
- **Multi-owner support** so a single vehicle can be
  used by both a driver (rides) and a courier
  (deliveries) — e.g. a family car.
- **Document expiry warnings** so owners renew
  insurance and inspection on time.
- **GDPR right-to-erasure** is owned centrally.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable `vehicle_id` (UUIDv7) for every vehicle. | 100% of registrations result in a row. |
| BR--002 | Send 99% of document expiry warnings 7+ days before expiry. | warning lag distribution. |
| BR--003 | Support multi-owner (driver + courier on the same vehicle). | 0 dispatch errors due to ownership conflicts. |
| BR--004 | Meet the Tier-2 SLO of 99.9% availability and P99 ≤ 30 ms on the read path. | SLO burn rate. |
| BR--005 | Implement GDPR right-to-erasure consistently. | 100% of erasure requests complete within SLA. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Driver / courier operations | owner | vehicle registration |
| Compliance | reviewer | GDPR |
| SRE on-call | operator | alerts, MTTR |

## 5. Actors / Personas

- **Driver / courier (vehicle owner)** — registers
  vehicles, adds insurance and inspection, adds
  co-owners.
- **Internal admin / support** — approves vehicles,
  erases (GDPR).

## 6. Business Capabilities

- **Vehicle registration** — plate, model, year,
  color, registration certificate.
- **Multi-owner support** — a vehicle can be
  associated with one driver and one courier.
- **Insurance tracking** — multiple insurance
  policies (current + past); expiry warnings.
- **Inspection tracking** — multiple inspection
  certificates; expiry warnings.
- **GDPR erasure** — anonymize; preserve
  `vehicle_id`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only writer of the `vehicle` schema. | MUST | data ownership |
| BR--011 | The service MUST emit `vehicle.registered.v1` on registration. | MUST | event architecture |
| BR--012 | The service MUST emit `vehicle.approved.v1` on approval. | MUST | event architecture |
| BR--013 | The service MUST emit `vehicle.insurance.expired.v1` and `vehicle.inspection.expired.v1`. | MUST | safety |
| BR--014 | The service MUST send document expiry warnings 30, 7, 1 day before expiry. | MUST | operations |
| BR--015 | The service MUST support multi-owner (driver + courier on the same vehicle). | MUST | product |
| BR--016 | The service MUST anonymize PII on erasure; preserve `vehicle_id`. | MUST | GDPR |
| BR--017 | The service MUST support per-country plate format validation. | SHOULD | product |
| BR--018 | The service MUST support a primary owner per vehicle. | MUST | data model |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A vehicle has at most one primary driver owner and one primary courier owner. | Configurable; one of each. |
| BR--031 | The plate MUST conform to `vehicle.plate_format_per_country` for the country. | Validation on registration. |
| BR--032 | An expired insurance or inspection triggers `vehicle.*.expired.v1`; the driver/courier is auto-suspended (by `driver-service` / `courier-service`). | Cross-service flow. |
| BR--033 | `vehicle_id` is never recycled, even on erasure. | Stability. |
| BR--034 | GDPR erasure preserves `vehicle_id`; trip / delivery records retain the reference but redact PII. | Legal hold. |

## 9. Assumptions

- `driver-service` and `courier-service` consume
  `vehicle.registered.v1` to link to their primary
  vehicle.
- Insurance and inspection documents are
  managed in `file-service`; this service stores
  only the `file_id`.

## 10. Constraints

- The service MUST NOT store document file
  content; only the `file_id` reference.
- The service MUST NOT call other services'
  databases directly.
- The service MUST use the standard event and
  error envelopes.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | claims validation |
| `configuration-service` | service | config hot-reload |
| `driver-service` | consumer | `vehicle.registered.v1`, `vehicle.*.expired.v1` |
| `courier-service` | consumer | `vehicle.registered.v1`, `vehicle.*.expired.v1` |
| `notification-service` | consumer | `vehicle.*.expiring.v1` |
| `audit-service` | consumer | `vehicle.*.v1` |
| `file-service` | service | document storage |
| Redis | infra | claim hot-cache |
| Kafka | infra | event bus |
| Vault | infra | DB credentials |

## 12. Business Workflows

- **Vehicle registration** (detailed in
  `WORKFLOWS.md`).
- **Insurance / inspection expiry** (detailed in
  `WORKFLOWS.md`).
- **Multi-owner** (detailed in `WORKFLOWS.md`).
- **GDPR right-to-erasure** (detailed in
  `WORKFLOWS.md`).

## 13. Exception Workflows

- **Plate format invalid** — 400
  `VALIDATION_FAILED`.
- **Co-owner already associated** — 409
  `CONFLICT`.
- **Erasure on a vehicle with active trip / delivery
  records** — the service performs the erasure but
  populates `warnings[]` in the response.

## 14. Success Criteria

- 100% of registered vehicles have a `vehicle_id`.
- 99% of document expiry warnings are sent 7+ days
  before expiry.
- A GDPR erasure completes end-to-end in ≤ 24
  hours (expedited) and is auditable.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Availability | ≥ 99.9% per 30d | uptime / total time per region |
| P99 read latency | ≤ 30 ms | request duration histogram |
| Expiry warning lag | 99% sent 7+ days before | warning timestamp vs. expiry |
| Erasure SLA | 100% within 24 h expedited | support ticket resolution time |

## 16. Acceptance Criteria

- A vehicle registration results in a
  `vehicle.vehicles` row and `vehicle.registered.v1`
  emitted.
- Adding a co-owner results in a
  `vehicle.owners` row and the owner's services
  are notified.
- An insurance expiring in 30 / 7 / 1 day results
  in `vehicle.insurance.expiring.v1` emitted.
- An insurance past expiry + grace period results
  in `vehicle.insurance.expired.v1` emitted.
- A GDPR erasure request results in PII redaction
  and `vehicle.erased.v1` emitted.
- A plate format validation rejects invalid plates
  with `400 VALIDATION_FAILED`.

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

