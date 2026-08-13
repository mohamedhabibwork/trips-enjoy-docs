# ADR-0021: 21-service architecture with `chat-service` (Phase 7.7 addition)

- Status: Accepted
- Date: 2026-08-12
- Authors: Platform Architecture
- Deciders: Architecture Review Board
- Tags: architecture, chat, cross-cutting, phase-7.7, consolidation

## Context and Problem Statement

[ADR-0017](0017-20-service-architecture.md) (2026-08-05) locked the
service catalog at **20 active services** (the result of the 58 → 20
consolidation). Since then, the cross-cutting Phase 7.7 (in-app chat
kernel) has been added to the plan, introducing a 21st service —
`chat-service` — that did not exist at the time of ADR-0017.

The platform's microservices map, the per-service documentation
contract, the platform's SUPER_ADMIN preset, the
`SERVICE_INTEGRATION_MATRIX.md`, and the per-service event catalog
all reference the 20-service catalog. Without a follow-up ADR:

- Other ADRs that call out "20 services" in their catalog-revision
  notes become ambiguous about whether `chat-service` is in scope.
- The new architecture docs (HLD, LLD, MICROSERVICES_MAP,
  SYSTEM_OVERVIEW) have already been updated to mention
  `chat-service` as the 21st, but the canonical decision is not
  recorded.
- The Mermaid diagrams in `MICROSERVICES_MAP.md` and the per-service
  dependency matrix in `SERVICE_INTEGRATION_MATRIX.md` need a
  canonical reference for the 21st row.

This ADR closes that gap: it formalizes the 20 → 21 service catalog
and the rationale for adding `chat-service` as the 21st.

## Decision Drivers

- **Avoid phone-number exposure as the default communication channel.**
  Per BR--002 / BR--011, a phone call between rider and driver, or
  customer and restaurant, leaks PII and creates off-platform
  accountability. The chat kernel is the platform-primitive
  alternative.
- **Consolidation cost.** The chat kernel could be absorbed into
  `notification-service` (which already owns the template and delivery
  layer), or into `trip-service` (which owns the trip aggregate).
  Both options were considered and rejected — see "Considered
  Options" below.
- **Critical path.** Without an in-app chat, the platform either
  fails closed (no communication channel between rider and driver
  during a trip) or falls back to phone calls (PII leak, off-platform
  accountability).

## Considered Options

- **Option A — Keep all 20 services (no chat kernel).** Phone-number
  call remains the default for rider ↔ driver and customer ↔
  restaurant communication. *Rejected*: violates BR--002 / BR--011.

- **Option B — Absorb chat into `notification-service`.**
  `notification-service` already owns the template and delivery
  layer (push, SMS, email). Adding WebSocket fan-out to a T2
  notification service is a re-architecture: the SLOs (T1 99.95% for
  chat) are different from the notification SLO (T2 99.9%), the
  data lifecycle (chat threads are persisted for the service-context
  duration + retention, not just delivered) is different, and the
  abuse surface (chat messages, attachments, block / mute / ban,
  GDPR sweep) is much larger than notifications.
  *Rejected*: bloats `notification-service` past its bounded
  context, and conflates two distinct SLO classes.

- **Option C — Absorb chat into `trip-service` (or food-order,
  courier).** Trip owns the trip aggregate; chat threads are scoped
  to a trip. *Rejected*: chat threads are also scoped to
  `food_order` and `delivery` contexts; collapsing into any one
  service creates a service that owns chat for all three contexts
  (which is its own service in everything but name).

- **Option D — Add `chat-service` as the 21st active service.**
  Go 1.25 + `chi` + `coder/websocket`, owns the `chat` schema
  (threads, messages, attachments, read state, moderation reports,
  blocks), Redis Pub/Sub fan-out for online delivery, REST + event
  delegation to `notification-service` for offline push fallback.
  *Chosen* — bounded context aligns with the platform convention
  of one service per business capability, the SLO (T1 99.95%) and
  language (Go) are distinct from the existing services, and the
  fan-out model (Redis Pub/Sub, WebSocket connection registry)
  matches the existing edge / hot path profile.

## Decision Outcome

Chosen option: **D — Add `chat-service` as the 21st active service.**

- **Locked catalog:** 21 active services.
- **Service:** `chat-service` (Go 1.25 + `net/http` + `chi` +
  `coder/websocket` + `pgx` + `go-redis/redis` + `segmentio/kafka-go`).
- **DB schema:** `chat` (PostgreSQL 19; `chat.messages` range-partitioned
  by `created_at` monthly).
- **Criticality:** T1 (99.95% SLO). chat is the primary
  rider ↔ driver and customer ↔ {restaurant, courier}
  communication channel; phone numbers must not be exposed.
- **Transport:** WebSocket (`WS /v1/chat/ws`) for online fan-out;
  REST for offline + admin.
- **Fan-out:** Redis Pub/Sub (`chat:thread:{id}` channel + per-replica
  connection registry) for cross-replica delivery.
- **Thread kinds:** `trip_chat`, `food_order_chat`, `delivery_chat`
  (1:1 between the two service-context participants).
- **Phase 7.7 timeline:** 8 sprints, parallel with Phase 7 / 7.5 /
  7.6 cross-cutting addenda for the existing 20 services.

### Service catalog (post-Phase 7.7)

The full updated catalog is in
[`../MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md). The 21st service
adds the following:

| Service | Owns data | DB schema | Criticality |
|---------|-----------|-----------|-------------|
| `chat-service` | chat threads (per service-context: trip / food order / delivery) + messages + participants + attachments + read state + moderation reports + user-level blocks | `chat` | T1 |

### Consequences

- Good: bounded context — chat threads, messages, moderation, and
  block / mute / ban live in one service with one SLO (T1) and one
  data lifecycle (service-context-scoped, not delivery-scoped).
- Good: clean re-use — the existing services (trip, food-order,
  courier) do not have to learn WebSocket fan-out; they emit
  one event (`ride.request.matched.v1`, `food.order.accepted.v1`,
  `delivery.courier.assigned.v1`) and the chat thread is bootstrapped
  by `chat-service` from the event consumer.
- Good: re-uses existing primitives — Redis Pub/Sub (already
  platform-wide), Keycloak JWT validation (already platform-wide),
  Postgres 19 partitioning (already platform-wide).
- Neutral: 7 existing services must wire in to chat-service
  (trip, food-order, courier as bootstrap producers; notification,
  admin, fraud-risk as event consumers; restaurant as passive
  participant reference). Each service has a `Phase 7.7` block in
  its `PLAN.md` per the platform convention.
- Bad: one more service in the catalog. After the 58 → 20
  consolidation, the 20 → 21 increment is intentional and small
  (one new schema, one new pod profile, one new SUPER_ADMIN role
  entry), but the trend is non-zero and should be tracked in the
  platform's architecture budget.
- Bad: chat-service is **critical-path** for the rider ↔ driver
  experience; its unavailability degrades to "no chat" (not
  "phone call" — the platform must fail closed and surface
  `chat_service_unavailable` to the user). Per
  [`../SERVICE_ISOLATION.md`](../SERVICE_ISOLATION.md), chat-service
  is **CRITICAL** (T1) for trip / food-order / delivery flows.

### Confirmation

- All 21 services have a `PLAN.md` per
  [`../MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md).
- All 7 consumer services (trip, food-order, courier, restaurant,
  notification, admin, fraud-risk) ship a `Phase 7.7` block in
  their `PLAN.md` per the platform contract.
- `chat-service` ships its own `PLAN.md`, `TECH.md`, `BRD.md`,
  `SRS.md`, `ERD.md`, `INTEGRATION.md`, `WORKFLOWS.md`,
  `SKELETON.go.mod`, plus `MESSAGE_HISTORY.md` (delivery audit
  chain) and `seeds/` (template seed catalog).
- The `SUPER_ADMIN` preset is updated to `1 × platform.super_admin +
  21 × <service>.admin` per
  [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md).
- `services/README.md`, `RECOMMENDATIONS.md`, `PLAN_INDEX.md`,
  `MASTER_PLAN.md`, `MASTER_TASK.md`, `MICROSERVICES_MAP.md`,
  `SERVICE_INTEGRATION_MATRIX.md`, `EVENT_ARCHITECTURE.md`,
  `CONDUCTOR_WORKFLOWS.md` are all consistent with the 21-service
  catalog.
- All cross-doc references that previously said "20 services" are
  either marked as historical context (in the catalog-revision
  notes at the top of each ADR) or updated to 21 (in the body
  text).

## References

- [ADR-0017](0017-20-service-architecture.md) — the locked
  20-service catalog this ADR supersedes (incremental addition,
  not replacement).
- [ADR-0019](0019-request-id-at-the-edge.md) — the
  `X-Request-Id` propagation contract that `chat-service` inherits
  unchanged.
- [ADR-0020](0020-polymorphic-request-id.md) — the polymorphic
  `request_id` that ties `chat-service` thread creation to the
  underlying trip / food / delivery `request_id`.
- [`../MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — service
  catalog (chat-service is the 21st).
- [`../SERVICE_ISOLATION.md`](../SERVICE_ISOLATION.md) — chat-service
  is **CRITICAL** (T1) for trip / food / delivery flows.
- [`../EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — full
  `chat.*.v1` event catalog.
- [`../shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md)
  — chat-service is **NOT** a Conductor workflow (Phase 7.7 §11).
- [`../../IMPLEMENTATION_PHASES.md`](../../IMPLEMENTATION_PHASES.md)
  — Phase 7.7 (Weeks 45–46) schedule.
- [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) — the canonical
  58 → 20 consolidation history this ADR continues (20 → 21).
