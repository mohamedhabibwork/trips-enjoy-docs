# ride-safety-service — Business Requirements Document

## 1. Document Purpose

Read by trust & safety, product, engineering, customer support,
and compliance to align on what `ride-safety-service` does. The
service is on the critical path for life-safety events; getting
it wrong is unacceptable.

## 2. Business Context

A ride is a moment of vulnerability for both the customer and the
driver. The platform must give them tools to call for help, share
their location with trusted contacts, and record the trip if
needed. The platform must also respond to incidents quickly and
preserve evidence. This service is the system of record for that
response.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for trip safety state | 100% of trips have a safety row |
| BR--002 | Notify trusted contacts within 60 seconds of SOS | p99 ≤ 60s |
| BR--003 | Open a P1 support ticket within 60 seconds of SOS | p99 ≤ 60s |
| BR--004 | Page security on-call for life-safety incidents | 100% of P1 |
| BR--005 | Preserve encrypted evidence (recordings, location trail) | 100% |
| BR--006 | Be honest about stale data (live location) | if no point in 30s, mark `stale=true` |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Trust & Safety | owner | fast response, evidence preservation |
| Product (Rides) | owner | feature surface (share, record) |
| Customer Support | operator | incident queue |
| Engineering (Rides) | builder | latency, reliability |
| Legal & Compliance | reviewer | audit trail, encryption |

## 5. Actors / Personas

- **Customer (rider)** — triggers SOS / share / record during a
  trip.
- **Driver** — triggers SOS / share / record during a trip.
- **Trust & Safety agent** — reads incidents, contacts law
  enforcement.
- **Customer Support** — opens tickets, follows up.
- **Admin** — closes incidents with reason.

## 6. Business Capabilities

- SOS handling.
- Share-trip activation.
- Audio recording.
- Incident opening and audit.
- Live location for the duration of an incident.
- Notification of trusted contacts.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST initialise a trip safety row on `trip.started.v1`. | MUST | Product |
| BR--011 | The service MUST close the trip safety row on `trip.completed.v1`. | MUST | Product |
| BR--012 | The service MUST notify trusted contacts within 60 seconds of SOS. | MUST | Trust & Safety |
| BR--013 | The service MUST open a P1 support ticket within 60 seconds of SOS. | MUST | Trust & Safety |
| BR--014 | The service MUST page security on-call for life-safety incidents. | MUST | Trust & Safety |
| BR--015 | The service MUST encrypt sensitive artifacts (recordings, precise location) at rest. | MUST | Security |
| BR--016 | The service MUST allow the customer or driver to share the trip with up to N trusted contacts. | MUST | Product |
| BR--017 | The service MUST allow the customer or driver to record the trip audio (max N minutes). | SHOULD | Product |
| BR--018 | The service MUST preserve the live location trail for the duration of an incident. | MUST | Trust & Safety |
| BR--019 | The service MUST audit every SOS / share / record / incident. | MUST | Compliance |
| BR--020 | The service MUST allow admin to close an incident with a reason. | MUST | Customer Support |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | An incident is opened for every SOS; severity is set by the trigger (default "high" for SOS). | |
| BR--031 | The live location is pulled from `driver-location-service` while the trip is in `in_progress` and the incident is open. | |
| BR--032 | Audio recordings are stored in `file-service` with per-tenant encryption; only security can read them. | |
| BR--033 | The share-trip link refreshes every 30 seconds with the current location. | |
| BR--034 | The incident remains open until admin closes it or the trip ends and no follow-up is needed. | |

## 9. Assumptions

- The customer's trusted contacts are managed by `customer-service`.
- The driver's trusted contacts are managed by `driver-service`.
- The `notification-service` is the source of truth for delivery
  (SMS, push).

## 10. Constraints

- All SOS events are P1 until proven otherwise.
- All artifacts are encrypted at rest.
- The service is on the critical path for life-safety; the SLO
  is 99.95% (Tier-1).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `trip-service` | service | trip context |
| `customer-service` | service | trusted contacts |
| `driver-location-service` | service | live location |
| `file-service` | service | audio storage |
| `notification-service` | service | notify |
| `support-service` | service | open P1 ticket |
| `audit-service` | service | immutable audit log |

## 12. Business Workflows

- **SOS** — see `WORKFLOWS.md`.
- **Share trip** — see `WORKFLOWS.md`.
- **Audio recording** — see `WORKFLOWS.md`.
- **Incident close** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- Trusted contacts SMS fails: retry with alternative channel
  (push); on failure, log.
- `support-service` down: retry; on persistent failure, page
  on-call directly.
- `file-service` down: retry; on persistent failure, store
  the audio locally and upload on next launch.

## 14. Success Criteria

- SOS response time is within 60 seconds p99.
- 100% of SOS events open a P1 ticket.
- 100% of artifacts are encrypted at rest.
- Trust & Safety can investigate any incident within minutes.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| SOS notify-trusted-contacts latency P99 | ≤ 60s | `ride_safety_sos_seconds` |
| SOS support-ticket latency P99 | ≤ 60s | `ride_safety_sos_seconds` |
| Incident close SLA | ≤ 24h P1, ≤ 7d P2 | reporting |
| Recording storage success rate | ≥ 99% | `ride_safety_recording_total{status=success}` |

## 16. Acceptance Criteria

- An SOS notifies trusted contacts within 60s p99.
- An SOS opens a P1 ticket within 60s p99.
- Audio recordings are encrypted at rest.
- Live location is preserved for the duration of an incident.
- Every SOS / share / record is audited.

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

