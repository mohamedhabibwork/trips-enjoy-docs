# user-profile-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's product team, the
notification team, and the SRE on-call rotation. It captures
*why* the `user-profile-service` exists, the business
capabilities it provides, the business rules it enforces,
and the KPIs against which it is evaluated. It is the input
to the SRS, ERD, and INTEGRATION docs in this folder.

## 2. Business Context

Every platform user — customer, driver, courier, merchant
staff — has preferences (language, notifications) and a list
of devices they use. Without a single canonical store for
these, each persona service would have to grow its own
preferences tables, and the user would have to manage them
separately. The `user-profile-service`:

- **Single source of truth** for language, notification,
  and device data across all personas.
- **Single preferences UX** — a user manages their
  preferences in one place, and every channel honors
  them.
- **i18n readiness** — a single `preferred_locale` is
  available platform-wide, with support for AR/RTL
  from day one.
- **GDPR consistency** — erasure propagates to the
  profile in a single, predictable flow.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable `user_profile.profiles` row for every platform user. | 100% of `identity.user.created.v1` result in a profile row within 5 seconds. |
| BR--002 | Honor user language preferences at the edge. | 100% of responses localize based on `preferred_locale`. |
| BR--003 | Honor user notification preferences. | 0 notifications sent against a user's opt-out. |
| BR--004 | Maintain an accurate device list. | 100% of `identity.session.revoked.v1` (theft / force-logout) result in a device flag or removal within 30 seconds. |
| BR--005 | Meet the Tier-2 SLO of 99.9% availability and P99 ≤ 100 ms on the read path. | SLO burn rate. |
| BR--006 | Support GDPR right-to-erasure consistently. | 100% of `identity.user.erased.v1` result in profile anonymization within 60 seconds. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product team | owner | preferences UX, i18n coverage |
| Notification team | consumer | notification preferences |
| Mobile / web channel teams | consumer | device list, push token registration |
| All persona services (customer, driver, courier, merchant) | consumer | avatar, preferred_locale |
| `identity-service` | producer | lifecycle events |
| Compliance | reviewer | GDPR erasure |
| SRE on-call | operator | alerts, MTTR |

## 5. Actors / Personas

- **Customer** — manages preferences in the customer
  app.
- **Driver** — manages preferences in the driver app
  (limited; some are admin-controlled).
- **Courier** — same.
- **Merchant / restaurant staff** — manages preferences
  in the operator console.
- **Internal admin** — uses `admin-service` to fix
  issues, set org-wide defaults.

## 6. Business Capabilities

- **Language preferences** — primary + secondary locale
  (BCP-47).
- **Notification preferences** — per channel (push,
  email, SMS, in-app) per topic (marketing,
  transactional, safety, operational).
- **Device management** — register, list, unregister;
  per-device push token and platform metadata.
- **Avatar management** — upload (delegated to
  `file-service`), reference storage.
- **Read-only mode** — when the user is suspended /
  disabled, the profile is `read_only`.
- **Anonymization on erasure** — PII and devices
  cleared; `identity_id` reference preserved.
- **i18n catalogs** — per-locale message catalogs
  loaded at startup and on `configuration.updated.v1`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Every platform user MUST have a `user_profile.profiles` row. | MUST | architecture |
| BR--011 | The service MUST be the only writer of the `user_profile` schema. | MUST | data ownership |
| BR--012 | The service MUST emit `user.profile.updated.v1` on any change. | MUST | event architecture |
| BR--013 | The service MUST honor `Accept-Language` and `preferred_locale` in the response. | MUST | i18n |
| BR--014 | The service MUST support AR/RTL locales from day one. | MUST | product |
| BR--015 | The service MUST NOT send notifications; it stores preferences only. | MUST | bounded context |
| BR--016 | The service MUST register a device on `POST /v1/profiles/{id}/devices` with platform, model, OS, push token. | MUST | mobile |
| BR--017 | A device MUST be auto-removed after `idle_unregister_days` of no activity. | SHOULD | hygiene |
| BR--018 | The service MUST support avatar upload via `file-service` (delegated, not direct). | MUST | architecture |
| BR--019 | The service MUST mark the profile `read_only` on suspension/disable. | MUST | security |
| BR--020 | The service MUST anonymize the row on `identity.user.erased.v1`. | MUST | GDPR |
| BR--021 | The service MUST support per-topic, per-channel notification opt-out. | MUST | product |
| BR--022 | The service SHOULD support org-wide default notification preferences. | SHOULD | merchant / restaurant staff |
| BR--023 | The service MAY support a "do not disturb" window per user. | MAY | product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | `preferred_locale` MUST be in `user_profile.supported_locales`. | Otherwise fallback to `user_profile.default_locale`. |
| BR--031 | A notification MUST NOT be sent against a user's opt-out. | Enforced by `notification-service` reading prefs at send time. |
| BR--032 | A `read_only` profile MUST reject all writes (preferences, device, avatar). | 403 `FORBIDDEN` with `code: "PROFILE_READ_ONLY"`. |
| BR--033 | A device push token MUST be unique within the user's device list. | Re-registering the same token is idempotent. |
| BR--034 | Devices inactive for `idle_unregister_days` MUST be auto-removed. | Nightly job. |
| BR--035 | The avatar `file_id` MUST be in `file-service`. | This service only stores the reference. |
| BR--036 | Anonymization on erasure preserves `identity_id`. | Soft delete + tombstone. |
| BR--037 | The cached claim TTL MUST NOT exceed 1 hour. | Bounded staleness. |

## 9. Assumptions

- `identity-service` emits `identity.user.created.v1`
  for every new user before the user attempts any
  read/write in `user-profile-service`.
- `notification-service` reads preferences via REST or
  caches them from `user.profile.updated.v1`.
- Mobile clients register their device on first launch
  with the current push token.
- The platform's supported locales include
  `en-US` and `ar-SA` at minimum; the service can be
  extended to other locales via
  `configuration.updated.v1`.

## 10. Constraints

- The service MUST NOT store persona-specific profile
  data (name, email, phone, KYC, etc.).
- The service MUST NOT store notification
  delivery state.
- The service MUST NOT store the avatar file content
  (only the `file_id`).
- The service MUST NOT call `notification-service` to
  send notifications; it only stores preferences.
- The service MUST use the standard event and error
  envelopes.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | emits `identity.*.v1` |
| `file-service` | service | avatar storage |
| `configuration-service` | service | configuration hot-reload |
| `notification-service` | consumer | reads preferences |
| `customer-service`, `driver-service`, `courier-service`, `merchant-service` | consumer | read API for avatar, locale |
| `admin-service` | consumer | admin actions |
| `audit-service` | consumer | consumes `user.profile.*.v1` |
| `analytics-service` | consumer | consumes `user.profile.*.v1` |
| Redis | infra | claim hot-cache |
| Kafka | infra | event bus |
| Vault | infra | DB credentials |

## 12. Business Workflows

- **Profile creation on `identity.user.created.v1`**
  (detailed in `WORKFLOWS.md`).
- **Language preference change** (detailed in
  `WORKFLOWS.md`).
- **Notification preference change** (detailed in
  `WORKFLOWS.md`).
- **Device registration / unregistration** (detailed in
  `WORKFLOWS.md`).
- **Avatar upload** (detailed in `WORKFLOWS.md`).
- **Profile anonymization on erasure** (detailed in
  `WORKFLOWS.md`).

## 13. Exception Workflows

- **Unsupported locale** — fallback to
  `user_profile.default_locale`.
- **Profile `read_only`** — 403 `PROFILE_READ_ONLY` on
  any write.
- **Avatar too large** — 413 `PAYLOAD_TOO_LARGE`.
- **Avatar wrong MIME** — 415 `UNSUPPORTED_MEDIA_TYPE`.
- **Push token validation fails** — 400 `VALIDATION_FAILED`.
- **`identity-service` unreachable on read** — degrade
  to cache-served reads; write path fails closed.

## 14. Success Criteria

- 100% of platform users have a `user_profile.profiles`
  row within 5 seconds of `identity.user.created.v1`.
- 100% of `user.profile.updated.v1` are observed by
  declared consumers within 10 seconds (P99).
- 0 notifications are sent against a user's opt-out.
- 100% of `identity.user.erased.v1` result in profile
  anonymization within 60 seconds.
- AR/RTL locales render correctly in mobile and web.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Availability | ≥ 99.9% per 30d | uptime / total time per region |
| P99 read latency | ≤ 100 ms | request duration histogram |
| P99 propagation lag | ≤ 10 s | event time → consumer ack |
| Cache hit ratio | ≥ 90% | Redis hit / total lookups |
| Erasure latency | ≤ 60 s P99 | support ticket resolution time |
| Locale coverage | 100% of declared locales | configuration count vs. catalog count |

## 16. Acceptance Criteria

- An `identity.user.created.v1` event results in a
  `user_profile.profiles` row within 5 seconds.
- A `PATCH /v1/profiles/{id}` updates the row and emits
  `user.profile.updated.v1` within 1 second.
- A `POST /v1/profiles/{id}/devices` registers the
  device and emits `user.device.added.v1`.
- An `identity.user.erased.v1` event results in PII
  redaction and `user.profile.erased.v1` emitted within
  60 seconds.
- A suspended user's `PATCH /v1/profiles/{id}` returns
  `403 PROFILE_READ_ONLY`.
- An `Accept-Language: ar-SA` request is served with
  Arabic strings where the catalog has a translation.
- The avatar `file_id` is in `file-service`; this
  service only stores the reference.

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

