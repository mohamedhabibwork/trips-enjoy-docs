# notification-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md`.

### 1.1 `POST /v1/notifications`

- **Purpose**: Send a notification to a user (synchronous
  submission; the actual send is async, but the API returns
  a `notification_id` and an immediate `status`).
- **Auth**: Bearer JWT + role `service` (any internal service
  may submit on behalf of any user).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "data": {
      "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB",
      "fare_minor": 12345,
      "currency": "USD"
    },
    "category": "trip",
    "dedup_key": "trip-completed:01HZX9C5S3B1L7K0P2F8V4T6YDB",
    "locale_hint": "ar",
    "priority": "normal"
  }
  ```
  `priority` is `normal` | `urgent` (bypasses quiet hours and
  dedup). Default `normal`.
- **Response (202)**:
  ```json
  {
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "status": "queued",
    "channel": "push",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED` / 403 `FORBIDDEN`
  - 404 `USER_NOT_FOUND` / `TEMPLATE_NOT_FOUND`
  - 422 `NO_CONTACT` / `TEMPLATE_MISSING` / `IDEMPOTENCY_KEY_REUSED`
  - 503 `CIRCUIT_OPEN` (all channels' circuits open)
  - 504 `DEPENDENCY_TIMEOUT`

### 1.2 `GET /v1/notifications/{id}`

- **Purpose**: Read delivery state.
- **Auth**: Bearer JWT; the `user_id` of the notification must
  match the caller's `sub` (or `admin` / `support_agent`).
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_name": "trip.completed",
    "category": "trip",
    "channel": "push",
    "locale": "ar",
    "status": "delivered",
    "attempt": 1,
    "created_at": "2026-07-29T10:42:11.183Z",
    "sent_at": "2026-07-29T10:42:11.500Z",
    "delivered_at": "2026-07-29T10:42:12.100Z",
    "failure_reason": null,
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB"
  }
  ```
- **Errors**: 403 `FORBIDDEN` / 404 `NOT_FOUND`.

### 1.3 `GET /v1/preferences/{user_id}`

- **Purpose**: Read a user's preferences.
- **Auth**: Bearer JWT; the `user_id` must match the caller's
  `sub` (or `admin`).
- **Response (200)**:
  ```json
  {
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "preferences": [
      { "category": "trip", "channel": "push", "opt_in": true, "quiet_hours": null },
      { "category": "marketing", "channel": "email", "opt_in": false, "quiet_hours": null }
    ],
    "default_locale": "en",
    "default_channel_priority": ["push", "sms", "email", "in_app"]
  }
  ```

### 1.4 `PATCH /v1/preferences/{user_id}`

- **Purpose**: Update a user's preferences.
- **Auth**: Bearer JWT; the `user_id` must match the caller's
  `sub` (or `admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "preferences": [
      { "category": "marketing", "channel": "email", "opt_in": false },
      { "category": "trip", "channel": "push", "opt_in": true, "quiet_hours": { "start": "22:00", "end": "07:00", "timezone": "Asia/Riyadh" } }
    ]
  }
  ```
- **Response (200)**: same as GET.
- **Errors**: 400 / 403 / 422.

### 1.5 `POST /v1/admin/templates`

- **Purpose**: Create a template.
- **Auth**: Bearer JWT + role `admin` or `notification_ops`;
  body HMAC-SHA256 signed.
- **Idempotency**: required.
- **Request** (plain template, `channel ∈ {push, sms, email, in_app}`):
  ```json
  {
    "name": "trip.completed",
    "category": "trip",
    "channel": "push",
    "locale": "en",
    "subject": null,
    "body": "Your trip is complete. Fare: {{fare_minor}} {{currency}}.",
    "required_variables": ["trip_id", "fare_minor", "currency"],
    "metadata": { "deeplink": "uber://{{#if (eq service 'trip')}}trip{{/if}}{{#if (eq service 'food_order')}}order{{/if}}/{{request_id}}" }
  }
  ```
- **Request** (WhatsApp structured template, `channel='whatsapp'`):
  ```json
  {
    "name": "trip.completed",
    "category": "trip",
    "channel": "whatsapp",
    "locale": "ar",
    "template_type": "whatsapp_structured",
    "subject": null,
    "body": null,
    "body_structured": {
      "header": { "type": "text", "text": "تم إكمال رحلتك" },
      "body":   { "type": "text", "text": "وصلت إلى {{1}} في {{2}}. الإجمالي {{3}} {{4}}. شكراً لاختيارك {{5}}." },
      "footer": { "type": "text", "text": "{{platform_brand}}" },
      "buttons": [
        { "type": "url",   "text": "عرض الإيصال", "url":  "https://{{host}}/trips/{{request_id}}/receipt" },
        { "type": "phone", "text": "اتصل بالدعم", "phone":"+966110000000" }
      ],
      "variables": [
        { "key": "destination_address", "index": 1 },
        { "key": "arrived_at",          "index": 2 },
        { "key": "total",               "index": 3 },
        { "key": "currency_code",       "index": 4 },
        { "key": "platform_brand",      "index": 5 }
      ]
    },
    "provider_template_language": "ar_SA",
    "required_variables": ["destination_address", "arrived_at", "total", "currency_code", "platform_brand", "trip_id", "host"],
    "metadata": { "rtl": true, "deeplink": "trip://history/{{request_id}}" }
  }
  ```
- **Response (201)**: template shape, `version=1`. For WhatsApp
  templates the response also includes `provider_template_status`
  (initially `draft` until `/submit-for-approval` is called)
  and `template_history_id`.
- **Errors**: 400 / 401 / 403 / 409 / 422.

> **Discriminator rule.** A request with
> `channel='whatsapp'` MUST set `template_type='whatsapp_structured'`
> and provide a non-null `body_structured`. Any other `channel`
> MUST use `template_type='plain'` and provide a non-null `body`
> Handlebars string. See [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md)
> 3 for the full body-structured schema and the variable-index
> contract.

### 1.6 `GET /v1/admin/templates`

- **Purpose**: List templates (filter by `category`, `channel`,
  `locale`, `status`).
- **Auth**: Bearer JWT + role `admin`.
- **Response (200)**: paginated list.

### 1.7 `PATCH /v1/admin/templates/{id}`

- **Purpose**: Update a template (creates a new version; the
  old version is retained for audit).
- **Auth**: Bearer JWT + role `admin`; HMAC.
- **Idempotency**: required.
- **Request**: same fields as 1.5; any subset.
- **Response (200)**: template shape, `version=N+1`. The response
  also includes `template_history_id` — the immutable snapshot
  ID for the new version (also written to `notification.template_history`
  in the same transaction). Every subsequent send using this
  template version will record this `template_history_id` on
  its `deliveries` row.

> **WhatsApp structured templates** (`channel='whatsapp'`):
> the request body uses `template_type='whatsapp_structured'`,
> sends `body_structured` instead of `body`, and may omit
> `subject`. See [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md)
> for the full schema and approval workflow.

### 1.7.a `POST /v1/admin/templates/{id}/submit-for-approval`

- **Purpose**: For `channel='whatsapp'` structured templates:
  submit the current version to the configured WhatsApp
  provider for approval. The notification-service translates
  the `body_structured` JSON into the provider's "components"
  shape (`header` / `body` / `footer` / `buttons`), calls the
  gateway's provider API, and updates
  `templates.provider_template_status` to `submitted`.
- **Auth**: Bearer JWT + role `admin` or `notification_ops`;
  HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "locale": "ar",
    "category": "transactional"
  }
  ```
  `category ∈ {transactional, marketing, otp, alert}`. The
  `category` is forwarded to the provider as the template's
  business category.
- **Response (202)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "version": 3,
    "locale": "ar",
    "provider": "meta-cloud-whatsapp",
    "provider_template_id": null,
    "provider_template_status": "submitted",
    "submitted_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 400 / 403 / 404 / 409 / 422 `TEMPLATE_HAS_NO_BODY_STRUCTURED` /
  422 `PROVIDER_NOT_ONBOARDED` / 503 `PROVIDER_UNAVAILABLE`.

### 1.7.b `POST /v1/admin/templates/{id}/approve`

- **Purpose**: Internal helper used by the gateway's webhook
  handler when the provider posts a `template_status_update`
  webhook with status=`approved`. Translates the webhook into
  a `templates` row update plus a `template_history` snapshot
  with `approved_by` populated. Operators may also call this
  directly with `{"note": "..."}` for manual approval overrides
  (HMAC + role `notification_ops` only).
- **Auth**: Bearer JWT + role `admin`; HMAC. When called by
  the gateway over its service-to-service channel, the JWT
  carries the `service` role.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "locale": "ar",
    "provider_template_id": "tpl_ABC123xyz",
    "provider_template_language": "ar_SA",
    "note": "approved by Meta"
  }
  ```
- **Response (200)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "version": 3,
    "locale": "ar",
    "provider_template_id": "tpl_ABC123xyz",
    "provider_template_status": "approved",
    "provider_template_approved_at": "2026-07-29T10:50:11.183Z",
    "template_history_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PJ"
  }
  ```

### 1.7.c `POST /v1/admin/templates/{id}/publish` (atomic across locales)

- **Purpose**: Publish a new version of `name` atomically
  across all configured locales (and all channels for that
  `name`). Issues N snapshots — one per `(channel, locale)`
  combination — into `notification.template_history` in a
  single transaction so a delivery can never observe a
  half-published template set.
- **Auth**: Bearer JWT + role `notification.admin`; HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "name": "trip.completed",
    "channels": ["push", "sms", "email", "in_app", "whatsapp"],
    "locales": ["en", "ar"],
    "bodies": {
      "email.en":       { "subject": "Your trip is complete", "body": "..." },
      "email.ar":       { "subject": "رحلتك اكتملت",          "body": "..." },
      "whatsapp.en":    { "template_type": "whatsapp_structured", "body_structured": { /* … */ } },
      "whatsapp.ar":    { "template_type": "whatsapp_structured", "body_structured": { /* … */ } }
      /* etc. */
    }
  }
  ```
- **Response (200)**:
  ```json
  {
    "name": "trip.completed",
    "templates": [
      { "template_id": "…", "channel": "email", "locale": "en", "version": 4, "template_history_id": "…" },
      { "template_id": "…", "channel": "whatsapp", "locale": "ar", "version": 4, "template_history_id": "…", "provider_template_status": "submitted" }
    ],
    "published_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 1.7.d `GET /v1/admin/templates/{id}/history`

- **Purpose**: List the full publication history of a single
  logical template (across all locales and channels). Each
  row includes the snapshot id, version, the `diff_summary`,
  `published_by`, and (for WhatsApp) `approved_by`. Powers
  the "what was actually sent?" support workflow.
- **Auth**: Bearer JWT + role `admin` or `support_agent`.
- **Response (200)**: paginated list ordered by `revision_no DESC`.

### 1.8 `POST /v1/admin/suppressions`

- **Purpose**: Add a global suppression rule.
- **Auth**: Bearer JWT + role `admin`; HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "category": "marketing",
    "reason": "Crisis comms freeze",
    "expires_at": "2026-08-15T00:00:00Z"
  }
  ```
- **Response (201)**: suppression shape.

### 1.9 `GET /v1/admin/suppressions`

- **Purpose**: List active suppressions.
- **Auth**: Bearer JWT + role `admin`.
- **Response (200)**: paginated list.

### 1.10 `DELETE /v1/admin/suppressions/{id}`

- **Purpose**: Remove a suppression.
- **Auth**: Bearer JWT + role `admin`; HMAC.
- **Response (204)**: no content.

### 1.11 `GET /v1/admin/deliveries`

- **Purpose**: List recent deliveries (for ops and support).
- **Auth**: Bearer JWT + role `admin` or `support_agent`.
- **Request (query)**: `?user_id=...&template_name=...&status=...&from=...&to=...`
- **Response (200)**: paginated list (with `rendered_body`
  redacted unless `support_agent` role).

### 1.12 `POST /v1/admin/erasure/{user_id}`

- **Purpose**: Right-to-erasure: delete a user's notification
  history.
- **Auth**: Bearer JWT + role `admin` or `support_agent`; HMAC.
- **Idempotency**: required.
- **Response (202)**:
  ```json
  {
    "erasure_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PF",
    "rows_affected": 1234,
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 1.13 Templates — Make a Deal *(Phase 7.5)*

The Make-a-Deal kernel publishes 5 templates below. Each template
binds to a `template_version_snapshot_id` per the existing
immutable-template audit chain (see 1.7.d); every delivery is
recorded against that snapshot so the audit chain can replay the
exact text the user received at any historical point.

| Template key | Trigger event | Audience | Channels |
|---|---|---|---|
| `deal.opened` | `ride.deal.opened.v1` / `food.deal.opened.v1` | rider / customer | push, in-app, SMS |
| `deal.bid_received` | `dispatch.deal.bid.submitted.v1` / `delivery.deal.bid.submitted.v1` | rider / customer (notify that a bid arrived) | push, in-app |
| `deal.counter_received` | `ride.deal.countered.v1` / `food.deal.countered.v1` | the targeted counterparty (rider → driver, or driver → rider) | push, in-app |
| `deal.accepted` | `ride.deal.accepted.v1` / `food.deal.accepted.v1` | both sides | push, in-app, SMS |
| `deal.expired` | `ride.deal.expired.v1` / `food.deal.expired.v1` | both sides | push, in-app |

**Rendering example** (`deal.bid_received` on `ride.deal.bid.submitted.v1`):

- Locale `en-AE`, channel `push`:
  > A driver offered **AED 38.00** for your ride from Dubai Mall to Burj Al Arab. Open the app to accept or counter.
- Locale `ar-AE`, channel `push`:
  > قدّم سائق عرضًا بقيمة **38.00 درهم** لرحلتك من دبي مول إلى برج العرب. افتح التطبيق للقبول أو تقديم عرض مضاد.

**Variable schema** (illustrative — example is `deal.bid_received`):

```json
{
  "amount_minor":     3800,
  "currency":         "AED",
  "pickup_address":   "Dubai Mall",
  "dropoff_address":  "Burj Al Arab",
  "bid_id":           "01HZX9C8K4D2H1A8N5J7V3R0Q9",
  "deal_id":          "01HZX9C5S3B1L7K0P2F8V4T6YDA",
  "expires_at":       "2026-08-05T10:42:26.183Z",
  "deep_link":        "https://app.uber.com/deals/01HZX9C5S3B1L7K0P2F8V4T6YDA"
}
```

**Suppression.** Subject to per-user preferences (existing
`GET /v1/preferences/{user_id}` flow). The deal kernel does NOT
introduce a new suppression surface.

**Failure.** Re-uses the existing `POST /v1/notifications` retry
policy (per 1.1, max 3 retries with exponential backoff; on
permanent failure the existing `notification.failed.v1` event is
emitted).

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| ``notification-service` (provider ACL)` | POST | `/v1/sends` | send a rendered message via a channel | 2s | 3 (exponential) | yes (per channel) |
| ``notification-service` (provider ACL)` | POST | `/v1/templates/submit` | submit a WhatsApp template for provider approval | 5s | 2 | yes |
| ``notification-service` (provider ACL)` | GET | `/v1/templates/{id}/status` | poll WhatsApp template approval status | 2s | 3 | yes |
| ``notification-service` (provider ACL)` | DELETE | `/v1/templates/{id}` | delete a WhatsApp template | 2s | 1 | yes |
| ``customer-service` (cross-persona profile)` | GET | `/v1/profiles/{user_id}` | read locale, device list | 500ms | 1 | yes |
| `customer-service` | GET | `/v1/customers/{id}` | read phone, email | 500ms | 1 | yes |
| `driver-service` | GET | `/v1/drivers/{id}` | read driver phone, email | 500ms | 1 | yes |
| `courier-service` | GET | `/v1/couriers/{id}` | read courier phone, email | 500ms | 1 | yes |
| ``restaurant-service` (merchant)` | GET | `/v1/merchants/{id}` | read merchant email | 500ms | 1 | yes |
| `configuration-service` | GET | `/v1/config/notification` | read defaults, retry policy | 500ms | 3 | yes |

All outbound calls carry `X-Correlation-Id` and `traceparent`.

> WhatsApp template lifecycle (submit / status / delete / approve)
> is performed exclusively via ``notification-service` (provider ACL)`
> using its plug-in provider model. See
> [`../`notification-service` (provider ACL)/WHATSAPP_PROVIDER_CONTRACT.md`](../notification-service/WHATSAPP_PROVIDER_CONTRACT.md)
> for the provider contract that backs these calls.

## 3. Produced Events

### 3.1 `notification.sent.v1`

- **Producer**: `notification-service`.
- **Topic**: `notification.notification.sent`.
- **Trigger**: every successful send (status reaches `sent`
  or `delivered`).
- **Partition key**: `user_id`.
- **Schema (data)**:
  ```json
  {
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_name": "trip.completed",
    "category": "trip",
    "channel": "push",
    "locale": "ar",
    "attempt": 1,
    "delivered_at": "2026-07-29T10:42:12.100Z",
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `notification.notification.sent.dlq`.
- **Consumers**: ``admin-service` (support module)`, `audit-service`,
  ``reporting-service` (data lake)`.

### 3.2 `notification.failed.v1`

- **Producer**: `notification-service`.
- **Topic**: `notification.notification.failed`.
- **Trigger**: persistent failure (retries exhausted).
- **Partition key**: `user_id`.
- **Schema (data)**:
  ```json
  {
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_name": "trip.completed",
    "category": "trip",
    "channel": "sms",
    "attempt": 3,
    "failure_reason": "CIRCUIT_OPEN",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.3 `notification.suppressed.v1`

- **Producer**: `notification-service`.
- **Topic**: `notification.notification.suppressed`.
- **Trigger**: a notification was suppressed (preference, quiet
  hours, dedup, suppression list, no contact).
- **Partition key**: `user_id`.
- **Schema (data)**:
  ```json
  {
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_name": "marketing.promo",
    "category": "marketing",
    "suppression_reason": "opt_out",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.4 `notification.delivered.v1`

- **Producer**: `notification-service`.
- **Topic**: `notification.notification.delivered`.
- **Trigger**: a delivery was confirmed delivered by the
  provider's webhook (e.g. Meta Cloud `delivered` event,
  SendGrid delivery event, APNs delivery event).
- **Partition key**: `user_id`.
- **Schema (data)**:
  ```json
  {
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_name": "trip.completed",
    "category": "trip",
    "channel": "whatsapp",
    "locale": "ar",
    "attempt": 1,
    "delivered_at": "2026-07-29T10:42:12.100Z",
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.5 `notification.read.v1` (WhatsApp only)

- **Producer**: `notification-service`.
- **Topic**: `notification.notification.read`.
- **Trigger**: the recipient opened/reads the WhatsApp message
  (provider posts a `read` webhook). Other channels do not
  support this event.
- **Partition key**: `user_id`.
- **Schema (data)**:
  ```json
  {
    "notification_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "template_name": "trip.completed",
    "category": "trip",
    "channel": "whatsapp",
    "read_at": "2026-07-29T10:43:00.000Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.6 `notification.template.published.v1`

- **Producer**: `notification-service`.
- **Topic**: `notification.template.published`.
- **Trigger**: a new template version was published (either
  `POST /v1/admin/templates`, `PATCH /v1/admin/templates/{id}`,
  or `POST /v1/admin/templates/{id}/publish`). Powers the
  ``reporting-service` (data lake)` "template change" dashboards and the
  `audit-service` immutable log of template content.
- **Partition key**: `template_id`.
- **Schema (data)**:
  ```json
  {
    "template_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "trip.completed",
    "channel": "whatsapp",
    "locale": "ar",
    "template_type": "whatsapp_structured",
    "version": 3,
    "template_history_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PJ",
    "provider_template_id": "tpl_ABC123xyz",
    "provider_template_status": "approved",
    "published_by": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "approved_by": "01HZX9C5S3B1L7K0P2F8V4T6YDD",
    "diff_summary": {
      "added_variables":     ["currency_code"],
      "removed_variables":   [],
      "body_changed":        true,
      "structure_changed":   false,
      "subject_changed":     false
    },
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Retry / DLQ**: 5 with backoff; replays are idempotent
  (``reporting-service` (data lake)` and `audit-service` key on
  `template_history_id`).

## 4. Consumed Events

### 4.1 `trip.started.v1`

- **Producer**: `trip-service`.
- **Topic**: `trip.trip.started`.
- **Reason**: "your driver is on the way" notification.
- **Handler**:
  1. Inbox insert.
  2. Resolve `customer_id` → `user_id`; resolve locale;
     resolve preferences.
  3. Dedup check.
  4. Render template `trip.started` for the chosen channel
     and locale.
  5. Hand off to ``notification-service` (provider ACL)`.
  6. Emit `notification.sent.v1` (or `.failed.v1`).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `trip.arrived.v1`

Same as 4.1 with template `trip.arrived`.

### 4.3 `trip.completed.v1`

Same as 4.1 with template `trip.completed`.

### 4.4 `trip.cancelled.v1`

Same as 4.1 with template `trip.cancelled`.

### 4.5 `food.order.placed.v1`

Same as 4.1 with template `food.order.placed`.

### 4.6 `food.order.accepted.v1`, `food.order.preparing.v1`, `food.order.ready.v1`, `food.order.cancelled.v1`

Same as 4.1 with the corresponding template.

### 4.7 `delivery.pickup.v1`, `delivery.in_transit.v1`, `delivery.completed.v1`, `delivery.failed.v1`

Same as 4.1 with the corresponding template.

### 4.8 `payment.failed.v1`

Same as 4.1 with template `payment.failed`.

### 4.9 `payment.refund.completed.v1`

Same as 4.1 with template `payment.refund.completed`.

### 4.10 `ride.safety.sos.v1`

- **Reason**: emergency broadcast. Bypasses quiet hours,
  dedup, and preference opt-out (safety override).
- **Handler**:
  1. Inbox insert.
  2. Resolve user, locale, devices.
  3. Render template `safety.sos` (priority `urgent`).
  4. Send on push, SMS, and email (all channels) — at
     least one must succeed.
  5. Emit `notification.sent.v1` per channel.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 5 attempts (safety).
- **Failure**: DLQ; on-call paged.

### 4.10.a `trip.reward.granted.v1`

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.granted`.
- **Reason**: A per-trip guaranteed reward was granted. The
  customer gets a "you've earned a per-trip reward" message
  (when `trip.reward.user.kind` is for the customer). The driver
  gets a "you received a per-trip top-up" message.
- **Handler**:
  1. Inbox insert.
  2. Resolve `customer_id` + `driver_id` to `user_id`; locale;
     preferences.
  3. Dedup check.
  4. Render templates `trip.reward.granted.customer` and/or
     `trip.reward.granted.driver` (whichever applies).
  5. Hand off to ``notification-service` (provider ACL)`.
  6. Emit `notification.sent.v1` (or `.failed.v1`).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.10.b `trip.reward.reversed.v1`

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.reversed`.
- **Reason**: The per-trip reward was reversed (e.g. trip
  disputed). Notify both customer and driver.
- **Handler**: same shape as 4.10.a with templates
  `trip.reward.reversed.customer` and `trip.reward.reversed.driver`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.10.c `pricing.geo_config.updated.v1`

- **Producer**: `admin-service`.
- **Topic**: `pricing.geo_config.updated`.
- **Reason**: An operator changed a `pricing.geo_overrides` /
  `pricing.geo_config` record. This is an operator-facing
  signal — by default **suppressed** because recipients are
  customers. Operators must opt in to a `notification.admins`
  recipient list to receive the broadcast.
- **Handler**:
  1. Inbox insert.
  2. If `notification.admins` is configured, render template
     `pricing.geo_config.updated` and broadcast to the admin
     recipients. Otherwise, emit `notification.suppressed.v1`
     with reason `no_operator_recipients`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.11 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: template defaults, retry policy, channel
  priority, dedup window, quiet hours default, suppressed
  categories, WhatsApp 24h-window policy, WhatsApp approval
  required flag, all changed.
- **Handler**: reload config (idempotent; config hash
  compared before swap).
- **Deduplication**: inbox on `event_id`.
- **Failure**: DLQ.

### 4.12 `comms.whatsapp.template_status_update.v1`

- **Producer**: ``notification-service` (provider ACL)`.
- **Topic**: `comms.whatsapp.template_status_update`.
- **Reason**: a WhatsApp provider approved / rejected / paused
  one of our submitted templates. The notification-service
  must record this on the `templates` row (update
  `provider_template_status`, `provider_template_id`,
  `provider_template_approved_at`), then publish a fresh
  `notification.template_history` snapshot with the
  `approved_by` populated (for `approved`) and a new
  `template_history_id`.
- **Handler**:
  1. Inbox insert (dedupe on `event_id`).
  2. Locate the matching `(template_id, locale)` via the
     `provider_template_id` mirror on `templates`.
  3. Apply state transition (`draft|submitted → approved|rejected|paused`).
  4. For `approved`: write a new `template_history` row with
     `approved_by` set; emit `notification.template.published.v1`
     so downstream consumers (analytics, audit) see the
     approval.
  5. For `rejected`: keep the existing `template_history`
     chain (no new snapshot); update the rejection reason.
- **Deduplication**: inbox on `event_id`; the handler is also
  idempotent on `(template_id, locale, provider_template_status)`.
- **Failure**: DLQ + manual reconciliation.

### 4.13 `comms.whatsapp.delivered.v1`, `comms.whatsapp.read.v1`

These are part of the channel-specific family
(`comms.<channel>.<state>.v1`) emitted by ``notification-service` (provider ACL)`
when the provider posts a delivery receipt or a read receipt.
The notification-service consumes them and updates the
matching `delivery` row (move `status` to `delivered` or `read`,
populate `delivered_at` / `read_at`), then emits the
corresponding `notification.delivered.v1` / `notification.read.v1`
event so analytics and audit capture the recipient-side
confirmation.

The handler is idempotent (delivery state transitions are
unidirectional `sent → delivered → read`).

### 4.14 `*.deal.*.v1` *(Make a Deal — Phase 7.5)*

This service consumes 12 deal events spanning the ride and food
verticals. The recipient of each event is mapped to one of the 5
deal templates defined in 1.13.

| Event | Producer | Template | Audience |
|---|---|---|---|
| `ride.deal.opened.v1` | ``trip-service` (ride-request)` | `deal.opened` | rider |
| `ride.deal.bid.submitted.v1` | ``driver-service` (dispatch)` | `deal.bid_received` | rider |
| `ride.deal.countered.v1` | ``trip-service` (ride-request)` (rider counters) OR ``driver-service` (dispatch)` (driver counters) | `deal.counter_received` | the targeted counterparty |
| `ride.deal.accepted.v1` | ``trip-service` (ride-request)` OR ``driver-service` (dispatch)` | `deal.accepted` | both sides |
| `ride.deal.rejected.v1` | either side | `deal.counter_received` (rejected framing) | the rejected party |
| `ride.deal.expired.v1` | timer holder | `deal.expired` | both sides |
| `food.deal.opened.v1` | `food-order-service` | `deal.opened` | customer |
| `food.deal.bid.submitted.v1` | ``courier-service` (dispatch)` | `deal.bid_received` | customer |
| `food.deal.countered.v1` | `food-order-service` (customer counters) OR ``courier-service` (dispatch)` (courier counters) | `deal.counter_received` | the targeted counterparty |
| `food.deal.accepted.v1` | `food-order-service` OR ``courier-service` (dispatch)` | `deal.accepted` | both sides |
| `food.deal.rejected.v1` | either side | `deal.counter_received` (rejected framing) | the rejected party |
| `food.deal.expired.v1` | timer holder | `deal.expired` | both sides |
| `chat.message.offline_delivery_required.v1` *(Phase 7.7 — In-App Chat)* | `chat-service` | `chat.message.received` | the offline recipient (rider / driver / customer / courier / restaurant staff) |

- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `<topic>.dlq`.
- **Localisation**: per-user preference lookup against
  `GET /v1/preferences/{user_id}` (1.3); honour channel opt-outs.
- **Batching**: deal events are sent immediately (no batching) — the deal is time-sensitive and the rider's app needs the notification within 1 s of the event to keep the negotiation responsive.

### 4.15 `chat.message.offline_delivery_required.v1` *(Phase 7.7 — In-App Chat)*

- **Producer**: `chat-service` (the chat thread's recipient is not
  on a connected WebSocket; this service is the offline-push fallback).
- **Reason**: deliver a chat push notification to the offline recipient
  so the conversation continues asynchronously.
- **Handler**:
  1. Inbox insert on `event_id`.
  2. Resolve `data.recipient_user_id` → profile (locale, device list).
  3. Look up the `chat_message_received` template (en + ar + fr + ur
     locales); render with `sender_display_name` and `body_preview`.
  4. Honour `chat.quiet_hours.{user_id}`; when `data.urgency = urgent`,
     bypass quiet hours.
  5. Send via the highest-priority available channel
     (`notification.channel.priority` config); default `["push", "sms", "email", "in_app"]`.
  6. Emit `notification.sent.v1` (or `.failed.v1`).
- **P99 latency target**: ≤ 1500 ms from event to push delivery
  (`chat.message.offline_delivery_seconds` SLO).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `chat.message.offline_delivery_required.dlq`.
- **Note**: this is a hard dependency at chat-service rollout — the
  chat-service falls back to "next session" in-app banner only when
  `notification-service` is unreachable; the rollout is gated on
  this consumer being live.

## 5. Reliability

- **Timeouts** (defaults):
  - ``notification-service` (provider ACL)`: 2s.
  - User / customer / driver / courier / merchant reads:
    500ms.
  - `configuration-service`: 500ms.
- **Retries** (exponential backoff with jitter):
  - Gateway calls: 3 attempts (5s, 30s, 120s).
  - Safety: 5 attempts (1s, 5s, 30s, 60s, 300s).
  - Read calls: 1 retry.
- **Circuit breakers** per channel: open on ≥ 3 consecutive
  5xx/timeout in 30s; half-open after 30s; close on 2
  successes.
- **Channel fallback**: if push circuit is open, try SMS; if
  SMS circuit is open, try email; if all open, return 503.
- **Bulkheads**: separate connection pools per channel.
- **Outbox**: `notification.outbox` table; poller publishes
  at-least-once; rows purged 24h after `published_at`.
- **Inbox**: `notification.inbox` table; dedupe on
  `event_id`.
- **DLQ**: every topic has a paired `<topic>.dlq`; 30-day
  retention.
- **Reconciliation**: a daily job scans for delivery rows in
  `sending` state older than 5 minutes and marks them
  `failed` with reason `STUCK_SENDING`; this is rare and
  indicates a bug.

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event.
  - The `headers.correlation_id` of every outbox row.
  - The `correlation_id` column of every delivery row.
- For events consumed from Kafka, the event's
  `correlation_id` becomes the new `correlation_id` for
  the resulting notification.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments HTTP, Kafka, DB,
  Redis.
- One root span per notification; render, channel selection,
  gateway call, retry as child spans.
- Sample 100% of errors, 10% of successes in production; 100%
  in staging.
- The inbound `traceparent` (from the producer service) is
  honored when consuming from Kafka; the resulting
  notification's trace links back to the producer's trace.


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
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``notification-service` (provider ACL)`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``courier-service` (delivery)`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``restaurant-service` (merchant)`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``pricing-service` (promotion)`](../pricing-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (safety)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``customer-service` (cross-persona profile)`](../customer-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (checkout)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (dispatch)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (incentives)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (inventory)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (menu)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (merchant)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 15 more_ | |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-kotlin v3.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.phase7.reward_grant.v1` | notification_service_grant_template | `request:{request_id}:reward:notif:grant` |
| `wf.phase7.reward_reversal.v1` | notification_service_reversal_template | `request:{request_id}:reward:notif:reverse` |
| `wf.refund.standard.v1` | notification_service_refund_template | `refund:{refund_id}:notif` |
| `wf.refund.partial.v1` | notification_service_refund_template | `refund:{refund_id}:notif` |
| `wf.refund.food_reject.v1` | notification_service_refund_template | `refund:{refund_id}:notif` |
| `wf.refund.cancellation.v1` | notification_service_refund_template | `refund:{refund_id}:notif` |
| `wf.refund.dispute.v1` | notification_service_refund_template | `refund:{refund_id}:notif` |
| `wf.refund.cod_failed.v1` | notification_service_refund_template | `refund:{refund_id}:notif` |
| `wf.onboarding.driver.v1` | notification_service_approval_template | `driver:{id}:onboarding:notif` |
| `wf.onboarding.courier.v1` | notification_service_approval_template | `courier:{id}:onboarding:notif` |
| `wf.phase75.deal_rider.v1` | notification_service_deal_template (5 templates) | `deal:{deal_id}:notif:*` |
| `wf.phase75.deal_driver.v1` | notification_service_deal_template (5 templates) | `deal:{deal_id}:notif:*` |
| `wf.phase75.deal_food.v1` | notification_service_deal_template (5 templates) | `deal:{deal_id}:notif:*` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| (no inbound Kafka signals — REST trigger only or worker is reactive to conductor-kafka-bridge events) | – | – |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| (no compensation — terminal states only, or compensation is no-op) | – | – |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.uber.io`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../MASTER_TASK.md) 7-9
