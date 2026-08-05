# notification-service

## 1. Purpose

`notification-service` is the platform's **user-visible messaging
orchestrator**. It owns the templates, preferences, and delivery
state for every **push, SMS, email, in-app, and WhatsApp**
message the platform sends to customers, drivers, couriers,
merchants, and restaurant staff. The service is the *brain* that
decides *what* to send, *in which channel*, and *when*; the
*muscle* that actually delivers the message lives in
`communication-gateway-service`.

WhatsApp is treated as a first-class peer of push, SMS, email,
and in-app, with one structural difference: WhatsApp templates
are **provider-approved structured components** (`header` /
`body` / `footer` / `buttons` + numbered variables) rather than
free-form Handlebars text. That difference is captured by the
`template_type` and `body_structured` columns on `templates` —
see [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) for the
full body schema, approval workflow, and 24-hour customer-service
window policy.

## 2. Bounded Context

**Bounded Context**: *Orchestration of user-visible messages*.

In scope:

- Per-channel notification templates (push, SMS, email, in-app,
  WhatsApp structured).
- User notification preferences (per channel, per category,
  per locale).
- Channel selection (which channel(s) to use for a given
  recipient + category).
- Template rendering (variable interpolation, locale selection,
  i18n). For WhatsApp structured templates the variables are
  indexed (`"1"`, `"2"`, …) per `body_structured.variables[]`.
- Delivery state (queued, sent, delivered, read, failed,
  suppressed). WhatsApp adds `accepted` (provider pipeline ack)
  and `read` (recipient opened).
- Template versioning & immutable history
  (`notification.template_history`).
- Bind every delivery to its rendered template snapshot via
  `deliveries.template_version_snapshot_id`.
- Dedup (collapse duplicate notifications within a window).
- Retry, DLQ, escalation.
- Quiet hours (do-not-disturb).
- Suppression rules (e.g. user has uninstalled the app → no push;
  no phone → no SMS).
- WhatsApp 24-hour customer-service-window enforcement
  (`notification.whatsapp.template_24h_window_enforced`).

Out of scope:

- Provider credentials, provider health, raw send logs —
  `communication-gateway-service`.
- The actual provider adapter and plug-in contract —
  `communication-gateway-service` + [`WHATSAPP_PROVIDER_CONTRACT.md`](../communication-gateway-service/WHATSAPP_PROVIDER_CONTRACT.md).
- Inbox / read state (the user's "unread" flag) — owned by the
  client app and the `customer-service` history.
- Email content for marketing campaigns — owned by
  `promotion-service` (which may use this service for delivery,
  but the campaign logic is elsewhere).
- Trip / order state changes — owned by `trip-service`,
  `food-order-service`, etc.

## 3. Responsibilities

- Maintain `notification.templates`,
  `notification.template_history` (immutable snapshot),
  `notification.preferences`, `notification.deliveries`,
  `notification.suppressions`.
- Provide `POST /v1/notifications` (send a notification) and
  `GET /v1/notifications/{id}` (read delivery state).
- Provide `POST /v1/admin/templates` and `PATCH
  /v1/admin/templates/{id}` (admin CRUD on templates).
- Provide `POST /v1/admin/templates/{id}/submit-for-approval`,
  `POST /v1/admin/templates/{id}/approve`, and
  `POST /v1/admin/templates/{id}/publish` (atomic-across-locales
  WhatsApp approval + multi-locale publishing).
- Provide `GET /v1/admin/templates/{id}/history` (audit chain).
- Provide `GET /v1/preferences/{user_id}` and
  `PATCH /v1/preferences/{user_id}` (user-managed preferences).
- Consume domain events (`trip.completed.v1`,
  `food.order.placed.v1`, `payment.failed.v1`,
  `comms.whatsapp.template_status_update.v1`, …) and translate
  them into notifications per template + preference rules.
- Choose channel(s) based on preferences, quiet hours,
  suppression rules, channel availability, and (for WhatsApp)
  the 24h customer-service window.
- Hand off the rendered notification to
  `communication-gateway-service` over REST.
- Snapshot the exact template version into
  `notification.template_history` and bind the
  `deliveries.template_version_snapshot_id` so support can
  always reproduce the rendered content.
- Track delivery state; retry on transient failure; route
  persistent failures to DLQ.
- Honor dedup windows (e.g. "don't send the same
  `order.placed` notification twice within 60s").
- Emit `notification.sent.v1`, `notification.delivered.v1`,
  `notification.read.v1`, `notification.failed.v1`,
  `notification.suppressed.v1`, and
  `notification.template.published.v1` events.

## 4. Explicitly NOT Owned

- **Provider credentials, provider send logs** —
  `communication-gateway-service`.
- **Marketing campaigns** — `promotion-service`.
- **User identity / KYC** — `identity-service`,
  `customer-service`, `driver-service`, `courier-service`.
- **Trip / order state** — `trip-service`, `food-order-service`,
  `delivery-service`.
- **The user's "unread" inbox** — client app + the
  `customer-service` history.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer app | system | read its own preferences; receive notifications |
| Driver app | system | read its own preferences; receive notifications |
| Courier app | system | read its own preferences; receive notifications |
| Merchant / Restaurant back-office | system | read its own preferences; receive notifications |
| `trip-service` | system | producer of `trip.*.v1` events that trigger notifications |
| `food-order-service` | system | producer of `food.order.*.v1` |
| `payment-service` | system | producer of `payment.*.v1` |
| `ride-safety-service` | system | producer of `ride.safety.*.v1` |
| `support-service` | system | read delivery state for ticket investigation |
| `communication-gateway-service` | system | downstream provider routing |
| `admin-service` | system | admin CRUD on templates |
| End user (customer / driver / courier / merchant staff) | human | manage their own preferences (via API) |
| Operations (admin) | human | manage templates, override preferences |

## 6. Dependencies

### Synchronous (REST)

- `communication-gateway-service` — send a rendered message via
  a channel — SLO 99.9% — circuit breaker: yes (per channel).
- `identity-service` — resolve a Keycloak `sub` to a user
  profile (locale, device list) — SLO 99.95% — circuit
  breaker: no (gateway handles).
- `customer-service` / `driver-service` / `courier-service` /
  `merchant-service` — read contact info (phone, email) — SLO
  99.95% — circuit breaker: yes.
- `configuration-service` — read template defaults, channel
  priorities, retry policy — SLO 99.95% — circuit breaker: yes.
- `user-profile-service` — read locale, device list — SLO
  99.9% — circuit breaker: yes.

### Asynchronous (events consumed)

Many — see `INTEGRATION.md`. Examples:

- `trip.completed.v1` → "your trip is complete" notification.
- `food.order.placed.v1` → "order received" notification.
- `payment.failed.v1` → "your payment failed" notification.
- `ride.safety.sos.v1` → emergency broadcast.

### Asynchronous (events produced)

- `notification.sent.v1` — every successful send.
- `notification.failed.v1` — every persistent failure (after
  retries exhausted).

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript) — high throughput, good
  i18n libs, async I/O.
- Database: PostgreSQL 18 in schema `notification` (templates,
  preferences, deliveries, suppressions).
- Cache: Redis 7 (per-service) for preference lookups, dedup
  windows, quiet hours.
- Event broker: Kafka.
- Template engine: Handlebars (compiled and cached).

## 8. Database Ownership

- Schema: `notification`
- Migrations: `services/notification-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (templates, preferences).
- Partitioning: yes — `notification.deliveries` partitioned by
  month (high volume).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/notifications | service | send a notification (synchronous) |
| GET | /v1/notifications/{id} | bearer (own) / service | get delivery state |
| GET | /v1/preferences/{user_id} | bearer (own) / service | read preferences |
| PATCH | /v1/preferences/{user_id} | bearer (own) | update preferences |
| POST | /v1/admin/templates | admin | create template (plain or WhatsApp structured) |
| GET | /v1/admin/templates | admin | list templates |
| PATCH | /v1/admin/templates/{id} | admin | update template → creates new version + `template_history` snapshot |
| POST | /v1/admin/templates/{id}/submit-for-approval | admin | submit a WhatsApp template to the provider for approval |
| POST | /v1/admin/templates/{id}/approve | admin / service | mark a WhatsApp template as approved (called from the gateway webhook) |
| POST | /v1/admin/templates/{id}/publish | notification.admin | atomic-across-locales publish (creates one `template_history` snapshot per `(channel, locale)` in one transaction) |
| GET | /v1/admin/templates/{id}/history | admin / support | full publication history for "what was actually sent?" support workflow |
| POST | /v1/admin/suppressions | admin | add a suppression rule |
| GET | /v1/admin/deliveries | admin | list recent deliveries (for ops) |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `notification.sent.v1` | successful send via `comms-gateway` | `support-service`, `audit-service`, `analytics-service` |
| `notification.failed.v1` | persistent failure (retries exhausted) | `support-service`, `audit-service`, `analytics-service` |
| `notification.suppressed.v1` | suppressed by user preference / quiet hours / suppression rule | `analytics-service` |

(Full contracts in INTEGRATION.md.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.started.v1` | `trip-service` | "your driver is on the way" | lookup template, render, send |
| `trip.arrived.v1` | `trip-service` | "your driver has arrived" | same |
| `trip.completed.v1` | `trip-service` | "your trip is complete" | same |
| `trip.cancelled.v1` | `trip-service` | "your trip was cancelled" | same |
| `food.order.placed.v1` | `food-order-service` | "order received" | same |
| `food.order.accepted.v1` | `food-order-service` | "order accepted" | same |
| `food.order.preparing.v1` | `food-order-service` | "being prepared" | same |
| `food.order.ready.v1` | `food-order-service` | "ready for pickup" | same |
| `food.order.delivered.v1` | `delivery-service` | "delivered" | same |
| `food.order.cancelled.v1` | `food-order-service` | "order cancelled" | same |
| `payment.failed.v1` | `payment-service` | "payment failed" | same |
| `payment.refund.completed.v1` | `payment-service` | "refund processed" | same |
| `ride.safety.sos.v1` | `ride-safety-service` | emergency broadcast | same (priority channel) |
| `trip.reward.granted.v1` | `trip-service` | "you've earned a per-trip reward" | render + send (when `trip.reward.user.kind` is for the customer) |
| `trip.reward.reversed.v1` | `trip-service` | "a per-trip reward was reversed" | render + send |
| `pricing.geo_config.updated.v1` | `admin-service` | "geo-config changed" (operator-facing, not user-facing — suppressed by default) | suppressed unless `notification.admins` includes operator recipients |
| `configuration.updated.v1` | `configuration-service` | template defaults, channel priorities | reload config |

(Full contracts in INTEGRATION.md.)

## 12. External Integrations

- **`communication-gateway-service`** — downstream; the actual
  provider routing. We do not call providers directly.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `notification.default_locale` | string | configuration-service | `en` |
| `notification.channel.priority` | array | configuration-service | e.g. `["push", "sms", "email", "in_app", "whatsapp"]` |
| `notification.retry.max_attempts` | int | configuration-service | default 3 |
| `notification.retry.backoff_seconds` | int[] | configuration-service | default `[5, 30, 120]` |
| `notification.dedup.window_seconds` | int | configuration-service | default 60 |
| `notification.quiet_hours.default` | object | configuration-service | e.g. `{ "start": "22:00", "end": "07:00" }` |
| `notification.suppress_categories` | array | configuration-service | categories to globally suppress (e.g. marketing) |
| `notification.whatsapp.template_24h_window_enforced` | bool | configuration-service | default `true`. Enforces the 24h freeform-window policy: if no inbound message from the recipient in the last 24h, refuse freeform (only pre-approved structured templates are allowed). |
| `notification.whatsapp.approval_required` | bool | configuration-service | default `true`. Refuses to send a WhatsApp template whose `provider_template_status ≠ 'approved'`. |
| `notification.template_history.retention_days` | int | configuration-service | default indefinite (audit policy). Lower values only honored via `audit-service`-authored retention run. |

## 14. Security

- **AuthN**: bearer JWT (validated at gateway); internal calls
  use client-credentials tokens.
- **AuthZ**: user can read/update their own preferences; admin
  role for templates and suppressions; service role for
  `POST /v1/notifications`.
- **Secrets**: none directly. Provider credentials live in
  `communication-gateway-service`.
- **PII**: phone, email, locale are PII; we may store them on
  the delivery row for delivery state and audit. Encrypted at
  rest (`pgcrypto`).

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`, `trace_id`,
  `notification_id`, `user_id`, `channel`, `template_id`,
  `delivery_status`, `latency_ms`.
- **Metrics**: RED (per route) + business:
  `notifications_sent_total{channel, template_id, status}`,
  `notifications_failed_total{channel, template_id, reason}`,
  `notifications_suppressed_total{reason}`,
  `notification_render_seconds` (histogram),
  `notification_delivery_seconds` (histogram),
  `channel_circuit_state{channel}`.
- **Traces**: OpenTelemetry; root span per notification;
  gateway call as child span.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka
  reachable; at least one channel's circuit is closed),
  `/started`.

## 16. Scalability

- **Replicas**: default 8 (high volume, fan-out from many
  producers).
- **HPA**: CPU 60%, custom metric
  `notifications_in_flight > 2000` per replica.
- **Hot path**: render + send. P99 ≤ 200ms (cache hit),
  ≤ 1s (cold).

## 17. Local Development

- `docker compose up notification-service` brings up the
  service, its DB, Redis, Kafka, and a mock
  `communication-gateway-service` that just acks.
- Seed: 24 templates × 5 channels × 2 locales covering common
  events, with en + ar variants. WhatsApp templates use the
  structured `body_structured` shape. See
  [`seeds/templates.v1.json`](./seeds/templates.v1.json) and
  the rendering walk-through in
  [`seeds/RENDERING_DEMO.md`](./seeds/RENDERING_DEMO.md).
- Tests: unit (template rendering, structured-template
  variable substitution, dedup, channel selection, 24h
  window enforcement, WhatsApp approval reconciliation),
  integration (Kafka in, comms-gateway out, provider
  webhook), contract (pact).

## 18. Deployment

- **Image**: `ghcr.io/uber/notification-service:<git-sha>`.
- **Replicas**: 8 in production.
- **Resource limits**: see deployment-arch (`cpu: 500m`,
  `memory: 768Mi` requests; 1 CPU, 1.5Gi limits).
- **Migrations**: run as a Kubernetes Job on deploy.
- **Templates** are loaded as part of the build (compiled
  Handlebars). New templates can be hot-added via the admin
  API without a redeploy.


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
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — WhatsApp structured template model, approval workflow, 24h window
- [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) — `notification.template_history` immutable audit table + diff summary + snapshot chain
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — `notification.deliveries.template_version_snapshot_id` audit chain
- [`PLAN.md`](./PLAN.md) — implementation tracker for the WhatsApp + history extension
- [`seeds/templates.v1.json`](./seeds/templates.v1.json) — JSON seed catalog of 24 templates × 5 channels × 2 locales
- [`seeds/RENDERING_DEMO.md`](./seeds/RENDERING_DEMO.md) — Mermaid template-rendering demo (en + ar + WhatsApp)

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`identity-service`](../identity-service/README.md), [`merchant-service`](../merchant-service/README.md), [`payment-service`](../payment-service/README.md), [`promotion-service`](../promotion-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md), [`user-profile-service`](../user-profile-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`api-gateway`](../api-gateway/README.md), [`branch-service`](../branch-service/README.md), [`checkout-service`](../checkout-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-incentive-service`](../driver-incentive-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`identity-service`](../identity-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
- [`../../workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) — SOS, fraud, emergency response
- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
