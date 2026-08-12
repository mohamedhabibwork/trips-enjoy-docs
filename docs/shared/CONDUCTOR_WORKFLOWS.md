# Conductor Workflows — Shared Contract

> **Canonical reference** for every workflow that runs on the
> Netflix Conductor engine. Every participating service's
> `INTEGRATION.md` "Conductor Workers" links here. Single source of
> truth for workflow IDs, task names, input/output schemas,
> compensation steps, and Kafka signal mappings.
>
> Adopted per [ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md).

## 1. Deployment topology

Conductor is deployed as a Kubernetes-native cluster colocated with
the platform baseline (per [PLATFORM_BASELINE.md](PLATFORM_BASELINE.md)):

| Component | Pods | Backing store | Notes |
|---|---|---|---|
| `conductor-server` | 3 nodes (Raft consensus) | PostgreSQL 19 (shared cluster) | Stateless API + workflow engine; runs as `StatefulSet` |
| `conductor-elasticsearch` | 3 nodes | Elasticsearch 2.x | Visibility index for workflow history search |
| `conductor-redis` | 3 nodes | Redis 8.x | Task queue (per [OSS_DEPENDENCIES.md 2](OSS_DEPENDENCIES.md)) |
| `conductor-kafka-bridge` | 2 nodes | – | Translates Kafka signals → Conductor signals and Conductor completion → Kafka events |
| `conductor-ui` | 2 nodes | – | Read-only UI at `https://conductor.<env>.uber.io` |

Workers are **colocated in each participating service binary** —
each service registers its `ConductorTask` implementations at startup.
Lost workers are detected via 5s heartbeat; tasks are reassigned
within 30s.

All Conductor-internal traffic is mTLS via Istio ambient mode
(same as service-to-service traffic per
[PLATFORM_BASELINE.md 1](PLATFORM_BASELINE.md)).

## 2. Workflow ID conventions

13 workflow definitions total. Each is a JSON DSL file under
`/conductor-workflows/<workflow-id>.json` deployed via the Conductor
metadata API.

| # | Workflow ID | Phase | Owner service | Pattern |
|---|---|---|---|---|
| 1 | `wf.phase7.reward_grant.v1` | Phase 7 | `trip-service` | 6-consumer fan-out (driver earnings, wallet, ledger, notification, audit, reporting) |
| 2 | `wf.phase7.reward_reversal.v1` | Phase 7 | `trip-service` | 6-consumer fan-out with reversal semantics |
| 3 | `wf.phase75.deal_rider.v1` | Phase 7.5 | `trip-service` | TTL-driven negotiation state machine |
| 4 | `wf.phase75.deal_driver.v1` | Phase 7.5 | `driver-service` | TTL-driven negotiation state machine |
| 5 | `wf.phase75.deal_food.v1` | Phase 7.5 | `food-order-service` | TTL-driven negotiation state machine |
| 6 | `wf.refund.standard.v1` | Phase 5 | `payment-service` | 5-step compensation saga |
| 7 | `wf.refund.partial.v1` | Phase 5 | `payment-service` | 5-step compensation saga |
| 8 | `wf.refund.food_reject.v1` | Phase 5 | `payment-service` | 6-step compensation saga (restaurant-reject) |
| 9 | `wf.refund.cancellation.v1` | Phase 5 | `payment-service` | 5-step compensation saga |
| 10 | `wf.refund.dispute.v1` | Phase 5 | `payment-service` | 7-step compensation saga (includes chargeback path) |
| 11 | `wf.refund.cod_failed.v1` | Phase 5 | `payment-service` | 4-step compensation saga (COD failure) |
| 12 | `wf.onboarding.driver.v1` | Phase 2 | `driver-service` | Long-running human-task workflow |
| 13 | `wf.onboarding.courier.v1` | Phase 2 | `courier-service` | Long-running human-task workflow |

Each workflow carries a `version` integer; in-flight runs are pinned
to the version they started with. New versions deploy via
`POST /api/metadata/workflow/<id>?version=N+1` and do not affect
in-flight runs.

## 3. Per-workflow spec

### 3.1 Phase 7 — Guaranteed Rewards fan-out

**Workflow IDs**: `wf.phase7.reward_grant.v1`, `wf.phase7.reward_reversal.v1`

**Trigger**: Kafka signal `trip.reward.granted.v1` (for grant) or
`trip.reward.reversed.v1` (for reversal), translated by
`conductor-kafka-bridge` into Conductor signal `tripCompletedSignal`
with the workflow ID derived from the event header.

**Input schema** (Conductor input JSON):

```json
{
  "request_id": "<UUIDv7>",
  "service": "trip" | "food_order" | "courier_delivery",
  "driver_id": "<UUIDv7>",
  "customer_id": "<UUIDv7>",
  "amount_minor_units": <integer>,
  "granted_at": "<RFC3339>",
  "reward_type": "guaranteed_topup" | "correction" | "user_credit"
}
```

**Conductor SWITCH** (on `service` field, first task):

```
SWITCH task: route_by_service
  case "trip"       → ride_side_fanout
  case "food_order" → order_side_fanout
  case default       → error_task (unknown service)
```

**Task list (grant — ride side, `service = "trip"`)** — fan-out to 6 consumers:

1. `payment_service_driver_earnings_grant` (worker in `payment-service`)
   — `Idempotency-Key: request:{request_id}:reward:driver:grant`
2. `payment_service_wallet_grant` (worker in `payment-service`)
   — `Idempotency-Key: request:{request_id}:reward:user:grant`
3. `ledger_service_posting` (worker in `ledger-service`)
   — `Idempotency-Key: request:{request_id}:reward:ledger:posting`
4. `notification_service_grant_template` (worker in `notification-service`)
   — `Idempotency-Key: request:{request_id}:reward:notif:grant`
5. `audit_service_reward_row` (worker in `audit-service`)
   — `Idempotency-Key: request:{request_id}:reward:audit:row`
6. `reporting_service_reward_fact` (worker in `reporting-service`)
   — `Idempotency-Key: request:{request_id}:reward:reporting:fact`

**Task list (grant — order side, `service = "food_order"`)** — same 6 tasks with
the same `request:{request_id}:reward:{role}:grant` idempotency keys; downstream
services resolve the concrete aggregate via `GET /v1/requests/{request_id}` from
`food-order-service`.

**Task list (reversal)** — same 6 consumers with `*_reversal` task
suffix and `Idempotency-Key: request:{request_id}:reward:<role>:reverse`.

**Compensation steps (reverse order)** —

- `compensate_payment_service_driver_earnings_grant` → `payment.void`
- `compensate_payment_service_wallet_grant` → `wallet.debit`
- `compensate_ledger_service_posting` → `ledger.reverse_posting`
- `compensate_notification_service_grant_template` → no-op
  (notification cannot be unsent)
- `compensate_audit_service_reward_row` → append a
  `audit.reward_reversal.v1` row (append-only; cannot unsert)
- `compensate_reporting_service_reward_fact` → append a
  `fact.reward_reversal.v1` row (append-only)

**Output schema**:

```json
{
  "trip_id": "<UUIDv7>",
  "completed_steps": [<integer>],
  "compensated_steps": [<integer>],
  "completion_at": "<RFC3339>",
  "status": "completed" | "compensated" | "failed"
}
```

**Kafka signals (in)**: `trip.reward.granted.v1`,
`trip.reward.reversed.v1` (both via `conductor-kafka-bridge`).

**Kafka events (out)**: emitted by the **workers' outbox** (NOT by
Conductor) — `payment.captured.v1` (driver earnings),
`wallet.credited.v1` (user-side), `ledger.posted.v1`,
`notification.sent.v1`, `audit.reward.v1`, `fact.reward.v1`.

**SLA timers**: full fan-out must complete within 1s p95; reversal
must complete within 300ms p95.

**Owner + participants**: `trip-service` (orchestrator +
`conductor-kafka-bridge` trigger),
`payment-service` (driver earnings + wallet workers),
`ledger-service`, `notification-service`, `audit-service`,
`reporting-service`.

### 3.2 Phase 7.5 — Make-a-Deal kernel

**Workflow IDs**: `wf.phase75.deal_rider.v1`,
`wf.phase75.deal_driver.v1`, `wf.phase75.deal_food.v1`

**Trigger**: API call `POST /v1/deals` to the participating service's
rider-side / driver-side / food-side endpoint. Conductor workflow is
started synchronously by the service's REST handler.

**Input schema**:

```json
{
  "deal_id": "<UUIDv7>",
  "ride_type": "standard" | "premium" | "xl" | "food",
  "rider_id": "<UUIDv7>",
  "driver_id": "<UUIDv7>",
  "initial_offer_minor_units": <integer>,
  "fairness_band_minor_units": <integer>,
  "fairness_band_max_minor_units": <integer>,
  "window_ttl_seconds": <integer>,
  "bid_ttl_seconds": <integer>,
  "max_counter_rounds": <integer>
}
```

**Task list** (driver side; rider side mirrors with `rider_*` task
prefix):

1. `driver_deal_offer_receive` (worker in `driver-service`)
2. `driver_deal_counter_or_accept` (worker in `driver-service`,
   TTL `bid_ttl_seconds`)
3. `pricing_service_fairness_check` (worker in `pricing-service`)
   — `GET /v1/quotes/{id}/fairness-band`
4. `decision_branch` (Conductor `SWITCH` task):
   - **matched**: `deal_match_complete` → emit `ride.deal.matched.v1`
   - **countered**: loop back to step 2 up to `max_counter_rounds`
   - **expired** (window timer fired): `deal_expired_complete` →
     emit `ride.deal.expired.v1`
   - **rejected**: `deal_rejected_complete` → emit
     `ride.deal.rejected.v1`

**Compensation steps (reverse order)** — none. The deal state
machine is not financial; no compensation needed.

**Output schema**: see `docs/shared/DEAL_FEATURE.md`.

**Kafka signals (in)**: deal TTL fires are Conductor-internal
timers; no external Kafka signal.

**Kafka events (out)**: emitted by the **rider-side service's outbox**
(NOT by Conductor) — `ride.deal.matched.v1`,
`ride.deal.expired.v1`, `ride.deal.rejected.v1`,
`ride.deal.countered.v1`, etc.

**SLA timers**: `window_ttl_seconds` (default 60s),
`bid_ttl_seconds` (default 15s), `max_counter_rounds` (default 3).

**Owner + participants**: `trip-service` (rider side),
`driver-service` (driver side), `food-order-service` (food side),
`pricing-service` (fairness check), `notification-service` (deal
templates), `audit-service` (`audit.deal_transition.v1`),
`configuration-service` (key family `deal.*`).

### 3.3 Refund orchestration

**Workflow IDs**: `wf.refund.standard.v1`,
`wf.refund.partial.v1`, `wf.refund.food_reject.v1`,
`wf.refund.cancellation.v1`, `wf.refund.dispute.v1`,
`wf.refund.cod_failed.v1`

**Trigger**: `payment-service` REST endpoint `POST /v1/refunds`
starts the workflow synchronously.

**Input schema**:

```json
{
  "refund_id": "<UUIDv7>",
  "category": "standard" | "partial" | "food_reject" | "cancellation" | "dispute" | "cod_failed",
  "original_payment_id": "<UUIDv7>",
  "amount_minor_units": <integer>,
  "reason_code": "<string>",
  "initiated_by": "customer" | "merchant" | "ops" | "system"
}
```

**Task list (standard refund, 5 steps)**:

1. `payment_service_validate_refund` — `Idempotency-Key: refund:{refund_id}:validate`
2. `payment_service_capture_reversal` — `Idempotency-Key: refund:{refund_id}:capture_reversal`
3. `ledger_service_debit_posting` — `Idempotency-Key: refund:{refund_id}:ledger:posting`
4. `wallet_service_credit` (if original was wallet-funded)
   — `Idempotency-Key: refund:{refund_id}:wallet:credit`
5. `notification_service_refund_template`
   — `Idempotency-Key: refund:{refund_id}:notif`

**Compensation steps (reverse order)** —

- `compensate_notification_service_refund_template` → no-op
- `compensate_wallet_service_credit` → `wallet.debit`
- `compensate_ledger_service_debit_posting` → `ledger.reverse_posting`
- `compensate_payment_service_capture_reversal` → `payment.re_authorized`
- `compensate_payment_service_validate_refund` → no-op

**Dispute refund** extends the task list with a `chargeback_*` step
between steps 2 and 3; see [REFUND_WORKFLOWS.md](../workflows/REFUND_WORKFLOWS.md).

**Output schema**:

```json
{
  "refund_id": "<UUIDv7>",
  "status": "completed" | "compensated" | "failed",
  "completed_at": "<RFC3339>",
  "compensated_steps": [<integer>]
}
```

**Kafka signals (in)**: none (REST trigger only).

**Kafka events (out)**: emitted by `payment-service` outbox —
`payment.refund.initiated.v1`, `payment.refund.completed.v1`,
`payment.refund.failed.v1`.

**SLA timers**: full saga must complete within 5 minutes (per
[ACCOUNTING_WORKFLOWS.md](../workflows/ACCOUNTING_WORKFLOWS.md)
"Refund SLA"). Compensation timer fires at 10 minutes.

**Owner + participants**: `payment-service` (orchestrator + capture
worker), `ledger-service`, `notification-service`,
`customer-service` (customer-notification side-effect),
`restaurant-service` (read-only consumer for food rejections),
`food-order-service` (read-only consumer).

### 3.4 Driver/Courier onboarding

**Workflow IDs**: `wf.onboarding.driver.v1`,
`wf.onboarding.courier.v1`

**Trigger**: `driver-service` / `courier-service` REST endpoint
`POST /v1/drivers/{id}/onboarding` (or `/v1/couriers/{id}/onboarding`).

**Input schema**:

```json
{
  "driver_id": "<UUIDv7>",
  "city_id": "<string>",
  "vehicle_type": "sedan" | "suv" | "motorbike" | "bicycle",
  "documents": [{ "type": "license" | "registration" | "insurance" | "id_card", "file_id": "<UUIDv7>" }],
  "started_at": "<RFC3339>"
}
```

**Task list (driver, 8 steps)**:

1. `identity_service_kyc_start` (worker in `identity-service`)
2. `identity_service_document_verify` (per document, parallel)
3. `fraud_risk_service_risk_score` (worker in `fraud-risk-service`)
4. `admin_service_manual_approval` (HUMAN TASK in `admin-service`
   UI — Conductor user-task with 24h SLA timer)
5. `notification_service_approval_template`
6. `driver_service_training_module_complete` (HUMAN TASK in
   `driver-service` UI — Conductor user-task with 7-day SLA timer)
7. `driver_service_vehicle_inspection` (HUMAN TASK in
   `driver-service` UI — Conductor user-task with 3-day SLA timer)
8. `driver_service_activation` (worker in `driver-service`) →
   emit `driver.activated.v1`

**Compensation steps** — none for the human-task path (a rejected
onboarding is a terminal `rejected` state, not a compensated one).

**Output schema**:

```json
{
  "driver_id": "<UUIDv7>",
  "status": "activated" | "rejected" | "expired",
  "activated_at": "<RFC3339>",
  "documents_verified": [<integer>],
  "risk_score": <float>
}
```

**Kafka signals (in)**: admin approval, training complete, vehicle
inspection complete (all via `conductor-kafka-bridge`).

**Kafka events (out)**: emitted by `driver-service` / `courier-service`
outbox — `driver.onboarding.kyc_started.v1`,
`driver.onboarding.approved.v1`, `driver.onboarding.rejected.v1`,
`driver.activated.v1`, `courier.onboarding.*.v1`,
`courier.activated.v1`.

**SLA timers**: KYC verification 24h, manual approval 24h, training
7 days, vehicle inspection 3 days. SLA breach fires
`driver.onboarding.sla_breach.v1`.

**Owner + participants**: `driver-service` / `courier-service`
(orchestrators + activation workers), `identity-service` (KYC
worker), `admin-service` (manual approval human task),
`fraud-risk-service` (risk-score worker), `notification-service`
(templates), `audit-service` (read-only consumer).

## 4. Compensation conventions

- **Naming**: compensation task names follow
  `compensate_<forward_task_name>`.
- **Idempotency-key namespace**: every forward and compensation task
  uses the same idempotency-key prefix
  `<aggregate>:<id>:<action>` so a replayed compensation cannot
  double-apply.
- **Reverse-order**: Conductor's default `compensationSteps` block
  runs in reverse order automatically; per-flow overrides are encoded
  in the workflow JSON.
- **No-op compensation**: when a forward step has no compensation
  (e.g. notification send, audit row append), the compensation task
  is a no-op that still emits a `compensation.completed.v1` metric
  for SLO observability.
- **Append-only inviolable rows**: financial ledger postings,
  audit rows, and reporting fact rows are append-only
  (per [DATABASE_ARCHITECTURE.md](../architecture/DATABASE_ARCHITECTURE.md)
  "Append-only financial state"); their compensation is a **new
  reversal row**, never an UPDATE/DELETE.

## 5. Worker SDK conventions

| Language | SDK | Version | Notes |
|---|---|---|---|
| Kotlin/Java | `io.conductor:conductor-client` | 3.x | Spring Boot starter auto-config |
| Go | `github.com/conductor-sdk/conductor-go` | 1.x | Idiomatic worker interface |
| TypeScript/Node | `@io-conductor/conductor-typescript` | 1.x | None of the four flows require new TS workers |
| Python | `conductor-python` | 1.x | FastAPI integration via dependency |

Workers register against Conductor at startup; lost workers are
detected via 5s heartbeat and tasks are reassigned within 30s.

Each service's `INTEGRATION.md` "Conductor Workers" lists the exact
task names, idempotency-key namespaces, and configuration keys for
its workers.

## 6. Kafka signal adapter

The `conductor-kafka-bridge` deployment is the single integration
point between the platform's Kafka event bus and the Conductor
engine.

**Inbound** (Kafka → Conductor signal):

| Kafka topic | Conductor signal | Workflow ID |
|---|---|---|
| `trip.reward.granted.v1` | `tripRewardGrantedSignal` | `wf.phase7.reward_grant.v1` |
| `trip.reward.reversed.v1` | `tripRewardReversedSignal` | `wf.phase7.reward_reversal.v1` |
| `food.order.rejected.v1` | `foodOrderRejectedSignal` | `wf.refund.food_reject.v1` |
| `payment.refund.requested.v1` | `paymentRefundRequestedSignal` | `wf.refund.<category>.v1` |
| `driver.onboarding.approved.v1` | `driverOnboardingApprovedSignal` | `wf.onboarding.driver.v1` |
| `courier.onboarding.approved.v1` | `courierOnboardingApprovedSignal` | `wf.onboarding.courier.v1` |

The bridge translates Kafka header `Idempotency-Key` to Conductor
`correlationId` so retries are deduplicated.

**Outbound** (Conductor completion → Kafka):

The bridge emits `conductor.workflow.completed.v1` (and
`*.failed.v1`, `*.compensated.v1`) on each workflow terminal
transition. Other platform services consume these via standard
Kafka + inbox.

## 7. Observability

- **Conductor UI**: `https://conductor.<env>.uber.io` — read-only,
  per-env, RBAC-gated via the `platform.admin` role.
- **Workflow history export**: every workflow's history is exported
  to `reporting-service` (data lake) via Kafka topic
  `conductor.workflow.history.v1`.
- **Metrics**: scraped as Prometheus targets:
  `conductor_workflow_started_total`,
  `conductor_workflow_completed_total`,
  `conductor_workflow_failed_total`,
  `conductor_task_duration_seconds`,
  `conductor_compensation_total`.
- **Tracing**: OTel traces propagated from Conductor task → worker →
  downstream service → outbox.
- **Logging**: structured JSON logs from `conductor-server` and each
  worker's invocation context.

## 8. Operational runbook

- **Re-drive a stuck workflow**: `POST /api/workflow/{id}/restart` via
  the Conductor admin API (admin-service scopes).
- **Manually compensate**: `POST /api/workflow/{id}/compensate` via
  the Conductor admin API.
- **Deploy a new workflow version**: `POST /api/metadata/workflow/{id}?version=N+1`
  with the new JSON DSL. In-flight runs remain pinned to their start
  version.
- **Drain a worker for deploy**: scale the worker pod replicas to 0
  via `kubectl scale deployment/<service> --replicas=0 --field-selector=role=conductor-worker`;
  Conductor reassigns in-flight tasks to other replicas.

### 8.1 Live state API

The runtime source of truth for a Conductor workflow is the Conductor
UI at `https://conductor.<env>.uber.io`. For programmatic access, the
admin-service exposes a read-only live-state API per
[`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.18:

- `GET /v1/admin/conductor/workflows/{id}/state` — get live state for a
  single workflow run, or list active runs across the workflow ID when
  no run is specified.

The endpoint is gated by `platform.admin` minimum. Returns
`workflow_id`, `runs[]` (each with `run_id`, `owner_service`,
`current_step`, `available_actions[]`, `sla_timer_status`,
`actor_role_required`, `started_at`, `last_updated_at`,
`run_history_summary`). Pagination by 100; filters by owner service,
date range, SLA timer breached.

This endpoint is mirrored in [`MASTER_TASK.md`](../MASTER_TASK.md) 12
"Workflow Live State (doc-side projection)" — the 12 table is a
doc-side projection of the runtime state; the canonical runtime
source is the endpoint + the Conductor UI.

For the new service-request workflows (3.5), the live-state API
returns the same fields; the `current_step` is whichever Conductor
worker task is in flight, and `available_actions` is the set of
state-machine transitions available at the current step
(`[approve]`, `[deny]`, `[cancel]`, etc.).

## 9. Per-service INTEGRATION.md cross-references

Every service that participates in any of the 13 workflows has a
`## <N+1>. Conductor Workers` section in its
`services/<svc>/INTEGRATION.md`. The cross-references below link to
those sections:

| Service | INTEGRATION.md anchor |
|---|---|
| `trip-service` | `services/trip-service/INTEGRATION.md#conductor-workers` |
| `driver-service` | `services/driver-service/INTEGRATION.md#conductor-workers` |
| `courier-service` | `services/courier-service/INTEGRATION.md#conductor-workers` |
| `payment-service` | `services/payment-service/INTEGRATION.md#conductor-workers` |
| `pricing-service` | `services/pricing-service/INTEGRATION.md#conductor-workers` |
| `customer-service` | `services/customer-service/INTEGRATION.md#conductor-workers` |
| `notification-service` | `services/notification-service/INTEGRATION.md#conductor-workers` |
| `ledger-service` | `services/ledger-service/INTEGRATION.md#conductor-workers` |
| `audit-service` | `services/audit-service/INTEGRATION.md#conductor-workers` |
| `reporting-service` | `services/reporting-service/INTEGRATION.md#conductor-workers` |
| `identity-service` | `services/identity-service/INTEGRATION.md#conductor-workers` |
| `admin-service` | `services/admin-service/INTEGRATION.md#conductor-workers` |
| `fraud-risk-service` | `services/fraud-risk-service/INTEGRATION.md#conductor-workers` |
| `food-order-service` | `services/food-order-service/INTEGRATION.md#conductor-workers` |
| `restaurant-service` | `services/restaurant-service/INTEGRATION.md#conductor-workers` |

## 10. Append-not-renumber note

This document is the canonical reference for every Conductor
workflow. Workflow definitions live in JSON files deployed via the
Conductor metadata API; per-service `INTEGRATION.md` "Conductor
Workers" links here. When adding a new workflow or modifying an
existing one, append a new 3.x section here and bump the version
in the workflow JSON — do not renumber existing sections.


### 3.5 Service-request workflow family

**NOT customer-facing.** The `wf.service_request.*.v1` workflows are
admin/operator access/change/onboarding requests owned by `admin-service`.
They are NOT related to the customer-facing `request_id` polymorphism introduced
in [ADR-0020](../../architecture/adrs/0020-polymorphic-request-id.md).

Per [ADR-0018](adrs/0018-workflow-engine-conductor.md) and the
super-admin preset alignment (per
[`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md)),
the platform adopts four new Conductor workflows covering operator/admin
self-service requests. All four are owned by `admin-service`; workers
are colocated in `admin-service` and `identity-service`. Trigger is a
REST endpoint on `admin-service` (the start is synchronous, the
worker tasks are async).

#### 3.5.1 `wf.service_request.access.v1`

**Trigger**: `POST /v1/admin/access-requests` on `admin-service`.

**Input schema**:

```json
{
  "request_id": "<UUIDv7>",
  "requester_id": "<UUIDv7>",
  "target_role": "<role_name>",
  "scope": "<service-name or '*'>",
  "justification": "<string ≥ 32 chars>",
  "duration_seconds": <integer, optional; defaults to 86400>
}
```

**Task list**:

1. `admin_service_request_validate` (worker in `admin-service`)
2. `admin_service_fraud_risk_review` (worker in `fraud-risk-service`)
3. `admin_service_manual_approval` (HUMAN TASK in `admin-service` UI;
   gated by `platform.admin`; 24h SLA)
4. `identity_service_role_grant` (worker in `identity-service`)
5. `audit_service_record_grant` (worker in `audit-service`)

**Compensation steps (reverse order)**:

- `compensate_audit_service_record_grant` → no-op
- `compensate_identity_service_role_grant` → `identity.role.revoke`
- `compensate_admin_service_manual_approval` → no-op
- `compensate_admin_service_fraud_risk_review` → no-op
- `compensate_admin_service_request_validate` → no-op

**Output schema**:

```json
{
  "request_id": "<UUIDv7>",
  "status": "approved" | "denied" | "compensated",
  "approved_at": "<RFC3339>",
  "expires_at": "<RFC3339, optional>"
}
```

**Kafka signals (in)**: none (REST trigger).

**Kafka events (out)** (emitted by `admin-service` outbox):

- `admin.access_request.opened.v1`
- `admin.access_request.approved.v1` or `admin.access_request.denied.v1`
- `identity.role.granted.v1` (one per granted role)

**SLA timer**: 24h from `approved_at` for the alias (if `duration_seconds` provided) — auto-revoke via `shared/TIME_BOUNDED_ALIASES.md`.

**Owner + participants**: `admin-service` (orchestrator + manual
approval worker), `fraud-risk-service` (review worker),
`identity-service` (grant worker), `audit-service` (record worker).

#### 3.5.2 `wf.service_request.change.v1`

**Trigger**: `POST /v1/admin/change-requests` on `admin-service`.

**Input schema**:

```json
{
  "request_id": "<UUIDv7>",
  "requester_id": "<UUIDv7>",
  "change_kind": "rollback" | "config_edit" | "feature_flag_toggle" | "service_deploy" | "data_migration",
  "target_service": "<service-name>",
  "target_resource_id": "<resource-id>",
  "justification": "<string ≥ 64 chars>",
  "rollback_plan": "<string ≥ 32 chars>",
  "blast_radius": "low" | "medium" | "high"
}
```

**Task list**:

1. `admin_service_change_validate` (worker in `admin-service`)
2. `configuration_service_impact_assessment` (worker in `configuration-service`)
3. `audit_service_change_impact_emit` (worker in `audit-service`)
4. `notification_service_change_announce` (worker in `notification-service`)
5. `admin_service_co_signer_review` (HUMAN TASK; requires a different
   `platform.super_admin` than the requester; 48h SLA)
6. `admin_service_change_apply` (worker in `admin-service`)
7. `configuration_service_change_verify` (worker in `configuration-service`)

**Compensation steps (reverse order)**:

- `compensate_configuration_service_change_verify` → revert to baseline
- `compensate_admin_service_change_apply` → `change.undo`
- `compensate_admin_service_co_signer_review` → no-op
- `compensate_notification_service_change_announce` → no-op
- `compensate_audit_service_change_impact_emit` → no-op
- `compensate_configuration_service_impact_assessment` → no-op
- `compensate_admin_service_change_validate` → no-op

**Output schema**:

```json
{
  "request_id": "<UUIDv7>",
  "status": "approved" | "denied" | "applied" | "rolled_back",
  "applied_at": "<RFC3339>",
  "rolled_back_at": "<RFC3339, optional>"
}
```

**Kafka events (out)** (emitted by `admin-service` outbox):

- `admin.change_request.opened.v1`
- `admin.change_request.approved.v1` or `admin.change_request.denied.v1`
- `admin.change_request.applied.v1`
- `admin.change_request.rolled_back.v1`

**SLA timer**: 48h from open to apply; auto-rollback window 24h
post-apply (configurable per change).

**Owner + participants**: `admin-service` (orchestrator + change
apply + co-signer review), `configuration-service` (impact assessment
+ verify), `audit-service` (emit), `notification-service` (announce).

#### 3.5.3 `wf.service_request.service_onboarding.v1`

**Trigger**: `POST /v1/admin/service-onboarding-requests` on
`admin-service`. Operator requests a new service or a major version
upgrade.

**Input schema**:

```json
{
  "request_id": "<UUIDv7>",
  "requester_id": "<UUIDv7>",
  "service_name": "<string>",
  "version": "<semver>",
  "is_new_service": <boolean>,
  "deployment_runbook_url": "<string>",
  "rollback_runbook_url": "<string>"
}
```

**Task list**:

1. `admin_service_onboarding_validate` (worker in `admin-service`)
2. `configuration_service_provision_keys` (worker in `configuration-service`)
3. `identity_service_provision_realm_client` (worker in `identity-service`)
4. `audit_service_record_onboarding` (worker in `audit-service`)
5. `notification_service_onboarding_announce` (worker in `notification-service`)
6. `platform_review_board_approval` (HUMAN TASK; requires
   `platform.super_admin`; 72h SLA; 2 of 3 board members required)
7. `admin_service_onboarding_complete` (worker in `admin-service`)
8. `admin_service_onboarding_emit_event` (worker in `admin-service`)

**Compensation steps (reverse order)**:

- `compensate_admin_service_onboarding_emit_event` → no-op
- `compensate_admin_service_onboarding_complete` → no-op
- `compensate_platform_review_board_approval` → no-op
- `compensate_notification_service_onboarding_announce` → no-op
- `compensate_audit_service_record_onboarding` → no-op
- `compensate_identity_service_provision_realm_client` → `client.delete`
- `compensate_configuration_service_provision_keys` → `config.delete`
- `compensate_admin_service_onboarding_validate` → no-op

**Output schema**:

```json
{
  "request_id": "<UUIDv7>",
  "status": "approved" | "denied" | "deployed",
  "deployed_at": "<RFC3339>"
}
```

**Kafka events (out)** (emitted by `admin-service` outbox):

- `admin.service_onboarding.opened.v1`
- `admin.service_onboarding.approved.v1` or `.denied.v1`
- `admin.service_onboarding.deployed.v1`

**SLA timer**: 72h from open to approval.

**Owner + participants**: `admin-service`, `configuration-service`,
`identity-service`, `audit-service`, `notification-service`. The
`platform_review_board_approval` HUMAN TASK requires a quorum of 2 of
3 platform-review-board members (`platform.super_admin`).

#### 3.5.4 `wf.service_request.time_bounded_alias.v1`

**Trigger**: `POST /v1/admin/access-requests/{id}/alias` on
`admin-service` (the request ID references a previously-opened access
request). The operator requests a time-bounded SUPER_ADMIN alias per
[`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md).

**Input schema**:

```json
{
  "request_id": "<UUIDv7>",
  "requester_id": "<UUIDv7>",
  "alias_ttl_seconds": <integer>,
  "justification": "<string ≥ 32 chars>",
  "incident_id": "<UUIDv7, optional>"
}
```

**Task list**:

1. `admin_service_alias_validate` (worker in `admin-service`)
2. `audit_service_alias_intent_record` (worker in `audit-service`)
3. `notification_service_security_pages` (worker in
   `notification-service`; pages security on-call)
4. `co_signer_approval` (HUMAN TASK; requires a different
   `platform.super_admin` than the requester; 24h SLA)
5. `identity_service_time_bounded_grant` (worker in `identity-service`;
   sets `super_admin_grant.expires_at = now() + alias_ttl_seconds`)
6. `audit_service_alias_grant_record` (worker in `audit-service`)

**Compensation steps (reverse order)**:

- `compensate_audit_service_alias_grant_record` → no-op
- `compensate_identity_service_time_bounded_grant` →
  `identity.role.revoke` (immediate)
- `compensate_co_signer_approval` → no-op
- `compensate_notification_service_security_pages` → no-op
- `compensate_audit_service_alias_intent_record` → no-op
- `compensate_admin_service_alias_validate` → no-op

**Output schema**:

```json
{
  "request_id": "<UUIDv7>",
  "status": "approved" | "denied" | "expired" | "revoked",
  "expires_at": "<RFC3339>",
  "revoked_at": "<RFC3339, optional>"
}
```

**Kafka events (out)** (emitted by `admin-service` outbox):

- `admin.alias_request.opened.v1`
- `admin.alias_request.approved.v1` or `.denied.v1`
- `admin.alias_request.expired.v1` (auto-revoke at expiry)
- `admin.alias_request.revoked.v1` (manual revoke before expiry)
- `identity.role.granted.v1` (with `expires_at` header)
- `identity.role.revoked.v1` (on expiry or manual revoke)

**SLA timer**: 24h from open to co-signer approval; alias TTL is
operator-specified (typically 24h-14d per `shared/TIME_BOUNDED_ALIASES.md`
5).

**Owner + participants**: `admin-service` (orchestrator + alias
validate), `audit-service` (intent + grant record), `notification-service`
(security pages), `identity-service` (time-bounded grant).

### 3.6 Request orchestration pattern

The canonical request orchestrator is started **synchronously** by the owning
service at request creation time. Its workflow ID is
`wf.process.{service}.{request_id}.v1` and is persisted on the `requests.workflow_process_id`
column in the same transaction that inserts the request row.

**Workflow ID**: `wf.process.{service}.{request_id}.v1`

**Trigger**: REST handler in the owning service (`POST /v1/rides` for trip,
`POST /v1/orders` for food-order). The handler inserts `trip.requests` (or
`food_order.requests`) with the new `request_id` and `workflow_process_id`
in the same local transaction, then returns `202 Accepted` with the
`request_id` to the caller.

**Input schema**:

```json
{
  "request_id": "<UUIDv7>",
  "service": "trip" | "food_order" | "courier_delivery",
  "customer_id": "<UUIDv7>"
}
```

The `workflow_process_id` stamped on the `requests` row is
`wf.process.{service}.{request_id}.v1` — derived directly from the input.

**Task list** — owned by the owning service; drives the lifecycle state
machine:

1. `request_match` — match driver/courier (trip: driver; food_order: courier;
   courier_delivery: courier). Emits `request.matched.v1`.
2. `request_active` — transition to `in_progress`. Emits `request.in_progress.v1`.
3. `request_complete` — terminal success. Emits `request.completed.v1`.

Failure and cancellation paths emit `request.failed.v1` or `request.cancelled.v1`
and trigger compensation via the downstream Conductor workflows
(`wf.phase7.reward_reversal.v1`, `wf.refund.*.v1`, etc.) using
`request:{request_id}:...` idempotency keys.

**Output schema**:

```json
{
  "request_id": "<UUIDv7>",
  "workflow_process_id": "wf.process.{service}.{request_id}.v1",
  "status": "requested" | "matched" | "in_progress" | "completed" | "cancelled" | "failed",
  "completed_at": "<RFC3339, optional>"
}
```

**Kafka signals (out)**: `request.created.v1` (on insert),
`request.matched.v1`, `request.in_progress.v1`, `request.completed.v1`,
`request.cancelled.v1`, `request.failed.v1`.

**Kafka signals (in)**: downstream services that need to correlate to the
request lifecycle subscribe to the `request.*.v1` topic; the
`workflow_process_id` in the event payload enables direct correlation to
the Conductor run.

**SLA timers**: defined per concrete aggregate state machine; the
`wf.process.{service}.{request_id}.v1` workflow itself has no hard SLA —
it is the container for the state machine. Conductor reassigns stuck tasks
within 30s (per §1).

**Owner + participants**: owning service (orchestrator + state machine),
`payment-service` (saga participants via `request:{request_id}:...`
idempotency keys), `notification-service` (templates driven by `service` +
`request_id`), `ledger-service` / `audit-service` / `reporting-service`
(downstream consumers of `request.*.v1` events).

**Cross-references**:
- [ADR-0020](../../architecture/adrs/0020-polymorphic-request-id.md) — schema,
  naming conventions, event taxonomy.
- [`../TYPE_CATALOG.md`](./TYPE_CATALOG.md) §10 — `wf.process.{service}.{request_id}.v1`
  format definition.
- [`RIDE_WORKFLOWS.md`](../workflows/RIDE_WORKFLOWS.md) — ride-side
  lifecycle with `request.*.v1` parent events.
- [`FOOD_ORDER_WORKFLOWS.md`](../workflows/FOOD_ORDER_WORKFLOWS.md) —
  food-order lifecycle with `request.*.v1` parent events.

---

## 11. Phase 7.7 — In-App Chat events (cross-cutting, *not* Conductor workflows)

`chat-service` (Phase 7.7) is the **21st active service** but is
**not a Conductor workflow participant**. Chat lifecycle is an
in-service saga owned by `chat-service`: thread bootstrap + close
happen in reaction to `trip.*.v1` / `food.order.*.v1` /
`delivery.*.v1` events, and the per-message send / fan-out / offline
fallback is owned by `chat-service` itself (outbox + Redis Pub/Sub).

The events below are listed here because they participate in the
**same cross-cutting choreography** as the Conductor workflows (a
single timeline of side effects that consumers — `notification-service`,
`admin-service`, `fraud-risk-service` — already wire up).

| Event | Producer | Consumer(s) | When |
|-------|----------|-------------|------|
| `chat.thread.created.v1` | `chat-service` | `notification-service` (in-app banner), `audit-service`, `reporting-service` | on bootstrap (consumed `ride.request.matched.v1` / `food.order.accepted.v1` / `delivery.courier.assigned.v1`) |
| `chat.thread.closed.v1` | `chat-service` | `notification-service`, `audit-service`, `reporting-service` | on terminal event (`trip.completed.v1` / `food.order.delivered.v1` / `delivery.completed.v1` / cancellation variants) |
| `chat.message.sent.v1` | `chat-service` | `audit-service`, `reporting-service`, `search-service` (admin-only) | every accepted send |
| `chat.message.read.v1` | `chat-service` | `reporting-service` | every read receipt |
| `chat.attachment.shared.v1` | `chat-service` | `audit-service`, `reporting-service`, `fraud-risk-service` | every attachment scan success |
| `chat.message.reported.v1` | `chat-service` | `admin-service` (support ticket for `safety`/`abuse`/`illegal`), `fraud-risk-service` (abuse signal), `audit-service` | every report |
| `chat.message.moderated.v1` | `chat-service` | `reporting-service`, `audit-service` | admin hide / remove |
| `chat.message.offline_delivery_required.v1` | `chat-service` | `notification-service` (push), `audit-service` | recipient offline |
| `chat.user.blocked.v1` / `chat.user.muted.v1` / `chat.user.banned.v1` | `chat-service` | `reporting-service`, `audit-service` | every user-level action |
| `chat.user.gdpr_erased.v1` | `chat-service` | `audit-service`, `reporting-service` | GDPR sweep |

The **bootstrap / close pairing** is the critical cross-service
contract:

```
trip-service                chat-service                    trip-service events it consumes
─────────────               ─────────────                    ───────────────────────────────
ride.request.matched.v1  ─► create trip_chat
                           ◄── chat.thread.created.v1
trip.arrived.v1          ─► system message: "driver has arrived"
trip.started.v1          ─► system message: "trip started"
                           (chat becomes available to rider + driver)
trip.completed.v1        ─► close trip_chat + final system message
trip.cancelled.v1        ─► close trip_chat + reason
                           ◄── chat.thread.closed.v1
```

The same shape applies for `food_order_chat` (anchored on
`food.order.accepted.v1` / `food.order.delivered.v1` /
`food.order.cancelled.v1` / `food.order.preparing.v1` /
`food.order.ready.v1`) and `delivery_chat` (anchored on
`delivery.courier.assigned.v1` / `delivery.pickup.v1` /
`delivery.completed.v1` / `delivery.cancelled.v1`).

### 11.1 Why chat is NOT a Conductor workflow

- The bootstrap / close pairing is **eventually consistent** (the
  thread may be created up to N seconds after the matched event; the
  service tolerates the gap).
- The thread is a **local read-write resource**, not a multi-step
  cross-service computation — there is no compensation graph.
- The platform already pays the cost of an in-service saga (ADR-0010)
  for the matching / dispatch / capture flows; chat lifecycle fits
  the same pattern.

If a future v2 introduces **multi-party chat with escalation**
(support tickets joined into a thread mid-flow), that becomes a
candidate for a Conductor workflow `wf.phase77.support_chat.v1`
following the same shape as `wf.refund.dispute.v1`. The v1 chat
service is intentionally simple.

### 11.2 Critical-path tasks (cross-reference)

The 47 `T-CHAT-*` tasks in [`MASTER_TASK.md` 13.1](../MASTER_TASK.md#13-phase-77--in-app-chat-registry-added-2026-08-12)
are the implementation tracking. The 8 cross-service integration
tasks (T-<SVC>-P77-01) in [`MASTER_TASK.md` 13.2](../MASTER_TASK.md#132-phase-77-participation-in-existing-services)
register the participation of `trip-service`, `food-order-service`,
`courier-service`, `restaurant-service`, `notification-service`,
`admin-service`, `fraud-risk-service`, `api-gateway`.

The 4 critical-path edges are in
[`MASTER_TASK.md` 13.3](../MASTER_TASK.md#133-phase-77-critical-path-tasks):
`chat-service` binary + DDL → all consumers; Redis Pub/Sub fan-out
→ offline fallback; `notification-service` consumer live before
offline delivery; `admin-service` consumer live before report →
ticket flow.

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary

