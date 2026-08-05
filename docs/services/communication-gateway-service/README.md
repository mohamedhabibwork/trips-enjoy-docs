# communication-gateway-service

## 1. Purpose

`communication-gateway-service` is the platform's **anti-corruption
layer in front of external messaging providers** (SMS, email,
push, **WhatsApp**). It owns provider credentials, provider
health, and the raw send log. The service is the *only*
component in the platform that talks to SMS providers (Twilio,
MessageBird, Unifonic), email providers (SendGrid, SES),
push providers (APNs, FCM), or WhatsApp providers (Meta Cloud
API, 360dialog, Twilio WhatsApp, MessageBird WhatsApp,
Gupshup, …). Its purpose is to keep the rest of the platform
stable as we swap providers, to bound provider cost with a
routing + retry layer, and to give support agents a single
place to look up "did the SMS / WhatsApp go out?".

New providers — WhatsApp or otherwise — are **onboarded purely
through config** (`POST /v1/admin/providers` + capability
profile + a Vault credential path), with **no schema change
and no new code**. The plug-in contract lives in
[`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md).

## 2. Bounded Context

**Bounded Context**: *External messaging providers (SMS / email
/ push / WhatsApp)*.

In scope:

- Provider credentials (per channel, per environment).
- Provider health (latency, error rate, circuit state).
- Provider routing (primary + fallback per channel).
- Provider plug-in adapter contract (the capability matrix in
  `WHATSAPP_PROVIDER_CONTRACT.md` is generic — applies to
  WhatsApp today and to Telegram / RCS / etc. tomorrow).
- Rate-limit handling (per provider, per destination).
- Send log (every send and its disposition). For WhatsApp this
  includes the provider-side template id + status, and
  `accepted`/`read` states.
- Webhook ingestion (delivery receipts, opt-outs, WhatsApp
  `accepted`/`read`/`template_status_update` events).
- OTP delivery (with rate limits per phone / per IP).
- WhatsApp 24-hour customer-service-window tracking
  (`whatsapp_window_anchor_at`, `whatsapp_window_window_seconds`).
- Anti-corruption layer that hides provider-specific response
  shapes from the rest of the platform.

Out of scope:

- Notification templates, user preferences, dedup, quiet hours,
  delivery *state* (sent / delivered / failed at the business
  level) — `notification-service`.
- The notification-side structured-template model
  (`body_structured`, `provider_template_id` mirror)
  — `notification-service` (the gateway only mirrors these
  to the provider).
- User identity — `identity-service`, `customer-service`, etc.
- Marketing campaign logic — `promotion-service`.

## 3. Responsibilities

- Maintain `comms_gateway.providers`,
  `comms_gateway.provider_capabilities`,
  `comms_gateway.sends`, `comms_gateway.webhook_events`.
- Provide `POST /v1/sends` accepting `(channel, recipient, body,
  idempotency_key, metadata)` and returning a
  `gateway_request_id` and the chosen provider. For WhatsApp
  the body is replaced by `whatsapp_template_name` +
  `whatsapp_template_language` + `whatsapp_variables`.
- Provide `POST /v1/webhooks/sms/{provider}`,
  `POST /v1/webhooks/email/{provider}`,
  `POST /v1/webhooks/push/{provider}`,
  `POST /v1/webhooks/whatsapp/{provider}` to ingest provider
  callbacks (delivery receipts, opt-outs, WhatsApp
  `accepted` / `read` / `template_status_update`).
- Provide `POST /v1/templates/submit`,
  `GET  /v1/templates/{id}/status`,
  `DELETE /v1/templates/{id}` for the WhatsApp template
  approval lifecycle. Called by `notification-service` only.
- Provide `POST /v1/admin/providers` to onboard a new provider
  with capabilities (zero-schema-change onboarding).
- Provide `GET /v1/admin/providers/{name}/capabilities` to
  answer "what does provider X support?".
- Provide `POST /v1/otp` for OTP delivery (used by
  `identity-service` and `payment-service`).
- Maintain a token bucket per provider (per QPS) and per
  destination (per phone, per email, per device).
- Circuit-breaker per provider; auto-failover to a fallback
  provider when the primary is unhealthy. Each WhatsApp
  provider also has its own circuit (no shared pool).
- Emit `comms.sms.sent.v1`, `comms.email.sent.v1`,
  `comms.push.sent.v1`, `comms.whatsapp.accepted.v1`,
  `comms.whatsapp.delivered.v1`, `comms.whatsapp.read.v1`,
  `comms.whatsapp.failed.v1`,
  `comms.whatsapp.template_status_update.v1`.
- Emit `comms.send.failed.v1` for every persistent failure on
  any channel.
- Honor opt-outs (STOP for SMS, STOP for WhatsApp, unsubscribe
  for email) by ingesting webhook events and storing the
  opt-out in `comms_gateway.optouts`.

## 4. Explicitly NOT Owned

- **Notification templates, preferences, dedup, quiet hours** —
  `notification-service`.
- **User identity, KYC** — `identity-service`,
  `customer-service`, etc.
- **Marketing campaigns** — `promotion-service`.
- **Provider-side data** (e.g. the contact list inside
  Twilio) — we only store the provider's reference id for
  the message.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `notification-service` | system | primary caller; sends push, SMS, email, WhatsApp; submits/queries/deletes WhatsApp templates |
| `identity-service` | system | OTP delivery for phone verification |
| `payment-service` | system | OTP delivery for cardholder verification (3DS) |
| `ride-safety-service` | system | emergency broadcast (push + SMS + WhatsApp) |
| `admin-service` | system | admin operations (provider rotation, provider onboarding) |
| Operations (admin) | human | manage providers, view send log |
| Provider (Twilio, SendGrid, APNs, FCM, Meta Cloud, 360dialog, Gupshup, …) | system | external |

## 6. Dependencies

### Synchronous (REST)

- **SMS provider (primary)** — Twilio (or MessageBird /
  Unifonic) — SLO 99.9% — circuit breaker: yes.
- **SMS provider (fallback)** — second vendor — circuit
  breaker: yes (separate).
- **Email provider (primary)** — SendGrid (or SES) — SLO
  99.9% — circuit breaker: yes.
- **Email provider (fallback)** — second vendor — circuit
  breaker: yes.
- **Push provider (APNs)** — Apple Push Notification
  service — SLO 99.9% — circuit breaker: yes.
- **Push provider (FCM)** — Firebase Cloud Messaging — SLO
  99.9% — circuit breaker: yes.
- **WhatsApp provider (primary)** — Meta Cloud API direct,
  or one of: Twilio WhatsApp / MessageBird WhatsApp /
  360dialog / Gupshup — SLO 99.9% — circuit breaker: yes.
  Selected via `comms.whatsapp.provider` config or
  `providers.regional_routing`.
- **WhatsApp provider (fallback)** — second WhatsApp vendor
  for auto-failover — circuit breaker: yes (separate).
- `configuration-service` — read provider config, rate
  limits — SLO 99.95% — circuit breaker: yes.

### Asynchronous (events consumed)

- `configuration.updated.v1` from `configuration-service` —
  provider selection, rate limits — duplicate handling:
  reload is idempotent (config hash compared).
- `feature_flag.updated.v1` from `feature-flag-service` —
  toggle mock provider — duplicate handling: reload.

### Asynchronous (events produced)

- `comms.sms.sent.v1`, `comms.email.sent.v1`,
  `comms.push.sent.v1` — every successful provider ack.
- `comms.send.failed.v1` — every persistent failure.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript) — good async I/O for
  webhook handling.
- Database: PostgreSQL 18 in schema `comms_gateway`
  (providers, credentials refs, sends, webhook events,
  optouts).
- Cache: Redis 7 (per-service) for rate-limit counters and
  opt-out lookups.
- Event broker: Kafka.
- Provider SDKs:
  - Twilio Node SDK for SMS.
  - @sendgrid/mail for email.
  - node-apn for APNs.
  - firebase-admin for FCM.

## 8. Database Ownership

- Schema: `comms_gateway`
- Migrations: `services/communication-gateway-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (providers, optouts).
- Partitioning: yes — `comms_gateway.sends` partitioned by
  month (high volume).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/sends | service | send a message (sms, email, push, whatsapp) |
| GET | /v1/sends/{gateway_request_id} | service | read send status |
| POST | /v1/otp | service | deliver an OTP |
| POST | /v1/templates/submit | service | submit a WhatsApp template to the provider for approval |
| GET | /v1/templates/{id}/status | service / admin | poll the provider's WhatsApp template approval status |
| DELETE | /v1/templates/{id} | admin | delete a previously approved WhatsApp template |
| POST | /v1/webhooks/sms/{provider} | provider signature | SMS delivery receipt |
| POST | /v1/webhooks/email/{provider} | provider signature | email delivery receipt |
| POST | /v1/webhooks/push/{provider} | provider signature | push delivery receipt |
| POST | /v1/webhooks/whatsapp/{provider} | provider signature | WhatsApp delivery / read / accepted / template-status webhooks |
| POST | /v1/admin/providers | admin + mTLS | onboard a new provider (zero-schema-change) |
| GET | /v1/admin/providers/{name}/capabilities | admin | "what does provider X support?" |
| POST | /v1/admin/providers/capabilities | admin | enable / disable a single capability on an existing provider |
| POST | /v1/admin/providers/rotate | admin + mTLS | rotate provider credentials |
| GET | /v1/admin/sends | admin | list recent sends (audit) |
| GET | /v1/admin/optouts | admin | list opt-outs |

(Full contracts in INTEGRATION.md; provider plug-in contract in [`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md).)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `comms.sms.sent.v1` | successful SMS provider ack | `notification-service`, `audit-service`, `analytics-service` |
| `comms.email.sent.v1` | successful email provider ack | same |
| `comms.push.sent.v1` | successful push provider ack | same |
| `comms.whatsapp.accepted.v1` | WhatsApp provider pipeline ack | same |
| `comms.whatsapp.delivered.v1` | WhatsApp delivery webhook | same |
| `comms.whatsapp.read.v1` | WhatsApp read webhook | `notification-service`, `analytics-service` |
| `comms.whatsapp.failed.v1` | WhatsApp persistent failure | same as `comms.send.failed.v1` |
| `comms.whatsapp.template_status_update.v1` | provider reports a template approval | `notification-service`, `analytics-service` |
| `comms.send.failed.v1` | persistent failure on any channel | `notification-service`, `audit-service` |

(Full contracts in INTEGRATION.md.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | provider selection, rate limits changed | reload config |
| `feature_flag.updated.v1` | `feature-flag-service` | mock provider toggle, debug logging | re-evaluate flag |

(Full contracts in INTEGRATION.md.)

## 12. External Integrations

- **Twilio** (or MessageBird / Unifonic) — SMS. Credentials
  in Vault at `kv/platform/<env>/comms-gateway/sms/twilio`.
- **SendGrid** (or SES) — email. Credentials in Vault at
  `kv/platform/<env>/comms-gateway/email/sendgrid`.
- **APNs** — Apple Push Notification service. Credentials
  in Vault at
  `kv/platform/<env>/comms-gateway/push/apns`.
- **FCM** — Firebase Cloud Messaging. Credentials in Vault at
  `kv/platform/<env>/comms-gateway/push/fcm`.
- **Meta Cloud API** (WhatsApp direct) — Credentials in Vault
  at `kv/platform/<env>/comms-gateway/whatsapp/meta-cloud`.
- **Twilio WhatsApp** — same Twilio credential path as SMS,
  distinguished by the `provider_kind='whatsapp_bsp'` row
  in `comms_gateway.providers`.
- **360dialog / MessageBird WhatsApp / Gupshup** — secondary
  WhatsApp providers, each with their own Vault credential
  path. Onboarded via `POST /v1/admin/providers`.
- **Vault** — credential storage for all of the above.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `comms.sms.provider` | string | configuration-service | `twilio` / `messagebird` / `unifonic` |
| `comms.sms.fallback_provider` | string | configuration-service | nullable |
| `comms.email.provider` | string | configuration-service | `sendgrid` / `ses` |
| `comms.email.fallback_provider` | string | configuration-service | nullable |
| `comms.push.ios.provider` | string | configuration-service | `apns` |
| `comms.push.android.provider` | string | configuration-service | `fcm` |
| `comms.whatsapp.enabled` | bool | configuration-service | default `false` — feature flag for staged rollout |
| `comms.whatsapp.provider` | string | configuration-service | nullable until onboarded; see `WHATSAPP_PROVIDER_CONTRACT.md` |
| `comms.whatsapp.fallback_provider` | string | configuration-service | nullable |
| `comms.whatsapp.regional_routing` | map | configuration-service | country code → provider priority |
| `comms.rate_limit.sms.per_phone_per_minute` | int | configuration-service | default 5 |
| `comms.rate_limit.sms.per_provider_qps` | int | configuration-service | default 1000 |
| `comms.rate_limit.email.per_recipient_per_minute` | int | configuration-service | default 10 |
| `comms.rate_limit.email.per_provider_qps` | int | configuration-service | default 500 |
| `comms.rate_limit.push.per_device_per_minute` | int | configuration-service | default 60 |
| `comms.rate_limit.whatsapp.per_recipient_per_minute` | int | configuration-service | default 10 |
| `comms.rate_limit.whatsapp.per_provider_qps` | int | configuration-service | default 500 |
| `comms.otp.ttl_seconds` | int | configuration-service | default 300 |
| `comms.otp.max_attempts` | int | configuration-service | default 5 |
| `comms.whatsapp.window.seconds` | int | configuration-service | default 86400 (= 24h); snapshotted on every send into `sends.whatsapp_window_window_seconds` |

## 14. Security

- **AuthN**: bearer JWT for `POST /v1/sends` and
  `POST /v1/otp` (service-to-service); provider signatures
  on webhook endpoints; mTLS for `POST /v1/admin/providers/rotate`.
- **AuthZ**: role `service` for `POST /v1/sends` (only
  `notification-service` and `identity-service` may call);
  role `admin` for provider rotation.
- **Secrets**: provider credentials in Vault; never in env
  or source. Rotated quarterly.
- **PII**: phone, email, device token are PII; we may store
  them on the send row for the send log and audit. Encrypted
  at rest (`pgcrypto`).

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`, `trace_id`,
  `gateway_request_id`, `channel`, `provider`, `recipient_hash`
  (SHA-256 of phone/email/device for PII safety), `latency_ms`,
  `status`.
- **Metrics**: RED (per route) + business:
  `comms_sends_total{channel, provider, status}`,
  `comms_send_seconds` (histogram),
  `provider_circuit_state{channel, provider}`,
  `provider_rate_limit_remaining{channel, provider}`,
  `comms_optouts_total{channel, reason}`.
- **Traces**: OpenTelemetry; root span per send; provider
  call as child span; webhook as child span.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka + at
  least one provider per channel reachable), `/started`.

## 16. Scalability

- **Replicas**: default 6. Webhook traffic and send traffic
  are both bursty.
- **HPA**: CPU 60%, custom metric
  `comms_sends_per_second > 200` per replica.
- **Hot path**: `POST /v1/sends`. P99 ≤ 500ms (push, where
  APNs/FCM are fast), ≤ 1.5s (SMS/email, where the provider
  is slower).

## 17. Local Development

- `docker compose up communication-gateway-service` brings
  up the service, its DB, Redis, and a mock SMS/email/push
  provider that acks immediately.
- Seed: 4 mock providers (twilio, sendgrid, apns, fcm) with
  fake credentials.
- Tests: unit, integration (with mock providers), contract
  (with real provider sandbox in staging).

## 18. Deployment

- **Image**: `ghcr.io/uber/communication-gateway-service:<git-sha>`.
- **Replicas**: 6 in production.
- **Resource limits**: see deployment-arch (`cpu: 500m`,
  `memory: 768Mi` requests; 1 CPU, 1.5Gi limits).
- **Migrations**: run as a Kubernetes Job on deploy.
- **Credentials**: loaded from Vault on pod start; restart
  on rotation.


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
- [`PLAN.md`](./PLAN.md) — implementation tracker (11 phases including WhatsApp)
- [`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md) — plug-in provider capability matrix, adapter lifecycle, onboarding playbook

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`promotion-service`](../promotion-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md)
- **Depended on by**: [`notification-service`](../notification-service/README.md), [`support-service`](../support-service/README.md)

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

- [`../../workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) — SOS, fraud, emergency response
