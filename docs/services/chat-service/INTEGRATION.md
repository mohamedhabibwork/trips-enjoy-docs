# chat-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/chat/threads`

- **Purpose**: List threads for the current user (rider / driver /
  customer / courier / restaurant_staff).
- **Auth**: Bearer JWT (`sub` resolves to the user).
- **Query params**: `state` (default `open`), `kind` (`trip_chat` /
  `food_order_chat` / `delivery_chat`), `cursor`, `limit` (default 50,
  max 200).
- **Response (200)**:
  ```json
  {
    "threads": [
      {
        "id": "01HZX…",
        "kind": "trip_chat",
        "context_id": "01HZX…",
        "state": "open",
        "participants": [
          { "user_id": "01HZX…", "role": "rider", "display_name": "Sarah" },
          { "user_id": "01HZX…", "role": "driver", "display_name": "Ahmed" }
        ],
        "last_message_at": "2026-08-12T10:23:11Z",
        "unread_count": 2,
        "created_at": "2026-08-12T10:00:00Z"
      }
    ],
    "next_cursor": "01HZX…"
  }
  ```
- **Errors**: 401, 403, 429.

### 1.2 `GET /v1/chat/threads/{id}`

- **Purpose**: Read thread metadata.
- **Auth**: Bearer JWT (participant only).
- **Response (200)**: full thread including participants, last 5 messages
  (preview), and the caller's read state.
- **Errors**: 401, 403 `FORBIDDEN_NOT_PARTICIPANT`, 404 `THREAD_NOT_FOUND`.

### 1.3 `GET /v1/chat/threads/{id}/messages`

- **Purpose**: Paginate message history.
- **Auth**: Bearer JWT (participant only).
- **Query params**: `cursor` (the `id` of the last message in the previous
  page; NULL for first page), `limit` (default 50, max 200),
  `direction` (`backward` / `forward`, default `backward`).
- **Response (200)**:
  ```json
  {
    "messages": [
      {
        "id": "01HZX…",
        "thread_id": "01HZX…",
        "sender_id": "01HZX…",
        "sender_kind": "user",
        "body": "I'm at the blue door",
        "has_attachment": false,
        "visibility": "visible",
        "created_at": "2026-08-12T10:23:11Z"
      },
      {
        "id": "01HZX…",
        "sender_kind": "system",
        "system_message_key": "driver_arrived",
        "system_message_args": { "driver_name": "Ahmed" },
        "body": "Ahmed has arrived at the pickup",
        "created_at": "2026-08-12T10:21:00Z"
      }
    ],
    "next_cursor": "01HZX…",
    "has_more": true
  }
  ```
- **Errors**: 401, 403, 404, 429.

### 1.4 `POST /v1/chat/threads/{id}/messages`

- **Purpose**: Send a message.
- **Auth**: Bearer JWT (participant only; not muted / banned).
- **Idempotency**: `client_msg_id` in body; dedup via UNIQUE index
  (FR--015).
- **Request**:
  ```json
  {
    "body": "I'm at the blue door",
    "client_msg_id": "01HZX…"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX…",
    "thread_id": "01HZX…",
    "sender_id": "01HZX…",
    "sender_kind": "user",
    "body": "I'm at the blue door",
    "has_attachment": false,
    "visibility": "visible",
    "created_at": "2026-08-12T10:23:11Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` (body empty / > 4 000 chars)
  - 401, 403 `FORBIDDEN_NOT_PARTICIPANT` / `MUTED` / `BANNED`
  - 404 `THREAD_NOT_FOUND`
  - 409 `THREAD_CLOSED`
  - 429 `RATE_LIMITED`
- **Side effects**: persist + outbox row → Kafka `chat.message.sent.v1`
  + Redis Pub/Sub `chat:thread:{thread_id}` fan-out → if recipient
  offline → `chat.message.offline_delivery_required.v1`.

### 1.5 `POST /v1/chat/threads/{id}/messages/{msg_id}/read`

- **Purpose**: Mark a message read for the calling participant.
- **Auth**: Bearer JWT (participant only).
- **Request**: `{}` (empty body).
- **Response (204)**: no content.
- **Errors**: 401, 403, 404.
- **Side effects**: update `chat.read_states.last_read_message_id`;
  emit `chat.message.read.v1`.

### 1.6 `POST /v1/chat/threads/{id}/typing`

- **Purpose**: Publish a typing heartbeat.
- **Auth**: Bearer JWT (participant only).
- **Request**: `{}`.
- **Response (204)**: no content.
- **Side effects**: Redis Pub/Sub `chat:typing:{thread_id}` (no persistence).

### 1.7 `POST /v1/chat/threads/{id}/attachments`

- **Purpose**: Register an attachment that has been uploaded to
  `file-service`.
- **Auth**: Bearer JWT (participant only).
- **Idempotency**: required `Idempotency-Key` header.
- **Request**:
  ```json
  {
    "message_id": "01HZX…",
    "file_id": "01HZX…",
    "mime": "image/jpeg",
    "bytes": 245678
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX…",
    "message_id": "01HZX…",
    "file_id": "01HZX…",
    "scan_status": "pending",
    "visibility": "pending_attachment"
  }
  ```
- **Errors**: 400 `INVALID_MIME`, 401, 403, 404, 409 `THREAD_CLOSED`,
  422 `FILE_SCAN_FAILED`.

### 1.8 `POST /v1/chat/threads/{id}/report`

- **Purpose**: Report a message.
- **Auth**: Bearer JWT (participant only).
- **Idempotency**: required `Idempotency-Key` header.
- **Request**:
  ```json
  {
    "message_id": "01HZX…",
    "reason": "abuse",
    "reason_text": "Threatening language"
  }
  ```
- **Response (201)**:
  ```json
  {
    "report_id": "01HZX…",
    "message_id": "01HZX…",
    "status": "open",
    "created_at": "2026-08-12T10:24:00Z"
  }
  ```
- **Errors**: 400, 401, 403, 404.
- **Side effects**: set `chat.messages.visibility = hidden` (recipient view);
  insert `chat.moderation_reports`; emit `chat.message.reported.v1`.

### 1.9 `POST /v1/chat/users/{user_id}/block`

- **Purpose**: Block another user at the user level.
- **Auth**: Bearer JWT (caller's `sub` must equal `user_id`).
- **Idempotency**: required `Idempotency-Key` header.
- **Request**: `{}`.
- **Response (201)**:
  ```json
  { "blocker_id": "01HZX…", "blocked_id": "01HZX…", "created_at": "…" }
  ```
- **Errors**: 400 (self-block), 401, 404.

### 1.10 `DELETE /v1/chat/users/{user_id}/block`

- **Purpose**: Unblock a user.
- **Auth**: Bearer JWT (caller's `sub`).
- **Response (204)**: no content.

### 1.11 `GET /v1/chat/users/{user_id}/blocked`

- **Purpose**: List users blocked by the caller.
- **Auth**: Bearer JWT (caller's `sub`).
- **Response (200)**: `{ "blocked": [ { "user_id": "…", "blocked_at": "…" } ] }`.

### 1.12 `POST /v1/chat/threads/{id}/close`

- **Purpose**: Force-close a thread (admin only).
- **Auth**: Service token (system) OR `chat.admin`.
- **Idempotency**: required `Idempotency-Key` header.
- **Request**:
  ```json
  { "reason_code": "admin_force_close", "reason_text": "…" }
  ```
- **Response (200)**: thread in `state = closed`.
- **Errors**: 401, 403, 404, 409 (already closed).

### 1.13 `WS /v1/chat/ws?token=<jwt>`

- **Purpose**: Bidirectional real-time messaging.
- **Auth**: JWT in the `token` query param (validated server-side).
- **Origin check**: `Origin` MUST be in `chat.websocket.allowed_origins`.
- **Inbound frames**:
  ```json
  { "type": "send", "thread_id": "01HZX…", "body": "I'm at the blue door", "client_msg_id": "01HZX…" }
  { "type": "read", "thread_id": "01HZX…", "message_id": "01HZX…" }
  { "type": "typing", "thread_id": "01HZX…" }
  { "type": "ping" }
  ```
- **Outbound frames**:
  ```json
  { "type": "message", "thread_id": "01HZX…", "message": { … } }
  { "type": "read", "thread_id": "01HZX…", "user_id": "…", "last_read_message_id": "01HZX…" }
  { "type": "typing", "thread_id": "01HZX…", "user_id": "…" }
  { "type": "presence", "user_id": "…", "online": true }
  { "type": "pong" }
  ```
- **Close codes**:
  - `4400 INVALID_TOKEN` — token missing or invalid.
  - `4403 FORBIDDEN_ORIGIN` — origin not in allow-list.
  - `4408 IDLE_TIMEOUT` — no ping within `chat.websocket.idle_timeout_seconds`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | /v1/identities/{sub} | resolve user profile | 500 ms | 2 | yes |
| `customer-service` / `driver-service` / `courier-service` / `restaurant-service` | GET | /v1/{plural}/{user_id} | participant profile | 500 ms | 2 | yes |
| `file-service` | GET | /v1/files/{file_id} | attachment metadata + scan status | 500 ms | 2 | yes |
| `notification-service` | POST | /v1/notifications | synchronous urgent push (rare; offline path is event-driven) | 500 ms | 2 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read `chat.*` config | 300 ms | 3 | yes |

## 3. Produced Events

### 3.1 `chat.thread.created.v1`

- **Producer**: `chat-service`.
- **Topic**: `chat.chat_thread.created`.
- **Trigger**: thread auto-created from a service-context event.
- **Schema version**: 1.
- **Partition key**: `aggregate_id` (= `thread_id`).
- **Consumers**: `notification-service` (in-app surface banner),
  `audit-service`, `reporting-service`, `search-service` (admin-only).
- **Schema**:
  ```json
  {
    "event_id": "01HZX…",
    "event_name": "chat.thread.created.v1",
    "occurred_at": "2026-08-12T10:00:00Z",
    "schema_version": 1,
    "producer": "chat-service",
    "tenant_id": "global",
    "correlation_id": "01HZX…",
    "aggregate_type": "ChatThread",
    "aggregate_id": "01HZX…",
    "data": {
      "thread_id": "01HZX…",
      "kind": "trip_chat",
      "context_id": "01HZX…",
      "participants": [
        { "user_id": "01HZX…", "role": "rider" },
        { "user_id": "01HZX…", "role": "driver" }
      ],
      "created_at": "2026-08-12T10:00:00Z"
    }
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ `chat.chat_thread.created.dlq`.

### 3.2 `chat.thread.closed.v1`

- **Trigger**: terminal event OR admin force-close OR retention sweep.
- **Schema version**: 1.
- **Partition key**: `thread_id`.
- **Schema**: same envelope; `data` includes `kind`, `context_id`,
  `closed_at`, `close_reason`.

### 3.3 `chat.message.sent.v1`

- **Trigger**: every accepted message send (REST or WebSocket).
- **Schema version**: 1.
- **Partition key**: `thread_id`.
- **Consumers**: `audit-service`, `reporting-service`,
  `search-service` (admin-only).
- **Schema**: same envelope; `data` includes `message_id`,
  `thread_id`, `sender_id`, `sender_kind`, `body_preview` (first 80
  chars — body itself NOT included to avoid PII in the bus),
  `has_attachment`, `created_at`.

> **PII note.** The body is NOT included in the event payload; the
> `audit-service` consumer reads the full row via
> `GET /v1/chat/threads/{thread_id}/messages` (admin / audit token).
> This keeps the message body off the bus.

### 3.4 `chat.message.read.v1`

- **Trigger**: read receipt.
- **Partition key**: `thread_id`.

### 3.5 `chat.attachment.shared.v1`

- **Trigger**: attachment scan succeeded.
- **Partition key**: `thread_id`.

### 3.6 `chat.message.reported.v1`

- **Trigger**: a participant reports a message.
- **Partition key**: `thread_id`.
- **Consumers**: `admin-service` (support — opens a ticket if
  `reason ∈ {abuse, safety, illegal}`), `fraud-risk-service`,
  `audit-service`.
- **Schema**: same envelope; `data` includes `report_id`,
  `message_id`, `thread_id`, `reporter_id`, `reason`, `reason_text`.

### 3.7 `chat.message.moderated.v1`

- **Trigger**: admin hides / removes a message.
- **Partition key**: `thread_id`.

### 3.8 `chat.message.offline_delivery_required.v1`

- **Trigger**: message recipient is offline (no local WebSocket).
- **Partition key**: `recipient_user_id`.
- **Consumers**: `notification-service` (push).
- **Schema**: same envelope; `data` includes `message_id`,
  `thread_id`, `sender_id`, `sender_role`, `recipient_user_id`,
  `body_preview`, `created_at`, `urgency` (`normal` | `urgent`).

### 3.9 `chat.user.blocked.v1` / `chat.user.muted.v1` / `chat.user.banned.v1`

- **Trigger**: user-level block / mute / ban.
- **Partition key**: `user_id`.

## 4. Consumed Events

### 4.1 `ride.request.matched.v1`

- **Producer**: `trip-service`.
- **Reason**: create `trip_chat` thread with `[rider, driver]`.
- **Handler**: idempotent on `context_id = trip_id` (UNIQUE).
  Resolve `rider_id` and `driver_id` from the event; call
  `customer-service` / `driver-service` to get display names +
  locales; insert `chat.threads` + 2 `chat.participants`; emit
  `chat.thread.created.v1`.
- **Deduplication**: inbox on `event_id` + UNIQUE on `(kind, context_id)`.
- **Retry**: 3 with backoff; failure → DLQ
  `chat.ride_request_matched.dlq`.

### 4.2 `trip.arrived.v1`

- **Producer**: `trip-service`.
- **Reason**: system message "driver has arrived".
- **Handler**: insert `chat.messages` with `sender_kind = system`,
  `system_message_key = driver_arrived`,
  `system_message_args = { driver_name }`; update
  `chat.threads.last_message_at`; emit `chat.message.sent.v1`.
- **Idempotency**: inbox on `event_id`.

### 4.3 `trip.started.v1`

Same as 4.2; `system_message_key = trip_started`.

### 4.4 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: close thread + final system message.
- **Handler**: write system message "trip complete"; set
  `chat.threads.state = closing`; emit `chat.thread.closed.v1`;
  schedule `state = closed` after the 1-hour grace window
  (BR--037).
- **Idempotency**: inbox on `event_id`.

### 4.5 `trip.cancelled.v1`

Same as 4.4; `close_reason = service_cancelled`; include
`reason_code`, `reason_text` in the system message.

### 4.6 `food.order.accepted.v1`

- **Producer**: `food-order-service`.
- **Reason**: create `food_order_chat` thread with
  `[customer, restaurant_staff]`.
- **Handler**: idempotent on `context_id = order_id`. Resolve
  customer + restaurant staff (the operator who accepted); insert
  thread + 2 participants; emit `chat.thread.created.v1`.
- **Idempotency**: inbox + UNIQUE.

### 4.7 `food.order.preparing.v1`

- **Reason**: system message "order is being prepared".

### 4.8 `food.order.ready.v1`

- **Reason**: system message "order is ready for pickup".

### 4.9 `food.order.delivered.v1`

- **Reason**: close thread.

### 4.10 `food.order.cancelled.v1`

- **Reason**: close thread; include reason.

### 4.11 `delivery.courier.assigned.v1`

- **Producer**: `courier-service`.
- **Reason**: create `delivery_chat` thread with `[customer, courier]`.

### 4.12 `delivery.pickup.v1`

- **Reason**: system message "courier picked up your order".

### 4.13 `delivery.completed.v1`

- **Reason**: close thread.

### 4.14 `delivery.cancelled.v1`

- **Reason**: close thread.

### 4.15 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload `chat.*` config.

## 5. Admin APIs (under `/admin/v1/chat/...`)

All admin endpoints require `chat.admin` role or `platform.support`
with `X-Audit-Reason` header. Every call emits
`audit.admin.chat.v1`.

| Method | URI | Purpose |
|--------|-----|---------|
| `GET` | `/admin/v1/chat/threads/{id}` | read with full PII |
| `POST` | `/admin/v1/chat/threads/{id}/close` | force-close |
| `POST` | `/admin/v1/chat/threads/{id}/messages/{msg_id}/hide` | hide a message (`visibility = hidden`) |
| `POST` | `/admin/v1/chat/threads/{id}/messages/{msg_id}/remove` | remove a message (`visibility = removed`) |
| `POST` | `/admin/v1/chat/users/{user_id}/mute` | mute |
| `DELETE` | `/admin/v1/chat/users/{user_id}/mute` | unmute |
| `POST` | `/admin/v1/chat/users/{user_id}/ban` | ban |
| `DELETE` | `/admin/v1/chat/users/{user_id}/ban` | unban |
| `GET` | `/admin/v1/chat/reports` | list moderation reports |
| `POST` | `/admin/v1/chat/reports/{id}/resolve` | resolve a report |
| `POST` | `/admin/v1/chat/users/{user_id}/gdpr-erase` | GDPR sweep |

## 6. Reliability

- **Timeouts**: HTTP 500 ms; DB 5 s; Redis 200 ms; Kafka 5 s; WS
  send 200 ms.
- **Retries**: 2–3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `chat.outbox`.
- **Inbox**: yes, `chat.inbox`.
- **DLQ**: every consumed topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: nightly `reporting-service` job verifies that
  every active trip / order / delivery has a corresponding open
  thread; misses raise an alert.

## 7. Correlation IDs

All requests carry `X-Correlation-Id` (alias for `X-Request-Id` per
[ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md));
the service propagates it to outbound calls and embeds it in the
event envelope.

## 8. Distributed Tracing

OpenTelemetry SDK; one root span per HTTP request or per WS frame;
propagated through Kafka and Redis Pub/Sub. Sample 100% on errors,
10% on success.

## 9. Threat Surface (per `SECURITY_ARCHITECTURE.md` 18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin force-close / hide) | HMAC-SHA256 signature + `X-Audit-Reason` |
| Repudiation | audit log + audit chain |
| Information disclosure | no raw phone numbers / emails in payload (BR--011); pgcrypto at rest |
| Denial of service | rate limits (per user, per thread); circuit breakers |
| Elevation of privilege | resource-level ownership checks (`thread.participants`) |
| WebSocket origin spoofing | origin allow-list (FR--019) |
| Spam | rate limit + burst threshold + profanity filter + report-driven block |
| Attachment-borne malware | file-service scan before visibility (FR--031–033) |


---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)
- [`PLAN.md`](./PLAN.md) — implementation tracker

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services → soon 21)
- [`../../../main.md`](../../../main.md) — top-level platform specification