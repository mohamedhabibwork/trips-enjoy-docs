# food-order-service — Implementation Plan

**Domain:** Food Marketplace
**Tier:** 2 (position 18 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T1 (99.95%)
**DB Schema:** `food_order`
**Cache:** Redis — active order
**HPA:** CPU 60%, 2–6, p99 < 150ms

---

## Purpose

**Phase 5 — Food Delivery & Financial.** Begin after `courier-service` (delivery) goes live.

This PLAN.md is the source of truth for **how** `food-order-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Create schema `food_order`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | Add `food_order.outbox` and `food_order.inbox` for reliable eventing | pending | T-ORD-03 | food_order.outbox, food_order.inbox | food_order.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Idempotency-Key middleware on every mutating route | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Pagination + filtering on every list endpoint | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-ORD-03 | food_order.admin | food_order.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Publish events per the integration map below | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Avro schema registered in Schema Registry on first publish | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Single consumer per partition; pause-on-error with backoff | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Dead-letter topic after N retries | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Redis — active order | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Push-invalidate on every write that affects the cache key | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Stampede protection on hot keys (single-flight) | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Sync dependencies: restaurant-service, `restaurant-service` (branch), customer-service, pricing-service | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-ORD-03 | food_order.admin | food_order.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | `X-Audit-Reason` header required on admin mutations | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | Field-level encryption for PII (driver license, payment method) | pending | T-ORD-03 | food_order.admin | food_order.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Metrics: RED per route + business counters specific to this service | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-ORD-03 | food_order.admin | food_order.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-ORD-03 | food_order.admin | food_order.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-02 | Pre-upgrade Job for migrations | pending | T-ORD-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-ORD-02 | food_order.admin | food_order.admin | — | — |
| T-ORD-04 | Smoke test in staging before production rollout | pending | T-ORD-03 | food_order.admin | food_order.admin | — | — |
### Phase 7.5 — Make-a-Deal Kernel


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
**Make-a-Deal participation (Phase 7.5).** Embedded per service — no central binary.
- Endpoints:
  - `POST /v1/orders/{id}/deal`
  - `POST /v1/deals/{id}/counter`
  - `POST /v1/deals/{id}/accept`
  - `POST /v1/deals/{id}/reject`
- Produces:
  - `food.deal.opened.v1`
  - `food.deal.countered.v1`
  - `food.deal.accepted.v1`
  - `food.deal.rejected.v1`
  - `food.deal.expired.v1`
- Consumes:
  - `delivery.deal.bid.submitted.v1`

### Phase 7.7 — In-App Chat (cross-cutting)

This service participates in Phase 7.7 (in-app chat kernel added 2026-08-12).
Single source of truth: [`services/chat-service/PLAN.md`](../chat-service/PLAN.md).
On `food.order.accepted.v1` (restaurant accepts the order), this service
is the canonical **customer-side trigger** that causes `chat-service` to
bootstrap a `food_order_chat` thread; on `food.order.delivered.v1` and
cancellation variants it is the canonical **close trigger**.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-P77-01 | Wire `chat-service` client to `chat-service` REST API per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) — `POST /v1/admin/chat/threads` (admin) and `GET /v1/chat/threads/{id}` (read-only) | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-P77-02 | On `food.order.accepted.v1` emission, also call `POST /v1/chat/threads` with `thread_kind=food_order_chat`, `context_id=order_id`, `participants=[customer_id, restaurant_id]` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §2.2 | pending | T-ORD-P77-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-P77-03 | On `food.order.delivered.v1` and all cancellation variants (`food.order.cancelled.v1`, `food.order.rejected.v1`), call `POST /v1/chat/threads/{id}/close` to signal thread close to `chat-service` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §2.4 | pending | T-ORD-P77-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-P77-04 | Consume `chat.message.reported.v1` from `chat-service`; if `severity >= abuse` and `actor_role = customer`, open abuse ticket in `admin-service` per [`workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) | pending | T-ORD-P77-01 | food_order.admin | food_order.admin | platform.safety | no |
| T-ORD-P77-05 | Idempotency-key namespace `chat:thread:{order_id}:{accepted|closed}` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §4.1 | pending | T-ORD-P77-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-P77-06 | Outbox + DLQ for `chat-service` calls per the platform outbox pattern in [`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md) — chat-service is **CRITICAL** (T1) per [`architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) | pending | T-ORD-P77-01 | food_order.admin | food_order.admin | — | no |

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `restaurant-service` | per `INTEGRATION.md` | sync dependency | Yes |
| ``restaurant-service` (branch)` | per `INTEGRATION.md` | sync dependency | Yes |
| `customer-service` | per `INTEGRATION.md` | sync dependency | Yes |
| `pricing-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `food.order.placed` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.order.accepted` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.order.rejected` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.deal.opened.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.deal.countered.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.deal.accepted.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.deal.rejected.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `food.deal.expired.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `checkout.completed` | see INTEGRATION.md | see INTEGRATION.md |
| `branch.busy` | see INTEGRATION.md | see INTEGRATION.md |
| `delivery.deal.bid.submitted.v1` | see INTEGRATION.md | see INTEGRATION.md |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T1 (99.95%))
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage
- [ ] OpenAPI 3.x spec published and validated
- [ ] `INTEGRATION.md` is the source of truth for endpoints and events

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)
- [Implementation Phases](../../IMPLEMENTATION_PHASES.md)
- [Service Integration Matrix](../../SERVICE_INTEGRATION_MATRIX.md)

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-P70-01 | Implement review-projection hook that emits `review.zone_aggregated.v1` (debounced per zone) from food-order reviews — Producer per [`MASTER_PLAN.md`](../../MASTER_PLAN.md) Phase 7 table row 129 | pending | — | food_order.admin | food_order.admin | — | — |
| T-ORD-P70-02 | Wire rating-density aggregation trigger via Conductor signal per [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-ORD-P70-01 | food_order.admin | food_order.admin | — | — |
| T-ORD-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-ORD-P70-02 | food_order.admin | food_order.admin | — | — |

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ORD-P76-01 | Register Conductor worker for `wf.phase75.deal_food.v1` — Producer — customer-side endpoint + 5 food events | pending | — | food_order.admin | food_order.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 2, Position 18** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`customer-service`](../customer-service/README.md) (customer profile), [`restaurant-service`](../restaurant-service/README.md) (menu + KYC), [`pricing-service`](../pricing-service/README.md) (quote with tax), [`payment-service`](../payment-service/README.md) (food saga), [`courier-service`](../courier-service/README.md) (delivery dispatch), [`notification-service`](../notification-service/README.md) (order push), [`configuration-service`](../configuration-service/README.md) (menu rules) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`chat-service`](../chat-service/README.md) (Phase 7.7 — customer↔restaurant thread; food-order-service starts; threads bootstrap on `food.order.accepted.v1`) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-ORD-NN (Phase 1-10) | per task | per task | per task | per task |
| T-ORD-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-ORD-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-ORD-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
