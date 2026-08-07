# ADR-0018: Netflix Conductor as Workflow Engine for Cross-Cutting Flows

- Status: Accepted
- Date: 2026-08-06
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: workflows, orchestrator, conductor, saga, compensation, distributed-transactions, phase-7, phase-7-5, service-request

> **Scope clarification (2026-08-06, appended per append-not-renumber):**
> This ADR was originally drafted covering **4 flow classes** (Phase
> 7 rewards fan-out / Phase 7.5 Make-a-Deal / refund orchestration /
> driver+courier onboarding). On the same day, the **service-request
> flow family** was approved and added to the Conductor scope,
> bringing the final **17 workflows across 5 flow families**
> documented in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md)
> 3. The 4 additional service-request workflows are owned by
> `admin-service` and integrate the SUPER_ADMIN preset / time-bounded
> aliases contract. See the "Scope clarification: service-request
> family" addendum below for the full row; the original four-flow
> table and consequences above remain canonical for those flows.

## Context and Problem Statement

[ADR-0010](0010-saga-pattern.md) chose in-service sagas — orchestrated
inside the service that owns the root aggregate (e.g.
`payment-service` ride-saga and food-saga) — for all distributed
workflows. That decision was correct for the platform's flow depth at
the time (3–7 step financial flows).

Since ADR-0010 was accepted (2026-07-29), four new flow classes have
emerged whose characteristics exceed the in-service pattern's sweet
spot:

1. **Multi-consumer fan-out > 6** — Phase 7 — Guaranteed Rewards
   fans out `trip.reward.granted.v1` to 6 consumers
   (`payment-service` driver-earnings, `payment-service` wallet,
   `ledger-service`, `notification-service`, `audit-service`,
   `reporting-service`) with strict ordering, per-step idempotency
   keys, and a corresponding reversal event.
2. **TTL-driven timer transitions** — Phase 7.5 — Make-a-Deal kernel
   runs the state machine
   `open → negotiating → countered → matched/expired/rejected` with
   TTLs `deal.window.ttl_seconds`, `deal.bid.ttl_seconds`,
   `deal.max_counter_rounds`. Today timers are implemented as
   scheduled jobs in each participating service.
3. **Long-running human-task workflows** — Driver and Courier
   onboarding run for days-to-weeks with human approval steps at the
   admin-service UI; today's hand-rolled state machine must be
   hand-pollable for SLA timers.
4. **Compensation matrices with N-step rollback ordering** — Refund
   orchestration has 6 categories (standard, partial, food-reject,
   cancellation, dispute, COD-failed) each with a different forward /
   compensation sequence; the in-service pattern scales linearly with
   the number of compensation branches.

The decision is whether to **adopt Netflix Conductor as a workflow
engine for these four flows**, keep the in-service saga pattern for
all flows (status quo from ADR-0010), or pick another engine
(Temporal, Camunda 8 Zeebe, LittleHorse).

### Scope of the supersession

ADR-0018 supersedes **only** the workflow-engine-rejected section of
ADR-0010 for the four flows listed above. The in-service saga pattern
remains the default for every other workflow, including the
`payment-service` ride-saga and food-saga (T0 / 99.99% SLO). This is
a **targeted adoption** — not a wholesale replacement.

## Decision Drivers

- **Polyglot SDK coverage.** The platform is Kotlin/Spring, Go, Node/TS,
  and Python/FastAPI. The chosen engine must offer first-class SDKs for
  every language in active service development.
- **Saga compensation primitive.** The refund orchestration's
  multi-step rollback ordering is a hard requirement; the chosen
  engine must offer a first-class compensation construct.
- **Event-sourced audit trail.** Financial flows are SOX-auditable.
  The chosen engine must expose a durable, queryable history.
- **In-flight versioning.** Workflow definitions evolve weekly; the
  chosen engine must pin in-flight runs to the version they started
  with.
- **Kafka integration.** The platform is Kafka-first. The chosen
  engine must consume Kafka signals and publish workflow completion
  events back to Kafka without a custom adapter per flow.
- **OSS license policy.** The chosen engine must be Apache-2.0 or a
  similar permissive OSS license (the platform already lists
  HashiCorp Vault's BUSL-1.1 and Grafana's AGPL-3.0 for UI-tier
  only). See [OSS_DEPENDENCIES.md](../../shared/OSS_DEPENDENCIES.md).
- **Multi-region support.** The platform's deployment baseline is
  multi-region active-active per [PLATFORM_BASELINE.md](../../shared/PLATFORM_BASELINE.md).
  The chosen engine must not regress this.
- **Operational tractability.** The platform team explicitly avoids
  adding a new piece of infrastructure to operate unless the value
  exceeds the operational cost (per ADR-0010 "Decision drivers").
- **Existing 99.99% SLO financial flows stay unchanged.** The
  `payment-service` ride-saga and food-saga must not be disturbed
  by this decision.

## Considered Options

- **Status quo — in-service saga (ADR-0010)** — keep all workflows
  in service code.
- **Apache Airflow** — Python DAG orchestration.
- **Temporal** — MIT; polyglot first-class SDKs; event-sourced
  history; per-namespace pricing model.
- **Netflix Conductor (Apache-2.0)** — JSON-spec-first + typed
  workers; first-class `compensationSteps`; broadest SDK line-up
  (Java/Python/Go/JS/TS/C#/Ruby/Rust); native Kafka source/sink;
  battle-tested at Netflix, Tesla, LinkedIn, JP Morgan, Swiggy.
- **Camunda 8 (Zeebe)** — Apache-2.0 core; requires Enterprise tier
  for HA and multi-region; BPMN-first authoring.
- **LittleHorse** — Apache-2.0; emerging; no TypeScript SDK yet.

## Decision Outcome

Chosen option: **"Netflix Conductor (Apache-2.0) for the four new
cross-cutting flows; in-service saga (ADR-0010) remains the default
for all other workflows"**, because:

1. Conductor's `compensationSteps` primitive is first-class and maps
   directly to the refund orchestration's N-step rollback ordering.
2. Conductor's SDK lineup covers every polyglot service language in
   this platform — Kotlin/Java (`payment-service`, `trip-service`,
   `restaurant-service`, `food-order-service`, `pricing-service`,
   `courier-service`, `customer-service`, `driver-service`, `admin-service`,
   `notification-service`, `ledger-service`, `audit-service`, `reporting-service`,
   `configuration-service`, `search-service`), Go (`api-gateway`,
   `file-service`, `audit-service`, `geolocation-service`), and
   Python/FastAPI (`fraud-risk-service`). Node/TS services
   (`identity-service`) are not participants in the four flows, so
   SDK maturity there is not on the critical path.
3. Apache-2.0 matches the platform's OSS policy (per
   [OSS_DEPENDENCIES.md](../../shared/OSS_DEPENDENCIES.md)). The
   platform already lists BUSL-1.1 (HashiCorp Vault) and AGPL-3.0-only
   (Grafana — UI tier only), so Apache-2.0 is fully compatible.
4. JSON-spec-first workflow definitions are reviewable by Ops and
   Compliance (the JSON DSL is the same artifact reviewed for change
   control as Avro schemas today).
5. Native Kafka source/sink removes the adapter layer Temporal needs
   for Kafka signal bridging.
6. Existing 99.99% SLO `payment-service` sagas are not displaced —
   targeted adoption protects the highest-criticality financial flow.
7. Operational footprint (server cluster + Elasticsearch + Redis)
   is comparable to the in-service saga's own observability footprint
   (Postgres state + outbox + inbox + reconciliation) — the
   operational delta is bounded.

The four flows that move to Conductor are:

| Flow | Workflow ID prefix | Owner service | Participating services |
|---|---|---|---|
| Phase 7 — Guaranteed Rewards fan-out | `wf.phase7.reward_*` | `trip-service` | `payment-service`, `pricing-service`, `customer-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service`, `configuration-service` |
| Phase 7.5 — Make-a-Deal kernel | `wf.phase75.deal_*` | `trip-service` / `food-order-service` | `pricing-service`, `configuration-service`, `notification-service`, `audit-service`, `driver-service`, `courier-service` |
| Refund orchestration | `wf.refund.*` | `payment-service` | `payment-service`, `ledger-service`, `notification-service`, `customer-service`, `restaurant-service`, `food-order-service` |
| Driver/Courier onboarding | `wf.onboarding.{driver,courier}` | `driver-service` / `courier-service` | `identity-service`, `admin-service`, `fraud-risk-service`, `notification-service`, `audit-service` |

See [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md)
for the canonical workflow definitions, task names, compensation
steps, and Kafka signal mappings.

### Scope clarification: service-request family (appended 2026-08-06)

On the same day this ADR was accepted, the **service-request flow
family** was approved for Conductor orchestration. It brings the
total to **17 workflows across 5 flow families across 15
participating services** per
[`shared/CONDUCTOR_WORKFLOWS.md` 3](../../shared/CONDUCTOR_WORKFLOWS.md).

| Flow | Workflow ID prefix | Owner service | Participating services | Why Conductor |
|---|---|---|---|---|
| **Service-request** (NEW) | `wf.service_request.*` | `admin-service` | `admin-service`, `identity-service`, `fraud-risk-service`, `configuration-service`, `audit-service`, `notification-service` | Operator-initiated self-service with formal HUMAN TASK approvals (platform.admin / platform.super_admin), time-bounded alias TTLs (per [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md)), and 2-of-2 / 2-of-3 quorum gates |

The four service-request workflow IDs added:

- `wf.service_request.access.v1` (5 tasks, HUMAN TASK approval by
  `platform.admin`, 24h SLA, optional time-bounded alias TTL) —
  triggers `POST /v1/admin/access-requests` per
  [`admin-service/INTEGRATION.md` 1.17](../../services/admin-service/INTEGRATION.md).
- `wf.service_request.change.v1` (7 tasks, co-signer HUMAN TASK by
  a different `platform.super_admin`, 48h SLA, configurable
  `blast_radius` ∈ {low, medium, high}, 24h post-apply rollback
  window) — triggers `POST /v1/admin/change-requests`.
- `wf.service_request.service_onboarding.v1` (8 tasks, 2-of-3
  quorum HUMAN TASK `platform.super_admin` platform-review-board,
  72h SLA) — triggers `POST /v1/admin/service-onboarding-requests`.
- `wf.service_request.time_bounded_alias.v1` (6 tasks, co-signer
  HUMAN TASK, 24h SLA) — triggers `POST /v1/admin/access-requests/{id}/alias`
  and pairs with the [[trips-enjoy-super-admin-preset-management]] alias
  lifecycle.

All four follow the same compensation convention as the original
four flows: reverse-order auto-compensation via Conductor
`compensationSteps`, idempotency-key namespace
`<aggregate>:<id>:<action>`, append-only rows (ledger, audit,
reporting) get a **reversal row** never UPDATE/DELETE — mirroring
the reversal rule from
[[accounting-four-layer-truth-model]].

### Consequences

- **Good:** Conductor's `compensationSteps` primitive gives refund
  orchestration a first-class rollback ordering that the in-service
  pattern must hand-roll per flow.
- **Good:** Conductor's polyglot SDK lineup covers every participating
  service language.
- **Good:** Conductor's `version` field pins in-flight workflows to
  the version they started with, mirroring our existing event-version
  policy (`EVENT_ARCHITECTURE.md` "Event versioning").
- **Good:** Kafka-native signal source and event sink eliminate the
  adapter layer Temporal would require.
- **Good:** JSON-spec-first definitions enable Ops/Compliance review
  without code change-control.
- **Good:** Targeted adoption preserves the 99.99% SLO of the
  `payment-service` ride-saga and food-saga.
- **Good:** Workflow history export to `reporting-service` (data
  lake) extends the existing audit and reconciliation architecture.
- **Bad:** Conductor introduces a new on-call surface
  (conductor-server cluster + Elasticsearch + Redis). Mitigation: every
  per-service `INTEGRATION.md` "Conductor Workers" section lists
  runbook links; the Phase 7.6 stub tasks in each participating
  service's `PLAN.md` include operational-readiness items.
- **Bad:** Workers colocated in service binaries couple the service
  to Conductor's availability. Mitigation: each worker is a typed
  adapter implementing a `ConductorTask` interface; the service runs
  unchanged if Conductor is unreachable (the durable outbox holds the
  work; the Kafka signal adapter buffers); ADR-0018 commits to a
  chaos test that asserts no event loss when Conductor is unavailable
  for > 5 minutes.
- **Bad:** JSON DSL can drift from the canonical event catalog.
  Mitigation: `shared/CONDUCTOR_WORKFLOWS.md` 3 mandates DSL review
  against [EVENT_ARCHITECTURE.md](../EVENT_ARCHITECTURE.md); DSL diffs
  go through the same change control as Avro schema changes.
- **Bad:** Conductor's TypeScript SDK maturity is variable. Mitigation:
  none of the four flows require new TS workers — all participating
  workers are in Kotlin, Go, or Python services.
- **Neutral:** Conductor's worker SDKs do not replace our outbox /
  inbox / idempotency-key pattern — workers still use the platform
  baseline (per [PLATFORM_BASELINE.md](../../shared/PLATFORM_BASELINE.md)).
  Conductor orchestrates; the platform baseline guarantees
  exactly-once-effect.
- **Neutral:** Temporal was a viable candidate but was deferred
  because of its per-namespace pricing model and the data-model
  mismatch with our event catalog (per ADR-0010's earlier rejection,
  now revisited). We do not pre-commit to Temporal.

### Confirmation

- **Per-flow success rate ≥ 99.9%** for each of the four workflows.
  Alert on step-failure rate > 0.5% over 5 minutes.
- **Compensation rate**: alert on any non-zero compensation rate for a
  workflow in steady state.
- **Worker heartbeats**: Conductor worker heartbeats at 5s; lost
  worker = task reassigned within 30s.
- **DSL drift**: weekly CI job verifies every Conductor workflow's
  referenced Kafka topics and event names exist in
  `EVENT_ARCHITECTURE.md`.
- **Chaos test**: kill `conductor-server` mid-workflow; assert that
  the workflow resumes from its persisted state and completes with no
  event loss and no double-application. Repeat for each of the four
  workflows.
- **Operational readiness**: Conductor runbook, dashboards, and
  alert rules are published before any of the four workflows go live.

## Pros and Cons of the Options

### Status quo — in-service saga (ADR-0010)

Every workflow lives in service code; per-service state machines +
outbox + Kafka events + idempotency-key + reconciliation.

- Good: Strong-enough end-to-end consistency without 2PC.
- Good: Explicit compensation; the compensation matrix is the source
  of truth for "what happens when a step fails."
- Good: Replayable; the orchestrator's state is durable.
- Good: Visibility (admin API, metrics, traces).
- Good: Reconciliation jobs detect drift.
- Good: Zero new infrastructure.
- Bad: Orchestrator is a critical component.
- Bad: Compensation matrix is a source of complexity for flows with
  N-step rollback (refund orchestration).
- Bad: Choreographed flows are harder to see end-to-end.
- Bad: Timer-driven transitions (Make-a-Deal TTLs) must be
  hand-implemented per service.
- Bad: Long-running human-task workflows (onboarding) are hard to
  SLA-poll.

### Apache Airflow

Python DAG orchestration. Widely deployed for batch ETL.

- Good: Mature.
- Good: Large ecosystem.
- Bad: Python-only DAG authoring — no first-class SDK for Kotlin, Go,
  or our other languages.
- Bad: No saga / compensation primitive.
- Bad: No event-sourced history.
- Bad: No in-flight workflow versioning.
- Bad: Single-region only.
- Bad: Built for batch, not for long-running business workflows.

### Temporal

 MIT; polyglot first-class SDKs; event-sourced history; multi-region.

- Good: First-class Saga compensation pattern.
- Good: Event-sourced history gives a deterministic audit trail.
- Good: `Workflow.GetVersion()` for in-flight versioning.
- Good: First-class SDKs for Java, Kotlin, Go, TypeScript, Python,
  .NET, Ruby, Rust.
- Good: Multi-region active-active namespaces.
- Bad: Per-namespace and per-workflow pricing model is a recurring
  operational concern.
- Bad: Workflow history data model is not our event-catalog data
  model; integrating with our audit trail is non-trivial (per
  ADR-0010's earlier rejection, now revisited).
- Bad: Temporal Cloud is the only fully-managed multi-region
  deployment option; self-hosting multi-region is non-trivial.
- **Status**: deferred. Conductor satisfies all four named flows;
  Temporal remains a future consideration if Conductor proves
  insufficient.

### Netflix Conductor — chosen

JSON-spec-first workflow DSL + typed workers in any language.
First-class `compensationSteps`. Selected for **17 workflows
across 5 flow families** (Phase 7 / 7.5 / refunds / onboarding /
service-request) across **15 participating services** per the
scope clarification above.

- Good: First-class compensation primitive.
- Good: Polyglot SDK lineup (Java, Python, Go, JS/TS, C#, Ruby,
  Rust).
- Good: Native Kafka source/sink.
- Good: Apache-2.0 license.
- Good: JSON-spec-first reviewable by Ops/Compliance.
- Good: `version` field pins in-flight runs.
- Good: Battle-tested at hyperscale (Netflix, Tesla, LinkedIn, JP
  Morgan, Swiggy).
- Bad: Operational footprint (server cluster + ES + Redis) is new
  on-call surface.
- Bad: Workers colocated in service binaries couple services to
  Conductor availability.
- Bad: JSON DSL may drift from event catalog without CI enforcement.

### Camunda 8 (Zeebe)

Apache-2.0 core; Zeebe broker + Operate + Tasklist + Optimize +
Identity + ES/OS.

- Good: Excellent BPMN authoring for business analysts.
- Good: Event-sourced partitions in the broker.
- Good: Multi-region cluster replication (Enterprise tier).
- Bad: Enterprise tier required for HA and multi-region — conflicts
  with the platform's multi-region active-active baseline.
- Bad: Operational footprint is the heaviest of the candidates
  (broker + 4 UI/admin apps + ES/OS).
- Bad: BPMN-first authoring model may not match our polyglot team's
  culture (we are JSON + code-first everywhere else).
- **Status**: rejected.

### LittleHorse

Apache-2.0; emerging; event-sourced.

- Good: Apache-2.0.
- Good: Event-sourced WfRuns in Postgres or Kafka journal.
- Good: Native Kafka via LittleHorse Connect.
- Good: Multi-region architecturally supported.
- Bad: No TypeScript SDK yet.
- Bad: Production adoption base is small (announced GA 2025).
- Bad: Operational maturity unproven at hyperscale.
- **Status**: deferred. Worth tracking for future re-evaluation.

## References

- [ADR-0010](0010-saga-pattern.md) — superseded by ADR-0018 for the
  four named flows only; remains the default for every other
  workflow.
- [ADR-0017](0017-20-service-architecture.md) — 20-service
  architecture; Conductor workers are colocated in these 20 service
  binaries.
- [ADR-0009](0009-transactional-outbox.md) — outbox pattern; Conductor
  workers still use outbox to publish their completion events.
- [MASTER_PLAN.md](../../MASTER_PLAN.md) — Phase 7 and Phase 7.5
  sections; the four flows live in these phases.
- [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md)
  — canonical workflow definitions, IDs, compensation steps, Kafka
  signal mappings.
- [EVENT_ARCHITECTURE.md](../EVENT_ARCHITECTURE.md) — event catalog;
  Conductor DSL diffs go through the same change control.
- [FAILURE_HANDLING.md](../FAILURE_HANDLING.md) — compensation matrix;
  Conductor `compensationSteps` extend this matrix for the four named
  flows.
- [PLATFORM_BASELINE.md](../../shared/PLATFORM_BASELINE.md) —
  PostgreSQL/Kafka/Keycloak baseline; Conductor workers inherit it.
- [OSS_DEPENDENCIES.md](../../shared/OSS_DEPENDENCIES.md) — OSS policy;
  Conductor is Apache-2.0.
- Netflix — *Conductor: a microservices orchestration engine*
  (https://conductor-oss.github.io/conductor/).