# ADR-0008: API Gateway at the Edge

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: edge, gateway, routing, authn, rate-limiting, observability

> **Catalog revision (2026-08-05, appended per append-not-renumber):**
> the locked catalog is **20 services** per
> [ADR-0017](0017-20-service-architecture.md). The "58 services"
> figures in this ADR predate the 58 → 20 consolidation; the
> gateway responsibilities (TLS termination, JWT validation, rate
> limiting, header injection, observability) and the consequences
> below apply unchanged to the surviving 20-service catalog.

## Context and Problem Statement

The platform exposes 58 services to multiple channels (customer
mobile, customer web, driver mobile, driver web, courier mobile,
courier web, restaurant operator console, merchant operator console,
support console, admin console, partner integrations). Every request
must be authenticated (JWT validation), authorized (RBAC + scopes at
the edge, resource-level at the service), rate-limited (per-token,
per-IP, per-route), observed (one trace per request, RED metrics),
and translated from a public contract to an internal one (header
injection, claim mapping). We need a single edge that does these
cross-cutting concerns in one place so that every service does not
have to re-implement them, and so that the gateway is the only
component that touches the public network.

The decision is what the edge looks like: a managed API gateway, a
self-hosted API gateway, a service mesh only (no gateway), or direct
exposure of services through a load balancer.

## Decision Drivers

- Single point of edge: TLS termination, WAF, JWT validation,
  claim-to-header translation, rate limiting, request logging, and
  distributed-trace context propagation in one component.
- Multi-channel routing: the same gateway serves customer, driver,
  courier, staff, and internal traffic, with per-channel CORS,
  rate limits, and observability.
- Version routing: `/v1/...` and `/v2/...` can run side-by-side
  during a deprecation window; the gateway routes by URI prefix
  (or by `Api-Version` header) to the right service version.
- Multi-region: a regional gateway per region, with a global anycast
  load balancer in front.
- Defense in depth: the gateway is the first line; services also
  re-validate the JWT and apply their own rate limits.
- Operationally mature: well-understood upgrade path, hot-reload of
  routes, metrics, and tracing.
- Stateless: all state (rate-limit counters, idempotency cache,
  token-revocation set) lives in Redis; the gateway itself can be
  scaled horizontally without coordination.

## Considered Options

- **Self-hosted API gateway (Kong, NGINX + custom auth filter,
  Envoy with custom filters)** — the chosen option.
- **Managed API gateway (AWS API Gateway, Azure API Management,
  Google Cloud Endpoints)** — cloud-vendor offering.
- **Service mesh only (Istio, Linkerd)** — sidecar per service;
  no central edge.
- **Direct exposure through a load balancer** — services exposed
  directly; no gateway.
- **Multiple gateways (one per channel)** — separate gateway per
  persona.

## Decision Outcome

Chosen option: "**Self-hosted API gateway** (NGINX with a custom
auth filter, or Envoy with custom filters; configurable per
deployment)", because (a) it gives us a single, stateless edge that
we fully control — TLS termination, WAF integration, JWT
validation, claim-to-header translation, rate limiting, request
logging, and trace propagation in one place, (b) it is multi-region
and multi-channel out of the box, (c) it is stateless (all state in
Redis), so we can scale it horizontally without coordination, and
(d) the alternative (managed API gateway) is tied to a single cloud
and has per-request pricing that scales linearly with our volume.

The gateway is the only component that terminates TLS for public
traffic; services are only reachable through it (and through
service-to-service mTLS in the cluster). The gateway is not trusted
to make ownership decisions; resource-level authorization is
enforced by the service.

### Consequences

- Good: Single edge for all cross-cutting concerns. JWT validation,
  rate limiting, header injection, CORS, request logging, and trace
  propagation are in one component and are the same for every
  service.
- Good: Multi-channel and multi-version routing. The same gateway
  serves customer, driver, courier, staff, and internal traffic,
  with per-channel CORS, per-channel rate limits, and per-version
  URI routing.
- Good: Stateless. The gateway is scaled horizontally; all state
  (rate-limit counters, idempotency cache, token-revocation set)
  is in Redis (ADR-0006).
- Good: Defense in depth. The gateway is the first line; services
  re-validate the JWT and apply their own per-route rate limits.
- Good: Multi-region. A regional gateway per region, behind a
  global anycast load balancer.
- Good: No vendor lock-in. The gateway is a self-hosted NGINX or
  Envoy; we can swap it without changing the services.
- Bad: An additional piece of infrastructure to operate and to
  upgrade. (Mitigation: a dedicated edge-platform team; quarterly
  drills; canary deploys for gateway configuration changes.)
- Bad: The gateway can become a single point of failure for the
  platform. We mitigate with N+1 replicas per region, with a
  PodDisruptionBudget, and with a runbook for fast rollback.
- Bad: Latency. Every request adds a hop. The gateway is in the
  same region as the service, and the hop is < 5ms; well within
  the budget.
- Bad: Configuration drift between gateway and services. We
  mitigate by generating the gateway's route table from the same
  source of truth as the services' OpenAPI specs.
- Neutral: The gateway does NOT make ownership decisions; it
  enforces coarse RBAC and rate limits, and the service enforces
  fine-grained scopes and resource ownership.

### Confirmation

- Gateway availability ≥ 99.99% per region (Tier-1 SLO).
- P99 latency added by the gateway ≤ 5ms.
- 100% of public traffic terminates TLS at the gateway; no
  service is publicly reachable.
- 100% of requests carry a valid JWT validated by the gateway;
  no service accepts an unvalidated token.
- Rate-limit coverage: every public endpoint has a documented
  per-token and per-IP rate limit; load tests verify the
  `429 RATE_LIMITED` response.
- Versioning: every endpoint has a documented deprecation
  timeline; old versions stay for ≥ 6 months with `Deprecation`
  and `Sunset` headers.

## Pros and Cons of the Options

### Self-hosted API gateway (NGINX / Envoy)

The chosen option. A stateless edge that terminates TLS, validates
JWTs, translates claims to headers, rate-limits, logs, and
propagates trace context.

- Good: Single edge for all cross-cutting concerns.
- Good: Multi-channel, multi-version, multi-region.
- Good: Stateless; horizontal scaling.
- Good: No vendor lock-in.
- Good: Mature operational story; well-understood.
- Bad: Additional infrastructure to operate and to upgrade.
- Bad: Single point of failure (mitigated by replicas and
  runbooks).
- Bad: Latency hop (mitigated by co-location with services in
  the same region).
- Bad: Configuration drift (mitigated by code generation from
  OpenAPI).

### Managed API gateway (AWS API Gateway, Azure API Management, Google Cloud Endpoints)

Cloud-vendor offering.

- Good: Fully managed; no cluster to run.
- Good: Tight integration with the cloud's IAM, WAF, and
  observability.
- Good: Per-region HA is the cloud's problem.
- Bad: Vendor lock-in to a single cloud. We deploy in EU and KSA
  regions and want a uniform edge.
- Bad: Per-request pricing scales linearly with our volume; at
  our scale, this is a material line item.
- Bad: Limited customization for claim-to-header translation
  and per-channel rate limits; we would write Lambda functions
  to fill the gaps, which adds latency and complexity.
- Bad: Cold-start latency for some customizations.

### Service mesh only (Istio, Linkerd)

Sidecars per service; no central edge.

- Good: Mutual TLS, traffic shaping, and per-service policy
  without a central edge.
- Good: Good for service-to-service traffic inside the cluster.
- Bad: The public edge still needs a gateway for TLS termination,
  JWT validation, and rate limiting; the mesh is for
  east-west, not north-south.
- Bad: Per-pod sidecars add memory and CPU overhead; for our
  58 services × N replicas, this is non-trivial.
- Bad: A second control plane (mesh + gateway) doubles the
  operational surface; we'd rather have one edge.

### Direct exposure through a load balancer

Services exposed directly; no gateway.

- Good: Simplest possible architecture.
- Good: Lowest latency (one hop).
- Bad: Every service must implement JWT validation, rate
  limiting, header injection, CORS, request logging, and trace
  propagation. We would re-implement the gateway 58 times.
- Bad: No central place to enforce cross-cutting policy.
- Bad: No central place to do WAF, DDoS protection, or bot
  detection.
- Bad: No version routing; every service must handle its own
  `/v1/...` / `/v2/...` routing.

### Multiple gateways (one per channel)

Separate gateway per persona (customer, driver, courier, staff,
internal).

- Good: Per-channel isolation; a misbehaving customer gateway
  does not affect the driver gateway.
- Good: Per-channel rate limits and CORS are easy.
- Bad: 5+ gateways to operate, to upgrade, and to keep in sync.
- Bad: Configuration drift between gateways is a real risk.
- Bad: The cross-cutting concerns (JWT validation, header
  injection, request logging) are duplicated 5+ times.

## References

- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — Edge Layer: API
  Gateway, WAF, rate limit, auth edge.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) —
  defense in depth, token validation, rate limiting, admin
  security.
- [`KEYCLOAK_ARCHITECTURE.md`](../KEYCLOAK_ARCHITECTURE.md) —
  JWT validation at the gateway, claim-to-header translation,
  logout and revocation.
- [`API_STANDARDS.md`](../API_STANDARDS.md) — the contract the
  gateway enforces: headers, error envelope, rate-limit
  headers, deprecation headers.
- [`DEPLOYMENT_ARCHITECTURE.md`](../DEPLOYMENT_ARCHITECTURE.md) —
  the gateway in the deployment topology, with replicas, HPA,
  PDB.
- ADR-0006 — Redis for the gateway's state (rate limits,
  revocation set, idempotency cache).
- ADR-0004 — REST as the primary API style; the gateway
  terminates HTTP.
