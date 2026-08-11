# chat-service

## 1. Purpose

`chat-service` owns the **in-app, real-time, 1:1 / 1:few chat threads**
between participants who are connected by a service-specific
context: a rider and their driver during a trip; a customer and a
restaurant while an order is being prepared; a customer and their
courier during a delivery. The service is the single source of
truth for the chat thread, the message history, read state, typing
indicators, attachments, and moderation actions. It owns the
real-time WebSocket fan-out (via Redis Pub/Sub across replicas)
and falls back to a push notification through `notification-service`
when the recipient is offline.

The platform's `notification-service` continues to own **system-to-user
notifications** ("your trip is complete", "your order is ready",
push, SMS, email, in-app, WhatsApp templates); `chat-service` is the
**user-to-user conversation channel** that is bound to a transient
service context. The two never overlap on the wire: a chat message
is a user-typed payload delivered between two participants; a
notification is a template-rendered payload delivered by the platform
to one user.

## 2. Bounded Context

Bounded context: **Chat Thread (per service context)**.

In scope:

- Thread lifecycle (`open → closing → closed → archived`) tied to
  the lifetime of a `trip`, `food_order`, or `delivery` aggregate.
- Participants (1:1 or 1:few) with role-aware permissions
  (`rider`, `driver`, `customer`, `courier`, `restaurant_staff`,
  `support_agent`).
- Message persistence, including text and attachment references.
- Real-time delivery over WebSocket with Redis Pub/Sub cross-replica
  fan-out.
- Read state, typing indicators, presence.
- System messages auto-generated from trip / order / delivery state
  transitions ("driver has arrived", "order is being prepared",
  "courier is on the way").
- Attachment references (the bytes live in `file-service`; the chat
  thread holds metadata + the link).
- Moderation (report, block, profanity filter, abuse signal).
- Offline handoff: when the recipient is not connected via
  WebSocket, emit `chat.message.offline_delivery_required.v1` and
  `notification-service` sends a push.
- Phone-number masking: the platform never reveals the recipient's
  raw phone number in the chat thread; calls go through a
  number-masking relay owned by `customer-service` (Phase 7.7+).

Out of scope (explicitly):

- **System notifications** — `notification-service` (push, SMS,
  email, WhatsApp templates).
- **Support conversations** — owned by `admin-service` (support
  module). A future `support_chat` thread kind is reserved but
  v1 ships `trip_chat`, `food_order_chat`, `delivery_chat` only.
- **Trip / order / delivery state machines** — `trip-service`,
  `food-order-service`, `courier-service` (chat-service **reads**
  their events and writes system messages, but does not own the
  state).
- **File bytes** — `file-service`. Chat holds metadata + the
  `file_id`; upload itself is delegated.
- **Voice / phone calls** — `customer-service` (number-masking
  relay). Chat-service exposes a "call" affordance that delegates.
- **End-to-end encryption** — planned for v2; v1 stores messages
  encrypted at rest (`pgcrypto`).
- **Marketing / promotional chat** — owned by
  `pricing-service` (promotion).

## 3. Responsibilities

- Maintain `chat.threads`, `chat.participants`, `chat.messages`
  (partitioned by month), `chat.message_attachments`,
  `chat.read_states`, `chat.moderation_reports`,
  `chat.blocked_users`, `chat.outbox`, `chat.inbox`.
- Provide REST endpoints under `/v1/chat/...` for thread
  discovery, history pagination, message send, attachment upload,
  read receipt, report, and block.
- Provide the WebSocket endpoint `WS /v1/chat/ws?token=<jwt>` for
  bidirectional real-time messaging.
- Consume trip / food-order / delivery events and auto-create the
  corresponding chat thread at the right state transition
  (`ride.request.matched.v1`, `food.order.accepted.v1`,
  `delivery.courier.assigned.v1`).
- Consume the same events to write **system messages** that
  appear in the thread ("driver has arrived",
  "order is being prepared", "courier picked up your food",
  "your trip is complete").
- Close the thread on the corresponding terminal event
  (`trip.completed.v1`, `trip.cancelled.v1`,
  `food.order.delivered.v1`, `food.order.cancelled.v1`,
  `delivery.completed.v1`).
- Publish `chat.thread.created.v1`, `chat.message.sent.v1`,
  `chat.message.read.v1`, `chat.attachment.shared.v1`,
  `chat.message.reported.v1`, `chat.message.moderated.v1`,
  `chat.message.offline_delivery_required.v1`,
  `chat.thread.closed.v1`.
- Honor rate limits per participant (`chat.rate_limit.messages_per_minute`),
  per-thread (`chat.rate_limit.thread_messages_per_minute`), and
  the global `chat.spam_detection` profile.
- Honor the user's "do not disturb" window via
  `configuration-service.chat.quiet_hours.{user_id}`; offline
  delivery is suppressed during quiet hours unless the message is
  from a system actor or marked urgent.
- Track presence in Redis (TTL 60s, refreshed by the WebSocket
  ping/pong).
- Cooperate with `notification-service` for offline push delivery.
- Cooperate with `admin-service` (support module) for moderation
  escalation: every `chat.message.reported.v1` opens a ticket
  when the report category is `abuse` or `safety`.
- Cooperate with `fraud-risk-service`: every report produces an
  abuse signal the scoring model consumes.
- Cooperate with `audit-service`: every state transition and
  moderation action is appended to the immutable audit chain.

## 4. Explicitly NOT Owned

- **System-to-user notifications** — `notification-service`.
- **Trip / food-order / delivery state** — `trip-service`,
  `food-order-service`, `courier-service`.
- **File storage** — `file-service` (chat holds the link, not the
  bytes).
- **Voice / phone** — `customer-service` (number masking).
- **User identity claims** — `identity-service` (we read `sub`).
- **Search over chat history** — `search-service` (consumes
  `chat.message.sent.v1` and indexes for compliance / discovery).
- **Reporting dashboards** — `reporting-service` (consumes all
  `chat.*.v1` for analytics).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer app | system | read own threads; write own messages; report; block |
| Driver app | system | read trip thread; write own messages; report; block |
| Courier app | system | read delivery thread; write own messages; report; block |
| Restaurant back-office | system | read food-order thread; write own messages; report |
| `trip-service` | system | producer of `trip.*.v1` events that create / close trip threads |
| `food-order-service` | system | producer of `food.order.*.v1` events that create / close food-order threads |
| `courier-service` | system | producer of `delivery.*.v1` events that create / close delivery threads |
| `notification-service` | system | consumer of `chat.message.offline_delivery_required.v1` |
| `admin-service` (support) | system | consumer of `chat.message.reported.v1` for moderation |
| `fraud-risk-service` | system | consumer of `chat.message.reported.v1` for abuse scoring |
| `audit-service` | system | consumer of all `chat.*.v1` |
| `reporting-service` | system | consumer of all `chat.*.v1` |
| `search-service` | system | consumer of `chat.message.sent.v1` (admin-search only) |
| `configuration-service` | system | provider of `configuration.updated.v1` |
| Operations (admin) | human | read with reason; force-close; mute; ban |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — resolve `sub` → profile (display name, locale)
  for the thread participant label — SLO 99.95% — circuit breaker: yes.
- `customer-service` / `driver-service` / `courier-service` /
  `restaurant-service` — read participant profile + active-context
  membership check — SLO 99.95% — circuit breaker: yes.
- `file-service` — request a pre-signed upload URL for attachments —
  SLO 99.9% — circuit breaker: yes.
- `configuration-service` — read chat config (rate limits,
  retention, quiet hours) — SLO 99.95% — circuit breaker: yes.
- `notification-service` — request offline push
  (also reachable via event — REST is the synchronous path for
  urgent messages) — SLO 99.9% — circuit breaker: yes.

### Asynchronous (events consumed)

- `ride.request.matched.v1` from `trip-service` — create `trip_chat`
  thread — duplicate handling: inbox dedup.
- `trip.arrived.v1` from `trip-service` — write system message
  "driver has arrived".
- `trip.started.v1` from `trip-service` — write system message
  "trip started".
- `trip.completed.v1` from `trip-service` — close `trip_chat`
  thread + write system message "trip complete".
- `trip.cancelled.v1` from `trip-service` — close thread.
- `food.order.accepted.v1` from `food-order-service` — create
  `food_order_chat` thread.
- `food.order.preparing.v1` from `food-order-service` — write system
  message "order is being prepared".
- `food.order.ready.v1` from `food-order-service` — write system
  message "order is ready for pickup".
- `food.order.delivered.v1` from `food-order-service` — close
  thread.
- `food.order.cancelled.v1` from `food-order-service` — close
  thread.
- `delivery.courier.assigned.v1` from `courier-service` — create
  `delivery_chat` thread.
- `delivery.pickup.v1` from `courier-service` — write system message
  "courier picked up your order".
- `delivery.completed.v1` from `courier-service` — close thread.
- `delivery.cancelled.v1` from `courier-service` — close thread.
- `configuration.updated.v1` from `configuration-service` —
  reload config.

## 7. Technology Assumptions

- Runtime: **Go 1.25.x** (`net/http` + `chi` + `coder/websocket`).
- Database: PostgreSQL 19, per-service schema `chat`.
- Cache: Redis 8 (per-service) for presence, typing indicators,
  rate-limit counters, Pub/Sub fan-out.
- Event broker: Kafka (with transactional outbox).
- WebSocket: native `nhooyr.io/websocket` (now
  `coder/websocket`) for fan-out; Redis Pub/Sub for cross-replica
  delivery.
- Migrations: `golang-migrate v4`.
- API contract: OpenAPI 3.1 generated from code; contract tests
  with Pact.

## 8. Database Ownership

- Schema: `chat` (one schema, owned exclusively by this service).
- Migrations: `services/chat-service/migrations/`
  (versioned, forward-only).
- Soft delete: **yes** on `chat.messages` and
  `chat.moderation_reports`; **no** on `chat.threads` (the thread
  has a `state` column that captures closure).
- Partitioning: yes — `chat.messages` is range-partitioned by
  `created_at` (monthly partitions; default retention 30d after
  thread close, configurable up to 7y for support threads).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/chat/threads | bearer (own) | list threads for the current user |
| GET | /v1/chat/threads/{id} | bearer (participant) | read thread metadata |
| GET | /v1/chat/threads/{id}/messages | bearer (participant) | paginate history (`cursor`, `limit`) |
| POST | /v1/chat/threads/{id}/messages | bearer (participant) | send a message |
| POST | /v1/chat/threads/{id}/messages/{msg_id}/read | bearer (participant) | mark read |
| POST | /v1/chat/threads/{id}/typing | bearer (participant) | typing-indicator heartbeat (no persistence) |
| POST | /v1/chat/threads/{id}/attachments | bearer (participant) | register an attachment (delegates upload to `file-service`) |
| POST | /v1/chat/threads/{id}/report | bearer (participant) | report a message |
| POST | /v1/chat/users/{user_id}/block | bearer (own) | user-level block |
| DELETE | /v1/chat/users/{user_id}/block | bearer (own) | unblock |
| GET | /v1/chat/users/{user_id}/blocked | bearer (own) | list blocked |
| POST | /v1/chat/threads/{id}/close | bearer (system) | force-close (admin or service token) |
| WS | /v1/chat/ws?token=... | JWT in query | bidirectional real-time |
| GET | /v1/chat/health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `chat.thread.created.v1` | thread auto-created from service event | `notification-service`, `audit-service`, `reporting-service` |
| `chat.thread.closed.v1` | thread closed on terminal event | `notification-service`, `audit-service`, `reporting-service` |
| `chat.message.sent.v1` | every accepted message | `audit-service`, `reporting-service`, `search-service` (admin-only) |
| `chat.message.read.v1` | a participant marks read | `reporting-service` |
| `chat.attachment.shared.v1` | an attachment is registered | `audit-service`, `reporting-service`, `fraud-risk-service` (abuse signal) |
| `chat.message.reported.v1` | a participant reports a message | `admin-service` (support), `fraud-risk-service`, `audit-service` |
| `chat.message.moderated.v1` | admin hides / removes a message | `reporting-service`, `audit-service` |
| `chat.message.offline_delivery_required.v1` | recipient is not on WebSocket | `notification-service` (push), `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `ride.request.matched.v1` | `trip-service` | create `trip_chat` thread with [rider, driver] | idempotent by `request_id` (UNIQUE on `context_id`) |
| `trip.arrived.v1` | `trip-service` | system message | write system message in the thread |
| `trip.started.v1` | `trip-service` | system message | write system message |
| `trip.completed.v1` | `trip-service` | close thread + final system message | set `state = closed`; write "trip complete"; start retention timer |
| `trip.cancelled.v1` | `trip-service` | close thread | set `state = closed`; reason text |
| `food.order.accepted.v1` | `food-order-service` | create `food_order_chat` thread with [customer, restaurant_staff] | idempotent by `order_id` |
| `food.order.preparing.v1` | `food-order-service` | system message | write "order is being prepared" |
| `food.order.ready.v1` | `food-order-service` | system message | write "order is ready for pickup" |
| `food.order.delivered.v1` | `food-order-service` | close thread | set `state = closed` |
| `food.order.cancelled.v1` | `food-order-service` | close thread | set `state = closed` |
| `delivery.courier.assigned.v1` | `courier-service` | create `delivery_chat` thread with [customer, courier] | idempotent by `delivery_id` |
| `delivery.pickup.v1` | `courier-service` | system message | write "courier picked up your order" |
| `delivery.completed.v1` | `courier-service` | close thread | set `state = closed` |
| `delivery.cancelled.v1` | `courier-service` | close thread | set `state = closed` |
| `configuration.updated.v1` | `configuration-service` | reload config | cache invalidation |

(Full contracts in `INTEGRATION.md`.)

## 12. External Integrations

- None directly. All provider routing goes through other services.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `chat.websocket.ping_interval_seconds` | int | configuration-service | default 25 |
| `chat.websocket.idle_timeout_seconds` | int | configuration-service | default 90 |
| `chat.rate_limit.messages_per_minute` | int | configuration-service | default 30 (per participant) |
| `chat.rate_limit.thread_messages_per_minute` | int | configuration-service | default 60 (per thread) |
| `chat.attachment.max_bytes` | int | configuration-service | default 25 MB |
| `chat.attachment.allowed_mime` | array | configuration-service | `["image/jpeg", "image/png", "image/webp"]` for v1 |
| `chat.profanity.enabled` | bool | configuration-service | default true |
| `chat.profanity.allow_override` | array | configuration-service | admin-managed allow-list of words |
| `chat.spam.burst_threshold` | int | configuration-service | default 10 in 10s |
| `chat.retention.days.{thread_kind}` | int | configuration-service | default 30d for `trip_chat`/`food_order_chat`/`delivery_chat` |
| `chat.presence.ttl_seconds` | int | configuration-service | default 60 |
| `chat.system_message.locale` | string | configuration-service | default `en`; supports `ar` (Cairo HQ), `fr`, `ur` |
| `chat.quiet_hours.{user_id}` | object | configuration-service | `{start, end}` user-level (opt-in) |
| `chat.moderation.escalate_on` | array | configuration-service | `["abuse", "safety", "illegal"]` |
| `chat.offline_delivery.fanout_ms` | int | configuration-service | default 1500; throttle before push |

## 14. Security

- **AuthN**: Bearer JWT validated at gateway; WebSocket connects
  authenticate with the JWT in the query string (`token=<jwt>`)
  and the server closes the socket on token-expiry / invalid
  signature.
- **AuthZ**: a participant can read / write only the threads they
  are a member of; the membership is created at thread bootstrap
  and frozen (no participant joins a thread after creation in v1).
  Admin override requires `X-Audit-Reason` and is recorded in
  the audit log.
- **Phone-number masking**: the chat payload MUST NOT contain raw
  phone numbers. The "call" affordance (when added) goes through
  `customer-service` number-masking.
- **PII**: message bodies are PII. Stored encrypted at rest
  (`pgcrypto`); readable only via the chat-service REST API or
  the participant WebSocket.
- **Rate limits**: per-user and per-thread; configurable via
  `configuration-service`; rejects with `429 RATE_LIMITED`.
- **Profanity filter**: yes (configurable word list); reports of
  bypass are forwarded to `fraud-risk-service` and
  `admin-service` (support).
- **Abuse signal**: every `chat.message.reported.v1` is a feature
  fed to the `fraud-risk-service` scoring model.
- **WebSocket origin check**: yes — the upgrade is rejected if
  the `Origin` header is not in the allow-list
  (`chat.websocket.allowed_origins`).
- **Admin endpoints**: `/admin/v1/chat/...` for thread inspection,
  force-close, mute, ban; requires `chat.admin` or
  `platform.support` (with `X-Audit-Reason`).

## 15. Observability

- **Logs**: JSON to stdout with `correlation_id`, `trace_id`,
  `thread_id`, `participant_id`, `user_id`, `thread_kind`,
  `event`, `latency_ms`.
- **Metrics**:
  - RED (per route): `chat_http_requests_total{route,status}`,
    `chat_http_request_seconds{route}` (histogram).
  - WebSocket: `chat_websocket_connections{state}` (gauge:
    `open`/`closing`/`closed`), `chat_websocket_messages_total{direction,kind}`,
    `chat_websocket_bytes_total{direction}`,
    `chat_websocket_reconnect_total{reason}`.
  - Business: `chat_threads_created_total{kind}`,
    `chat_threads_closed_total{kind,reason}`,
    `chat_messages_sent_total{kind,thread_kind,sender_role}`,
    `chat_messages_read_seconds` (histogram),
    `chat_attachments_shared_total{mime}`,
    `chat_moderation_reports_total{reason}`,
    `chat_offline_deliveries_total{thread_kind}`,
    `chat_thread_lifetime_seconds{thread_kind}` (histogram),
    `chat_message_s_end_to_end_seconds{thread_kind}` (histogram
    send → fan-out → recipient ack).
- **Traces**: OpenTelemetry; root span per HTTP / WS frame;
  child spans for `pgx.Query`, `redis.PubSub.Publish`,
  `kafka.Produce`; the WS frame is the root span for the inbound
  side and the outbound fan-out is a child.
- **Health**: `/health`, `/ready` (DB + Kafka + Redis +
  downstream reachability for at least the participant-lookup
  service), `/started`.
- **Alerts**:
  - `chat_websocket_connections` drop > 30% in 5m.
  - `chat_messages_sent_total` rate flat for 10m (fan-out stuck).
  - `chat_offline_deliveries_total` > 30% of `chat_messages_sent_total`
    (push channel may be down).

## 16. Scalability

- **Replicas**: default 6 (Go; ~30 MB per pod); HPA on
  `chat_websocket_connections` per replica (> 5 000) and CPU.
- **Hot path**: `POST /v1/chat/threads/{id}/messages` is the
  dominant write path; it accepts, persists in the same
  PostgreSQL transaction as the outbox row, publishes to Kafka
  via the outbox dispatcher, and publishes to Redis Pub/Sub for
  fan-out. P99 target ≤ 80 ms.
- **WebSocket fan-out**: each replica keeps a `chat:conn:{user_id}`
  Redis set of (user → replica-id) bindings. Inbound message
  publishes to `chat:thread:{thread_id}` Redis Pub/Sub channel;
  every replica subscribes to that channel and forwards to the
  matching local connection if any participant is local. Cross-replica
  fan-out is at most one Redis Pub/Sub publish and one Redis read
  per replica.
- **Read replica**: one read replica in the region for
  `GET /v1/chat/threads/{id}/messages` history pagination.

## 17. Local Development

```bash
docker compose up chat-service postgres kafka redis
go run ./cmd/chat-service
```

Seed: a `trip_chat` thread with 3 messages between a rider and
driver; a `food_order_chat` thread with 5 messages; a
`delivery_chat` thread with 2 messages. Configurable
`chat.seed.enabled` defaults to true in dev and false in
production.

Tests: unit (thread bootstrap, idempotency, profanity filter,
rate-limit, attachment metadata), integration (Kafka in,
WebSocket fan-out, Redis Pub/Sub, file-service delegation),
contract (pact with `trip-service`, `food-order-service`,
`courier-service`, `notification-service`).

## 18. Deployment

- **Image**: `registry.uber.io/chat-service:<git-sha>`.
- **Replicas**: 6 (HPA to 40).
- **Resource limits**: `cpu: 500m–1500m`, `memory: 512Mi–1.5Gi`.
- **Migrations**: K8s Job before rolling deploy.
- **WebSocket gateway**: the api-gateway exposes
  `wss://api.<region>.uber.io/v1/chat/ws` and proxies to the
  chat-service via `linkerd` mTLS; no sticky session needed because
  each WebSocket frame is stateless from the gateway's perspective
  once authenticated.


---

## Appendix A — Thread kinds (Phase 7.7 v1)

| Kind | Created by | Closed by | Participants (default) | Retention |
|---|---|---|---|---|
| `trip_chat` | `ride.request.matched.v1` | `trip.completed.v1` / `trip.cancelled.v1` | `[rider, driver]` | 30d (configurable) |
| `food_order_chat` | `food.order.accepted.v1` | `food.order.delivered.v1` / `food.order.cancelled.v1` | `[customer, restaurant_staff]` | 30d (configurable) |
| `delivery_chat` | `delivery.courier.assigned.v1` | `delivery.completed.v1` / `delivery.cancelled.v1` | `[customer, courier]` | 30d (configurable) |
| `support_chat` | *(future — v2)* | — | — | 7y |
| `merchant_chat` | *(future — v2)* | — | — | 90d |

## Appendix B — Relationship to other services

| Service | Relationship |
|---|---|
| `trip-service` | producer of `trip.*.v1` events that create / close the trip thread |
| `food-order-service` | producer of `food.order.*.v1` events that create / close the food-order thread |
| `courier-service` | producer of `delivery.*.v1` events that create / close the delivery thread |
| `notification-service` | consumer of `chat.message.offline_delivery_required.v1` (push fallback) |
| `admin-service` (support) | consumer of `chat.message.reported.v1` (opens support ticket) |
| `fraud-risk-service` | consumer of `chat.message.reported.v1` (abuse signal) |
| `search-service` | consumer of `chat.message.sent.v1` (admin-only compliance search) |
| `reporting-service` | consumer of every `chat.*.v1` (analytics + retention sweeps) |
| `audit-service` | consumer of every `chat.*.v1` (immutable chain) |
| `configuration-service` | provider of `configuration.updated.v1` |
| `identity-service` | resolver of `sub` → profile |
| `customer-service` / `driver-service` / `courier-service` / `restaurant-service` | participant profile lookup + active-context membership check |
| `file-service` | attachment byte storage (chat holds the link only) |

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
- [`PLAN.md`](./PLAN.md) — implementation tracker

### Related services

- **Depends on**: [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`courier-service`](../courier-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`notification-service`](../notification-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`search-service`](../search-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — `trip_chat` thread created on `ride.request.matched.v1`, closed on `trip.completed.v1`
- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — `food_order_chat` thread created on `food.order.accepted.v1`, closed on `food.order.delivered.v1`
- [`../../workflows/COURIER_WORKFLOWS.md`](../../workflows/COURIER_WORKFLOWS.md) — `delivery_chat` thread created on `delivery.courier.assigned.v1`, closed on `delivery.completed.v1`
- [`../../workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) — SOS and report flows include a chat-message escalation path