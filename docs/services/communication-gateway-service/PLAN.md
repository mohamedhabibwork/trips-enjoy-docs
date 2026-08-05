# communication-gateway-service — Implementation Plan

**Domain:** Platform & Operations
**Tier:** 2
**Technology:** Go + chi
**Criticality:** T2 (99.9% SLO)
**DB Schema:** `comms_gateway`
**Cache:** Redis — delivery receipts, dedup
**HPA:** RPS, 3–50, p99 < 100ms

---

## Purpose

`communication-gateway-service` is the platform's anti-corruption layer in front of external messaging providers (SMS, email, push). It is the only component that talks to Twilio, SendGrid, APNs, or FCM, providing provider health tracking, routing with failover, rate-limit handling, and a unified send log.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `comms_gateway`: tables `providers`, `credentials`, `sends` (partitioned by month), `webhook_events`, `optouts`, `outbox`, `inbox`
- [ ] Key columns: `sends(id UUID, gateway_request_id UUID UNIQUE, channel TEXT, provider TEXT, recipient_hash TEXT, idempotency_key TEXT UNIQUE, status TEXT, provider_ref_id TEXT, sent_at TIMESTAMPTZ, delivered_at TIMESTAMPTZ)`
- [ ] Write golang-migrate migrations (forward-only)
- [ ] Implement `Send` aggregate, provider routing logic, `OptOut` repository

### Phase 2 — REST API
- [ ] `POST /v1/sends` — send a message (SMS/email/push); returns `gateway_request_id`
- [ ] `GET /v1/sends/{gateway_request_id}` — read send status
- [ ] `POST /v1/otp` — deliver OTP (rate-limited per phone/IP)
- [ ] `POST /v1/webhooks/sms/{provider}` — ingest SMS delivery receipt
- [ ] `POST /v1/webhooks/email/{provider}` — ingest email delivery receipt
- [ ] `POST /v1/webhooks/push/{provider}` — ingest push delivery receipt
- [ ] `POST /v1/admin/providers/rotate` — rotate provider credentials (admin + mTLS)
- [ ] `GET /v1/admin/sends` — list recent sends for audit (admin)
- [ ] `GET /v1/admin/optouts` — list opt-outs (admin)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `comms.sms.sent.v1` → on successful SMS provider ack
- [ ] Publish `comms.email.sent.v1` → on successful email provider ack
- [ ] Publish `comms.push.sent.v1` → on successful push provider ack
- [ ] Publish `comms.send.failed.v1` → on persistent failure after retries
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `configuration.updated.v1` → reload provider selection and rate limits
- [ ] Consume `feature_flag.updated.v1` → re-evaluate mock provider flag

### Phase 5 — Caching
- [ ] Redis: delivery receipt dedup window (TTL = message TTL)
- [ ] Redis: rate-limit token buckets per provider (per QPS) and per destination (per phone/email/device)
- [ ] Redis: opt-out lookups (`optout:{channel}:{recipient_hash}`)

### Phase 6 — External Integrations
- [ ] Twilio (or MessageBird/Unifonic) — SMS primary + fallback
- [ ] SendGrid (or SES) — email primary + fallback
- [ ] APNs — iOS push notifications
- [ ] FCM (Firebase Cloud Messaging) — Android push notifications
- [ ] HashiCorp Vault — provider credentials (`kv/platform/<env>/comms-gateway/...`)
- [ ] Circuit breakers per provider; auto-failover to fallback on open

### Phase 7 — Security
- [ ] JWT bearer auth via `coreos/go-oidc v3` for service-to-service calls
- [ ] Provider webhook signature verification (Twilio signature, SendGrid event webhook)
- [ ] mTLS for `POST /v1/admin/providers/rotate`
- [ ] Required scopes/roles: `service` role for `/v1/sends`, `admin` for provider rotation
- [ ] PII: phone/email/device token encrypted at rest (`pgcrypto`); recipient stored as SHA-256 hash in logs
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `gateway_request_id`, `channel`, `provider`, `recipient_hash`, `latency_ms`, `status`
- [ ] Metrics: RED per route + `comms_sends_total{channel,provider,status}`, `comms_send_seconds`, `provider_circuit_state{channel,provider}`, `provider_rate_limit_remaining`, `comms_optouts_total{channel,reason}`
- [ ] OpenTelemetry traces with child spans for provider call, webhook ingestion
- [ ] Health endpoints: `/health`, `/ready` (DB + Redis + Kafka + at least one provider per channel), `/started`

### Phase 9 — Testing
- [ ] Unit tests: provider routing, rate-limit logic, opt-out enforcement
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis); mock providers
- [ ] E2E tests: send SMS, provider failover, webhook delivery receipt, OTP flow

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (RPS, 3–50 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

### Phase 11 — WhatsApp plug-in provider (zero-schema-change onboarding)
- [ ] Run the `comms_gateway` schema v1.1 migration (see
      [`ERD.md`](./ERD.md) §12). Verify the new CHECK
      constraints on every channel-touching table and the
      new `provider_capabilities` table.
- [ ] Implement `POST /v1/admin/providers` (HMAC + mTLS) that
      registers a new `providers` row + asserts capabilities
      in `provider_capabilities`. Unknown capability name →
      422 `UNKNOWN_CAPABILITY`. Duplicate name → 409
      `PROVIDER_ALREADY_EXISTS`.
- [ ] Implement `GET /v1/admin/providers/{name}/capabilities`.
- [ ] Implement `POST /v1/admin/providers/capabilities`
      (HMAC) to add/disable a single capability without
      re-registering.
- [ ] Implement the WhatsApp adapter layer:
      `MetaCloudWhatsApp`, `360dialogWhatsApp`,
      `MessageBirdWhatsApp`, `TwilioWhatsApp`, `GupshupWhatsApp`.
      Each adapter implements `capabilities()` and the
      vendor-specific methods; capability assertion in the DB
      is the single source of routing truth (NOT the adapter
      class name).
- [ ] Implement `POST /v1/webhooks/whatsapp/{provider}` with
      signature verification via the per-provider
      `webhook_signature_header` + `webhook_signature_algorithm`.
      Recognised event types: `accepted`, `sent`, `delivered`,
      `read`, `failed`, `template_status_update`, `optout`,
      `bounce`, `complaint`.
- [ ] Implement `POST /v1/templates/submit`,
      `GET /v1/templates/{id}/status`,
      `DELETE /v1/templates/{id}` for the WhatsApp template
      lifecycle. Service-to-service auth — only
      `notification-service` may call.
- [ ] Implement `comms.whatsapp.accepted.v1`,
      `comms.whatsapp.delivered.v1`, `comms.whatsapp.read.v1`,
      `comms.whatsapp.failed.v1`,
      `comms.whatsapp.template_status_update.v1` events via
      the existing outbox pipeline.
- [ ] Persist `sends.whatsapp_*` columns on every WhatsApp send
      (template id, language, immediate status, encrypted
      components, 24h-window snapshot). The CHECK constraints
      in `ERD.md` §12 must pass on the test suite.
- [ ] Add `comms.rate_limit.whatsapp.per_recipient_per_minute`
      and `comms.rate_limit.whatsapp.per_provider_qps`
      Redis token buckets.
- [ ] Add circuit-breaker pool for `channel='whatsapp'`
      isolated from SMS/email/push.
- [ ] Operator onboarding playbook documented in
      [`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md)
      §6.
- [ ] Acceptance: onboarding a new WhatsApp provider in a
      staging environment takes ≤ 1 hour end-to-end (Vault
      write → admin POST → first test send → webhook →
      `notification-service` snapshot → read event).

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| Twilio / MessageBird / Unifonic | SMS API | Send SMS | Yes (with fallback) |
| SendGrid / SES | Email API | Send email | Yes |
| APNs | Push API | iOS push | Yes |
| FCM | Push API | Android push | Yes |
| Meta Cloud / 360dialog / Twilio WhatsApp / MessageBird WhatsApp / Gupshup | WhatsApp API | Send WhatsApp structured template | Yes (isolated pool, with fallback) |
| `configuration-service` | `GET /v1/configurations/{key}` | Provider config, rate limits | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `comms.sms.sent.v1` | `comms.sms.sent` | Successful SMS provider ack | `notification-service`, `audit-service`, `analytics-service` |
| `comms.email.sent.v1` | `comms.email.sent` | Successful email provider ack | `notification-service`, `audit-service`, `analytics-service` |
| `comms.push.sent.v1` | `comms.push.sent` | Successful push provider ack | `notification-service`, `audit-service`, `analytics-service` |
| `comms.whatsapp.accepted.v1` | `comms.whatsapp.accepted` | WhatsApp provider pipeline ack | `notification-service`, `audit-service`, `analytics-service` |
| `comms.whatsapp.delivered.v1` | `comms.whatsapp.delivered` | WhatsApp delivery webhook | same |
| `comms.whatsapp.read.v1` | `comms.whatsapp.read` | WhatsApp read webhook | `notification-service`, `analytics-service` |
| `comms.whatsapp.failed.v1` | `comms.whatsapp.failed` | WhatsApp persistent failure | `notification-service`, `audit-service` |
| `comms.whatsapp.template_status_update.v1` | `comms.whatsapp.template_status_update` | Provider reports template status | `notification-service`, `analytics-service` |
| `comms.send.failed.v1` | `comms.send.failed` | Persistent failure (any channel) | `notification-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `configuration.updated.v1` | `configuration-service` | Reload provider selection, regional routing, and rate limits (per channel + per WhatsApp) |
| `feature_flag.updated.v1` | `feature-flag-service` | Re-evaluate mock provider toggle |
| `notification.retry_requested.v1` | `notification-service` | (existing) Retry previously failed sends; idempotent on `gateway_request_id` |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 100ms)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage
- [ ] **Phase 11**: onboarding a new WhatsApp provider in
      ≤ 1 hour from Vault write to first test send + webhook
      reconciliation, with zero schema changes
- [ ] **Phase 11**: every WhatsApp send row carries the
      provider-template id, language, immediate status,
      encrypted components, and 24h-window snapshot

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 100ms)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [WHATSAPP_PROVIDER_CONTRACT](WHATSAPP_PROVIDER_CONTRACT.md) — plug-in provider capability matrix, onboarding playbook
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
