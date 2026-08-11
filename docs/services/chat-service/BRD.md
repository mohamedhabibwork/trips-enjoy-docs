# chat-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business requirements for `chat-service` — the
platform's in-app real-time messaging layer bound to a service
context (trip, food order, delivery). It is the contract between the
product team (defining the rider / driver / customer / courier /
restaurant experience) and the engineering team (implementing the
service). The decisions in this document feed the per-service
implementation in [`PLAN.md`](./PLAN.md).

## 2. Business Context

The platform operates two consumer products — ride-hailing and food
delivery — on one shared foundation. In both products, two human
actors share a transient service context: a rider and a driver during
a trip; a customer and a restaurant while an order is being
prepared; a customer and a courier during a delivery. Today the only
in-product communication channel is **calling the other party's real
phone number**, which exposes PII, defeats the platform's safety
posture, and creates an off-platform conversation the support team
cannot audit.

`chat-service` introduces a **first-party, in-app, real-time chat
thread** between the two participants. The thread is created at the
start of the service context, lives for the duration of the context,
and is archived (not deleted) for a configurable retention window
after the context ends. The chat is the **only** channel the
platform surfaces for participant-to-participant communication during
the context: no raw phone numbers are ever shown to either party,
and the platform captures the full message history for safety,
support, and compliance.

The chat-service is a **core differentiator** for the platform's
safety posture and for the customer experience ("I can reach my
driver without giving out my phone number, and if there's a problem
the support team can see what was said").

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a real-time, in-app chat thread between the two participants of every active service context | 100% of trips, food orders, and deliveries have a chat thread by the time the context starts (p99 ≤ 2 s from `ride.request.matched.v1`) |
| BR--002 | Eliminate phone-number exposure as the default communication channel | ≤ 1% of participants request a phone call (down from baseline ~15%) |
| BR--003 | Deliver a chat message to an online recipient within 200 ms p99 | `chat_message_send_to_delivery_seconds` p99 ≤ 200 ms |
| BR--004 | Fall back to a push notification when the recipient is offline, so the conversation continues asynchronously | 100% of messages to offline recipients trigger a push within 1.5 s |
| BR--005 | Capture the full message history for the duration of the context + the configured retention window | 100% of accepted messages are persisted in `chat.messages` before the write API returns |
| BR--006 | Surface safety-relevant reports to the support and fraud-risk teams | Every `chat.message.reported.v1` produces a `support_ticket` and a fraud-risk feature within 60 s |
| BR--007 | Auto-write system messages that mirror the trip / order / delivery state machine | 100% of state-transition events that have a chat-system-message mapping result in a system message in the thread |
| BR--008 | Support multilingual participants with locale-aware system messages | System messages rendered in the recipient's locale (default `en`; supports `ar`, `fr`, `ur`) |
| BR--009 | Allow the participant to attach a photo (delivery dispute, item damage, etc.) | Attachment upload → file-service delegation in ≤ 500 ms p99 |
| BR--010 | Provide an admin / support surface for moderation (read with reason, force-close, mute, ban) | 100% of admin actions recorded in the immutable audit chain |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Riders | end user | chat with driver during trip; share location |
| Drivers | end user | chat with rider during trip; ask for directions |
| Customers | end user | chat with restaurant; chat with courier |
| Couriers | end user | chat with customer; report access issues |
| Restaurant staff | end user | chat with customer about substitutions, prep issues |
| Support agents | operations | read thread history for ticket investigation |
| Trust & Safety | operations | receive abuse / safety reports; force-close; mute; ban |
| Product (Rides) | product | rider / driver experience |
| Product (Food) | product | customer / restaurant / courier experience |
| Engineering (chat-service) | engineering | service owner |
| Compliance / Legal | governance | retention; PII handling; data-subject deletion requests |

## 5. Actors / Personas

- **Rider** — the customer who booked the trip. Speaks first ("I'm
  at the blue door"). Reads fast.
- **Driver** — accepts the trip and drives to the pickup. Wants to
  ask "are you at the gate?" with one tap. Reads fast.
- **Customer (food)** — places a food order. Asks the restaurant
  for substitutions, ETA, or to fix a wrong item. Reads at normal
  pace.
- **Restaurant staff** — manages the order in the kitchen. Wants to
  push the customer to a substitute when an item is out. Reads at
  kitchen pace.
- **Courier** — picks up the food. Wants to tell the customer "I'm
  at the gate" or "I can't find the building". Reads fast.
- **Support agent** — investigates a ticket. Reads with `reason`.
- **Trust & Safety** — investigates an abuse report. Reads with
  `reason`, force-closes the thread, mutes or bans a participant.

## 6. Business Capabilities

- Auto-create a chat thread when a service context begins.
- Auto-close the thread when the service context ends (terminal
  event).
- Bidirectional real-time message delivery over WebSocket.
- Read history with cursor-based pagination.
- Read receipts (per participant).
- Typing indicators (no persistence; ephemeral in Redis).
- Presence (online / last seen) per participant.
- Attachments (image only in v1; bytes in `file-service`).
- User-level block (no future threads between the two participants).
- Per-message report (with reason categories).
- Admin / support surface: read with reason, force-close, mute, ban.
- Auto-generated system messages from the state machines of
  `trip-service`, `food-order-service`, `courier-service`.
- Offline push fallback through `notification-service`.
- Locale-aware system messages.
- Multi-region replication (chat in `eu-west` and `ap-southeast`).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The platform MUST auto-create a chat thread for every trip, food order, and delivery at the moment the context begins. | MUST | Product |
| BR--011 | The platform MUST NOT expose the raw phone number of either participant in the chat UI or chat payload. | MUST | Trust & Safety |
| BR--012 | The platform MUST deliver an accepted message to an online recipient within 200 ms p99 from the send API. | MUST | Product |
| BR--013 | The platform MUST fall back to a push notification when the recipient is offline, so the conversation continues asynchronously. | MUST | Product |
| BR--014 | The platform MUST persist every accepted message in `chat.messages` before the write API returns. | MUST | Compliance |
| BR--015 | The platform MUST close the chat thread on the corresponding terminal event (`trip.completed.v1`, `food.order.delivered.v1`, `delivery.completed.v1`). | MUST | Product |
| BR--016 | The platform MUST auto-write system messages that mirror the state machine of the underlying service context. | MUST | Product |
| BR--017 | The platform MUST allow a participant to report a message with a reason category. | MUST | Trust & Safety |
| BR--018 | The platform MUST open a support ticket for every report with category `abuse`, `safety`, or `illegal`. | MUST | Support |
| BR--019 | The platform MUST allow a participant to attach an image to a message (delegated to `file-service`). | SHOULD | Product |
| BR--020 | The platform MUST allow a participant to block another user from future threads. | SHOULD | Trust & Safety |
| BR--021 | The platform MUST honour the participant's "do not disturb" window for offline delivery unless the message is from a system actor or marked urgent. | SHOULD | Product |
| BR--022 | The platform MUST render system messages in the recipient's locale (default `en`; supports `ar`, `fr`, `ur`). | SHOULD | Product |
| BR--023 | The platform MUST provide an admin / support surface to read with reason, force-close, mute, or ban. | MUST | Support |
| BR--024 | The platform MUST NOT allow a new participant to join a thread after its creation (no late-join in v1). | MUST | Trust & Safety |
| BR--025 | The platform MUST rate-limit message sending per participant and per thread to prevent spam. | MUST | Trust & Safety |
| BR--026 | The platform MUST encrypt message bodies at rest (`pgcrypto`). | MUST | Compliance |
| BR--027 | The platform MUST support data-subject deletion requests: hard-delete all `chat.messages` rows for a user, redacted to `[deleted by request]`. | MUST | Compliance (GDPR) |
| BR--028 | The platform MUST publish every state transition, message send, report, and moderation action as a `chat.*.v1` event for downstream analytics + audit. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A `trip_chat` thread is bound to a single `trip_id`; only the trip's rider and driver may participate. | Membership is fixed at thread creation. |
| BR--031 | A `food_order_chat` thread is bound to a single `food_order_id`; only the customer and the assigned restaurant staff may participate. | The restaurant staff is the operator who accepted the order. |
| BR--032 | A `delivery_chat` thread is bound to a single `delivery_id`; only the customer and the assigned courier may participate. | The courier is the one who accepted the dispatch. |
| BR--033 | A closed thread is read-only (no new messages, no new attachments). Read history remains available until the retention sweep purges it. | The `state` column transitions `open → closing → closed → archived`. |
| BR--034 | A participant who is blocked at the user level MUST NOT be added to a new thread; the system message in the existing thread will say "[participant left]". | The block persists across threads. |
| BR--035 | A reported message is hidden from the recipient (and the reporter's view of the chat) immediately; the message remains in `chat.messages` for the immutable audit chain. | Admin / support can still see the message. |
| BR--036 | The platform displays the recipient's first name only (no surname, no phone, no email). | Reduces impersonation risk. |
| BR--037 | The chat thread auto-closes at most 1 hour after the underlying service context's terminal event (whichever is later). | Allows a final "thanks" exchange after `trip.completed.v1`. |
| BR--038 | All attachments MUST be scanned by `file-service` (ClamAV) before they become visible to the recipient; a pending attachment is shown as "uploading…" to the sender and invisible to the recipient until scan succeeds. | Prevents malware delivery via chat. |

## 9. Assumptions

- The participant has the platform's mobile app installed and
  authenticated with a Keycloak JWT.
- The participant has granted notification permission; offline
  fallback uses the platform's existing push channels
  (`notification-service`).
- The platform's `notification-service` is the trusted channel for
  offline delivery; we do not bypass it with our own APNs / FCM
  credentials.
- `trip-service`, `food-order-service`, `courier-service` continue
  to emit the same state-transition events they emit today; chat
  becomes a new consumer.
- The participant's locale is resolvable via
  `customer-service` / `driver-service` / `courier-service` /
  `restaurant-service` profile lookups.
- The retention window for `trip_chat`, `food_order_chat`,
  `delivery_chat` is 30 days after thread close by default;
  `support_chat` (future) will use a longer window.

## 10. Constraints

- **Privacy / GDPR**: message bodies are PII; data-subject deletion
  requests MUST hard-delete within 30 days.
- **Phone-number masking**: never expose raw phone numbers in chat
  payload.
- **Compliance**: the immutable audit chain (`audit-service`)
  retains the message metadata even after data-subject deletion
  (with the body redacted to `[deleted by request]`).
- **Mobile bandwidth**: keep payloads small; default `messages`
  page size 50; attachment bytes are streamed through `file-service`.
- **Cold-start budget**: thread bootstrap (consume event → persist
  → emit `chat.thread.created.v1`) MUST complete in ≤ 500 ms p99.
- **WebSocket gateway**: the api-gateway already terminates TLS and
  exposes WebSocket; chat-service speaks plain WS / WSS over linkerd
  mTLS internally.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `trip-service` | service | producer of `ride.request.matched.v1`, `trip.*.v1` events |
| `food-order-service` | service | producer of `food.order.*.v1` events |
| `courier-service` | service | producer of `delivery.*.v1` events |
| `notification-service` | service | consumer of `chat.message.offline_delivery_required.v1`; offline push fallback |
| `admin-service` (support) | service | consumer of `chat.message.reported.v1`; moderation |
| `fraud-risk-service` | service | consumer of `chat.message.reported.v1`; abuse signal |
| `identity-service` | service | `sub` → profile |
| `customer-service` / `driver-service` / `courier-service` / `restaurant-service` | service | participant profile + active-context membership check |
| `file-service` | service | attachment byte storage |
| `audit-service` | service | immutable audit chain |
| `reporting-service` | service | analytics + retention sweep |
| `search-service` | service | admin-only compliance search index |
| `configuration-service` | service | `chat.*` config keys |
| Keycloak | provider | JWT issuer |
| Redis 8 | infra | presence, typing, rate-limit, Pub/Sub fan-out |
| Kafka | infra | event bus |
| PostgreSQL 19 | infra | per-service `chat` schema |

## 12. Business Workflows

- **Thread bootstrap on service-context start** — trip / food /
  delivery event → `chat-service` creates the thread, adds the
  participants, emits `chat.thread.created.v1`.
  Details in [`WORKFLOWS.md`](./WORKFLOWS.md) 1.
- **Real-time message send (online recipient)** — sender hits
  `POST /v1/chat/threads/{id}/messages` → persist → outbox → Kafka
  → Pub/Sub → fan-out to recipient's WebSocket. Details in
  [`WORKFLOWS.md`](./WORKFLOWS.md) 2.
- **Offline fallback** — recipient not connected →
  `chat.message.offline_delivery_required.v1` → `notification-service`
  → push. Details in [`WORKFLOWS.md`](./WORKFLOWS.md) 3.
- **Thread close on service-context end** — terminal event →
  `chat-service` writes final system message, sets `state = closed`,
  emits `chat.thread.closed.v1`. Details in
  [`WORKFLOWS.md`](./WORKFLOWS.md) 4.
- **Report and moderation** — participant reports a message →
  message hidden → `chat.message.reported.v1` → `admin-service`
  opens ticket + `fraud-risk-service` adjusts score.
  Details in [`WORKFLOWS.md`](./WORKFLOWS.md) 5.
- **Block** — user blocks another → `chat.blocked_users` →
  no future thread will include the pair. Details in
  [`WORKFLOWS.md`](./WORKFLOWS.md) 6.

## 13. Exception Workflows

- **Recipient WebSocket disconnects mid-message** — chat-service
  retries fan-out via `chat.message.offline_delivery_required.v1`.
- **`file-service` attachment scan fails** — message + attachment
  are persisted in `chat.messages` but `visibility = hidden` until
  the scan completes; the recipient sees "uploading…". Sender gets
  an explicit "scan failed, please retry" surface.
- **Recipient blocks sender mid-thread** — sender sees
  "[participant left]" as a system message; sender's subsequent
  messages return `403 PARTICIPANT_BLOCKED`.
- **`notification-service` is down** — chat falls back to
  `in-app notification centre` only (in-app banner next time the
  recipient opens the app); no SMS / push until `notification-service`
  recovers.
- **WebSocket origin mismatch** — chat-service closes the socket
  with `4403 FORBIDDEN_ORIGIN`; the client app retries with the
  native WebSocket of the api-gateway origin.
- **Thread context aggregate not found** — the upstream
  trip / order / delivery ID is unknown; chat-service logs WARN,
  emits `chat.thread.bootstrap_failed.v1`, and DLQs the event.

## 14. Success Criteria

- 100% of trips, food orders, and deliveries have a chat thread by
  the time the context starts.
- ≤ 1% of participants request a phone call within the first
  90 days of launch.
- `chat_message_send_to_delivery_seconds` p99 ≤ 200 ms in production.
- `chat_message_offline_delivery_seconds` p99 ≤ 1500 ms.
- 100% of state-transition events that have a system-message
  mapping produce a system message in the thread (verified by a
  `reporting-service` reconciliation job).
- Zero "no thread" reports from participants in production
  (measured by `support.ticket.thread_missing`).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Threads created per day (rides + food + delivery) | match the per-day ride + food + delivery count | `chat_threads_created_total{kind}` |
| Mean messages per active thread | ≥ 1.5 (engagement) | `reporting-service` daily rollup |
| Online delivery p99 latency | ≤ 200 ms | `chat_message_send_to_delivery_seconds` |
| Offline delivery p99 latency | ≤ 1500 ms | `chat_message_offline_delivery_seconds` |
| Phone-call requests | ≤ 1% of participants | `customer-service.call.requested` filtered by "during active thread" |
| Reports per 1k threads | ≤ 5 (target) | `chat_moderation_reports_total` |
| Reports resolved by auto-action (block / hide) | ≤ 30% | `admin-service.support.tickets` joined on `chat.*` |
| Push fallback rate | ≤ 30% of messages | `chat_offline_deliveries_total` / `chat_messages_sent_total` |

## 16. Acceptance Criteria

- `trip-service` emits `ride.request.matched.v1` →
  `chat-service` writes the thread, the rider and driver apps see
  the chat surface within 2 s.
- The rider sends "I'm at the blue door" → the driver receives
  the message within 200 ms (online).
- The driver is offline → the rider's message triggers a push
  notification within 1.5 s.
- The driver reports a message with reason `abuse` → the message
  is hidden from both participants; a a ticket opens in
  `admin-service` (support).
- A user blocks another → no future thread includes both.
- The system message "driver has arrived" appears in the chat
  when the trip's `state` transitions to `arrived`.
- The thread auto-closes when the trip's `state` transitions to
  `completed`; the chat surface moves to a "Trip ended" view.
- The retention sweep purges `chat.messages` rows older than
  `chat.retention.days.trip_chat` (default 30d) after thread
  close.
- A data-subject deletion request hard-deletes all of the user's
  message bodies within 30 days; the audit chain retains metadata
  with `[deleted by request]`.