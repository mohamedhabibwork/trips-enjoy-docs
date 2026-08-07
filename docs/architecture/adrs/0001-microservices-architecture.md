# ADR-0001: Adopt a Microservices Architecture

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: architecture, services, decomposition, bounded-context

> **Catalog revision (2026-08-05):** the locked final catalog is
> **20 services** per
> [ADR-0017](0017-20-service-architecture.md) (supersedes the
> intermediate [ADR-0016](0016-service-domain-consolidation.md)).
> This ADR's architectural style (microservices, one bounded
> context per service, REST + Kafka, mTLS) remains canonical; only
> the catalog count and worked examples below were updated to the
> 20-service state. Internal scaling model: a survivor is one
> bounded-context product/public identity but may ship
> independently scalable internal Kubernetes workers from the
> same versioned release — see
> [ADR-0017 "Internal scaling model"](0017-20-service-architecture.md).

## Context and Problem Statement

The platform serves two product verticals — ride-hailing and a food
marketplace — across multiple countries and personas (customer,
driver, courier, restaurant staff, merchant staff, support, admin).
On day one the platform is a single monorepo and a single
deployment unit; the question is whether to keep that shape or
decompose it as the surface area grows. The two extremes (modular
monolith; 200+ nano-services) are both well-known failure modes.
We need to choose where on the spectrum the platform sits, and
what the rules of decomposition are.

The platform is described in
[`ARCHITECTURE.md`](../ARCHITECTURE.md), with the service catalog
in [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) (**20 active
services**, 38 obsolete absorbed per ADR-0017, 9 strategic bounded
contexts) and cross-context relationships in `DOMAIN_MAP.md` /
`CONTEXT_MAP.md`. The architecture must support independent
deploys, isolated failure domains, language/runtime flexibility
per service where justified, and per-service scaling.

## Decision Drivers

- Two product verticals (rides, food) on one platform with shared
  identity, payments, configuration, and observability.
- Multiple personas per vertical (customer/driver/courier;
  customer/restaurant-staff/merchant-staff) with different SLOs
  and deployment cadences.
- Multi-region, multi-country; some services (location,
  geolocation) have region-local hot paths that must scale
  independently.
- Independent deploys are mandatory: a Trip completion must not
  require a coordinated deploy of every other service.
- Strong ownership of data and state machines per bounded
  context; the customer/supplier, conformist, anti-corruption
  layer patterns apply.
- Operational maturity: we have a platform team, a CI/CD
  pipeline, and per-service on-call rotations; we do not have
  unlimited engineers.

## Considered Options

- **Microservices (one service per bounded context)** — **20
  services** in the locked catalog (per ADR-0017), database per
  service, REST + Kafka between them, mTLS in cluster.
- **Modular monolith** — one process, modular package boundaries,
  shared relational database, in-process module calls.
- **Coarse-grained services ("macroservices")** — ~10-15 services,
  one per strategic context (Identity, Pricing, Ride-hailing,
  Food, …), each containing several aggregates that today are
  separate services.
- **Serverless / functions-as-a-service** — function-per-handler,
  fully managed runtime, event-driven only.

## Decision Outcome

Chosen option: "**Microservices (one service per bounded
context)**", because the platform must (a) deploy services
independently at very different cadences (config + flags ship
daily; ledger changes once a quarter), (b) scale hot paths
(location writes, dispatch reads) without scaling cold paths,
(c) isolate failures so that a misbehaving `search-service`
reindex job does not affect `trip-service`, and (d) allow
per-service technology choice (e.g. PostGIS-heavy vs.
OLTP-light) where justified. We explicitly avoid the two failure
modes called out in `ARCHITECTURE.md` — distributed monolith (no
independent deploys) and nano-services (operational overhead
without benefit) — by sizing services to meaningful bounded
contexts. `trip-service` owns the Trip aggregate end-to-end;
`driver-service` owns the driver aggregate (profile + KYC +
vehicle + availability + location + dispatch + incentives)
through internal workers; `payment-service` owns all operational
money (46-gateway registry + ride/food sagas + wallet + driver/
courier earnings + merchant settlement + COD) through internal
sagas; none is a CRUD wrapper around a single table.

### Consequences

- Good: Independent deploys per service. `configuration-service`
  can roll a new value to production 20 times a day without
  coordinating with anyone.
- Good: Per-service scaling via internal Kubernetes workers.
  `driver-service` (location sub-aggregate) and
  `courier-service` (location sub-aggregate) are sized for
  sustained 10k+ writes/s per region as independently scalable
  workers; `reporting-service` scales for nightly batch workloads;
  `notification-service` scales per channel.
- Good: Per-service SLOs and on-call. A `payment-service` 99.95%
  SLO is owned by the payments team; a
  `configuration-service` (flags) 99.9% SLO is owned by the
  platform team.
- Good: Bounded context enforcement. New engineers can read one
  service's `INTEGRATION.md` and know everything that service
  touches.
- Bad: 20 services means 20 CI pipelines, 20 `Deployment`
  resources, 20 on-call rotations, 20 OTel dashboards.
  (Mitigation: a service template that generates the boilerplate;
  a platform team that owns the shared CI/Helm/OTel plumbing.)
- Bad: Distributed-system failure modes (network partitions,
  partial failure, eventual consistency) are the norm.
  (Mitigation: outbox + inbox, sagas, reconciliation jobs, and
  the Netflix Conductor engine for the 17 named cross-cutting
  workflows across 5 flow families per ADR-0018 — see ADR-0009,
  ADR-0010, ADR-0018, and `CONSISTENCY_STRATEGY.md`.)
- Neutral: Cross-service changes touch N repositories. We accept
  this in exchange for independent deploys; we mitigate with
  shared event schemas and a contract test pipeline.

### Confirmation

- 100% of services ship through a per-service pipeline with no
  cross-service merge gate.
- MTTR per service: median < 15 minutes (we can roll back a single
  service without coordinating others).
- Per-service SLO dashboards exist and are reviewed monthly.
- Number of services in the catalog is reviewed quarterly against
  the "nano-service" smell test (does this service own a
  meaningful aggregate? If not, merge it).

## Pros and Cons of the Options

### Microservices (one service per bounded context)

One service per meaningful aggregate / bounded context. **20
services** in the locked catalog (per ADR-0017), each with its
own PostgreSQL schema, REST API, event producers, and event
consumers. mTLS in cluster; REST over HTTPS at the edge through
`api-gateway`; events over Kafka.

- Good: Independent deploy, scale, and failure isolation per
  service.
- Good: Per-service runtime choice where justified (e.g. PostGIS
  in `geolocation-service`, in-memory geo-hash index in
  `driver-service` dispatch sub-aggregate).
- Good: Bounded context is enforced at the deployment boundary,
  not just at the package boundary.
- Bad: 20 services is still a real operational cost. The
  platform team must provide the template, the CI pipeline, the
  OTel collector, and the on-call tooling.
- Bad: Distributed-system failure modes are the default. Without
  outbox + inbox + sagas + reconciliation, the system is fragile.
- Bad: Cross-service changes touch N repos. A new event field
  means editing producer + N consumers.

### Modular monolith

A single process (or a small number of processes) with strong
package boundaries, a shared relational database, and in-process
module calls. Modules are deployable together but cannot be
deployed independently.

- Good: Operationally simple — one process, one DB, one log line
  per request.
- Good: Strong consistency across modules via DB transactions.
- Good: Cross-module refactors are local.
- Bad: Cannot scale hot paths independently.
  `driver-service` (location sub-aggregate) and `reporting-service`
  would share the same resource pool.
- Bad: A regression in any module can take down the whole
  platform. This is unacceptable for a Tier-1 rides service.
- Bad: Independent deploy is impossible; "release trains"
  become the norm. We have explicit evidence from peer companies
  that release trains throttle iteration speed.
- Bad: Package boundaries in a single repo are advisory; they
  erode over years without deployment-time enforcement.

### Macroservices (~10-15 services)

One service per strategic context. Identity owns all profile
sub-aggregates; Pricing owns pricing + promotion + tax + loyalty
rules; Ride-hailing owns the entire ride domain.

- Good: Fewer services (10-15) → less operational overhead.
- Good: Strong consistency inside a context.
- Bad: A `pricing-service` that owns pricing + promotion + tax
  + loyalty rules is large. Each of those rule sets has a
  different release cadence (pricing changes daily; tax rules
  change quarterly; promotions change hourly). The macroservice
  forces them to share a deploy.
- Bad: A failure in a non-critical sub-aggregate (e.g. loyalty
  tier recalculation) takes down the whole context.
- Bad: Hot-path scaling pulls cold-path with it. Loyalty
  recalculation has batch characteristics; pricing is on the
  hot path. Both share a pool.

### Serverless / FaaS

Functions-as-a-service, fully managed runtime, event-driven
only. Providers like AWS Lambda, Google Cloud Functions,
Cloudflare Workers.

- Good: Zero ops for capacity; per-request pricing.
- Good: Auto-scales to zero.
- Bad: Cold-start latency is unacceptable on hot paths (driver
  location write, dispatch match).
- Bad: Vendor lock-in per cloud; multi-cloud is impossible
  without rewriting the runtime layer.
- Bad: Local development is poor; testing distributed functions
  is harder than testing services.
- Bad: Long-running stateful workloads (ledger reconciliation,
  batch payouts) do not fit the FaaS model.

## References

- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — high-level
  architecture, service categorization, anti-patterns explicitly
  avoided.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — the
  **20-service catalog** with ownership, sync/async dependencies,
  criticality tier.
- `DOMAIN_MAP.md` and `CONTEXT_MAP.md` — strategic DDD context
  map (customer/supplier, conformist, anti-corruption layer)
  per bounded context.
- [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md) — the
  consistency model that makes the microservices decomposition
  tractable.
- [ADR-0017](0017-20-service-architecture.md) — the locked
  20-service catalog (supersedes ADR-0016).
- [ADR-0018](0018-workflow-engine-conductor.md) — Netflix
  Conductor for the 17 named cross-cutting workflows across 5
  flow families (Phase 7 / 7.5 / refunds / onboarding /
  service-request) across 15 participating services.
- Sam Newman, *Building Microservices*, 2nd ed. — design rules
  for service boundaries.
- Eric Evans, *Domain-Driven Design* — bounded context,
  aggregate, context map.
