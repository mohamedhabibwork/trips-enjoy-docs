# notification-service — Implementation Plan

> Mirrors the [`communication-gateway-service/PLAN.md`](../communication-gateway-service/PLAN.md)
> style. This is the implementation tracker for the v1.1
> WhatsApp + template-history extension. Use it as the
> project-management counterpart to the spec in
> [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md),
> [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md),
> [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md),
> [`ERD.md`](./ERD.md) §12 (migration snippet), and
> [`INTEGRATION.md`](./INTEGRATION.md).

## Phase 0 — Pre-requisites

- [ ] Confirm multi-region / multi-language matrix
      (`locale ∈ {en, ar, …}`) and lock the list of locales for
      v1.1.
- [ ] Lock the canonical capability list of WhatsApp providers
      with `communication-gateway-service` (see
      `../communication-gateway-service/WHATSAPP_PROVIDER_CONTRACT.md`).
- [ ] Approve the canonical `template_history.diff_summary`
      JSON schema with `analytics-service` and `audit-service`
      consumers.

## Phase 1 — Schema (v1.1 forward-only migration)

- [ ] Run the migration in [`ERD.md`](./ERD.md) §12
      (`notification` schema v1.1). Verify CHECK-constraint
      extensions and the new `template_history` table + trigger.
- [ ] Verify the append-only trigger blocks UPDATE / DELETE on
      `template_history` (negative test: try `UPDATE
      notification.template_history SET diff_summary = '{}' WHERE id = X`
      and confirm it raises the expected exception).
- [ ] Verify the discriminator CHECK enforces mutual exclusivity
      for `templates.template_type` / `body` / `body_structured`.
- [ ] Verify the WhatsApp delivery conditional CHECK enforces
      `rendered_provider_template_id IS NOT NULL` for
      `channel='whatsapp'`.
- [ ] Indexes pre-created for new columns: confirm the planner
      uses `deliveries_template_history_idx` for the support view.

## Phase 2 — Admin endpoints

- [ ] `POST /v1/admin/templates/{id}/submit-for-approval`
      (`notification-template-admin` role; HMAC).
- [ ] `POST /v1/admin/templates/{id}/approve`
      (called by webhook handler; admin/notification_ops role; HMAC).
- [ ] `POST /v1/admin/templates/{id}/publish`
      (atomic-across-locales; `notification.admin` role; HMAC).
- [ ] `GET  /v1/admin/templates/{id}/history`
      (`admin` / `support_agent` role; paginated).
- [ ] `POST /v1/admin/templates` and `PATCH /v1/admin/templates/{id}`
      accept `template_type` and `body_structured` payloads
      (backwards compatible with the existing `body` payload).
- [ ] All admin POSTs carry HMAC + `Idempotency-Key`.

## Phase 3 — Outbound WhatsApp lifecycle

- [ ] `POST /v1/templates/submit` to the gateway.
- [ ] `GET  /v1/templates/{id}/status` to the gateway.
- [ ] `DELETE /v1/templates/{id}` to the gateway.
- [ ] Plumb the response back into `templates.provider_template_*`
      + write the `template_history` snapshot.

## Phase 4 — Inbound WhatsApp events

- [ ] Consume `comms.whatsapp.template_status_update.v1`.
- [ ] Consume `comms.whatsapp.delivered.v1`.
- [ ] Consume `comms.whatsapp.read.v1`.
- [ ] Consume `comms.whatsapp.failed.v1`.
- [ ] All consumers are idempotent on `event_id`.
- [ ] All consumers write to the outbox in the same DB
      transaction as the state change.

## Phase 5 — Render pipeline

- [ ] Extend template renderer to support `template_type='whatsapp_structured'`.
- [ ] Substitute `whatsapp_variables["{index}"]` into
      `body_structured.variables[].index` matches; substitute
      remaining `{{key}}` patterns via the same Handlebars
      compiler.
- [ ] Render header / body / footer / buttons using the
      variable-substituted strings.
- [ ] Validate `required_variables[]` against
      `body_structured.variables[]` at publish time (admin).

## Phase 6 — Channel-selection + 24h window

- [ ] Extend channel priority config default to
      `["push", "sms", "email", "in_app", "whatsapp"]`.
- [ ] Implement `notification.whatsapp.template_24h_window_enforced` flag.
- [ ] Implement `notification.whatsapp.approval_required` flag.
- [ ] Implement WhatsApp STOP / template-scoped opt-out via
      `notification.preferences (channel='whatsapp', category=<X>)`.

## Phase 7 — Right-to-erasure interplay

- [ ] Update `POST /v1/admin/erasure/{user_id}` to NULL
      `rendered_subject_encrypted`, `rendered_body_encrypted`,
      and `user_id` on every `deliveries` row (without touching
      `template_history`).
- [ ] Add audit event `audit.notification.erasure.v1` with
      `erasure_id`, `actor_sub`, `user_id_hash`,
      `rows_affected`, `template_history_rows_affected=0`.

## Phase 8 — Observability

- [ ] RED metrics per route, including the new admin endpoints.
- [ ] Business metrics:
      - `notification_templates_published_total{channel, locale, status}`
      - `notification_template_history_size{channel, locale}`
      - `notification_whatsapp_template_approval_seconds{provider,status}`
        histogram (submit → approved)
      - `notification_whatsapp_24h_window_violations_total`
- [ ] Structured log: every `template_history` insert carries
      `template_id`, `revision_no`, `version`, `channel`, `locale`,
      `published_by`, `approved_by`, `diff_summary_hash`,
      `correlation_id`.

## Phase 9 — Tests

### Unit
- [ ] Handlebars rendering (existing).
- [ ] WhatsApp structured rendering — every variable position
      substituted exactly once.
- [ ] Locale fallback (`requested → user-profile → default`).
- [ ] 24h window enforcement (mock clock + boundary tests).
- [ ] Provider-template status state machine
      (`draft → submitted → approved | rejected → paused`).

### Integration (Testcontainers)
- [ ] Kafka in / comms-gateway out — WhatsApp happy path.
- [ ] `comms.whatsapp.template_status_update.v1` → new
      `template_history` row written.
- [ ] Right-to-erasure preserves `template_history`.

### Contract (pact)
- [ ] Outbound: `POST /v1/templates/submit` request shape matches
      the gateway's INTEGRATION.md.

### E2E
- [ ] Admin posts a structured template → submits → provider
      webhook → snapshot exists → a delivery uses it → the
      rendered output matches the expected `RENDERING_DEMO.md`.

## Phase 10 — Backfill (production, once)

- [ ] One-time `notification-ops` job: iterate every active
      `templates` row of v1.1 interest (whatsapp + email as
      priority) and write a `template_history` snapshot for
      the current `version`. Idempotent.
- [ ] Acceptance: `SELECT count(*) FROM notification.template_history`
      increases by exactly the number of active templates (modulo
      retries — confirmed by gap report).

## Phase 11 — Deployment

- [ ] Run the migration as a Kubernetes Job.
- [ ] Roll the service in 2 stages (canary → full) per
      `DEPLOYMENT_ARCHITECTURE.md`.
- [ ] Verify `notification.whatsapp.enabled` config remains
      `false` for non-WhatsApp regions / until the regional
      rollout completes.
- [ ] Communicate the new admin endpoints to `admin-service`
      and `support-service`.

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

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements (BR--028 … BR--036)
- [`SRS.md`](./SRS.md) — functional + non-functional requirements (FR--040 … FR--054)
- [`ERD.md`](./ERD.md) — data model + the canonical v1.1 migration snippet (§12)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows
- [`TECH.md`](./TECH.md) — technology profile
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — WhatsApp structured templates
- [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) — `notification.template_history` audit
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — delivery audit chain
- [`seeds/templates.v1.json`](./seeds/templates.v1.json) — JSON seed of 24 templates × 5 channels × 2 locales
- [`seeds/RENDERING_DEMO.md`](./seeds/RENDERING_DEMO.md) — Mermaid rendering demo

### Platform-wide

- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR
- [`../communication-gateway-service/WHATSAPP_PROVIDER_CONTRACT.md`](../communication-gateway-service/WHATSAPP_PROVIDER_CONTRACT.md) — plug-in provider contract
- [`../communication-gateway-service/PLAN.md`](../communication-gateway-service/PLAN.md) — implementation tracker for the gateway side
- [`../../README.md`](../../README.md) — services overview
- [`../../../main.md`](../../../main.md) — top-level platform specification
