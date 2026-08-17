# notification-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 0 (position 7 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T2 (99.9% SLO)
**DB Schema:** `notification`
**Cache:** Redis — per-channel rate-limit counters
**HPA:** Kafka consumer lag, 2–8, p99 < 250ms

---

> Mirrors the [``notification-service` (provider ACL)/PLAN.md`](../notification-service/PLAN.md)
> style. This is the implementation tracker for the v1.1
> WhatsApp + template-history extension. Use it as the
> project-management counterpart to the spec in
> [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md),
> [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md),
> [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md),
> [`ERD.md`](./ERD.md) 12 (migration snippet), and
> [`INTEGRATION.md`](./INTEGRATION.md).

## Phase 0 — Pre-requisites


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P0-01 | Confirm multi-region / multi-language matrix | pending | — | notification.admin | notification.admin | — | — |
      (`locale ∈ {en, ar, …}`) and lock the list of locales for
      v1.1.
| T-NTF-P0-02 | Lock the canonical capability list of WhatsApp providers | pending | T-NTF-P0-01 | notification.admin | notification.admin | — | — |
      with the provider ACL now absorbed into this service
      (see [`INTEGRATION.md` §2](./INTEGRATION.md#2-outbound-apis)).
| T-NTF-P0-03 | Approve the canonical `template_history.diff_summary` | pending | T-NTF-P0-02 | notification.admin | notification.admin | — | — |
      JSON schema with ``reporting-service` (data lake)` and `audit-service`
      consumers.

## Phase 1 — Schema (v1.1 forward-only migration)


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P1-01 | Run the migration in [`ERD.md`](./ERD.md) 12 | pending | — | notification.admin | notification.admin | — | — |
      (`notification` schema v1.1). Verify CHECK-constraint
      extensions and the new `template_history` table + trigger.
| T-NTF-P1-02 | Verify the append-only trigger blocks UPDATE / DELETE on | pending | T-NTF-P1-01 | notification.admin | notification.admin | — | — |
      `template_history` (negative test: try `UPDATE
      notification.template_history SET diff_summary = '{}' WHERE id = X`
      and confirm it raises the expected exception).
| T-NTF-P1-03 | Verify the discriminator CHECK enforces mutual exclusivity | pending | T-NTF-P1-02 | notification.admin | notification.admin | — | — |
      for `templates.template_type` / `body` / `body_structured`.
| T-NTF-P1-04 | Verify the WhatsApp delivery conditional CHECK enforces | pending | T-NTF-P1-03 | notification.admin | notification.admin | — | — |
      `rendered_provider_template_id IS NOT NULL` for
      `channel='whatsapp'`.
| T-NTF-P1-05 | Indexes pre-created for new columns: confirm the planner | pending | T-NTF-P1-04 | notification.admin | notification.admin | — | — |
      uses `deliveries_template_history_idx` for the support view.

## Phase 2 — Admin endpoints


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P2-01 | `POST /v1/admin/templates/{id}/submit-for-approval` | pending | — | notification.admin | notification.admin | — | — |
      (`notification-template-admin` role; HMAC).
| T-NTF-P2-02 | `POST /v1/admin/templates/{id}/approve` | pending | T-NTF-P2-01 | notification.admin | notification.admin | — | — |
      (called by webhook handler; admin/notification_ops role; HMAC).
| T-NTF-P2-03 | `POST /v1/admin/templates/{id}/publish` | pending | T-NTF-P2-02 | notification.admin | notification.admin | — | — |
      (atomic-across-locales; `notification.admin` role; HMAC).
| T-NTF-P2-04 | `GET  /v1/admin/templates/{id}/history` | pending | T-NTF-P2-03 | notification.admin | notification.admin | — | — |
      (`admin` / `support_agent` role; paginated).
| T-NTF-P2-05 | `POST /v1/admin/templates` and `PATCH /v1/admin/templates/{id}` | pending | T-NTF-P2-04 | notification.admin | notification.admin | — | — |
      accept `template_type` and `body_structured` payloads
      (backwards compatible with the existing `body` payload).
| T-NTF-P2-06 | All admin POSTs carry HMAC + `Idempotency-Key`. | pending | T-NTF-P2-05 | notification.admin | notification.admin | — | — |

## Phase 3 — Outbound WhatsApp lifecycle


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P3-01 | `POST /v1/templates/submit` to the gateway. | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P3-02 | `GET  /v1/templates/{id}/status` to the gateway. | pending | T-NTF-P3-01 | notification.admin | notification.admin | — | — |
| T-NTF-P3-03 | `DELETE /v1/templates/{id}` to the gateway. | pending | T-NTF-P3-02 | notification.admin | notification.admin | — | — |
| T-NTF-P3-04 | Plumb the response back into `templates.provider_template_*` | pending | T-NTF-P3-03 | notification.admin | notification.admin | — | — |
      + write the `template_history` snapshot.

## Phase 4 — Inbound WhatsApp events


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P4-01 | Consume `comms.whatsapp.template_status_update.v1`. | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P4-02 | Consume `comms.whatsapp.delivered.v1`. | pending | T-NTF-P4-01 | notification.admin | notification.admin | — | — |
| T-NTF-P4-03 | Consume `comms.whatsapp.read.v1`. | pending | T-NTF-P4-02 | notification.admin | notification.admin | — | — |
| T-NTF-P4-04 | Consume `comms.whatsapp.failed.v1`. | pending | T-NTF-P4-03 | notification.admin | notification.admin | — | — |
| T-NTF-P4-05 | All consumers are idempotent on `event_id`. | pending | T-NTF-P4-04 | notification.admin | notification.admin | — | — |
| T-NTF-P4-06 | All consumers write to the outbox in the same DB | pending | T-NTF-P4-05 | notification.admin | notification.admin | — | — |
      transaction as the state change.

## Phase 5 — Render pipeline


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P5-01 | Extend template renderer to support `template_type='whatsapp_structured'`. | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P5-02 | Substitute `whatsapp_variables["{index}"]` into | pending | T-NTF-P5-01 | notification.admin | notification.admin | — | — |
      `body_structured.variables[].index` matches; substitute
      remaining `{{key}}` patterns via the same Handlebars
      compiler.
| T-NTF-P5-03 | Render header / body / footer / buttons using the | pending | T-NTF-P5-02 | notification.admin | notification.admin | — | — |
      variable-substituted strings.
| T-NTF-P5-04 | Validate `required_variables[]` against | pending | T-NTF-P5-03 | notification.admin | notification.admin | — | — |
      `body_structured.variables[]` at publish time (admin).

## Phase 6 — Channel-selection + 24h window


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P6-01 | Extend channel priority config default to | pending | — | notification.admin | notification.admin | — | — |
      `["push", "sms", "email", "in_app", "whatsapp"]`.
| T-NTF-P6-02 | Implement `notification.whatsapp.template_24h_window_enforced` flag. | pending | T-NTF-P6-01 | notification.admin | notification.admin | — | — |
| T-NTF-P6-03 | Implement `notification.whatsapp.approval_required` flag. | pending | T-NTF-P6-02 | notification.whatsapp.approval_required | notification.whatsapp.approval_required | — | — |
| T-NTF-P6-04 | Implement WhatsApp STOP / template-scoped opt-out via | pending | T-NTF-P6-03 | notification.admin | notification.admin | — | — |
      `notification.preferences (channel='whatsapp', category=<X>)`.

## Phase 7 — Right-to-erasure interplay


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P7-01 | Update `POST /v1/admin/erasure/{user_id}` to NULL | pending | — | notification.admin | notification.admin | — | — |
      `rendered_subject_encrypted`, `rendered_body_encrypted`,
      and `user_id` on every `deliveries` row (without touching
      `template_history`).
| T-NTF-P7-02 | Add audit event `audit.notification.erasure.v1` with | pending | T-NTF-P7-01 | notification.admin | notification.admin | — | — |
      `erasure_id`, `actor_sub`, `user_id_hash`,
      `rows_affected`, `template_history_rows_affected=0`.

## Phase 8 — Observability


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P8-01 | RED metrics per route, including the new admin endpoints. | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P8-02 | Business metrics: | pending | T-NTF-P8-01 | notification.admin | notification.admin | — | — |
      - `notification_templates_published_total{channel, locale, status}`
      - `notification_template_history_size{channel, locale}`
      - `notification_whatsapp_template_approval_seconds{provider,status}`
        histogram (submit → approved)
      - `notification_whatsapp_24h_window_violations_total`
| T-NTF-P8-03 | Structured log: every `template_history` insert carries | pending | T-NTF-P8-02 | notification.admin | notification.admin | — | — |
      `template_id`, `revision_no`, `version`, `channel`, `locale`,
      `published_by`, `approved_by`, `diff_summary_hash`,
      `correlation_id`.

## Phase 9 — Tests


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|

### Unit
| T-NTF-P9-01 | Handlebars rendering (existing). | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P9-02 | WhatsApp structured rendering — every variable position | pending | T-NTF-P9-01 | notification.admin | notification.admin | — | — |
      substituted exactly once.
| T-NTF-P9-03 | Locale fallback (`requested → user-profile → default`). | pending | T-NTF-P9-02 | notification.admin | notification.admin | — | — |
| T-NTF-P9-04 | 24h window enforcement (mock clock + boundary tests). | pending | T-NTF-P9-03 | notification.admin | notification.admin | — | — |
| T-NTF-P9-05 | Provider-template status state machine | pending | T-NTF-P9-04 | notification.admin | notification.admin | — | — |
      (`draft → submitted → approved | rejected → paused`).


### Integration (Testcontainers)
| T-NTF-P9-06 | Kafka in / comms-gateway out — WhatsApp happy path. | pending | T-NTF-P9-05 | notification.admin | notification.admin | — | — |
| T-NTF-P9-07 | `comms.whatsapp.template_status_update.v1` → new | pending | T-NTF-P9-06 | notification.admin | notification.admin | — | — |
      `template_history` row written.
| T-NTF-P9-08 | Right-to-erasure preserves `template_history`. | pending | T-NTF-P9-07 | notification.admin | notification.admin | — | — |


### Contract (pact)
| T-NTF-P9-09 | Outbound: `POST /v1/templates/submit` request shape matches | pending | T-NTF-P9-08 | notification.admin | notification.admin | — | — |
      the gateway's INTEGRATION.md.


### E2E
| T-NTF-P9-10 | Admin posts a structured template → submits → provider | pending | T-NTF-P9-09 | notification.admin | notification.admin | — | — |
      webhook → snapshot exists → a delivery uses it → the
      rendered output matches the expected `RENDERING_DEMO.md`.

## Phase 10 — Backfill (production, once)


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P10-01 | One-time `notification-ops` job: iterate every active | pending | — | notification.admin | notification.admin | — | — |
      `templates` row of v1.1 interest (whatsapp + email as
      priority) and write a `template_history` snapshot for
      the current `version`. Idempotent.
| T-NTF-P10-02 | Acceptance: `SELECT count(*) FROM notification.template_history` | pending | T-NTF-P10-01 | notification.admin | notification.admin | — | — |
      increases by exactly the number of active templates (modulo
      retries — confirmed by gap report).

## Phase 11 — Deployment


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P11-01 | Run the migration as a Kubernetes Job. | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P11-02 | Roll the service in 2 stages (canary → full) per | pending | T-NTF-P11-01 | notification.admin | notification.admin | — | — |
      `DEPLOYMENT_ARCHITECTURE.md`.
| T-NTF-P11-03 | Verify `notification.whatsapp.enabled` config remains | pending | T-NTF-P11-02 | notification.whatsapp.enabled | notification.whatsapp.enabled | — | — |
      `false` for non-WhatsApp regions / until the regional
      rollout completes.
| T-NTF-P11-04 | Communicate the new admin endpoints to `admin-service` | pending | T-NTF-P11-03 | notification.admin | notification.admin | — | — |
      and ``admin-service` (support module)`.


## Acceptance criteria (release-blocking)

1. Every CHECK constraint in `notification` schema v1.1 is
   applied and verified.
2. The append-only trigger blocks UPDATE/DELETE on
   `template_history` with the documented error.
3. The 24 sample templates in
   [`seeds/templates.v1.json`](./seeds/templates.v1.json)
   load into dev/staging and are renderable in both `en` and
   `ar` for all five channels.
4. The end-to-end WhatsApp send (admin → provider webhook →
   snapshot → delivery → read webhook) is green in staging
   with one real provider cred and one mock cred.
5. The audit chain (`templates.id → templates.version →
   template_history.id → deliveries.template_version_snapshot_id`)
   reconstructs for any historical delivery in the last
   `retention` window.
6. Right-to-erasure preserves `template_history` rows
   while redacting `deliveries` body and `user_id`.

### Phase 7.7 — In-App Chat (cross-cutting)

This service participates in Phase 7.7 (in-app chat kernel added 2026-08-12)
as the **offline push fallback** for chat messages. Single source of truth:
[`services/chat-service/PLAN.md`](../chat-service/PLAN.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P77-01 | Consume `chat.message.offline_delivery_required.v1` from `chat-service`; look up the recipient's notification preferences (channel: push / SMS / email), render a notification, and send per [`INTEGRATION.md`](./INTEGRATION.md) §2 | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P77-02 | Add chat-specific templates: `chat.message.push.v1` (push), `chat.message.sms.v1` (SMS), `chat.message.email.v1` (email) — registered in `notification.templates` with `template_version_snapshot_id` audit binding per [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) | pending | T-NTF-P77-01 | notification.admin | notification.admin | — | — |
| T-NTF-P77-03 | Idempotency-key namespace `chat:msg:{message_id}:notif:{channel}` per [`INTEGRATION.md`](./INTEGRATION.md) §4.1 (extend the existing idempotency namespace pattern) | pending | T-NTF-P77-01 | notification.admin | notification.admin | — | — |
| T-NTF-P77-04 | Consume `chat.thread.created.v1` to send an **in-app banner** notification to participants (one-shot per thread creation); respects notification preferences and quiet hours | pending | T-NTF-P77-01 | notification.admin | notification.admin | — | — |
| T-NTF-P77-05 | DLQ + retry for offline push per [`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md) — chat-service is **CRITICAL** (T1) per [`architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md); offline push is **DEGRADABLE** (T2) — degraded behavior: message queued in DB until recipient reconnects | pending | T-NTF-P77-01 | notification.admin | notification.admin | — | no |

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements (BR--028 … BR--036)
- [`SRS.md`](./SRS.md) — functional + non-functional requirements (FR--040 … FR--054)
- [`ERD.md`](./ERD.md) — data model + the canonical v1.1 migration snippet (12)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows
- [`TECH.md`](./TECH.md) — technology profile
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — WhatsApp structured templates
- [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) — `notification.template_history` audit
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — delivery audit chain
- [`seeds/templates.v1.json`](./seeds/templates.v1.json) — JSON seed of 24 templates × 5 channels × 2 locales
- [`seeds/RENDERING_DEMO.md`](./seeds/RENDERING_DEMO.md) — Mermaid rendering demo

### Platform-wide

- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR
- [`INTEGRATION.md` §2](./INTEGRATION.md#2-outbound-apis) — plug-in provider contract (provider ACL now absorbed into this service)
- [`../../README.md`](../../README.md) — services overview
- [`../../../main.md`](../../../main.md) — top-level platform specification

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P70-01 | Implement Phase 7.0 hooks per [MASTER_PLAN.md](../../MASTER_PLAN.md) Phase 7 table for this service | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P70-02 | Wire Kafka signal adapter → Conductor signal per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-NTF-P70-01 | notification.admin | notification.admin | — | — |
| T-NTF-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-NTF-P70-02 | notification.admin | notification.admin | — | — |

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P75-01 | Implement Phase 7.5 deal state machine hooks per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P75-02 | Wire TTL-driven timer transitions via Conductor worker (per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 3.2) | pending | T-NTF-P75-01 | notification.admin | notification.admin | — | — |

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-NTF-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Worker — notification_service_grant_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-02 | Register Conductor worker for `wf.phase7.reward_reversal.v1` — Worker — notification_service_reversal_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-03 | Register Conductor worker for `wf.refund.standard.v1` — Worker — notification_service_refund_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-04 | Register Conductor worker for `wf.refund.partial.v1` — Worker — notification_service_refund_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-05 | Register Conductor worker for `wf.refund.food_reject.v1` — Worker — notification_service_refund_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-06 | Register Conductor worker for `wf.refund.cancellation.v1` — Worker — notification_service_refund_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-07 | Register Conductor worker for `wf.refund.dispute.v1` — Worker — notification_service_refund_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-08 | Register Conductor worker for `wf.refund.cod_failed.v1` — Worker — notification_service_refund_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-09 | Register Conductor worker for `wf.onboarding.driver.v1` — Worker — approval_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-10 | Register Conductor worker for `wf.onboarding.courier.v1` — Worker — approval_template | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-11 | Register Conductor worker for `wf.phase75.deal_rider.v1` — 5 deal templates | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-12 | Register Conductor worker for `wf.phase75.deal_driver.v1` — 5 deal templates | pending | — | notification.admin | notification.admin | — | — |
| T-NTF-P76-13 | Register Conductor worker for `wf.phase75.deal_food.v1` — 5 deal templates | pending | — | notification.admin | notification.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 7** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (provider API keys, templates, rate limits) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`customer-service`](../customer-service/README.md) (recipient profile for SMS / push / email lookup) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-NTF-NN (Phase 1-10) | per task | per task | per task | per task |
| T-NTF-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-NTF-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-NTF-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 4 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-NTF-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-NTF-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-NTF-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` | platform.admin | done | 2026-08-17 |
| T-NTF-P90-04 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test across **3 IT classes** (`NotificationServiceApplicationTests`, `NotificationCommandConsumerIT`, `AdminTemplatePublishIT`) | platform.admin | done | 2026-08-17 |
| T-NTF-P90-05 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-NTF-P90-06 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-NTF-P90-07 | `TestNotificationServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew build -x test` → BUILD SUCCESSFUL. `./gradlew test` → 26 tests run, 0 skipped, **23 unit tests pass cleanly across 8 suites** (`ApiExceptionHandlerTest` 3/3, `IdempotencyServiceTest` 3/3, `NotificationSeederTest` 2/2, `NotificationSendServiceTest` 2/2, `PartitionMaintenanceJobTest` 3/3, `HandlebarsRendererTest` 4/4, `TemplateRendererTest` 2/2, `WhatsappStructuredRendererTest` 4/4). 3 IT-class failures are pre-existing `org.yaml.snakeyaml.constructor.DuplicateKeyException` on `spring:` block (`application.yml` has two top-level `spring:` keys since the v1 baseline — confirmed via `git show 0bab68a:apps/notification-service/src/main/resources/application.yml`), identical to the pre-Phase-A state. No Phase A regression; the YAML duplicate is a pre-existing structural defect in this service's `application.yml` to be addressed in a follow-up YAML cleanup PR.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent / InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler + SecurityConfiguration + BaseEntity migration), or Phase D (partition cron + idempotency service + inbox listener). Those PRs follow in their own session once Phase 0/A is fully merged across all 14 Kotlin services.
