# fraud-risk-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md`.

### 1.1 `POST /v1/score`

- **Purpose**: Real-time risk scoring for a login, payment,
  or dispatch event.
- **Auth**: Bearer JWT + role `service` (any service may
  request a score; typically `identity-service`,
  `payment-service`, ``driver-service` (dispatch)`).
- **Idempotency**: not required (the operation is
  read-mostly; the score row is append-only and a duplicate
  request is fine).
- **Request (login)**:
  ```json
  {
    "event_type": "login",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "context": {
      "device_fingerprint": "01HZX9C8W6K0G3V2Y5N1Q4R7P9",
      "ip": "203.0.113.42",
      "user_agent": "Mozilla/5.0 ...",
      "geo": { "country": "US", "city": "Mountain View", "lat": 37.42, "lon": -122.08 }
    }
  }
  ```
- **Request (payment)**:
  ```json
  {
    "event_type": "payment",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "payment_id": "01HZX9C5S3B1L7K0P2F8V4T6YDD",
    "context": {
      "card_bin": "424242",
      "card_last4": "4242",
      "amount_minor": 12345,
      "currency": "USD",
      "merchant_id": "01HZX9C5S3B1L7K0P2F8V4T6YDE"
    }
  }
  ```
- **Request (dispatch)**:
  ```json
  {
    "event_type": "dispatch",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB",
    "context": {
      "driver_id": "01HZX9C5S3B1L7K0P2F8V4T6YDF",
      "claimed_lat": 24.7136,
      "claimed_lon": 46.6753,
      "gps_lat": 24.7136,
      "gps_lon": 46.6753,
      "distance_m": 5
    }
  }
  ```
- **Response (200)**:
  ```json
  {
    "score_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PA",
    "score": 0.12,
    "decision": "allow",
    "model_id": "01HZX9C5S3B1L7K0P2F8V4T6YDG",
    "model_version": 3,
    "reason_codes": [],
    "latency_ms": 45,
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 / 403
  - 503 `MODEL_INFERENCE_FAILED` (no fallback)
  - 504 `DEPENDENCY_TIMEOUT`

### 1.2 `POST /v1/block`

- **Purpose**: Block an account / card / device.
- **Auth**: Bearer JWT + role `service` (typically
  `payment-service` after a confirmed-fraud event).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "target_type": "card",
    "target_value": "4242424242424242",
    "reason": "Confirmed fraud: chargeback won",
    "severity": "high"
  }
  ```
- **Response (200)**:
  ```json
  {
    "block_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "status": "active",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 400 / 401 / 403 / 409 / 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.3 `POST /v1/allowlist`

- **Purpose**: Admin override (remove a block).
- **Auth**: Bearer JWT + role `admin` or `fraud_analyst_l2`
  + co-signature.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "target_type": "user",
    "target_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "reason": "False positive; reviewed by support",
    "co_signer_sub": "01HZX9C5G3V1L7K0P2F8V4T6DDX",
    "co_signer_signature": "..."
  }
  ```
- **Response (200)**: allowlist shape.

### 1.4 `GET /v1/scores/{id}`

- **Purpose**: Read a score.
- **Auth**: Bearer JWT + role `service` or `admin` or
  `fraud_analyst`.
- **Response (200)**: score shape.

### 1.5 `GET /v1/admin/scores`

- **Purpose**: List recent scores (filter by `event_type`,
  `decision`, `model_id`, `user_id`, `from`, `to`).
- **Auth**: Bearer JWT + role `fraud_analyst`.
- **Response (200)**: paginated list.

### 1.6 `GET /v1/admin/blocklists`

- **Purpose**: List blocklists (filter by `type`, `severity`,
  `tenant_id`).
- **Auth**: Bearer JWT + role `fraud_analyst`.
- **Response (200)**: paginated list.

### 1.7 `POST /v1/admin/blocklists`

- **Purpose**: Add a blocklist entry.
- **Auth**: Bearer JWT + role `fraud_analyst` + HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "type": "email",
    "value": "fraud@example.com",
    "reason": "Repeated fraud attempts",
    "severity": "high",
    "tenant_id": "01HZX9C5S3B1L7K0P2F8V4T6YDH",
    "expires_at": null
  }
  ```
- **Response (201)**: blocklist shape.
- **Errors**: 400 / 401 / 403 / 409 / 422.

### 1.8 `DELETE /v1/admin/blocklists/{id}`

- **Purpose**: Remove a blocklist entry.
- **Auth**: Bearer JWT + role `fraud_analyst` + HMAC.
- **Response (204)**: no content.

### 1.9 `POST /v1/admin/models/deploy`

- **Purpose**: Deploy a new model (blue/green).
- **Auth**: Bearer JWT + role `ml_engineer` or `admin` +
  HMAC + co-signature.
- **Idempotency**: required (idempotent on `model_id`).
- **Request**:
  ```json
  {
    "model_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "traffic_percentage": 10,
    "co_signer_sub": "01HZX9C5G3V1L7K0P2F8V4T6DDX",
    "co_signer_signature": "..."
  }
  ```
- **Response (200)**: model shape, `status=active`,
  `traffic_percentage=10`.
- **Errors**: 401 / 403 / 409 / 422.

### 1.10 `GET /v1/admin/models`

- **Purpose**: List models.
- **Auth**: Bearer JWT + role `ml_engineer` or `admin`.
- **Response (200)**: paginated list.

### 1.11 `GET /v1/admin/evaluations`

- **Purpose**: Model evaluation history.
- **Auth**: Bearer JWT + role `ml_engineer` or `fraud_analyst`.
- **Response (200)**: paginated list.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| S3 | GET | `<artifact_s3_path>` | load model artifact | 5s | 2 | yes |
| `identity-service` | GET | `/v1/identities/{sub}` | read profile | 500ms | 1 | no |
| `payment-service` | GET | `/v1/payments/{id}` | read payment history | 1s | 1 | yes |
| `configuration-service` | GET | `/v1/config/fraud-risk` | read thresholds, models | 500ms | 3 | yes |
| ``configuration-service` (flags)` | GET | `/v1/flags/fraud-risk.ab` | A/B routing | 300ms | 1 | yes |
| `reporting-service` | GET | `/v1/reports/features/{key}` | read aggregated features | 1s | 1 | yes |

All outbound calls carry `X-Correlation-Id` and `traceparent`.

## 3. Produced Events

### 3.1 `fraud.risk.scored.v1`

- **Producer**: `fraud-risk-service`.
- **Topic**: `fraud.risk.scored`.
- **Trigger**: every score.
- **Partition key**: `user_id` (or `payment_id` if user_id
  is null).
- **Schema (data)**:
  ```json
  {
    "score_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PA",
    "event_type": "payment",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "payment_id": "01HZX9C5S3B1L7K0P2F8V4T6YDD",
    "trip_id": null,
    "score": 0.12,
    "decision": "allow",
    "model_id": "01HZX9C5S3B1L7K0P2F8V4T6YDG",
    "model_version": 3,
    "reason_codes": [],
    "latency_ms": 45,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `fraud.risk.scored.dlq`.
- **Consumers**: `identity-service`, `payment-service`,
  ``driver-service` (dispatch)`, ``admin-service` (support module)`, `audit-service`,
  ``reporting-service` (data lake)`.

### 3.2 `fraud.account.blocked.v1`

- **Producer**: `fraud-risk-service`.
- **Topic**: `fraud.account.blocked`.
- **Trigger**: every block action.
- **Partition key**: `target_id` (or `user_id`).
- **Schema (data)**:
  ```json
  {
    "block_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "target_type": "user",
    "target_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "reason": "Confirmed fraud",
    "severity": "high",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `identity-service`, `customer-service`,
  `driver-service`, `courier-service`, ``admin-service` (support module)`,
  `audit-service`.

### 3.3 `fraud.model.deployed.v1`

- **Producer**: `fraud-risk-service`.
- **Topic**: `fraud.model.deployed`.
- **Trigger**: every model deploy.
- **Partition key**: `model_id`.
- **Schema (data)**:
  ```json
  {
    "model_id": "01HZX9C5S3B1L7K0P2F8V4T6YDG",
    "name": "payment_v3",
    "event_type": "payment",
    "version": 3,
    "traffic_percentage": 10,
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "co_signer_sub": "01HZX9C5G3V1L7K0P2F8V4T6DDX",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`.

### 3.4 `fraud.blocklist.updated.v1`

- **Producer**: `fraud-risk-service`.
- **Topic**: `fraud.blocklist.updated`.
- **Trigger**: every blocklist add / remove.
- **Partition key**: `blocklist_id`.
- **Schema (data)**:
  ```json
  {
    "blocklist_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "type": "email",
    "value_hash": "...",
    "action": "added",
    "severity": "high",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`.

## 4. Consumed Events

### 4.1 `identity.session.created.v1`

- **Producer**: `identity-service`.
- **Reason**: score the login.
- **Handler**:
  1. Inbox insert.
  2. Fetch user profile (KYC tier, account age).
  3. Run the scoring flow (blocklist → velocity → model).
  4. Emit `fraud.risk.scored.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `payment.attempted.v1`

- **Producer**: `payment-service`.
- **Reason**: score the payment.
- **Handler**: same as 4.1; emit `fraud.risk.scored.v1`.

### 4.3 `dispatch.matched.v1` (curated)

- **Producer**: ``driver-service` (dispatch)`.
- **Reason**: score the match (driver GPS vs. claimed
  location).
- **Handler**: same as 4.1.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: thresholds, active models, velocity limits
  changed.
- **Handler**: reload config (idempotent).

### 4.5 `feature_flag.updated.v1`

- **Producer**: ``configuration-service` (flags)`.
- **Reason**: A/B routing changed.
- **Handler**: reload A/B config.

## 5. Reliability

- **Timeouts**: 100ms (login), 200ms (payment, dispatch);
  5s for S3 model load; 500ms for configuration.
- **Retries**: 2 attempts with backoff. Never on 4xx.
- **Circuit breakers** per downstream: open on ≥ 3
  consecutive 5xx/timeout in 30s.
- **Model fallback**: rule-based model is always available;
  if ML fails, scoring continues with the rule-based model.
- **All-paths-fail fallback**: return `challenge` (the
  safest default).
- **Outbox / Inbox**: standard pattern.
- **DLQ**: every topic has a paired `<topic>.dlq`.
- **Reconciliation**: a daily job verifies that the
  `blocklists` Redis cache matches the PostgreSQL source
  of truth.

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event.
  - The `headers.correlation_id` of every outbox row.
  - The `correlation_id` column of every score row.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments HTTP, Kafka, DB,
  Redis, S3.
- One root span per score; model inference as child span;
  feature fetch as child spans.
- Sample 100% of errors, 10% of successes in production;
  100% in staging.
- The inbound `traceparent` is honored.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``driver-service` (dispatch)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``configuration-service` (flags)`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (ride-request)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (safety)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``pricing-service` (promotion)`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``admin-service` (support module)`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-python v1.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.onboarding.driver.v1` | fraud_risk_service_risk_score | `driver:{id}:fraud:score` |
| `wf.onboarding.courier.v1` | fraud_risk_service_risk_score | `courier:{id}:fraud:score` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| (no inbound Kafka signals — REST trigger only or worker is reactive to conductor-kafka-bridge events) | – | – |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| (no compensation — terminal states only, or compensation is no-op) | – | – |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.trips-enjoy.com`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../../MASTER_TASK.md) 7-9
