# ADR-0012: Kubernetes for Orchestration

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: infrastructure, kubernetes, orchestration, deployment, autoscaling

> **Catalog revision (2026-08-12, appended per append-not-renumber):**
> the locked catalog is **21 active services** per
> [ADR-0017](0017-20-service-architecture.md) and
> [ADR-0021](0021-21-service-architecture-with-chat.md) (chat-service
> added 2026-08-12). The "58 services" figures in this ADR predate the
> 58 → 20 → 21 consolidation; the
> Kubernetes orchestration, multi-region topology, internal-worker
> scaling model, and consequences below apply unchanged to the
> current 21-service catalog.

## Context and Problem Statement

The platform has 21 active services deployed across multiple regions (EU,
KSA, …) and multiple environments (dev, staging, prod). Each
service has its own SLO, its own scaling profile, its own
dependencies, and its own on-call rotation. The deployment
orchestrator must (a) schedule containers across a region, (b)
scale services horizontally on CPU and custom metrics (RPS, queue
depth, Kafka consumer lag), (c) roll out new versions with
zero downtime and fast rollback, (d) enforce network policies for
defense in depth, (e) integrate with the secrets manager
(Vault), (f) expose health, readiness, and startup probes, and
(g) be operable across multiple cloud providers (EKS, GKE, AKS)
and on-prem (for the KSA region's data-residency requirements).

The decision is which orchestrator to standardize on: Kubernetes
(self-managed or managed), HashiCorp Nomad, AWS ECS, or
serverless (Lambda, Cloud Run, Cloudflare Workers).

## Decision Drivers

- Multi-cloud: the platform runs on EKS, GKE, AKS, and on-prem
  (for KSA's data-residency). The orchestrator must be portable.
- Ecosystem: the orchestrator must integrate with Vault, the OTel
  collector, the Prometheus exporter, the Kafka client, the
  Postgres operator, and the service mesh (Istio or Linkerd)
  without per-cloud glue code.
- Autoscaling: HPA on CPU and custom metrics (RPS, queue depth,
  Kafka consumer lag) for the hot paths.
- Zero-downtime rolling deploys with `maxUnavailable: 0` and
  `maxSurge: 1`; readiness gates traffic; pre-stop hooks drain.
- Network policies: default-deny ingress and egress; explicit
  allow for the gateway → service, service → DB, service →
  Kafka, service → Keycloak, service → Redis matrix.
- Secrets: Vault Agent Injector or External Secrets Operator;
  no secrets in env files in source control.
- Operationally mature: well-understood upgrade path, RBAC, audit
  logs, multi-region federation, multi-tenancy within a cluster.
- 21 services × N replicas × M regions: the orchestrator must
  scale operationally as well as the workloads.

## Considered Options

- **Kubernetes (managed: EKS / GKE / AKS, and self-managed
  on-prem)** — the chosen option.
- **HashiCorp Nomad** — the other major orchestrator.
- **AWS ECS (Fargate or EC2)** — AWS-only orchestrator.
- **Serverless (Lambda, Cloud Run, Cloudflare Workers)** — fully
  managed, per-request pricing.

## Decision Outcome

Chosen option: "**Kubernetes (managed on cloud, self-managed
on-prem)**", because (a) it is the only orchestrator that is
uniformly available across EKS, GKE, AKS, and on-prem (the
platform's multi-cloud, multi-region footprint), (b) the
ecosystem (Helm, Argo CD, Vault Agent Injector, External Secrets
Operator, cert-manager, Istio/Linkerd, the Postgres operator, the
Redis operator, Prometheus operator, OTel operator) is mature
and works the same way on every cloud, (c) HPA on CPU and
custom metrics (RPS, queue depth, Kafka consumer lag) is
first-class and well-tested, (d) NetworkPolicy gives us
default-deny with explicit allow for the service-to-service
matrix, and (e) the operational maturity (RBAC, audit logs,
multi-tenancy within a cluster, per-region federation) is what
we need for Tier-1 availability.

The platform team owns the cluster lifecycle (upgrades, RBAC,
network policies, ingress, secrets) and the per-service Helm
chart template. The service teams own the per-service
`values.yaml`, the per-service autoscaling settings, and the
per-service HPA.

### Consequences

- Good: Multi-cloud and on-prem with the same orchestrator and
  the same Helm charts. A service that runs on EKS in `eu-west`
  runs on AKS in `ksa-central` with no chart changes.
- Good: Ecosystem maturity. Helm, Argo CD, Vault, OTel, Prometheus,
  Istio/Linkerd, cert-manager, the Postgres operator, the Redis
  operator — all first-class, all on every cloud.
- Good: Autoscaling on CPU and custom metrics. HPA on RPS for
  `api-gateway`; HPA on Kafka consumer lag for
  ``driver-service` (location)`; HPA on queue depth for
  `notification-service`.
- Good: Zero-downtime rolling deploys. `maxUnavailable: 0`,
  `maxSurge: 1`, readiness gates traffic, pre-stop hooks drain.
- Good: NetworkPolicy for defense in depth. Default-deny ingress
  and egress; explicit allow for the gateway → service, service
  → DB, service → Kafka, service → Keycloak, service → Redis
  matrix.
- Good: Secrets via Vault. Vault Agent Injector mounts secrets
  at runtime; the migration job and the deployment wait for
  the secret to be present; quarterly rotation is automatic.
- Good: Per-service `Deployment`, `Service`, `HPA`, `PDB`,
  `ServiceAccount`, `NetworkPolicy`. The platform team's
  template generates the boilerplate; the service teams
  customize.
- Bad: Kubernetes is operationally complex. (Mitigation: a
  dedicated platform team that owns the cluster lifecycle;
  quarterly DR drills; documented runbooks for upgrades,
  node failures, and network policy debugging.)
- Bad: Managed Kubernetes (EKS, GKE, AKS) still has
  cloud-specific surfaces (IAM, node groups, ingress). We
  mitigate with a thin abstraction layer (the per-service
  Helm chart) and a per-cloud values file.
- Bad: 21 services × N replicas × M regions is a non-trivial
  control-plane load. We mitigate with cluster federation per
  region and a per-region cluster admin team.
- Bad: Cold-start latency for some workloads (e.g. the first
  pod after a scale-from-zero) is unacceptable on the hot
  path. We mitigate with a minimum replica count for
  hot-path services.
- Neutral: The service mesh (Istio or Linkerd) is layered on
  top of Kubernetes for mTLS, traffic shaping, and per-service
  policy; it is not a replacement for the orchestrator.

### Confirmation

- Cluster availability per region ≥ 99.95% (Tier-1 SLO).
- Per-service rollout: 100% of services roll out with
  `maxUnavailable: 0` and `maxSurge: 1`; readiness gates
  traffic; pre-stop hooks drain.
- Per-service HPA: every Tier-1 service has an HPA on CPU and
  at least one custom metric (RPS, queue depth, or Kafka
  consumer lag).
- Per-service PDB: every Tier-1 service has a PDB with
  `minAvailable: 2`; every Tier-2 has `minAvailable: 1`.
- NetworkPolicy: 100% of services have a NetworkPolicy; the
  default-deny matrix is verified by a chaos test that
  asserts a denied pod-to-pod call.
- Secrets: 100% of secrets are mounted from Vault; no
  secrets in env files in source control (CI lint enforces).
- DR drill: a quarterly region failover exercise validates
  RPO ≤ 5 min and RTO ≤ 1 hour for Tier-1 services.

## Pros and Cons of the Options

### Kubernetes (managed + self-managed)

The chosen option. Portable across EKS, GKE, AKS, and on-prem;
mature ecosystem; first-class autoscaling, NetworkPolicy, and
secrets integration.

- Good: Multi-cloud and on-prem with the same orchestrator
  and the same Helm charts.
- Good: Ecosystem maturity (Helm, Argo CD, Vault, OTel,
  Prometheus, Istio/Linkerd, cert-manager, operators).
- Good: HPA on CPU and custom metrics.
- Good: Zero-downtime rolling deploys; readiness gates;
  pre-stop hooks.
- Good: NetworkPolicy for defense in depth.
- Good: Secrets via Vault; quarterly rotation.
- Good: Multi-tenancy within a cluster; RBAC; audit logs.
- Bad: Operationally complex; needs a dedicated platform
  team.
- Bad: Managed Kubernetes has cloud-specific surfaces.
- Bad: 21 services × N replicas × M regions is non-trivial
  control-plane load.
- Bad: Cold-start latency for some workloads (mitigated by
  minimum replica count).

### HashiCorp Nomad

A simpler orchestrator with first-class support for multiple
runtime types (containers, JVM, binaries).

- Good: Operationally simpler than Kubernetes.
- Good: Multi-runtime (containers, JVM, binaries) in one
  orchestrator.
- Good: First-class integration with Consul and Vault.
- Bad: Smaller ecosystem than Kubernetes; fewer Helm-equivalent
  chart repositories; fewer operators.
- Bad: Less mature autoscaling; custom metrics are second-class.
- Bad: Less mature NetworkPolicy equivalent.
- Bad: Cloud-provider managed offerings are limited (AWS
  Cloud Map, no first-class managed Nomad on GKE/AKS).
- Bad: We have less in-house Nomad expertise than Kubernetes
  expertise.

### AWS ECS (Fargate or EC2)

AWS-only orchestrator.

- Good: Operationally simple on AWS; first-class IAM
  integration.
- Good: Fargate removes the node-management burden.
- Bad: AWS-only; we deploy in EU and KSA regions and want a
  uniform orchestrator.
- Bad: Smaller ecosystem than Kubernetes; no Helm-equivalent;
  no Vault Agent Injector equivalent.
- Bad: Less mature HPA; custom metrics via CloudWatch are
  higher-latency.
- Bad: No NetworkPolicy equivalent; security groups are
  per-service but not as expressive.

### Serverless (Lambda, Cloud Run, Cloudflare Workers)

Fully managed, per-request pricing.

- Good: Zero ops for capacity; per-request pricing.
- Good: Auto-scales to zero.
- Bad: Cold-start latency is unacceptable on hot paths
  (driver location write, dispatch match).
- Bad: Vendor lock-in per cloud; multi-cloud is impossible
  without rewriting the runtime layer.
- Bad: Local development is poor; testing distributed
  functions is harder than testing services.
- Bad: Long-running stateful workloads (ledger
  reconciliation, batch payouts) do not fit the FaaS model.
- Bad: 15-minute execution limit on some platforms.

## References

- [`DEPLOYMENT_ARCHITECTURE.md`](../DEPLOYMENT_ARCHITECTURE.md) —
  the full deployment design: containers, Kubernetes topology,
  resource limits, health probes, ingress, configuration,
  secrets, DB connectivity, migrations, rolling deploys,
  zero-downtime, rollback, environments, multi-region,
  disaster recovery.
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — the layered view
  and the cross-cutting decisions.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) —
  network policies, secrets, defense in depth.
- [`OBSERVABILITY.md`](../OBSERVABILITY.md) — health,
  readiness, liveness probes; the OTel collector in the
  cluster.
- Kubernetes documentation — `Deployment`, `Service`, `HPA`,
  `PDB`, `NetworkPolicy`, `ServiceAccount`, `ResourceQuota`,
  `LimitRange`.
- Argo CD documentation — GitOps for per-service Helm
  releases.
- cert-manager, Vault Agent Injector, External Secrets
  Operator, Prometheus operator, OTel operator.
