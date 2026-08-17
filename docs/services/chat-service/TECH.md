# chat-service — Technology Profile

> One-page technology reference for `chat-service`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`INTEGRATION.md`](./INTEGRATION.md) · [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Edge / hot path (Go) — bound by WebSocket connection count and message throughput, not by domain complexity |
| **Language** | Go 1.25.x |
| **Framework** | `net/http` + `go-chi/chi v2` for REST; `coder/websocket` (formerly `nhooyr.io/websocket`) for WebSocket fan-out |
| **Build** | `go build` (Go 1.25 toolchain); module path `github.com/uber/chat-service` |
| **Container** | `gcr.io/distroless/static-debian12:nonroot` (multi-stage build, distroless final stage, non-root user) |

**Rationale.** The dominant hot path is `POST /v1/chat/threads/{id}/messages` and the WebSocket fan-out, both of which match the Edge / hot path profile in [`../RECOMMENDATIONS.md` 1](../RECOMMENDATIONS.md) (Go). The Go profile gives us ~30 MB per pod (vs 150+ MB for a JVM service), letting us run 5–10× the pod count on the same node pool for the same WebSocket fan-out capacity. The pattern mirrors ``courier-service` (tracking)``.

## 2. Key libraries

- `go-chi/chi` v2 (5.x) — REST router
- `coder/websocket` — WebSocket server
- `jackc/pgx` v5 — PostgreSQL 19 driver (preferred over `database/sql`)
- `pgx/v5/pgxpool` v5 — pgx connection pool
- `go-redis/redis` v9 — Redis 8 client (presence, typing, Pub/Sub, rate-limit)
- `segmentio/kafka-go` — Kafka producer / consumer
- `golang-migrate` v4 — SQL migrations
- `coreos/go-oidc` v3 — Keycloak OIDC verification
- `prometheus/client_golang` v1.20+ — metrics
- `go.opentelemetry.io/otel` 1.40+ — OpenTelemetry SDK
- `golang.org/x/time/rate` — rate limiter
- `spf13/viper` — configuration loader
- `go-playground/validator` v10 — request validation
- `sergi/go-diff` v1 — diff for WS reconnect replay

## 3. Data layer

- **Database**: PostgreSQL 19 (`chat` schema).
- **ORM / DSL**: not used; raw SQL via `pgx` + `database/sql`.
- **Migrations**: `golang-migrate v4` (versioned, forward-only).
- **Partitioning**: `chat.messages` is range-partitioned by `created_at` (monthly).
- **Indexing**: see [`ERD.md`](./ERD.md) 3.

## 4. Cache

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

## 5. External integrations

- **Keycloak** — JWT issuer; `coreos/go-oidc v3` for verification.
- **No other external providers**. The chat-service does not call
  APNs / FCM / SMS providers directly; offline push is delegated to
  `notification-service`.
- **Bootstrap event sources** (consumed from Kafka): `ride.request.matched.v1`,
  `trip.arrived.v1`, `trip.started.v1`, `trip.completed.v1`,
  `trip.cancelled.v1`, `food.order.accepted.v1`, `food.order.preparing.v1`,
  `food.order.ready.v1`, `food.order.delivered.v1`,
  `food.order.cancelled.v1`, `delivery.courier.assigned.v1`,
  `delivery.pickup.v1`, `delivery.completed.v1`,
  `delivery.cancelled.v1`, `configuration.updated.v1`.

**Produced events** (full contract in [`INTEGRATION.md`](./INTEGRATION.md)):

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

## 6. Security

- **AuthN**: Keycloak resource server (`coreos/go-oidc v3`)
- **AuthZ**: RBAC — per-service role `chat.admin` (full access), `chat.support` (read with `X-Audit-Reason`); platform roles `platform.support`, `platform.privacy` (GDPR), `platform.compliance`; user-level — a participant (rider / driver / customer / courier / restaurant_staff) can read / write only the threads they belong to.
- **Secrets**: external secret manager (Kubernetes External Secrets / Vault — no secret in env)
- **mTLS**: linkerd sidecar, all intra-cluster traffic
- **Payload**: FR--019 origin check + payload schema review + integration test that asserts no `phone` field is present (per [`SRS.md` FR--019](./SRS.md))

## 7. Observability

- **Tracing**: OpenTelemetry SDK 1.40+ → OTLP
- **Metrics**: Prometheus client (key: `chat_websocket_connections`, `chat_message_send_to_delivery_seconds`, `chat_message_offline_delivery_seconds`, `chat_offline_deliveries_total`)
- **Logs**: structured JSON to stdout (Loki)
- **Health**: `/health` (liveness), `/ready` (readiness — DB + Kafka + Redis + downstream reachability), `/started` (startup probe)
- **Audit**: every admin call emits `audit.admin.chat.v1` (consumed by `audit-service`)

## 8. Scaling

- **HPA signal**: `chat_websocket_connections > 5 000` per replica OR CPU > 60%
- **Replicas**: 6 baseline, HPA up to 40
- **Pod resources**: `cpu: 500m–1500m`, `memory: 512Mi–1.5Gi`
- **Performance SLO**:

| Path | Target |
|------|--------|
| `POST /v1/chat/threads/{id}/messages` p99 | ≤ 200 ms |
| `chat_message_send_to_delivery_seconds` p99 (online) | ≤ 200 ms |
| `chat_message_offline_delivery_seconds` p99 | ≤ 1500 ms |
| `GET /v1/chat/threads/{id}/messages` p99 | ≤ 150 ms |
| WebSocket fan-out (Redis Pub/Sub → local frame) | ≤ 80 ms |

## 9. Local dev

- **Run**: `go run ./cmd/chat-service`
- **Test**: `go test ./...`
- **Compose profile**: `docker compose up chat-service postgres kafka redis`
- **Seed**: a `trip_chat` thread with 3 messages between a rider and
  driver; a `food_order_chat` thread with 5 messages; a
  `delivery_chat` thread with 2 messages. Configurable
  `chat.seed.enabled` defaults to true in dev and false in
  production.
- **Tests**: unit (thread bootstrap, idempotency, profanity filter,
  rate-limit, attachment metadata), integration (Kafka in,
  WebSocket fan-out, Redis Pub/Sub, file-service delegation),
  contract (pact with `trip-service`, `food-order-service`,
  `courier-service`, `notification-service`).
- **Deployment**: image `registry.trips-enjoy.com/chat-service:<git-sha>`. Migrations: K8s Job before rolling deploy. WebSocket gateway: the api-gateway exposes `wss://api.<region>.trips-enjoy.com/v1/chat/ws` and proxies to the chat-service via `linkerd` mTLS; no sticky session needed.

## 10. Admin endpoints & RBAC

This service exposes `/admin/v1/chat/...` endpoints for the `admin-service`
BFF and platform operators. The platform-wide admin pattern (roles,
audit format, network policy, common endpoints) is in
[`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac);
this section documents the **per-service specifics**.

### 10.1 Keycloak admin roles accepted

This service accepts admin calls from these Keycloak roles:

- `platform.super_admin`
- `platform.admin`
- `platform.support`
- `platform.privacy` (GDPR sweep only)
- `platform.compliance` (GDPR sweep only)
- `chat.admin`
- `chat.support`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.chat.v1`
- **Consumer**: `audit-service` (writes to its immutable `audit` schema)
- **Fields**: `actor_id`, `actor_username`, `roles`, `endpoint`,
  `target_resource`, `action`, `reason_code` (required for PII access),
  `request_id`, `trace_id`, `result`, `duration_ms`

### 10.3 Data access policy (per-service)

The platform-wide policy table is in
[RECOMMENDATIONS.md 6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide).
This service refines it as follows:

| Data class | super_admin | admin | ops | support | privacy | compliance | engineering | data_eng |
|---|---|---|---|---|---|---|---|---|
| Threads (full PII) | ✓ | ✓ | — | read+reason | read+reason | read+reason | — | scrubbed |
| Messages (decrypted body) | ✓ | ✓ | — | read+reason | read+reason | read+reason | — | — |
| Moderation reports | ✓ | ✓ | — | read+reason | — | — | — | scrubbed |
| User blocks / mutes / bans | ✓ | ✓ | — | — | — | — | — | — |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
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

### 10.5 SUPER_ADMIN preset membership

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
  chat.admin: 1  # <-- new (Phase 7.7)
```

## 11. Open-source bundle

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

> **Deal Kernel Participation.** The chat-service is **NOT** a deal
> kernel participant. The deal flow is bounded by
> `food-order-service` / `courier-service` / `pricing-service` (per
> [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md)).
> The chat-service does not represent any deal-side boundary.

## See also

- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide tech map
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — full SPDX license catalogue
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis
- [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md) — service catalog (chat-service is the 21st)
