# ADR-0010: Saga Pattern for Distributed Workflows

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: workflows, saga, distributed-transactions, compensation, consistency

## Context and Problem Statement

Several platform workflows span multiple services and must be
strongly consistent end-to-end: completing a trip means marking the
trip `completed` in `trip-service`, capturing the payment in
`payment-service`, accruing driver earnings in
``payment-service` (driver earnings)`, and posting a double-entry ledger entry
in `ledger-service`. A food order from `checkout.completed.v1` to
`food.payment.completed.v1` spans ``food-order-service` (checkout)`,
`food-order-service`, ``food-order-service` (queue)`,
``payment-service` (food saga)`, `payment-service`,
`ledger-service`, ``payment-service` (merchant settlement)`, and
``payment-service` (courier earnings)`. These workflows cannot use a
distributed transaction (we do not do 2PC between services — see
[`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md)) and cannot
use a shared database (we have database-per-service — see
ADR-0002). They need a pattern that gives us strong-enough
end-to-end consistency with explicit compensation when a step
fails.

The decision is whether to use the **saga pattern** (with
orchestrated and choreographed variants), a **workflow engine**
(Temporal, Camunda, Airflow), **event chaining** (choreography
only), or **two-phase commit**.

## Decision Drivers

- Strong end-to-end consistency for financial flows (no money lost
  or created).
- Explicit compensation: every forward step has a defined
  compensation that restores an acceptable state (not a generic
  "undo").
- Idempotency: the saga can be replayed (e.g. after a crash) and
  produces the same result; the underlying operations are
  idempotent via the outbox + inbox + idempotency-key pattern.
- Visibility: the platform team and the service owners must be
  able to see the state of every in-flight saga and to debug
  failures.
- Operationally tractable: we do not want to operate a separate
  workflow engine if we can avoid it.
- Bounded saga depth: 3-7 steps is the typical range; we do not
  need long-running workflows of hundreds of steps.
- Two flavors needed: orchestrated for financial flows (where
  the saga state is the source of truth and compensations are
  explicit) and choreographed for non-financial cross-service
  notifications (where the flow is implicit and there is no
  central state to maintain).

## Considered Options

- **Saga pattern — orchestrated for financial flows,
  choreographed for non-financial flows** — the chosen option.
- **Workflow engine (Temporal, Camunda, Airflow)** — external
  workflow orchestration.
- **Event chaining (choreography only)** — every service reacts to
  events from the previous step; no central state.
- **Two-phase commit between services** — distributed transaction.
- **Long-lived synchronous chains** — call each service in turn
  from the request handler.

## Decision Outcome

Chosen option: "**Saga pattern — orchestrated for financial flows,
choreographed for non-financial flows**", because (a) it gives us
strong-enough end-to-end consistency without a distributed
transaction, (b) the saga state is stored in the orchestrator's
own database (no shared infrastructure), (c) compensations are
explicit business actions (e.g. `payment.void` for
`payment.authorized`; `payment.refund` for `payment.captured`),
not generic undos, (d) the orchestrator is a regular service with
its own observability (RED metrics, traces, audit events) and its
own on-call rotation, and (e) we already have the building blocks
(outbox, inbox, idempotency keys, reconciliation jobs) that make
the saga pattern correct.

The orchestrated flavor is used for financial flows:
``payment-service` (ride saga)` orchestrates the trip completion
→ payment capture → driver earning → ledger posting flow;
``payment-service` (food saga)` orchestrates the food order
completion → payment capture → merchant settlement → courier
earning flow. The choreographed flavor is used for non-financial
cross-service notifications (e.g. `trip.completed.v1` →
`notification-service` emails a receipt → ``pricing-service` (loyalty rules) / `customer-service` (account)` awards
points → ``trip-service` / `food-order-service` / `search-service` (review projections)` opens a rating prompt).

### Consequences

- Good: Strong-enough end-to-end consistency for financial flows
  without 2PC. The orchestrator owns the saga state; the
  underlying services are idempotent; the outbox + inbox pattern
  gives at-least-once with exactly-once effect.
- Good: Explicit compensation. Every forward step has a defined
  compensation that is a regular business action (not a generic
  undo). The compensation matrix is documented in
  [`FAILURE_HANDLING.md`](../FAILURE_HANDLING.md).
- Good: Replayable. The saga is keyed by an aggregate id (e.g.
  `trip_id`); a re-run re-enters the same state and produces the
  same result. A crash between steps is recovered by the
  orchestrator's startup logic (read the saga state, continue
  from the last completed step).
- Good: Visibility. The orchestrator exposes its saga state via
  an admin API; the on-call can see every in-flight saga and its
  current step. The orchestrator emits metrics
  (`saga.step.duration`, `saga.step.failures`,
  `saga.compensation.total`).
- Good: Reconciliation jobs in `reporting-service` detect drift
  (e.g. `trip.completed.v1` with no `ride.payment.completed.v1`
  after 5 minutes) and open a ticket.
- Bad: The orchestrator is a Tier-1 critical component. (Mitigation:
  N+1 replicas, PDB, canary deploys, on-call rotation, runbook.)
- Bad: The compensation matrix is the source of complexity. Every
  new step in a financial flow requires an explicit compensation
  in the saga and a corresponding capability in the downstream
  service. (Mitigation: a per-flow saga spec in
  `WORKFLOWS.md`; a code review checklist.)
- Bad: Choreographed flows are harder to see end-to-end. (Mitigation:
  a per-flow sequence diagram in the consuming service's
  `INTEGRATION.md`; OTel traces that stitch the spans across
  services.)
- Bad: The orchestrator and the choreographed consumers both use
  the outbox + inbox pattern, so the platform's per-service
  operational surface includes the publisher, the consumer, and
  the saga state.
- Neutral: We accept the operational cost of maintaining
  orchestrated sagas for the small set of financial flows; we
  do not generalize the pattern to every cross-service
  interaction.

### Confirmation

- 100% of financial flows are implemented as orchestrated sagas
  with explicit compensations; verified by a per-flow review
  against the saga spec in `WORKFLOWS.md`.
- Saga success rate ≥ 99.9% per flow; alert on a step-failure
  rate > 0.5% over 5 minutes.
- Compensation rate: alert on any non-zero compensation rate
  for a flow in steady state.
- Reconciliation lag: `trip.completed.v1` with no
  `ride.payment.completed.v1` after 5 minutes opens a ticket
  and pages the on-call.
- Replay correctness: a chaos test that kills the orchestrator
  mid-saga and asserts that the saga resumes and completes with
  no event loss and no double-application.

## Pros and Cons of the Options

### Saga pattern — orchestrated for financial flows, choreographed for non-financial flows

The chosen option. A saga is a sequence of local transactions,
each with a defined compensation. The orchestrator (for financial
flows) or the event chain (for non-financial flows) drives the
sequence and the compensation.

- Good: Strong-enough end-to-end consistency without 2PC.
- Good: Explicit compensation; the compensation matrix is the
  source of truth for "what happens when a step fails."
- Good: Replayable; the orchestrator's state is durable.
- Good: Visibility (admin API, metrics, traces).
- Good: Reconciliation jobs detect drift.
- Bad: Orchestrator is a critical component.
- Bad: Compensation matrix is a source of complexity.
- Bad: Choreographed flows are harder to see end-to-end.
- Bad: The platform's per-service operational surface includes
  the publisher, the consumer, and the saga state.

### Workflow engine (Temporal, Camunda, Airflow)

External workflow orchestration.

- Good: Mature; rich tooling (UI, history, replay).
- Good: Long-running workflows with timers and signals.
- Good: Camunda BPMN for business-analyst-friendly specs.
- Bad: An additional piece of infrastructure to operate.
- Bad: Temporal's per-namespace and per-workflow pricing; or
  Camunda's cluster operational cost.
- Bad: We already have the building blocks (outbox + inbox +
  idempotency keys) that make a hand-rolled saga tractable; a
  workflow engine is overkill for our flow depth (3-7 steps).
- Bad: Temporal's data model (workflow history) is not our
  data model; integrating it with our event catalog and our
  audit trail is non-trivial.

### Event chaining (choreography only)

Every service reacts to events from the previous step; no
central state.

- Good: No central orchestrator; no single point of failure.
- Good: Simple for shallow flows.
- Bad: Hard to see end-to-end; compensations are implicit.
- Bad: Cycle risk; ordering risk.
- Bad: Not suitable for financial flows where compensations
  must be explicit and the saga state must be queryable.

### Two-phase commit between services

Distributed transaction.

- Good: True atomicity.
- Bad: Coordinator-coupled; fragile.
- Bad: We explicitly do not use 2PC between services.

### Long-lived synchronous chains

Call each service in turn from the request handler.

- Good: Simplest possible code; no saga state.
- Bad: Tail latency is the sum of the tail latencies; an N-step
  chain has N× the P99 of a single step.
- Bad: A single failure in the chain rolls back the whole
  request; we cannot compensate partial work.
- Bad: The request handler is coupled to every downstream
  service; deploys are coupled.

## References

- [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md) —
  where strong consistency is required (money, dispatch, food
  order assignment) and where eventual consistency is
  acceptable.
- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — outbox
  and inbox patterns that make the saga correct.
- [`FAILURE_HANDLING.md`](../FAILURE_HANDLING.md) — the
  compensation matrix; DLQ; reconciliation jobs.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — the
  saga orchestrators (``payment-service` (ride saga)`,
  ``payment-service` (food saga)`) and the participants
  (`payment-service`, ``payment-service` (wallet)`, `ledger-service`,
  ``payment-service` (driver earnings)`, ``payment-service` (courier earnings)`,
  ``payment-service` (merchant settlement)`).
- ADR-0009 — outbox pattern, which the saga uses for its
  forward and compensation steps.
- Hector Garcia-Molina and Kenneth Salem, *Sagas* (1987) —
  the original saga paper.
- Chris Richardson, *Microservices Patterns* — saga pattern,
  choreography vs. orchestration.
