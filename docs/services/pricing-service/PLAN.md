# pricing-service — Implementation Plan

**Domain:** Ride-Hailing
**Tier:** 1 (position 15 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T1 (99.95%)
**DB Schema:** `pricing`
**Cache:** Redis — quote cache + rule snapshot
**HPA:** CPU 70%, 3–8, p99 < 100ms

---

## Purpose

**Phase 3 — Ride-Hailing.** Begin only after Phase 2 customer/driver/identity are live.

This PLAN.md is the source of truth for **how** `pricing-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Create schema `pricing`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | Add `pricing.outbox` and `pricing.inbox` for reliable eventing | pending | T-PRC-03 | pricing.outbox, pricing.inbox | pricing.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Idempotency-Key middleware on every mutating route | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Pagination + filtering on every list endpoint | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-PRC-03 | pricing.admin | pricing.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Publish events per the integration map below | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Avro schema registered in Schema Registry on first publish | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Single consumer per partition; pause-on-error with backoff | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Dead-letter topic after N retries | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Redis — quote cache + rule snapshot | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Push-invalidate on every write that affects the cache key | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Stampede protection on hot keys (single-flight) | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Sync dependencies: configuration-service, `pricing-service` (tax), `pricing-service` (promotion) | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-PRC-03 | pricing.admin | pricing.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | `X-Audit-Reason` header required on admin mutations | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | Field-level encryption for PII (driver license, payment method) | pending | T-PRC-03 | pricing.admin | pricing.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Metrics: RED per route + business counters specific to this service | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-PRC-03 | pricing.admin | pricing.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-PRC-03 | pricing.admin | pricing.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-02 | Pre-upgrade Job for migrations | pending | T-PRC-01 | pricing.admin | pricing.admin | — | — |
| T-PRC-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-PRC-02 | pricing.admin | pricing.admin | — | — |
| T-PRC-04 | Smoke test in staging before production rollout | pending | T-PRC-03 | pricing.admin | pricing.admin | — | — |
### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
**Rating-density + frequent-rider sub-pipelines.**
- rating-density surge-pressure (multiplicative, capped by pricing.surge.max_multiplier)
- frequent-rider loyalty discount (after promotion, before tax, capped by pricing.min_fare.{city_id})
**Geo-config consumer.** Cross-border trips produce both tax_origin and tax_destination line items.

### Phase 7.5 — Make-a-Deal Kernel


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
- Canonical endpoint: `GET /v1/quotes/{id}/fairness-band`.
- Produces:
  - `pricing.fairness_band.computed.v1`
- New rule kind: `pricing.geo_overrides.rule_kind = max_fare_override`.
- Resolution order: INTEGRATION.md 1.7.

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `configuration-service` | per `INTEGRATION.md` | sync dependency | Yes |
| ``pricing-service` (tax)` | per `INTEGRATION.md` | sync dependency | Yes |
| ``pricing-service` (promotion)` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `pricing.quote.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `pricing.fairness_band.computed.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `pricing.rating_density.applied.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `pricing.loyalty_discount.applied.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `pricing.geo_overrides.matched.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `pricing.geo_config.updated.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `configuration.updated` | see INTEGRATION.md | see INTEGRATION.md |
| `review.zone_aggregated.v1` | see INTEGRATION.md | see INTEGRATION.md |
| `loyalty.frequent_zone.aggregated.v1` | see INTEGRATION.md | see INTEGRATION.md |
| `pricing.geo_config.updated.v1` | see INTEGRATION.md | see INTEGRATION.md |

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

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PRC-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Read-only consumer of completion events | pending | — | pricing.admin | pricing.admin | — | — |
| T-PRC-P76-02 | Register Conductor worker for `wf.phase75.deal_rider.v1` — Worker — pricing_service_fairness_check (GET /v1/quotes/{id}/fairness-band) | pending | — | pricing.admin | pricing.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 1, Position 15** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (tariff overrides, tax tables, geo-config), [`customer-service`](../customer-service/README.md) (loyalty account exposure for the frequent-rider discount pipeline) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`trip-service`](../trip-service/README.md) (quote requests — pricing-service is a synchronous dep of trip-service's quote endpoint) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-PRC-NN (Phase 1-10) | per task | per task | per task | per task |
| T-PRC-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-PRC-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-PRC-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
