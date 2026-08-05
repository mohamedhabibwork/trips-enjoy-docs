# Feature Flag Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT (RS256,
Keycloak JWKS). Errors use the standard envelope.

### 1.1 `POST /v1/flags`

- **Purpose**: Create a new flag.
- **Auth**: Bearer JWT. Required role: `flag.admin`. Required header:
  `X-Audit-Reason`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "key": "new_pricing_v2",
    "name": "New pricing engine v2",
    "description": "Switch to v2 pricing",
    "type": "boolean",
    "default_value": false,
    "category": "release"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "key": "new_pricing_v2",
    "current_rule_set_version": 0,
    "created_at": "2026-07-29T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` / `AUDIT_REASON_REQUIRED`.
  - 401 / 403.
  - 409 `FLAG_EXISTS`.
  - 422 `VALIDATION_FAILED` (schema mismatch).

### 1.2 `PUT /v1/flags/{key}`

- **Purpose**: Update flag metadata (default, description, owner).
- **Auth**: Bearer JWT. Required role: `flag.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "default_value": false,
    "description": "Switch to v2 pricing",
    "expected_rule_set_version": 3
  }
  ```
- **Response (200)**: confirmation envelope.
- **Errors**: 400 / 401 / 403 / 404 / 409 `RULE_VERSION_CONFLICT`.

### 1.3 `POST /v1/flags/{key}/rules`

- **Purpose**: Add (or replace) a rule set.
- **Auth**: Bearer JWT. Required role: `flag.admin` (or
  `flag.experiment` if `category = "experiment"`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "rules": [
      {
        "rule_id": "01HZX…",
        "when": { "region": ["eu-west"], "percentage": 10 },
        "value": true
      }
    ],
    "expected_rule_set_version": 3,
    "reason": "Roll out to 10% in EU"
  }
  ```
- **Response (201)**:
  ```json
  {
    "flag_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "version": 4,
    "rules_count": 1,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**: as above + 422 if rule shape invalid.

### 1.4 `DELETE /v1/flags/{key}/rules/{rule_id}`

- **Purpose**: Remove a rule (creates a new rule set version).
- **Auth**: Bearer JWT. Required role: `flag.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: confirmation.
- **Errors**: 401 / 403 / 404.

### 1.5 `POST /v1/flags/{key}/evaluate`

- **Purpose**: Evaluate a flag against an evaluation context.
- **Auth**: Bearer JWT (or service account). Required scope:
  `flag.evaluate`.
- **Request**:
  ```json
  {
    "context": {
      "stable_id": "01HZX…",
      "stable_id_key": "customer_id",
      "user_id": "01HZX…",
      "segment": ["premium"],
      "region": "eu-west",
      "country": "NL",
      "app_version": "1.42.0",
      "custom": { "experiment_cohort": "A" }
    }
  }
  ```
- **Response (200)**:
  ```json
  {
    "key": "new_pricing_v2",
    "value": true,
    "variant": "treatment",
    "matched_rule_id": "01HZX…",
    "rule_set_version": 4,
    "reason": "rule_match",
    "evaluation_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB"
  }
  ```
- **Errors**:
  - 200 with `value=null` and `matched_rule_id="error"` + header
    `X-Flag-Error: 1` on a server error (caller falls back).
  - 404 `FLAG_NOT_FOUND`.
  - 401 / 403.

### 1.6 `POST /v1/flags/{key}/disable`

- **Purpose**: Trigger a kill switch.
- **Auth**: Bearer JWT. Required role: `flag.admin`. Required:
  `X-Audit-Reason`, `X-Signature`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**: `{ "reason": "Disable cash payments due to fraud" }`.
- **Response (200)**: confirmation.
- **Errors**: 400 / 401 / 403 / 404.

### 1.7 `GET /v1/flags/stream`

- **Purpose**: Long-poll update stream.
- **Auth**: Bearer JWT. Required scope: `flag.subscribe`.
- **Query params**: `keys=new_pricing_v2,…&wait_seconds=25`.
- **Response (200)**: array of updates.

### 1.8 `GET /v1/channels/{channel}/flags`

- **Purpose**: Filtered subset for a mobile / web client.
- **Auth**: Bearer JWT.
- **Response (200)**: filtered flag values.

## 2. Outbound APIs

This service does not call other services synchronously. The only
outbound is the broker publish.

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| n/a | n/a | n/a | n/a | n/a | n/a | n/a |

## 3. Produced Events

### 3.1 `feature_flag.updated.v1`

- **Producer**: `feature-flag-service`.
- **Topic**: `feature_flag.updated`.
- **Trigger**: any change to a flag or rule set.
- **Schema version**: 1.
- **Partition key**: `flag_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "feature_flag.updated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "feature-flag-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "FeatureFlag",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "key": "new_pricing_v2",
      "old_rule_set_version": 3,
      "new_rule_set_version": 4,
      "rules": [
        { "rule_id": "01HZX…", "when": { "region": ["eu-west"], "percentage": 10 }, "value": true }
      ],
      "actor_id": "01HZX…",
      "reason": "Roll out to 10% in EU"
    }
  }
  ```
- **Retry**: outbox poller, 3 attempts.
- **DLQ**: `feature_flag.updated.dlq`.

### 3.2 `feature_flag.disabled.v1`

- **Producer**: `feature-flag-service`.
- **Topic**: `feature_flag.disabled`.
- **Trigger**: kill switch.
- **Schema version**: 1.
- **Partition key**: `flag_id`.
- **Schema**: similar to 3.1 with `data.action = "disable"` and
  the reason.
- **Retry / DLQ**: outbox.

### 3.3 `feature_flag.experiment.started.v1`

- **Producer**: `feature-flag-service`.
- **Topic**: `feature_flag.experiment.started`.
- **Trigger**: an experiment flag is created or its rules change.
- **Schema version**: 1.
- **Partition key**: `flag_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "feature_flag.experiment.started.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "feature-flag-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "FeatureFlag",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "key": "new_pricing_v2",
      "experiment_id": "01HZX…",
      "metric": "conversion_rate",
      "variants": ["control", "treatment"],
      "started_at": "2026-07-29T10:42:11.183Z",
      "ends_at": "2026-08-29T10:42:11.183Z"
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 3.4 `feature_flag.experiment.stopped.v1`

Same shape as 3.3 with `event_name` = `feature_flag.experiment.stopped.v1`,
`data.action = "stop"`, and `data.stopped_at`.

## 4. Consumed Events

### 4.1 `customer.segment.changed.v1`

- **Producer**: `customer-service`.
- **Reason**: segment-based rules may now match this customer.
- **Handler**: invalidate evaluation cache for the affected customer
  id; the next evaluation reads the latest segment.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `admin.configuration.changed.v1`

- **Producer**: `admin-service`.
- **Reason**: A flag override was set.
- **Handler**: Apply override.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Linked config changed.
- **Handler**: Audit; emit `feature_flag.updated.v1`.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `user.flag.targeted.v1`

- **Producer**: `internal`.
- **Reason**: A user is added to a flag's targeting list.
- **Handler**: Apply targeting.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: read 1s; write 2s; DB 30s; Kafka publish 5s.
- **Retries**: bounded 3, exponential backoff + jitter; respects
  `Retry-After`.
- **Circuit breakers**: not required (no synchronous outbound).
- **Bulkheads**: long-poll pool separate from request pool.
- **Outbox**: yes, table `feature_flag.outbox`, polled every 200ms.
- **Inbox**: yes, table `feature_flag.inbox`.
- **DLQ**: every topic above has a paired DLQ with 30-day retention.
- **Reconciliation**: daily job compares the S3 snapshot to the DB
  state; drift opens a `support.ticket` and emits
  `reconciliation.drift.found.v1`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope and the
`audit_log` row.

## 7. Distributed Tracing

OpenTelemetry: one root span per HTTP request; child spans for
evaluation, DB, Redis, Kafka. `traceparent` propagated through
Kafka headers. Sample rate 100% for errors, 10% for successes.

### 4.2 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: pre-warm per-user evaluation caches (the SDK will
  query on the first request; pre-warming reduces the first-call
  latency).
- **Handler**: insert a sentinel into the per-user cache.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: a suspended customer must not receive kill-switch
  relief (e.g. `disable_cash_payments` for a fraudulent user).
  The flag service blocks evaluation for suspended customers.
- **Handler**: add `customer_id` to a Redis `blocked_users` set
  with TTL = 24h or until `customer.reinstated.v1`.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: a feature flag may be gated by a configuration
  value (e.g. `feature_flag.<key>.enabled`); the configuration
  change may force a flag re-evaluation.
- **Handler**: invalidate any cached evaluations that referenced
  the changed key.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.


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
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`user-profile-service`](../user-profile-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`communication-gateway-service`](../communication-gateway-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-staff-service`](../restaurant-staff-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`search-service`](../search-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` §1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in §1 of this document; the canonical catalog is in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

