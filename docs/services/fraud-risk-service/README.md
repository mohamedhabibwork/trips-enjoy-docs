# fraud-risk-service

## 1. Purpose

`fraud-risk-service` is the platform's **real-time risk
scoring and fraud detection authority**. It scores events
in real time — logins, payment attempts, dispatch attempts,
driver GPS anomalies — and produces a risk score that
downstream services use to allow, challenge, or block.
The service also maintains the device fingerprint cache,
the blocklists (email, phone, IP, device, card BIN), and
the model registry. The service is Tier-1: a regression
here stops payments and logins.

## 2. Bounded Context

**Bounded Context**: *Fraud detection / risk scoring*.

In scope:

- Real-time risk scoring (login, payment, dispatch).
- Device fingerprint cache (browser / app / device).
- Blocklists (email, phone, IP, device, card BIN, region).
- Velocity checks (attempts per minute / hour / day).
- Scoring models (rule-based + ML; registry).
- Account block actions (delegated to `identity-service`).
- Model evaluation and A/B testing.
- Audit log of every score and every block action.

Out of scope:

- User identity / KYC — `identity-service`, profile
  services.
- Payment execution — `payment-service`.
- Trip / order state — `trip-service`, `food-order-service`.
- Safety incidents — ``trip-service` (safety)` (this service
  may *score* a safety event, but does not handle the
  incident response).
- Account re-instatement — ``admin-service` (support module)` (re-instatement
  is a support action; this service only blocks).

## 3. Responsibilities

- Maintain `fraud_risk.scores`, `fraud_risk.device_fingerprints`,
  `fraud_risk.blocklists`, `fraud_risk.models`,
  `fraud_risk.evaluations`, `fraud_risk.actions`.
- Provide `POST /v1/score` (real-time scoring: login,
  payment, dispatch), `POST /v1/block` (block an account
  / card / device), `POST /v1/allowlist` (admin override).
- Provide admin / ops APIs
  (`GET /v1/admin/scores`, `GET /v1/admin/blocklists`,
  `POST /v1/admin/models/deploy`, etc.).
- Consume `identity.session.created.v1`,
  `payment.attempted.v1`, `dispatch.matched.v1` (curated
  events) and produce risk scores.
- Maintain a per-event model registry: which model is
  active for which event type (A/B / shadow).
- Emit `fraud.risk.scored.v1` for every score, consumed
  by the originating service to decide allow / challenge /
  block.
- Emit `fraud.account.blocked.v1` for every block action,
  consumed by `identity-service` and the relevant profile
  service.
- Honor GDPR / PDPL right-to-erasure: erase the user's
  scores and blocklist entries (the model itself is
  de-identified, not erased).

## 4. Explicitly NOT Owned

- **User identity, KYC, profile** — `identity-service`,
  profile services.
- **Payment execution** — `payment-service`.
- **Account re-instatement** — ``admin-service` (support module)`.
- **Safety incident response** — ``trip-service` (safety)`.
- **The model itself** is owned here; the *training data*
  is in the data warehouse (`reporting-service` /
  ``reporting-service` (data lake)`).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `identity-service` | system | producer of `identity.session.created.v1` |
| `payment-service` | system | producer of `payment.attempted.v1` |
| ``driver-service` (dispatch)` | system | producer of `dispatch.matched.v1` (curated) |
| ``trip-service` (ride-request)` | system | consumer of `fraud.risk.scored.v1` |
| `food-order-service` | system | consumer of `fraud.risk.scored.v1` |
| `customer-service` | system | consumer of `fraud.account.blocked.v1` |
| `driver-service` | system | consumer of `fraud.account.blocked.v1` |
| `courier-service` | system | consumer of `fraud.account.blocked.v1` |
| `admin-service` | system | admin operations |
| Fraud team (analyst) | human | review scores, manage blocklists, deploy models |
| ML engineer | human | deploy models |
| Security on-call | human | high-severity alerts |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — read profile (KYC tier, account
  age) — SLO 99.95% — circuit breaker: no (gateway).
- `payment-service` — read payment history — SLO 99.95% —
  circuit breaker: yes.
- `configuration-service` — read scoring thresholds,
  blocklist rules — SLO 99.95% — circuit breaker: yes.
- ``configuration-service` (flags)` — model A/B routing — SLO 99.9% —
  circuit breaker: yes.
- `reporting-service` — read aggregated features (e.g.
  "how many payments in the last 24h for this card?") —
  SLO 99.9% — circuit breaker: yes.

### Asynchronous (events consumed)

- `identity.session.created.v1` from `identity-service` —
  score the login.
- `payment.attempted.v1` from `payment-service` — score
  the payment.
- `dispatch.matched.v1` (curated) from ``driver-service` (dispatch)` —
  score the match (driver GPS vs. claimed location).
- `configuration.updated.v1` from `configuration-service` —
  thresholds changed.
- `feature_flag.updated.v1` from ``configuration-service` (flags)` —
  model A/B routing changed.

### Asynchronous (events produced)

- `fraud.risk.scored.v1` — every score.
- `fraud.account.blocked.v1` — every block action.
- `fraud.model.deployed.v1` — every model deploy (audit).
- `fraud.blocklist.updated.v1` — every blocklist change
  (audit).

## 7. Technology Assumptions

- Runtime: Python 3.12 (for ML) — scikit-learn, XGBoost,
  LightGBM, ONNX runtime for inference. Some hot paths
  may move to Go for latency.
- Database: PostgreSQL 18 in schema `fraud_risk` (scores,
  device fingerprints, blocklists, models, evaluations,
  actions).
- Cache: Redis 7 (per-service) for hot blocklist lookups,
  device fingerprint cache, in-flight score dedup.
- Event broker: Kafka.
- Model registry: in PostgreSQL (`fraud_risk.models`) +
  S3 for model artifacts.

## 8. Database Ownership

- Schema: `fraud_risk`
- Migrations: `services/fraud-risk-service/migrations/`
  (versioned, forward-only).
- Soft delete: yes (blocklists, models). Scores are
  append-mostly (with retention).
- Partitioning: yes — `fraud_risk.scores` partitioned by
  month (high volume).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/score | service | real-time scoring (login, payment, dispatch) |
| POST | /v1/block | service | block an account / card / device |
| POST | /v1/allowlist | admin + HMAC | override a block |
| GET | /v1/scores/{id} | service / admin | read a score |
| GET | /v1/admin/scores | admin | list recent scores (for analysis) |
| GET | /v1/admin/blocklists | admin | list blocklists |
| POST | /v1/admin/blocklists | admin + HMAC | add a blocklist entry |
| DELETE | /v1/admin/blocklists/{id} | admin + HMAC | remove |
| POST | /v1/admin/models/deploy | admin + HMAC + co-sig | deploy a new model |
| GET | /v1/admin/models | admin | list models |
| GET | /v1/admin/evaluations | admin | model evaluation history |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `fraud.risk.scored.v1` | every score | `identity-service`, `payment-service`, ``driver-service` (dispatch)` |
| `fraud.account.blocked.v1` | every block | `identity-service`, profile services |
| `fraud.model.deployed.v1` | every model deploy | `audit-service`, ``reporting-service` (data lake)` |
| `fraud.blocklist.updated.v1` | every blocklist change | `audit-service`, ``reporting-service` (data lake)` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `identity.session.created.v1` | `identity-service` | score the login | score, emit `fraud.risk.scored.v1` |
| `payment.attempted.v1` | `payment-service` | score the payment | score |
| `dispatch.matched.v1` (curated) | ``driver-service` (dispatch)` | score the match | score |
| `configuration.updated.v1` | `configuration-service` | thresholds changed | reload config |
| `feature_flag.updated.v1` | ``configuration-service` (flags)` | model A/B routing | reload |

## 12. External Integrations

- **S3 (object store)** — model artifacts.
- **Vault** — model signing keys, admin signing keys.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `fraud_risk.scoring.login.model_id` | UUID | configuration-service | active model for login scoring |
| `fraud_risk.scoring.payment.model_id` | UUID | configuration-service | active model for payment scoring |
| `fraud_risk.scoring.dispatch.model_id` | UUID | configuration-service | active model for dispatch scoring |
| `fraud_risk.threshold.login.allow` | number | configuration-service | score below this = allow |
| `fraud_risk.threshold.login.challenge` | number | configuration-service | score above challenge = require MFA |
| `fraud_risk.threshold.login.block` | number | configuration-service | score above block = block |
| `fraud_risk.threshold.payment.allow` | number | same | |
| `fraud_risk.threshold.payment.challenge` | number | same | |
| `fraud_risk.threshold.payment.block` | number | same | |
| `fraud_risk.velocity.payment.per_card_per_hour` | int | configuration-service | default 10 |
| `fraud_risk.velocity.login.per_ip_per_hour` | int | configuration-service | default 20 |

## 14. Security

- **AuthN**: bearer JWT (validated at gateway); internal
  calls use client-credentials tokens; mTLS for admin.
- **AuthZ**: RBAC (`service`, `fraud_analyst`, `ml_engineer`,
  `admin`).
- **Secrets**: model signing keys, admin signing keys in
  Vault; rotated quarterly.
- **PII**: device fingerprint, IP, email, phone are PII;
  encrypted at rest (`pgcrypto`).
- **Right-to-erasure**: scores and blocklist entries are
  erased within 24h of a request from ``admin-service` (support module)`.
  Models are de-identified (no per-user data) and not
  erased.

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`, `trace_id`,
  `score_id`, `event_type`, `model_id`, `score`, `decision`,
  `latency_ms`.
- **Metrics**: RED (per route) + business:
  `fraud_scores_total{event_type, decision, model_id}`,
  `fraud_score_seconds` (histogram),
  `fraud_blocks_total{event_type, reason}`,
  `fraud_model_latency_seconds{model_id, event_type}`,
  `fraud_blocklist_hits_total{blocklist_type}`.
- **Traces**: OpenTelemetry; root span per score; model
  inference as child span; feature fetch as child spans.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka
  reachable; at least one model loaded), `/started`.

## 16. Scalability

- **Replicas**: default 8 (high QPS, low latency).
- **HPA**: CPU 60%, custom metric
  `fraud_scores_per_second > 500` per replica.
- **Hot path**: `POST /v1/score`. P99 ≤ 100ms (login),
  ≤ 200ms (payment, with feature fetch).

## 17. Local Development

- `docker compose up fraud-risk-service` brings up the
  service, its DB, Redis, Kafka, and a mock model server.
- Seed: 3 models (login v1, payment v1, dispatch v1)
  with mock weights; 100 blocklist entries.
- Tests: unit, integration, model evaluation harness
  (against a held-out test set).

## 18. Deployment

- **Image**: `ghcr.io/uber/fraud-risk-service:<git-sha>`.
- **Replicas**: 8 in production.
- **Resource limits**: see deployment-arch (`cpu: 1`,
  `memory: 1.5Gi` requests; 2 CPU, 3Gi limits — ML
  inference is memory-hungry).
- **Migrations**: run as a Kubernetes Job on deploy.
- **Model deployment**: blue/green via the model registry;
  the active model is hot-swapped via `configuration.updated.v1`.


## 19. Accounting impact

`fraud-risk-service` produces **expense recognition** for the
fraud-loss and chargeback side of the ledger.

- **What money facts it owns:** risk scores, blocked-payment
  decisions, dispute / chargeback state machine (advisory),
  reserve recommendations.
- **Chargeback provisioning:** when `payment-service` opens a
  dispute (provider webhook), this service returns the
  `reserve_amount_minor` that `payment-service` posts as
  `6400_chargeback_losses` (expense) ↔ `chargeback_reserve`
  (liability). Resolution (win / loss) reverses or settles the
  reserve against `cash`.
- **Fraud-loss recognition:** confirmed-fraud transactions where
  the customer is made whole but no chargeback is possible
  (e.g. wallet-funded loss) are routed to the ledger via
  `payment-service` as a `6410_fraud_losses` expense posting.
- **Reconciliation:** indirect — chargeback / dispute state is
  reconciled by `payment-service` against the ledger daily at
  02:00 UTC.
- **Human operator path:** analyst override on risk decisions via
  `fraud-risk.analyst` role; overrides emit
  `admin.action.performed.v1`.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.


---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [``reporting-service` (data lake)`](../reporting-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [``driver-service` (dispatch)`](../driver-service/README.md), [`driver-service`](../driver-service/README.md), [``configuration-service` (flags)`](../configuration-service/README.md), [`food-order-service`](../food-order-service/README.md), [`identity-service`](../identity-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [``trip-service` (ride-request)`](../trip-service/README.md), [``trip-service` (safety)`](../trip-service/README.md), [``admin-service` (support module)`](../admin-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`identity-service`](../identity-service/README.md), [`payment-service`](../payment-service/README.md), [``pricing-service` (promotion)`](../pricing-service/README.md), [``admin-service` (support module)`](../admin-service/README.md), [``geolocation-service` (zones)`](../geolocation-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) — SOS, fraud, emergency response
- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (chargeback provisioning; fraud-loss recognition)
