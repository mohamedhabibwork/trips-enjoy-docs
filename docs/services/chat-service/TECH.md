# chat-service — Technology Profile

## 1. Language and Framework

- **Language**: Go 1.25.x.
- **Framework**: `net/http` + `go-chi/chi v2` for REST.
- **WebSocket**: `nhooyr.io/websocket` (now `coder/websocket`) for
  fan-out.
- **Rationale**: chat-service is **bound by connection count and
  message throughput**, not by domain complexity. The dominant hot
  path is `POST /v1/chat/threads/{id}/messages` and the WebSocket
  fan-out, both of which match the Edge / hot path profile in
  [`../RECOMMENDATIONS.md` 1](../RECOMMENDATIONS.md) (Go). The Go
  profile gives us ~30 MB per pod (vs 150+ MB for a JVM service),
  letting us run 5–10× the pod count on the same node pool for the
  same WebSocket fan-out capacity. The pattern mirrors
  ``courier-service` (tracking)``.

## 2. Build Tool

- **Tool**: `go build` (Go 1.25 toolchain).
- **Module path**: `github.com/uber/chat-service`.
- **Versioning**: semver; image is `registry.uber.io/chat-service:<git-sha>`.

## 3. Key Libraries

| Library | Version | Purpose |
|---------|---------|---------|
| `go-chi/chi` | v2 (5.x) | REST router |
| `coder/websocket` | latest | WebSocket server |
| `jackc/pgx` | v5 | PostgreSQL 19 driver (preferred over `database/sql`) |
| `go-redis/redis` | v9 | Redis 8 client (presence, typing, Pub/Sub, rate-limit) |
| `segmentio/kafka-go` | latest | Kafka producer / consumer |
| `golang-migrate` | v4 | SQL migrations |
| `coreos/go-oidc` | v3 | Keycloak OIDC verification |
| `prometheus/client_golang` | v1.20+ | metrics |
| `go.opentelemetry.io/otel` | 1.40+ | OpenTelemetry SDK |
| `golang.org/x/time/rate` | latest | rate limiter |
| `spf13/viper` | latest | configuration loader |
| `go-playground/validator` | v10 | request validation |
| `sergi/go-diff` | v1 | diff for WS reconnect replay |
| `pgx/v5/pgxpool` | v5 | pgx connection pool |

## 4. Data Layer

- **Database**: PostgreSQL 19 (`chat` schema).
- **ORM / DSL**: not used; raw SQL via `pgx` + `database/sql`.
- **Migration**: `golang-migrate v4` (versioned, forward-only).
- **Partitioning**: `chat.messages` is range-partitioned by
  `created_at` (monthly).
- **Indexing**: see [`ERD.md`](./ERD.md) 3.

## 5. Cache

- **Redis 8** (per-service cluster).
- **Keys**:
  - `chat:conn:{user_id}` → set of replica IDs holding a WebSocket
    for the user (TTL 60s, refreshed by WS ping).
  - `chat:typing:{thread_id}` → ephemeral Pub/Sub channel.
  - `chat:thread:{thread_id}` → Pub/Sub channel for fan-out.
  - `chat:rate:{user_id}:{window}` → per-user rate-limit counter.
  - `chat:rate:thread:{thread_id}:{window}` → per-thread rate-limit
    counter.
  - `chat:muted:{user_id}` → TTL key for mute.
  - `chat:banned:{user_id}` → persistent flag.

## 6. Event Broker

- **Kafka** (per platform convention; Outbox pattern + transactional
  producer).
- **Topics produced**:
  - `chat.chat_thread.created.v1`
  - `chat.chat_thread.closed.v1`
  - `chat.chat_message.sent.v1`
  - `chat.chat_message.read.v1`
  - `chat.chat_attachment.shared.v1`
  - `chat.chat_message.reported.v1`
  - `chat.chat_message.moderated.v1`
  - `chat.chat_message.offline_delivery_required.v1`
  - `chat.chat_user.blocked.v1`
  - `chat.chat_user.muted.v1`
  - `chat.chat_user.banned.v1`
  - `chat.chat_user.gdpr_erased.v1`
- **Topics consumed**: `ride.request.matched.v1`, `trip.arrived.v1`,
  `trip.started.v1`, `trip.completed.v1`, `trip.cancelled.v1`,
  `food.order.accepted.v1`, `food.order.preparing.v1`,
  `food.order.ready.v1`, `food.order.delivered.v1`,
  `food.order.cancelled.v1`, `delivery.courier.assigned.v1`,
  `delivery.pickup.v1`, `delivery.completed.v1`,
  `delivery.cancelled.v1`, `configuration.updated.v1`.

## 7. External Integrations

- **Keycloak** — JWT issuer; `coreos/go-oidc v3` for verification.
- **No other external providers**. The chat-service does not call
  APNs / FCM / SMS providers directly; offline push is delegated to
  `notification-service`.

## 8. Admin Endpoints

All under `/admin/v1/chat/...`. Auth: `chat.admin` role OR
`platform.support` + `X-Audit-Reason`. Every call emits
`audit.admin.chat.v1`.

| Method | Path | Min role | Purpose |
|--------|------|----------|---------|
| `GET` | `/admin/v1/chat/threads/{id}` | `chat.admin` | read with full PII |
| `POST` | `/admin/v1/chat/threads/{id}/close` | `chat.admin` | force-close |
| `POST` | `/admin/v1/chat/threads/{id}/messages/{msg_id}/hide` | `chat.admin` | hide |
| `POST` | `/admin/v1/chat/threads/{id}/messages/{msg_id}/remove` | `chat.admin` | remove |
| `POST` | `/admin/v1/chat/threads/{id}/messages/{msg_id}/unhide` | `chat.admin` | restore |
| `POST` | `/admin/v1/chat/users/{user_id}/mute` | `chat.admin` | mute |
| `DELETE` | `/admin/v1/chat/users/{user_id}/mute` | `chat.admin` | unmute |
| `POST` | `/admin/v1/chat/users/{user_id}/ban` | `chat.admin` | ban |
| `DELETE` | `/admin/v1/chat/users/{user_id}/ban` | `chat.admin` | unban |
| `GET` | `/admin/v1/chat/reports` | `chat.admin` / `platform.support` | list moderation reports |
| `POST` | `/admin/v1/chat/reports/{id}/resolve` | `chat.admin` | resolve |
| `POST` | `/admin/v1/chat/users/{user_id}/gdpr-erase` | `platform.compliance` | GDPR sweep |

Inherited from the platform (every service):
`/admin/v1/{health,config,metrics,audit,cache/clear,reindex,replay,force-state,services}`
(see [`../RECOMMENDATIONS.md` 6.4](../RECOMMENDATIONS.md)).

## 9. RBAC

- **Per-service role**: `chat.admin` (full access).
- **Per-service roles**: `chat.support` (read with `X-Audit-Reason`).
- **Platform roles**: `platform.support`, `platform.privacy`
  (GDPR), `platform.compliance`.
- **User-level**: a participant (rider / driver / customer / courier
  / restaurant_staff) can read / write only the threads they
  belong to.

## 10. SUPER_ADMIN Preset Membership

This service's `<service>.admin` role is **`chat.admin`** and is
added to the `SUPER_ADMIN` preset (1 × `platform.super_admin` +
21 × `<service>.admin` after the 21st-service addition).

```yaml
SUPER_ADMIN:
  platform.super_admin: 1
  api_gateway.admin: 1
  identity.admin: 1
  customer.admin: 1
  driver.admin: 1
  trip.admin: 1
  pricing.admin: 1
  restaurant.admin: 1
  food_order.admin: 1
  courier.admin: 1
  geolocation.admin: 1
  payment.admin: 1
  ledger.admin: 1
  configuration.admin: 1
  notification.admin: 1
  file.admin: 1
  audit.admin: 1
  admin.admin: 1
  reporting.admin: 1
  fraud_risk.admin: 1
  search.admin: 1
  chat.admin: 1  # <-- new
```

## 11. Open-Source Bundle

Per platform convention, the bundle SPDX matrix is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
The chat-service's bundle index entry:

| Library | Version | SPDX |
|---------|---------|------|
| `go-chi/chi` | v2 (5.x) | MIT |
| `coder/websocket` | latest | ISC |
| `jackc/pgx` | v5 | MIT |
| `go-redis/redis` | v9 | BSD-2-Clause |
| `segmentio/kafka-go` | latest | MIT |
| `golang-migrate` | v4 | MIT |
| `coreos/go-oidc` | v3 | Apache-2.0 |
| `prometheus/client_golang` | v1.20+ | Apache-2.0 |
| `go.opentelemetry.io/otel` | 1.40+ | Apache-2.0 |
| `golang.org/x/time/rate` | latest | BSD-3-Clause |
| `spf13/viper` | latest | MIT |
| `go-playground/validator` | v10 | MIT |
| `pgcrypto` (PostgreSQL extension) | bundled | PostgreSQL |

## 12. Deal Kernel Participation

The chat-service is **NOT** a deal kernel participant. The deal
flow is bounded by `food-order-service` / `courier-service` /
`pricing-service` (per [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md)).
The chat-service does not represent any deal-side boundary.

## 13. Configuration

All configuration is loaded from `configuration-service` at startup
and on `configuration.updated.v1`. The keys are listed in
[`README.md`](./README.md) 13.

## 14. Performance SLO

| Path | Target |
|------|--------|
| `POST /v1/chat/threads/{id}/messages` p99 | ≤ 200 ms |
| `chat_message_send_to_delivery_seconds` p99 (online) | ≤ 200 ms |
| `chat_message_offline_delivery_seconds` p99 | ≤ 1500 ms |
| `GET /v1/chat/threads/{id}/messages` p99 | ≤ 150 ms |
| WebSocket fan-out (Redis Pub/Sub → local frame) | ≤ 80 ms |

## 15. Resource Limits

- **Pod**: `cpu: 500m–1500m`, `memory: 512Mi–1.5Gi`.
- **Replicas**: 6 default, HPA to 40.
- **HPA signal**: `chat_websocket_connections > 5 000` per replica OR
  CPU > 60%.

## 16. Health

- `GET /health` — liveness.
- `GET /ready` — readiness (DB + Kafka + Redis + at least one
  downstream reachability).
- `GET /started` — startup probe.

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
- **Replicas**: 6 baseline, HPA up to 40.
- **Container base**: `gcr.io/distroless/static-debian12:nonroot`.
- **Migrations**: K8s Job before rolling deploy.
- **WebSocket gateway**: the api-gateway exposes
  `wss://api.<region>.uber.io/v1/chat/ws` and proxies to the
  chat-service via `linkerd` mTLS; no sticky session needed.