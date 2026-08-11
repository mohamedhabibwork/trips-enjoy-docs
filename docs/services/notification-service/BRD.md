# notification-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`notification-service` must do for the business. It is read by
product management, the platform architecture team, the
marketing team (for promotional messaging), the operations team
that handles customer support tickets, the service's
engineering team, and any auditor verifying the platform's
notification practices. It informs the templates catalog, the
channel mix per locale, the quiet-hours policy, and the
right-to-erasure flow for notification history.

## 2. Business Context

The platform sends millions of user-visible messages a day:
trip confirmations, driver-arrived alerts, food-order updates,
payment receipts, safety broadcasts, marketing offers, refund
notifications, and more. Each of these messages must be:

1. **Personalized** to the user's locale, profile, and
   preferences.
2. **Delivered** on the right channel (push, SMS, email,
   in-app) for the user and the moment.
3. **Honored** when the user opts out.
4. **Tracked** for delivery state, so support agents can
   answer "did the customer see the refund notification?".
5. **Audited** for compliance (right-to-erasure,
   anti-spam).

`notification-service` is the orchestration layer. It is the
*only* place in the platform that decides "send a push to this
user, with this template, in their locale, unless they opted
out of marketing notifications and it's currently their quiet
hours, and only if we haven't sent the same one in the last
60s." Without it, every producer (trip, order, payment) would
have to embed that logic, and they would each get it wrong in
different ways.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Deliver every triggered notification on at least one channel within 60 seconds of the trigger event | 99% of notifications delivered within 60s of the source event |
| BR--002 | Honor user preferences (opt-out, channel, quiet hours) 100% of the time | 0% preference violations in production |
| BR--003 | Make adding a new notification template a config-only change (no code change) | 100% of templates added via admin API; 0 code changes for new templates |
| BR--004 | Support 4 channels (push, SMS, email, in-app) and locale-aware rendering for at least en + ar | all templates have en + ar variants; channel selection honors user preferences |
| BR--005 | Suppress duplicate notifications within a configurable window (default 60s) per (user, template) | 100% of duplicates suppressed |
| BR--006 | Provide a complete delivery audit trail (sent, delivered, failed) for support agents | 100% of notifications have a delivery state row |
| BR--007 | Survive a single channel outage (e.g. push provider down) by falling back to other channels | fallback activation within 5s; 0 customer-facing 5xx |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Ride / Food) | consumer | "send the right message at the right time" |
| Marketing | consumer | promotional notifications (subject to preference) |
| Customer Support | consumer | "did the customer see the refund notification?" |
| Trust & Safety | consumer | emergency broadcasts (SOS) |
| Compliance / Legal | reviewer | GDPR right-to-erasure, anti-spam laws |
| Platform Architecture | owner | SLO, multi-channel reliability |
| Engineering (consumer services) | consumer | one API to send any notification |

## 5. Actors / Personas

- **Customer (rider / diner)**: receives notifications about
  trips, orders, payments, and (opted-in) marketing.
  Manages their own preferences (channel, opt-out, quiet
  hours) via the app.
- **Driver / Courier**: receives notifications about
  assignments, ride / delivery updates, earnings, and
  (opted-in) incentives. Manages their own preferences.
- **Merchant / Restaurant staff**: receives notifications
  about new orders, cancellations, and payouts.
- **Support agent**: looks up delivery state for a specific
  notification (e.g. "the customer says they didn't get the
  refund notification — was it sent?").
- **Operations (admin)**: manages templates, suppressions,
  and one-off broadcasts.

## 6. Business Capabilities

- **Template management** (CRUD on per-channel, per-locale
  templates).
- **Preference management** (per-user, per-channel, per-category
  preferences).
- **Channel selection** (push > SMS > email > in-app by
  default, but configurable per category and per user).
- **Locale-aware rendering** (en, ar, and any configured
  locale).
- **Dedup** (collapse duplicates within a window).
- **Quiet hours** (per-user do-not-disturb window).
- **Suppression** (admin can globally suppress a category;
  e.g. "no marketing during a crisis").
- **Delivery tracking** (sent, delivered, failed, suppressed).
- **Retry / DLQ** (transient failure → retry; persistent
  failure → DLQ + support ticket).
- **One-off broadcasts** (admin sends to a segment).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only writer of notification templates, preferences, and delivery state. | MUST | data ownership, platform architecture |
| BR--011 | The service MUST support per-channel templates (push, SMS, email, in-app, WhatsApp) with locale variants (at minimum en and ar). | MUST | i18n, product |
| BR--012 | The service MUST honor user preferences 100% of the time, including channel, opt-out, and quiet hours. | MUST | compliance, trust |
| BR--013 | The service MUST support dedup with a configurable per-(user, template) window (default 60s). | MUST | product (avoid spamming) |
| BR--014 | The service MUST track delivery state (queued, sent, delivered, failed, suppressed) for every notification. | MUST | support, audit |
| BR--015 | The service MUST retry transient failures (gateway 5xx, timeout) with exponential backoff (3 attempts). | MUST | reliability |
| BR--016 | The service MUST route persistent failures (after retries) to a DLQ and emit `notification.failed.v1` for ``admin-service` (support module)` to investigate. | MUST | support, audit |
| BR--017 | The service MUST select the channel based on user preferences, channel availability (circuit state), and category priority. | MUST | reliability, product |
| BR--018 | The service MUST fall back to the next channel if the primary channel's circuit is open. | MUST | reliability |
| BR--019 | The service MUST support a global suppression list (admin-managed) for "no marketing during incident X" scenarios. | MUST | ops, trust |
| BR--020 | The service MUST consume domain events (`trip.*.v1`, `food.order.*.v1`, `payment.*.v1`, `ride.safety.*.v1`, …) and translate them to notifications. | MUST | event-driven architecture |
| BR--021 | The service MUST emit `notification.sent.v1` and `notification.failed.v1` for ``admin-service` (support module)` and `audit-service`. | MUST | audit |
| BR--022 | The service MUST allow users to update their own preferences via the API (with ownership check). | MUST | GDPR, product |
| BR--023 | The service MUST allow admins to create / update / disable templates via the API. | MUST | ops, product |
| BR--024 | The service MUST respect quiet hours (configurable per user; default 22:00–07:00 user-local). | MUST | product |
| BR--025 | The service MUST NOT store notification bodies longer than 90 days (delivery state only is retained; bodies are encrypted). | MUST | GDPR / PII minimization |
| BR--028 | The service MUST support **WhatsApp** as a first-class channel with pre-approved structured templates (header/body/footer/buttons + numbered variables). | MUST | product (WhatsApp rollout) |
| BR--029 | The service MUST hold an immutable `notification.template_history` snapshot for every published template version, enabling support to reproduce the exact bytes that were rendered for any historical delivery. | MUST | audit, support, compliance |
| BR--030 | Each `notification.deliveries` row MUST bind to its rendered template snapshot via `template_version_snapshot_id` so the audit chain `template_id → templates.version → template_history.id → deliveries.template_version_snapshot_id` is reconstructable. | MUST | audit |
| BR--031 | Publishing a new version of `name` MUST happen atomically across all configured `(channel, locale)` pairs in a single transaction; no half-published template set may be visible to senders. | MUST | correctness |
| BR--032 | The service MUST enforce the WhatsApp 24-hour customer-service window: freeform messages may only be sent within 24h of the recipient's last inbound message; structured (pre-approved) templates may always be sent. | MUST | Meta Business policy |
| BR--033 | The service MUST refuse to send a WhatsApp template whose `provider_template_status != 'approved'` (configurable via `notification.whatsapp.approval_required`). | MUST | compliance, brand safety |
| BR--034 | The service MUST publish a `notification.template.published.v1` event on every version publication carrying the `template_history_id`, `provider_template_id`, `provider_template_status`, `published_by`, `approved_by`, and a structured `diff_summary`. | MUST | audit, analytics |
| BR--035 | The service MUST support both logical-locale fallback ("requested locale → user-profile locale → default locale") AND provider-locale mapping (a logical `ar` user may be served a provider-registered `ar_SA` template). | MUST | i18n, WhatsApp Business policy |
| BR--036 | The service MUST maintain WhatsApp STOP / opt-out as a `(channel, recipient_hash)` record in ``notification-service` (provider ACL)`, AND mirror a per-category preference in `notification.preferences` to enable template-scoped opt-outs (e.g. "no marketing templates, but OTP/transactional allowed"). | MUST | WhatsApp Business policy |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | Channel selection priority (default): `push > sms > email > in_app > whatsapp`. | overridden by per-category config |
| BR--021 | If the user has no device for push AND no phone, fall back to email. | if no email either, mark `failed` with reason `NO_CONTACT` |
| BR--022 | If the user has opted out of a category, suppress the notification (no channel is selected). | emit `notification.suppressed.v1` for analytics |
| BR--023 | Quiet hours apply to non-urgent categories only. Urgent (safety / SOS) bypasses quiet hours and uses push first. | |
| BR--024 | Dedup window: 60s default. Two notifications with the same `(user_id, template_id, dedup_key)` within the window are deduped; the first is delivered, the second is suppressed. | |
| BR--025 | Marketing notifications require explicit opt-in (per locale law, e.g. GDPR, PDPL). | |
| BR--026 | A notification is `delivered` only when the channel reports delivery (push: APNs/FCM ack; SMS: provider ack; email: provider ack). | `sent` is when we hand off to the gateway; `delivered` is when the gateway reports delivery |
| BR--027 | A failed notification that has exhausted retries MUST open a ``admin-service` (support module)` ticket if it's a money event (refund, payment, payout). | |
| BR--040 | For WhatsApp, the same `name` may have multiple `(channel='whatsapp', locale)` variants (`ar`, `en`, `ur`, …). `required_variables[]` MUST match every `{{var}}` in the plain `body` AND every `{key, index}` in `body_structured.variables[]`. | |
| BR--041 | The structured template body (`body_structured`) MUST mirror the WhatsApp Business API components payload: header may be `text` or `media` (`image`/`document`/`video`/`audio`), body is always `text`, footer is `text` (optional), buttons are limited to `url`, `phone`, `quick_reply`, `copy_code`. | |
| BR--042 | Every WhatsApp template publication MUST have a non-null `approved_by` recorded in `template_history` before the `templates.row` becomes sendable. | enforced by the discriminator CHECK |
| BR--043 | `template_history` is append-only; UPDATE/DELETE are blocked by trigger. Old versions NEVER mutate. | |
| BR--044 | Right-to-erasure on `deliveries` MUST NOT delete `template_history` rows (they contain no PII — only admin `sub` UUIDs). | the erasure endpoint redacts `rendered_body_encrypted` and nulls `user_id` on the delivery row |

## 9. Assumptions

- Provider credentials and provider health are owned by
  ``notification-service` (provider ACL)`; we only see a stable
  channel API.
- User preferences are managed by the user via the app; the
  app calls our `PATCH /v1/preferences/{user_id}` API.
- Templates are managed by ops via the admin console.
- The volume of notifications is bursty (e.g. during a
  football match end) but bounded; we can scale horizontally.
- i18n is a hard requirement (en + ar from day one).

## 10. Constraints

- **Latency**: P99 render + send ≤ 1s end-to-end.
- **Compliance**: GDPR / PDPL right-to-erasure; user can
  opt out; we must respect it 100%.
- **Reliability**: must not lose a safety notification; SOS
  bypasses quiet hours and dedup.
- **Channel availability**: push can be down for hours (e.g.
  APNs outage); we must fall back to SMS.
- **Cost**: SMS is the most expensive channel; push is
  cheapest; we should default to push when possible.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| ``notification-service` (provider ACL)` | service | downstream; channel routing |
| `identity-service` | system | resolve Keycloak sub |
| ``customer-service` (cross-persona profile)` | service | read locale, device list |
| `customer-service` | service | read phone, email, KYC tier |
| `driver-service` | service | read driver phone, email |
| `courier-service` | service | read courier phone, email |
| ``restaurant-service` (merchant)` | service | read merchant email |
| `configuration-service` | service | read template defaults, retry policy |
| Every domain service | producer | many events consumed |
| ``admin-service` (support module)` | consumer | reads delivery state |
| `audit-service` | consumer | reads `notification.*.v1` events |
| ``reporting-service` (data lake)` | consumer | reads `notification.*.v1` events |
| PostgreSQL 19 | infra | core storage |
| Redis 8 | infra | dedup, quiet hours, preference cache |
| Kafka | infra | events |
| Handlebars | lib | template engine |

## 12. Business Workflows

- **Send a notification (synchronous, from a service)** — see
  `WORKFLOWS.md` 1.
- **Consume an event and send (asynchronous)** — see
  `WORKFLOWS.md` 2.
- **User updates preferences** — see `WORKFLOWS.md` 3.
- **Retry on transient failure** — see `WORKFLOWS.md` 4.
- **Channel fallback activation** — see `WORKFLOWS.md` 5.

## 13. Exception Workflows

- **All channels' circuits open**: the notification is marked
  `failed` with reason `ALL_CHANNELS_UNAVAILABLE`; an alert
  fires; no customer-facing error (the producer's request
  is acked).
- **Preference service unreachable**: the service falls back
  to the cached preferences (Redis); if Redis is also down,
  we use the default preference set (push > SMS > email > in
  app). The delivery is logged with `preference_source=fallback`.
- **Template missing for the user's locale**: we fall back to
  the default locale (en); if that is also missing, we mark
  the notification `failed` with reason `TEMPLATE_MISSING` and
  page the on-call.
- **User has no contact info** (no device, no phone, no
  email): we mark the notification `failed` with reason
  `NO_CONTACT`; the producer is acked (it is not their
  problem).

## 14. Success Criteria

- 99% of notifications delivered within 60s of the source
  event.
- 0% preference violations in production.
- 0% dedup misses (a duplicate always suppressed).
- 100% of failed notifications have a delivery state row.
- 100% of SOS notifications delivered within 30s.
- Marketing opt-in rate > 40% in mature markets.
- Right-to-erasure request completes in < 30 days.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Notification delivery P95 (event → delivered) | ≤ 30s | `notification_delivery_seconds` P95 |
| Notification delivery P99 | ≤ 60s | same P99 |
| Preference violation rate | 0% | `notification_suppressed_total{reason=preference_violation}` (must be 0) |
| Channel fallback activation time | ≤ 5s | `channel_circuit_open_to_fallback_seconds` |
| Template miss rate | < 0.01% | `notification_failed_total{reason=template_missing}` |
| Notification cost per user per month | trending down | finance dashboard |
| Open rate (push, email) | ≥ 25% | `notification_opened_total` (from gateway callbacks) |

## 16. Acceptance Criteria

- All 16 business requirements implemented and verified by
  automated tests.
- A `trip.completed.v1` event in staging results in a
  notification being sent to the right user, on the right
  channel, in the right locale, within 5s.
- A user who opts out of marketing does not receive any
  marketing notifications (verified by an integration test).
- A push provider outage simulated in staging results in
  automatic fallback to SMS within 5s; no customer-facing
  errors.
- A duplicate event within 60s is suppressed (only one
  notification sent).
- A right-to-erasure request from ``admin-service` (support module)` results
  in the user's notification history being deleted within
  24h.
- All templates have en + ar variants.

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
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — WhatsApp structured template body model + approval workflow
- [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) — immutable audit table + diff summary + snapshot chain
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — delivery-side audit chain
- [`PLAN.md`](./PLAN.md) — implementation tracker
- [`seeds/templates.v1.json`](./seeds/templates.v1.json) — JSON seed catalog
- [`seeds/RENDERING_DEMO.md`](./seeds/RENDERING_DEMO.md) — Mermaid rendering demo

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

