# communication-gateway-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md`. Webhook
endpoints are publicly reachable but verify provider signatures.

### 1.1 `POST /v1/sends`

- **Purpose**: Send a message via SMS, email, push, or WhatsApp.
  For WhatsApp the body is a pre-approved structured template
  with parameters; for the other three channels it is plain
  body text (email also taking `subject`).
- **Auth**: Bearer JWT + role `service` (only
  `notification-service`, `identity-service`,
  `payment-service`, `ride-safety-service`).
- **Idempotency**: `Idempotency-Key` required.
- **Request** (SMS / push):
  ```json
  {
    "channel": "sms",
    "recipient": "+966551234567",
    "body": "Your OTP is 123456.",
    "subject": null,
    "priority": "normal",
    "metadata": {
      "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC"
    }
  }
  ```
- **Request** (email):
  ```json
  {
    "channel": "email",
    "recipient": "customer@example.com",
    "subject": "Your trip is complete",
    "body": "Hi Aisha, your trip ended at 10:42. Total: 42.50 SAR.",
    "priority": "normal",
    "metadata": { "user_id": "…", "notification_id": "…" }
  }
  ```
- **Request** (WhatsApp, structured template):
  ```json
  {
    "channel": "whatsapp",
    "recipient": "+966551234567",
    "priority": "normal",
    "whatsapp_template_name": "trip_completed_v3",
    "whatsapp_template_language": "ar_SA",
    "whatsapp_variables": {
      "1": "حي العليا، الرياض",
      "2": "10:42",
      "3": "42.50",
      "4": "SAR",
      "5": "Uber KSA"
    },
    "whatsapp_header_media_id": null,
    "metadata": {
      "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
      "template_history_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PJ"
    }
  }
  ```
  `whatsapp_template_name` is the provider's pre-approved
  template id (or our logical name, resolved to the id by
  the gateway against the `templates.provider_template_id`
  mirror in `notification.templates`). `whatsapp_variables`
  is a JSON object keyed by positional index `"1"`, `"2"`,
  … — the same indices declared in
  `notification.templates.body_structured.variables[]`. See
  [`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md)
  §4 for the variable-binding contract.
- **Response (202)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "sms",
    "provider": "twilio",
    "status": "sent",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
  For WhatsApp an additional `accepted: true` may appear in
  the response body when the provider's first async ack
  arrives (mirrored on `sends.status='accepted'`).
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED` / 403 `FORBIDDEN`
  - 422 `OPTED_OUT` / `RATE_LIMITED` / `IDEMPOTENCY_KEY_REUSED`
    / `TEMPLATE_NOT_APPROVED` / `WINDOW_EXPIRED` /
    `MEDIA_UPLOAD_FAILED`
  - 429 `RATE_LIMITED` (with `Retry-After`)
  - 503 `CIRCUIT_OPEN` / `PROVIDER_UNAVAILABLE`
  - 504 `DEPENDENCY_TIMEOUT`

### 1.2 `GET /v1/sends/{gateway_request_id}`

- **Purpose**: Read the current disposition of a send.
- **Auth**: Bearer JWT + role `service` or `admin` or
  `support_agent`.
- **Response (200)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "sms",
    "provider": "twilio",
    "status": "delivered",
    "attempt": 1,
    "provider_message_id": "SM1234567890abcdef",
    "created_at": "2026-07-29T10:42:11.183Z",
    "sent_at": "2026-07-29T10:42:11.500Z",
    "delivered_at": "2026-07-29T10:42:12.100Z"
  }
  ```
- **Errors**: 404 `NOT_FOUND` / 403 `FORBIDDEN`.

### 1.3 `POST /v1/otp`

- **Purpose**: Deliver an OTP via SMS (or email fallback)
  with stricter rate limits.
- **Auth**: Bearer JWT + role `service` (only
  `identity-service`, `payment-service`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "phone": "+966551234567",
    "code": "123456",
    "ttl_seconds": 300,
    "purpose": "phone_verification"
  }
  ```
  `purpose ∈ {phone_verification, payment_3ds, login, account_recovery}`.
- **Response (202)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PD",
    "channel": "sms",
    "provider": "twilio",
    "status": "sent",
    "occured_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 422 `OPTED_OUT` / 429 `RATE_LIMITED` (per
  phone or per IP exceeded) / 503 `CIRCUIT_OPEN`.

### 1.4 `POST /v1/webhooks/sms/{provider}`

- **Purpose**: Ingest an SMS provider webhook (delivery
  receipt, STOP opt-out, bounce).
- **Auth**: HMAC-SHA256 signature in `X-Twilio-Signature` (or
  equivalent); verified per provider.
- **Idempotency**: `webhook_event_id` (provider's event id);
  duplicate webhooks are no-ops.
- **Request**: provider-specific JSON or form-encoded payload.
- **Response (200)**: `{"received": true}`.
- **Errors**: 401 `SIGNATURE_INVALID` / 400 `VALIDATION_FAILED`.

### 1.5 `POST /v1/webhooks/email/{provider}`

Same as 1.4 for email (delivery, bounce, complaint,
unsubscribe, opened, clicked).

### 1.6 `POST /v1/webhooks/push/{provider}`

Same as 1.4 for push (delivery, error — APNs/FCM do not
have opt-out webhooks; opt-out is the user uninstalling the
app, detected by an invalid token at next send).

### 1.6.a `POST /v1/webhooks/whatsapp/{provider}`

- **Purpose**: Ingest a WhatsApp provider webhook. WhatsApp
  providers emit a richer event set than SMS/email/push;
  see the event-type list below.
- **Auth**: HMAC-SHA256 (or vendor-specific) signature in
  the header configured on `providers.webhook_signature_header`
  for this provider. The gateway verifies the signature
  BEFORE parsing the payload.
- **Idempotency**: `webhook_event_id` (provider's event id);
  duplicate webhooks are no-ops.
- **Recognised event types**:
  - `accepted` — Meta Cloud accepted the message into its
    pipeline. Mirrors `sends.status='accepted'` and emits
    `comms.whatsapp.accepted.v1`.
  - `sent` — the message left Meta Cloud (analogous to
    `comms.sms.sent.v1`).
  - `delivered` — the message hit the recipient's device.
    Mirrors `sends.status='delivered'` and emits
    `comms.whatsapp.delivered.v1`.
  - `read` — the recipient opened the message. Whitelist by
    user locale (read-receipts are opt-in per WhatsApp
    Business policy). Mirrors `sends.status='read'` and
    emits `comms.whatsapp.read.v1`.
  - `failed` — provider-side failure with `failure_reason`.
    Mirrors `sends.status='failed'` and emits
    `comms.whatsapp.failed.v1`.
  - `template_status_update` — the provider reports a
    approval status change on one of our submitted templates.
    Resolves to the matching `notification.templates` row
    via `provider_template_id` and forwards a
    `comms.whatsapp.template_status_update.v1` event for
    `notification-service` to consume.
  - `optout` — recipient opted out (template-scoped or
    channel-wide). Writes to `comms_gateway.optouts` and
    emits `comms.optout.recorded.v1`.
- **Request**: provider-specific JSON payload. The gateway
  normalises the payload into the canonical
  `webhook_events` schema (channel='whatsapp', event_type,
  provider_message_id, payload, signature_verified=true,
  correlation_id=…). The payload is stored opaquely in
  `webhook_events.payload` for forensics.
- **Response (200)**: `{"received": true}`.
- **Errors**: 401 `SIGNATURE_INVALID` / 400 `VALIDATION_FAILED`
  / 422 `UNKNOWN_EVENT_TYPE` (forwarded to DLQ; webhook
  is replayable by an admin once a new event type is
  registered).

### 1.6.b `POST /v1/templates/submit`

- **Purpose**: Submit a WhatsApp template to the provider
  for approval. Called by `notification-service` after the
  admin user posts a new structured template.
- **Auth**: Bearer JWT + role `service` (only
  `notification-service`).
- **Idempotency**: `Idempotency-Key` required (idempotent on
  `(template_id, locale)` — re-submitting returns the
  existing submission's `provider_template_id`).
- **Request**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "locale": "ar",
    "provider_template_language": "ar_SA",
    "category": "transactional",
    "components": { /* mirrors notification.templates.body_structured */ }
  }
  ```
- **Response (202)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "locale": "ar",
    "provider": "meta-cloud-whatsapp",
    "provider_template_id": null,
    "provider_template_status": "submitted",
    "submitted_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 1.6.c `GET /v1/templates/{id}/status`

- **Purpose**: Poll the provider's template approval status
  (used both by the notification-service reconciliation
  loop and by admin UIs that want a synchronous status).
- **Auth**: Bearer JWT + role `service` or `admin`.
- **Response (200)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "locale": "ar",
    "provider": "meta-cloud-whatsapp",
    "provider_template_id": "tpl_ABC123xyz",
    "provider_template_status": "approved",
    "provider_template_language": "ar_SA",
    "provider_template_approved_at": "2026-07-29T10:50:11.183Z",
    "reject_reason": null
  }
  ```

### 1.6.d `DELETE /v1/templates/{id}`

- **Purpose**: Delete a previously submitted (and possibly
  approved) WhatsApp template at the provider. Used by admin
  when retiring a notification template version.
- **Auth**: Bearer JWT + role `admin`; HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  { "locale": "ar", "reason": "superseded by v4" }
  ```
- **Response (200)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "locale": "ar",
    "provider": "meta-cloud-whatsapp",
    "provider_template_id": "tpl_ABC123xyz",
    "deleted_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 1.6.e `POST /v1/admin/providers`

- **Purpose**: Onboard a new provider (zero-schema-change).
  Registers a row in `comms_gateway.providers` plus the
  capability rows in `comms_gateway.provider_capabilities`.
  After this returns 201 the platform can route sends to
  the new provider without a code change.
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`;
  HMAC-SHA256; mTLS.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "name": "meta-cloud-whatsapp",
    "display_name": "Meta Cloud API (WhatsApp)",
    "channel": "whatsapp",
    "provider_kind": "whatsapp_direct",
    "vault_credential_path": "kv/platform/prod/comms-gateway/whatsapp/meta-cloud",
    "webhook_signature_header": "X-Hub-Signature-256",
    "webhook_signature_algorithm": "hmac_sha256",
    "capability_profile": "whatsapp_template_v1",
    "regional_routing": { "966": 1, "971": 1 },
    "capabilities": [
      { "capability": "send_template",                 "enabled": true },
      { "capability": "send_freeform_within_window",   "enabled": false },
      { "capability": "media_upload",                  "enabled": true },
      { "capability": "template_submit",               "enabled": true },
      { "capability": "template_status",               "enabled": true },
      { "capability": "webhook_signed_hmac_sha256",    "enabled": true },
      { "capability": "health_metrics",                "enabled": true },
      { "capability": "regional_routing",              "enabled": true },
      { "capability": "optout_keyword_stop",           "enabled": true },
      { "capability": "template_deletion",             "enabled": true },
      { "capability": "language_negotiation",          "enabled": true }
    ]
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PX",
    "name": "meta-cloud-whatsapp",
    "channel": "whatsapp",
    "provider_kind": "whatsapp_direct",
    "status": "active",
    "capabilities_registered": 11,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 400 / 401 / 403 / 409 `PROVIDER_ALREADY_EXISTS` /
  422 `UNKNOWN_CAPABILITY` / 503.

> Operators may also use `POST /v1/admin/providers/capabilities`
> (admin; HMAC) to add / disable individual capabilities on
> an existing provider without re-registering. See
> [`WHATSAPP_PROVIDER_CONTRACT.md`](./WHATSAPP_PROVIDER_CONTRACT.md)
> §6 for the full onboarding runbook.

### 1.6.f `GET /v1/admin/providers/{name}/capabilities`

- **Purpose**: List the capabilities currently asserted for a
  given provider. Powers the "what does provider X support?"
  debug view and the WhatsApp-template approval-recommendation
  engine ("only pick providers that support `template_status`").
- **Auth**: Bearer JWT + role `admin`, `notification_ops`,
  or `platform_engineer`.
- **Response (200)**:
  ```json
  {
    "provider_name": "meta-cloud-whatsapp",
    "channel": "whatsapp",
    "capabilities": [
      { "capability": "send_template",                 "enabled": true, "parameters": {} },
      { "capability": "send_freeform_within_window",   "enabled": false, "parameters": {} },
      { "capability": "media_upload",                  "enabled": true, "parameters": { "max_bytes": 16777216 } }
    ]
  }
  ```

### 1.7 `POST /v1/admin/providers/rotate`

- **Purpose**: Rotate a provider's credentials (the new
  credentials are written to Vault; the service reloads).
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`;
  HMAC-SHA256 signed body; mTLS.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "provider_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE",
    "new_vault_path": "kv/platform/prod/comms-gateway/sms/twilio/v2",
    "reason": "Quarterly rotation",
    "rollout_strategy": "blue_green"
  }
  ```
- **Response (200)**:
  ```json
  {
    "provider_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE",
    "old_path": "kv/platform/prod/comms-gateway/sms/twilio/v1",
    "new_path": "kv/platform/prod/comms-gateway/sms/twilio/v2",
    "rolled_out_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 401 / 403 / 409 `SIGNATURE_INVALID` /
  422 `IDEMPOTENCY_KEY_REUSED`.

### 1.8 `GET /v1/admin/sends`

- **Purpose**: List recent sends (for ops and support).
- **Auth**: Bearer JWT + role `admin` or `support_agent`.
- **Request (query)**: `?channel=sms&status=failed&from=...&to=...&user_id=...&notification_id=...`
- **Response (200)**: paginated list (with `recipient`
  redacted to `recipient_hash` unless `support_agent` role).

### 1.9 `GET /v1/admin/optouts`

- **Purpose**: List active opt-outs.
- **Auth**: Bearer JWT + role `admin` or `support_agent`.
- **Request (query)**: `?channel=sms&reason=STOP&recipient_hash=...`
- **Response (200)**: paginated list (with `recipient`
  redacted to `recipient_hash` unless `support_agent` role).

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Twilio (or equivalent) | POST | `/Messages.json` | send SMS | 2s | 3 (exponential) | yes (per provider) |
| SendGrid (or equivalent) | POST | `/v3/mail/send` | send email | 3s | 3 | yes |
| APNs | POST | `/3/device/{device_token}` | send push | 1s | 2 | yes |
| FCM | POST | `/v1/projects/{project}/messages:send` | send push | 1s | 2 | yes |
| `configuration-service` | GET | `/v1/config/comms-gateway` | read provider config, rate limits | 500ms | 3 | yes |
| `feature-flag-service` | GET | `/v1/flags/comms-gateway.mock_provider` | toggle mock provider | 300ms | 1 | yes |

All outbound calls carry `X-Correlation-Id` and `traceparent`.

## 3. Produced Events

### 3.1 `comms.sms.sent.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.sms.sent`.
- **Trigger**: successful SMS provider ack (or webhook
  delivery receipt).
- **Partition key**: `user_id` (or `recipient_hash` if
  `user_id` is null).
- **Schema (data)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "sms",
    "provider": "twilio",
    "provider_message_id": "SM1234567890abcdef",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "status": "delivered",
    "occurred_at": "2026-07-29T10:42:12.100Z",
    "latency_ms": 917,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `comms.sms.sent.dlq`.
- **Consumers**: `notification-service`, `audit-service`,
  `analytics-service`.

### 3.2 `comms.email.sent.v1`

Same as 3.1 with `channel: "email"`, `provider: "sendgrid"`.

### 3.3 `comms.push.sent.v1`

Same as 3.1 with `channel: "push"`, `provider: "apns"` or
`"fcm"`.

### 3.4 `comms.send.failed.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.send.failed`.
- **Trigger**: persistent failure (retries exhausted).
- **Partition key**: `user_id` (or `recipient_hash`).
- **Schema (data)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "sms",
    "provider": "twilio",
    "status": "failed",
    "attempt": 3,
    "failure_reason": "PROVIDER_5XX",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.4.a `comms.whatsapp.accepted.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.whatsapp.accepted`.
- **Trigger**: Meta Cloud (or other WhatsApp provider)
  accepted the message into its pipeline but has not yet
  confirmed delivery. Mirrors `sends.status='accepted'`.
- **Partition key**: `user_id` (or `recipient_hash`).
- **Schema (data)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "whatsapp",
    "provider": "meta-cloud-whatsapp",
    "whatsapp_template_id": "tpl_ABC123xyz",
    "whatsapp_template_language": "ar_SA",
    "whatsapp_template_status": "accepted",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `notification-service`, `analytics-service`,
  `audit-service`.

### 3.4.b `comms.whatsapp.delivered.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.whatsapp.delivered`.
- **Trigger**: Meta Cloud confirmed the message hit the
  recipient's device. Mirrors `sends.status='delivered'`.
- **Partition key**: `user_id` (or `recipient_hash`).
- **Schema (data)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "whatsapp",
    "provider": "meta-cloud-whatsapp",
    "whatsapp_template_id": "tpl_ABC123xyz",
    "delivered_at": "2026-07-29T10:42:12.100Z",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `notification-service`, `analytics-service`,
  `audit-service`.

### 3.4.c `comms.whatsapp.read.v1` (WhatsApp only)

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.whatsapp.read`.
- **Trigger**: recipient opened the message (provider posts
  `read` webhook). Whitelisted only for users in regions/locales
  where WhatsApp Business read-receipts are enabled.
- **Partition key**: `user_id` (or `recipient_hash`).
- **Schema (data)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "whatsapp",
    "provider": "meta-cloud-whatsapp",
    "whatsapp_template_id": "tpl_ABC123xyz",
    "read_at": "2026-07-29T10:43:00.000Z",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `notification-service`, `analytics-service`.

### 3.4.d `comms.whatsapp.failed.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.whatsapp.failed`.
- **Trigger**: persistent failure on a WhatsApp send
  (retries exhausted). Mirrors `sends.status='failed'`.
- **Partition key**: `user_id` (or `recipient_hash`).
- **Schema (data)**:
  ```json
  {
    "gateway_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "channel": "whatsapp",
    "provider": "meta-cloud-whatsapp",
    "whatsapp_template_id": "tpl_ABC123xyz",
    "attempt": 3,
    "failure_reason": "TEMPLATE_PAUSED",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `notification-service`, `analytics-service`,
  `audit-service`. Failure-reason enum covers
  `TEMPLATE_NOT_APPROVED`, `TEMPLATE_PAUSED`,
  `WINDOW_EXPIRED`, `MEDIA_UPLOAD_FAILED`, `PROVIDER_5XX`,
  `RATE_LIMITED`, `OPTED_OUT`, `INVALID_RECIPIENT`.

### 3.4.e `comms.whatsapp.template_status_update.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.whatsapp.template_status_update`.
- **Trigger**: provider reports a status change for one of
  our submitted templates (typically via the
  `template_status_update` webhook).
- **Partition key**: `provider_template_id` (so all updates
  for the same template land on the same partition).
- **Schema (data)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "trip.completed",
    "channel": "whatsapp",
    "locale": "ar",
    "provider": "meta-cloud-whatsapp",
    "provider_template_id": "tpl_ABC123xyz",
    "provider_template_language": "ar_SA",
    "provider_template_status": "approved",
    "previous_status": "submitted",
    "approved_at": "2026-07-29T10:50:11.183Z",
    "reject_reason": null,
    "occurred_at": "2026-07-29T10:50:11.183Z"
  }
  ```
- **Consumers**: `notification-service` (writes a new
  `template_history` snapshot when `approved`), `analytics-service`.

### 3.5 `comms.provider.rotated.v1`

- **Producer**: `communication-gateway-service`.
- **Topic**: `comms.provider.rotated`.
- **Trigger**: every provider key rotation.
- **Schema (data)**:
  ```json
  {
    "provider_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE",
    "channel": "sms",
    "provider": "twilio",
    "old_path": "kv/platform/prod/comms-gateway/sms/twilio/v1",
    "new_path": "kv/platform/prod/comms-gateway/sms/twilio/v2",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6YDX",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `audit-service` (high-severity).

## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.configuration.updated`.
- **Reason**: provider selection, rate limits, regional
  routing changed.
- **Handler**: reload config (idempotent; config hash
  compared before swap).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `feature_flag.updated.v1`

- **Producer**: `feature-flag-service`.
- **Topic**: `feature_flag.feature_flag.updated`.
- **Reason**: `comms-gateway.mock_provider` flag changed.
- **Handler**: re-evaluate the flag; swap the active
  provider adapter if needed.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `notification.requested.v1`

- **Producer**: `notification-service`.
- **Reason**: A notification needs to be sent.
- **Handler**: Dispatch to provider.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `notification.retry_requested.v1`

- **Producer**: `internal`.
- **Reason**: Retry a failed send.
- **Handler**: Re-dispatch.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts** (defaults):
  - SMS: 2s. Email: 3s. Push: 1s.
  - `configuration-service`: 500ms.
- **Retries** (exponential backoff with jitter, max 3):
  - Provider calls: 3 attempts (1s, 4s, 16s).
  - Read calls: 1 retry.
- **Circuit breakers** per provider: open on ≥ 3 consecutive
  5xx/timeout in 30s; half-open after 30s; close on 2
  successes.
- **Provider fallback**: when the primary's circuit opens,
  the next send routes to the fallback. If the fallback's
  circuit is also open, return 503 `CIRCUIT_OPEN`.
- **Bulkheads**: separate connection pools per provider.
- **Outbox**: `comms_gateway.outbox` table; poller publishes
  at-least-once; rows purged 24h after `published_at`.
- **Inbox**: `comms_gateway.inbox` table; dedupe on
  `event_id`.
- **DLQ**: every topic has a paired `<topic>.dlq`; 30-day
  retention.
- **Reconciliation**: a daily job scans for `sends` rows
  in `sending` state older than 5 minutes and marks them
  `failed` with reason `STUCK_SENDING`; this is rare and
  indicates a bug.

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event.
  - The `headers.correlation_id` of every outbox row.
  - The `correlation_id` column of every send row.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments HTTP, Kafka, DB,
  Redis.
- One root span per send; provider call as child span;
  webhook as child span.
- Sample 100% of errors, 10% of successes in production;
  100% in staging.
- The inbound `traceparent` is honored.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`promotion-service`](../promotion-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-safety-service`](../ride-safety-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`support-service`](../support-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` §1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in §1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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

