# chat-service — Implementation Plan

> **Phase 7.7 — Cross-Cutting: In-App Chat.** This is a new service
> added in the cross-cutting pass. The implementation runs in
> parallel with the Phase 7 / 7.5 / 7.6 addenda for the existing
> 20 services, and tightly couples to `trip-service`,
> `food-order-service`, `courier-service`, `notification-service`,
> `admin-service`, `fraud-risk-service`.

### Phase 7.7 — In-App Chat (cross-cutting)

| Aspect | Value |
|---|---|
| **Phase** | 7.7 (Weeks 45–46, 8 sprints) |
| **Service role** | New 21st active service — in-app chat kernel |
| **Criticality** | T1 (99.95% SLO) — chat is the primary rider↔driver and customer↔{restaurant,courier} communication channel; phone numbers must not be exposed |
| **DB schema** | `chat` (PostgreSQL 19, range-partitioned `chat.messages` by month) |
| **Transport** | WebSocket (`WS /v1/chat/ws`) for online fan-out; REST for offline + admin |
| **Fan-out** | Redis Pub/Sub (`chat:thread:{id}`) for cross-replica delivery |
| **Scope** | Threads: `trip_chat`, `food_order_chat`, `delivery_chat` (1:1 between the two service-context participants) |
| **Languages** | Go 1.25 + `net/http` + `chi` + `coder/websocket` (per [`TECH.md` 1](./TECH.md)) |

This section is the canonical per-service view of the Phase 7.7 plan.
The cross-service view (consumer services that wire in) lives in
each of their `PLAN.md` under their own `Phase 7.7` block. The
shared hub (event catalog, fan-out contract, integration contract)
lives in [`INTEGRATION.md`](./INTEGRATION.md), [`ERD.md`](./ERD.md),
and [`TECH.md`](./TECH.md).

## 1. Goals

- Provide a real-time, in-app, 1:1 chat thread between the two
  participants of every trip / food order / delivery context.
- Eliminate phone-number exposure as the default communication
  channel (BR--002 / BR--011).
- Auto-create / auto-close the thread on the underlying service
  context's lifecycle events.
- Deliver an accepted message within 200 ms p99 to an online
  recipient (NFR--001) and 1 500 ms p99 to an offline recipient
  (NFR--002).
- Provide a moderation surface (report, hide, remove, mute, ban)
  that integrates with `admin-service` (support) and
  `fraud-risk-service`.

## 2. Tasks

| ID | Task | Sprint | Notes |
|----|------|--------|-------|
| T-CHAT-001 | Bootstrap chat-service repo (Go 1.25 + chi + coder/websocket + pgx) | 7.7.1 | new repo `chat-service` |
| T-CHAT-002 | Provision PostgreSQL 19 schema `chat` + first 3 monthly partitions of `chat.messages` | 7.7.1 | `golang-migrate` |
| T-CHAT-003 | Implement `chat.threads`, `chat.participants`, `chat.messages`, `chat.message_attachments`, `chat.read_states`, `chat.moderation_reports`, `chat.blocked_users`, `chat.outbox`, `chat.inbox` DDL | 7.7.1 | per [`ERD.md`](./ERD.md) |
| T-CHAT-004 | Implement `pgcrypto` encryption on `chat.messages.body` | 7.7.1 | DATA--002 |
| T-CHAT-005 | Implement outbox dispatcher (transactional Kafka producer) | 7.7.1 | per platform pattern |
| T-CHAT-006 | Implement inbox + dedup | 7.7.1 | |
| T-CHAT-007 | Implement REST endpoints `GET /v1/chat/threads`, `GET /v1/chat/threads/{id}`, `GET /v1/chat/threads/{id}/messages`, `POST /v1/chat/threads/{id}/messages`, `POST /v1/chat/threads/{id}/messages/{msg_id}/read`, `POST /v1/chat/threads/{id}/typing` | 7.7.1 | per [`INTEGRATION.md`](./INTEGRATION.md) |
| T-CHAT-008 | Implement WebSocket endpoint `WS /v1/chat/ws` (coder/websocket + origin check + JWT in query) | 7.7.1 | FR--016–019 |
| T-CHAT-009 | Implement Redis Pub/Sub fan-out: `chat:thread:{id}` channel + per-replica connection registry | 7.7.1 | NFR--001 |
| T-CHAT-010 | Implement offline fallback: detect offline recipient → emit `chat.message.offline_delivery_required.v1` | 7.7.1 | NFR--002 |
| T-CHAT-011 | Implement thread bootstrap consumers: `ride.request.matched.v1` → trip_chat, `food.order.accepted.v1` → food_order_chat, `delivery.courier.assigned.v1` → delivery_chat | 7.7.2 | workflow 1 |
| T-CHAT-012 | Implement system-message consumers: `trip.arrived.v1`, `trip.started.v1`, `food.order.preparing.v1`, `food.order.ready.v1`, `delivery.pickup.v1` | 7.7.2 | FR--060–062 |
| T-CHAT-013 | Implement close consumers: `trip.completed.v1`, `trip.cancelled.v1`, `food.order.delivered.v1`, `food.order.cancelled.v1`, `delivery.completed.v1`, `delivery.cancelled.v1` | 7.7.2 | workflow 4 |
| T-CHAT-014 | Implement i18n: en + ar + fr + ur for system messages | 7.7.2 | locale = recipient's profile |
| T-CHAT-015 | Implement rate limit (per user + per thread, configurable) | 7.7.2 | FR per SRS |
| T-CHAT-016 | Implement profanity filter (configurable word list) | 7.7.3 | |
| T-CHAT-017 | Implement attachments: `POST /v1/chat/threads/{id}/attachments` + scan-status webhook integration with `file-service` | 7.7.3 | workflow 7 |
| T-CHAT-018 | Implement report: `POST /v1/chat/threads/{id}/report` + emit `chat.message.reported.v1` + set message visibility = hidden | 7.7.3 | workflow 5 |
| T-CHAT-019 | Implement block: `POST /v1/chat/users/{user_id}/block`, `DELETE`, `GET /v1/chat/users/{user_id}/blocked` + integrate with bootstrap-time check | 7.7.3 | workflow 6 |
| T-CHAT-020 | Implement admin endpoints (`/admin/v1/chat/...`): read, force-close, hide, remove, mute, ban, GDPR sweep | 7.7.4 | FR--070–074, FR--080–082 |
| T-CHAT-021 | Implement GDPR sweep job (nightly + admin-triggered) | 7.7.4 | FR--080–082 |
| T-CHAT-022 | Implement retention sweep job (drop partitions older than `chat.retention.days.{kind}`) | 7.7.4 | DATA--003, retention |
| T-CHAT-023 | Wire chat-service to the api-gateway: `wss://api.<region>.uber.io/v1/chat/ws` | 7.7.4 | via linkerd |
| T-CHAT-024 | Update `SUPER_ADMIN` preset (1 × `platform.super_admin` + 21 × `<service>.admin`) to include `chat.admin` | 7.7.4 | TECH.md 10 |
| T-CHAT-025 | Wire chat into `trip-service` BRD + INTEGRATION + WORKFLOWS (consume `chat.message.sent.v1` for support workflow; link to chat thread from trip detail) | 7.7.5 | rider ↔ driver |
| T-CHAT-026 | Wire chat into `food-order-service` BRD + INTEGRATION + WORKFLOWS | 7.7.5 | customer ↔ restaurant |
| T-CHAT-027 | Wire chat into `courier-service` BRD + INTEGRATION + WORKFLOWS | 7.7.5 | customer ↔ courier |
| T-CHAT-028 | Wire chat into `restaurant-service` INTEGRATION (passive reference) | 7.7.5 | restaurant staff participation |
| T-CHAT-029 | Wire chat into `notification-service` INTEGRATION (consumer of `chat.message.offline_delivery_required.v1`) | 7.7.5 | offline push |
| T-CHAT-030 | Wire chat into `admin-service` INTEGRATION (consumer of `chat.message.reported.v1` → support ticket) | 7.7.5 | moderation |
| T-CHAT-031 | Wire chat into `fraud-risk-service` INTEGRATION (consumer of `chat.message.reported.v1` → abuse signal) | 7.7.5 | abuse scoring |
| T-CHAT-032 | Update architecture docs: `SYSTEM_OVERVIEW.md` (catalog table + capability matrix), `MICROSERVICES_MAP.md` (new row + diagram), `SERVICE_ISOLATION.md`, `DATABASE_ARCHITECTURE.md` (21st schema) | 7.7.6 | master indexes |
| T-CHAT-033 | Update `services/README.md` index, `RECOMMENDATIONS.md` master table, `PLAN_INDEX.md`, `MASTER_PLAN_SUMMARY.md`, `MASTER_TASK.md` | 7.7.6 | master indexes |
| T-CHAT-034 | Add chat event flows to `docs/shared/CONDUCTOR_WORKFLOWS.md` | 7.7.6 | cross-service events |
| T-CHAT-035 | Update `workflows/RIDE_WORKFLOWS.md`, `workflows/FOOD_ORDER_WORKFLOWS.md`, `workflows/COURIER_WORKFLOWS.md` to mention chat handoff | 7.7.6 | end-to-end flows |
| T-CHAT-036 | Add ADR for chat-service: `adrs/0019-chat-service-cross-cutting.md` | 7.7.6 | design decision |
| T-CHAT-037 | Add per-service TECH.md 12 (Deal Kernel) reference — chat-service is NOT a deal participant | 7.7.6 | already in TECH.md 12 |
| T-CHAT-038 | Mobile client integration: rider / driver / customer / courier / restaurant staff SDK updates (call `WS /v1/chat/ws`, render thread, send message, mark read, attachment, report, block) | 7.7.7 | client-side |
| T-CHAT-039 | E2E test: trip end-to-end with chat (rider ↔ driver) | 7.7.7 | Playwright / mobile integration |
| T-CHAT-040 | E2E test: food order end-to-end with chat (customer ↔ restaurant) | 7.7.7 | |
| T-CHAT-041 | E2E test: delivery end-to-end with chat (customer ↔ courier) | 7.7.7 | |
| T-CHAT-042 | E2E test: offline fallback → push delivery | 7.7.7 | |
| T-CHAT-043 | Load test: 200k concurrent WebSocket connections per region | 7.7.7 | NFR--006 |
| T-CHAT-044 | Load test: 20k messages/sec sustained per region | 7.7.7 | NFR--007 |
| T-CHAT-045 | GDPR compliance review + sign-off | 7.7.8 | BR--027 |
| T-CHAT-046 | Trust & Safety review + sign-off | 7.7.8 | BR--011, BR--017–018 |
| T-CHAT-047 | Rollout: 10% canary → 50% → 100% per region | 7.7.8 | measured by `chat_threads_created_total{kind}` |

## 3. Compatibility Window

For at least six calendar months from 2026-08-12:

- The bootstrap events continue to be emitted by the upstream
  services (`trip-service`, `food-order-service`, `courier-service`).
- The `chat.*.v1` event topics are stable and versioned.
- A consumer that does not yet know about chat can ignore the
  `chat.thread.created.v1` event — its absence does not break the
  upstream context.
- The `notification-service` offline-push consumer is **mandatory**
  at rollout: without it, offline messages are not delivered. The
  rollout will block until the consumer is deployed.

## 4. Out of Scope (Phase 7.7 v1)

- **`support_chat`** thread kind — owned by `admin-service` (support module) for now; future.
- **`merchant_chat`** thread kind — future.
- **End-to-end encryption** — future (v2).
- **Voice / video messages** — future.
- **Read-the-other-party's-typing-in-real-time** — typing indicators
  are supported in v1 (FR--022), but the typing "…" UX polish is
  client-side.
- **Group chat** — not in v1; the bounded context is 1:1 / 1:few
  bound to a service context.

## 5. Dependencies on Other Phases

- Phase 7.6 Conductor: chat-service does NOT participate in
  Conductor workflows (no cross-cutting compensation graph). It
  runs its own in-service saga for thread lifecycle and offline
  fan-out (eventually consistent).
- Phase 7.5 Make a Deal: chat-service is NOT a deal-kernel
  participant (TECH.md 12).

## 6. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| WebSocket fan-out lag at peak (Friday-evening ride surge) | Medium | High | HPA on `chat_websocket_connections`; Redis Pub/Sub scales horizontally with replicas |
| `notification-service` outage blocks offline delivery | Medium | High | chat-service falls back to "next session" in-app banner only; alert on `chat_offline_deliveries_total` flatline |
| Phone-number leak via WebSocket payload | Low | Critical | FR--019 origin check + payload schema review + integration test that asserts no `phone` field is present |
| GDPR sweep lagging behind request | Low | High | operator-triggerable `POST /admin/v1/chat/users/{id}/gdpr-erase`; nightly batch for backlog |
| Attachment-borne malware | Low | High | file-service scan BEFORE visibility (FR--031–033); ClamAV signature updates are file-service's responsibility |
| Cross-service FK violation on `context_id` | Low | High | no DB FK to `trip-service.trips.id` per platform convention (cross-service refs are UUID without FK) |

## 7. Rollout

- **Region**: `eu-west` first (Cairo HQ, peak ride-hailing); then
  `ap-southeast`.
- **Stage**: 10% canary (one city) → 50% (one country) → 100% per
  region, gated on `chat_message_send_to_delivery_seconds` p99 and
  `chat_offline_deliveries_total` rate.
- **Mobile**: phased rollout via the existing rider / driver /
  customer / courier / restaurant apps.
- **Operator enable**: the `chat.admin` role is added to the
  `SUPER_ADMIN` preset at deploy time (TECH.md 10).
## 8. Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 3, Position 21** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (WebSocket limits, rate limits, retention), [`identity-service`](../identity-service/README.md) (Keycloak JWKS for JWT verification) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`trip-service`](../trip-service/README.md), [`food-order-service`](../food-order-service/README.md), [`courier-service`](../courier-service/README.md) (thread bootstrap events — chat-service can start without them; threads bootstrap on the next matching event), [`notification-service`](../notification-service/README.md) (offline push fallback), [`admin-service`](../admin-service/README.md) (moderation), [`fraud-risk-service`](../fraud-risk-service/README.md) (abuse scoring), [`restaurant-service`](../restaurant-service/README.md) (passive participant lookup) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed. chat-service is the last to come up in Tier 3 (after the Tier 2 services that produce bootstrap events are live).
- **Single-service rollout** — rolling deploy with **canary required** because chat-service has the largest blast radius (many consumers).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed; chat-service is the last in the cross-cutting pass.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).
