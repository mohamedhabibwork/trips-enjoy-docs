# communication-gateway-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`communication-gateway-service` must do for the business. It
is read by the platform architecture team, the procurement team
that manages vendor contracts, the operations team that
investigates delivery issues, the service's engineering team,
and any auditor verifying the platform's compliance with
anti-spam and privacy laws. It informs the multi-vendor
strategy, the rate-limit policy, the opt-out handling, and
the per-region provider routing.

## 2. Business Context

The platform sends SMS, email, and push notifications to
millions of users. Each channel has multiple providers (Twilio
for SMS, SendGrid for email, APNs/FCM for push) and each
provider has a contract, a per-message cost, a quota, a
latency profile, and a delivery-rate profile. The platform's
product team wants:

1. **Vendor stability** — when one provider is down, we
   fall back to another without any customer-facing change.
2. **Cost optimization** — we route to the cheapest
   provider that meets the SLA.
3. **Compliance** — opt-outs (STOP, unsubscribe) are honored
   immediately, not "eventually".
4. **Audit** — support agents can answer "did the SMS go
   out?" in seconds.
5. **OTP** — phone-based identity verification and 3DS flows
   depend on a fast, reliable OTP channel.

`communication-gateway-service` is the *only* place in the
platform that talks to external providers. Without it, every
service that wants to send a notification would embed provider
SDKs, get rate limits wrong, miss opt-outs, and lock us into
a single vendor.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a single, stable, vendor-neutral API for SMS, email, push, and WhatsApp | every send flows through this service; no service embeds provider SDKs |
| BR--002 | Survive a single provider outage with zero customer-facing errors | fallback activation ≤ 5s after primary circuit opens |
| BR--003 | Honor opt-outs (STOP, unsubscribe) within 1 minute of the user's request | 100% of opt-outs honored |
| BR--004 | Keep per-message cost trending down via multi-vendor routing | average per-channel cost trending down month over month |
| BR--005 | Deliver OTPs in P95 ≤ 5s and P99 ≤ 15s | API P95/P99 measured at the edge |
| BR--006 | Provide a complete send log for support and audit | 100% of sends have a `gateway_request_id` and a delivery disposition |
| BR--007 | Allow swapping a vendor in ≤ 30 days without downstream service code changes | documented vendor-portability exercise |
| BR--008 | Stay under per-phone, per-email, per-device rate limits (anti-spam) | 100% of sends respect rate limits |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Procurement | owner | vendor contracts, cost |
| Platform Architecture | owner | vendor abstraction, SLO |
| Operations | consumer | "did the SMS go out?" |
| Trust & Safety | consumer | emergency broadcasts |
| Compliance | reviewer | opt-out, anti-spam, GDPR |
| Finance | reviewer | per-channel cost |
| Marketing | consumer | high-volume promotional sends |
| Engineering (consumer services) | consumer | one API for any channel |

## 5. Actors / Personas

- **`notification-service`** — primary caller; sends push,
  SMS, email.
- **`identity-service`** — sends OTP for phone verification.
- **`payment-service`** — sends OTP for 3DS.
- **`ride-safety-service`** — sends emergency broadcasts
  (push + SMS, bypassing rate limits).
- **Operations (admin)** — manages providers, rotates
  credentials, investigates delivery issues.
- **End user** — receives the messages; can opt out (STOP,
  unsubscribe).

## 6. Business Capabilities

- **Send** (SMS, email, push) via the chosen provider with
  per-channel circuit breaker and fallback.
- **OTP** (specialized send with rate limits per phone / IP).
- **Webhook ingestion** (delivery receipts, opt-outs,
  bounces).
- **Opt-out management** (STOP for SMS, unsubscribe for
  email, app uninstall for push).
- **Rate limiting** (per provider, per destination, per IP
  for OTP).
- **Provider rotation** (admin).
- **Mock provider** (for dev, test, CI).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only component in the platform that talks to external SMS, email, or push providers. | MUST | data ownership, platform architecture |
| BR--011 | The service MUST support a primary and a fallback provider per channel (SMS, email, push). | MUST | vendor risk |
| BR--012 | The service MUST honor opt-outs within 1 minute of the user's request. | MUST | compliance, anti-spam |
| BR--013 | The service MUST enforce per-provider QPS limits and per-destination rate limits. | MUST | vendor contract, anti-spam |
| BR--014 | The service MUST provide a stable, vendor-neutral API for sending. | MUST | platform architecture |
| BR--015 | The service MUST support OTP delivery with stricter rate limits (per phone, per IP). | MUST | security, 3DS |
| BR--016 | The service MUST ingest provider webhooks (delivery receipts, opt-outs, bounces) and update the send disposition. | MUST | audit, opt-out |
| BR--017 | The service MUST allow admin to rotate provider credentials without downtime. | MUST | security, ops |
| BR--018 | The service MUST emit `comms.*.sent.v1` for every successful provider ack, and `comms.send.failed.v1` for every persistent failure. | MUST | audit, analytics |
| BR--019 | The service MUST NOT log or persist raw phone numbers / email addresses / device tokens in plain text. | MUST | GDPR, PII |
| BR--020 | The service MUST support a mock provider for dev / test / CI. | MUST | DX, CI |
| BR--021 | The service MUST cache opt-out lookups in Redis with sub-millisecond reads (so the send path is not slowed by the lookup). | MUST | performance |
| BR--022 | The service MUST support regional provider routing (e.g. APAC SMS provider for APAC phone numbers, EMEA provider for EMEA). | MUST | cost, latency |
| BR--023 | The service MUST retry transient failures (provider 5xx, timeout) with exponential backoff. | MUST | reliability |
| BR--024 | The service MUST support HMAC-SHA256 signature verification on provider webhooks. | MUST | security |
| BR--040 | The service MUST support WhatsApp as a first-class transport channel (alongside SMS, email, push). | MUST | product (WhatsApp rollout) |
| BR--041 | Onboarding a new provider (any WhatsApp vendor, or future Telegram/RCS/Slack) MUST require **zero schema changes** — only `POST /v1/admin/providers` + a Vault credential path. | MUST | vendor agility |
| BR--042 | The service MUST encode the plug-in provider capability matrix in `comms_gateway.provider_capabilities`; calling code resolves capabilities by name, not by adapter class. | MUST | extensibility |
| BR--043 | The service MUST emit channel-specific `comms.<channel>.<state>.v1` events (`comms.whatsapp.accepted/delivered/read/failed.v1`, `comms.whatsapp.template_status_update.v1`) so downstream consumers (notification-service, analytics, audit) can act on WhatsApp-specific state transitions. | MUST | audit, observability |
| BR--044 | The service MUST track the 24-hour customer-service window for each WhatsApp send (`sends.whatsapp_window_anchor_at` + `sends.whatsapp_window_window_seconds`), snapshotted at send time from `comms.whatsapp.window.seconds`. | MUST | Meta Business policy |
| BR--045 | The service MUST honor WhatsApp STOP opt-outs within 1 minute of the user's request by ingesting the provider's `optout` webhook and writing `(channel='whatsapp', recipient_hash)` into `comms_gateway.optouts`. | MUST | compliance, Meta Business policy |
| BR--046 | The service MUST support WhatsApp template submission, status polling, and deletion via `POST /v1/templates/submit`, `GET /v1/templates/{id}/status`, `DELETE /v1/templates/{id}` so that notification-service does not talk to providers directly. | MUST | boundary integrity |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | Per-destination rate limits (per phone / email / device) are enforced before the provider call. | |
| BR--021 | Opt-outs are checked before the provider call. | |
| BR--022 | The fallback provider is used only when the primary's circuit is open. | |
| BR--023 | Provider selection is deterministic per (channel, region, recipient prefix). | e.g. APAC phone numbers → APAC SMS provider |
| BR--024 | Emergency broadcasts (priority `urgent`, e.g. SOS) bypass rate limits and opt-outs. | |
| BR--025 | OTP rate limit: 5 attempts per phone per hour, 10 attempts per IP per hour. | |
| BR--026 | Send log retention: 90 days; older rows are anonymized (recipient_hash retained, raw PII purged). | per retention policy |
| BR--027 | Provider credentials are never logged. | |
| BR--028 | Webhook endpoints require provider signature verification. | |
| BR--050 | Each WhatsApp send row in `sends` MUST carry the provider-side template id, the registered language, the immediate template status, the encrypted rendered components, and the window anchor + length (snapshot at send time). | |
| BR--051 | The `whatsapp` channel's circuit breaker pool is isolated from SMS/email/push so that a WhatsApp outage does not consume SMS or email capacity. | |
| BR--052 | Provider webhook signature verification header is per-provider — recorded in `providers.webhook_signature_header` and `providers.webhook_signature_algorithm`. | enables exotic schemes (e.g. APNs JWT, Meta `X-Hub-Signature-256`) without schema change |

## 9. Assumptions

- Provider APIs are stable enough that a swap takes ≤ 30
  days (vendor-portability exercise).
- Provider webhooks deliver reliably (with retries); we
  idempotently process them.
- The volume of sends is bursty (e.g. during a stadium
  match end) but bounded; we can scale horizontally.
- Phone numbers are E.164; email addresses are valid format;
  device tokens are APNs or FCM tokens.

## 10. Constraints

- **Cost**: SMS is the most expensive channel; we must
  default to push when possible.
- **Compliance**: GDPR / PDPL / TCPA / anti-spam laws; opt-outs
  must be honored.
- **Reliability**: OTPs must be delivered; the
  `identity-service` and `payment-service` flows depend on
  them.
- **PII**: phone, email, device token are PII; we must
  protect them and not log in plain text.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Twilio (or MessageBird / Unifonic) | provider | SMS primary; per-region routing |
| SendGrid (or SES) | provider | email |
| APNs | provider | iOS push |
| FCM | provider | Android push |
| `notification-service` | service | primary caller |
| `identity-service` | service | OTP for phone verification |
| `payment-service` | service | OTP for 3DS |
| `ride-safety-service` | service | emergency broadcasts |
| `configuration-service` | service | read provider config, rate limits |
| `feature-flag-service` | service | mock provider toggle |
| `audit-service` | consumer | reads `comms.*.sent.v1` events |
| `analytics-service` | consumer | reads `comms.*.sent.v1` events |
| PostgreSQL 18 | infra | core storage |
| Redis 7 | infra | rate limit counters, opt-out cache |
| Kafka | infra | events |
| Vault | infra | provider credentials |

## 12. Business Workflows

- **Send an SMS** — see `WORKFLOWS.md` §1.
- **Deliver an OTP** — see `WORKFLOWS.md` §2.
- **Ingest a webhook (delivery receipt)** — see
  `WORKFLOWS.md` §3.
- **Honour an opt-out (STOP)** — see `WORKFLOWS.md` §4.
- **Provider fallback activation** — see `WORKFLOWS.md` §5.

## 13. Exception Workflows

- **All providers' circuits open** for a channel: the
  service returns 503 `CIRCUIT_OPEN`; the caller (typically
  `notification-service`) falls back to its own retry
  policy.
- **Rate limit exceeded**: the send is rejected with 429
  `RATE_LIMITED`; the caller is expected to retry later
  (or use a different channel).
- **Opt-out detected**: the send is suppressed; no provider
  call; the caller is acked.
- **Provider webhook signature invalid**: 401; the webhook
  is rejected; the provider retries.

## 14. Success Criteria

- 100% of sends flow through this service (no service
  embeds provider SDKs directly).
- 100% of opt-outs honored within 1 minute.
- P95 OTP delivery ≤ 5s, P99 ≤ 15s.
- Vendor swap (e.g. Twilio → MessageBird) completed in ≤ 30
  days.
- Per-channel cost trending down month over month.
- Zero PAN, CVV, or financial PII ever stored or processed.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Send P95 (push) | ≤ 200ms | `comms_send_seconds{channel=push}` P95 |
| Send P95 (SMS) | ≤ 1s | `comms_send_seconds{channel=sms}` P95 |
| Send P95 (email) | ≤ 1.5s | `comms_send_seconds{channel=email}` P95 |
| OTP delivery P95 | ≤ 5s | `comms_otp_delivery_seconds` P95 |
| Fallback activation time | ≤ 5s | `provider_circuit_open_to_fallback_seconds` |
| Opt-out honor latency | ≤ 1 minute | `optout_request_to_honor_seconds` P95 |
| Per-channel cost per 1k sends | trending down | finance dashboard |
| Provider delivery rate | ≥ 95% | `comms_sends_total{status=delivered} / total` |

## 16. Acceptance Criteria

- All three channels (SMS, email, push) implemented with
  primary + fallback.
- All 16 business requirements implemented and verified by
  automated tests.
- A simulated primary provider outage in staging results in
  automatic fallback within 5s; no customer-facing errors.
- An opt-out webhook from Twilio results in the next send
  to that phone being suppressed (verified by an integration
  test).
- An OTP delivery P95 ≤ 5s in the load test.
- A vendor swap dry run (config-only) succeeds without any
  downstream service code change.
- Provider credentials are loaded from Vault and never
  appear in logs.

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
- [`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md) — plug-in provider capability matrix, onboarding playbook

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

