# driver-incentive-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/incentives/quests`

- **Purpose**: List active quests for the driver.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "id": "...",
        "name": "20 trips in zone X by Friday",
        "reward_minor": 10000,
        "currency": "AED",
        "active_until": "...",
        "opted_in": false,
        "progress": { "current": 5, "target": 20 }
      }
    ]
  }
  ```

### 1.2 `GET /v1/incentives/quests/{id}/progress`

- **Purpose**: Quest progress.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "id": "...",
    "current": 5,
    "target": 20,
    "percent": 25
  }
  ```

### 1.3 `POST /v1/incentives/quests/{id}/opt-in`

- **Purpose**: Opt in to a quest.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: the opt-in record.

### 1.4 `POST /v1/incentives/quests/{id}/opt-out`

- **Purpose**: Opt out.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: the opt-in record with `opted_out_at` set.

### 1.5 `GET /v1/incentives/bonuses`

- **Purpose**: List available bonuses.
- **Auth**: Bearer JWT (driver).
- **Response (200)**: list of bonus definitions.

### 1.6 `GET /v1/incentives/guarantees`

- **Purpose**: List active surge guarantees.
- **Auth**: Bearer JWT (driver).
- **Response (200)**: list of guarantee definitions.

### 1.7 `POST /v1/incentives` (admin)

- **Purpose**: Create a quest / bonus / guarantee.
- **Auth**: Bearer JWT (admin) with `X-Audit-Reason`.
- **Request**:
  ```json
  {
    "type": "quest",
    "name": "20 trips in zone X by Friday",
    "city_id": "...",
    "zone_id": "...",
    "rule": { "target_trips": 20, "reward_minor": 10000, "currency": "AED" },
    "eligibility": { "min_rating": 4.0, "min_trip_count": 10, "requires_opt_in": true },
    "active_from": "2026-07-29T00:00:00.000Z",
    "active_until": "2026-08-02T23:59:59.000Z"
  }
  ```
- **Response (201)**: the created incentive.

### 1.8 `PATCH /v1/incentives/{id}` (admin)

- **Purpose**: Update a quest / bonus / guarantee.
- **Auth**: Bearer JWT (admin) with `X-Audit-Reason`.
- **Response (200)**: the updated incentive.

### 1.9 `POST /v1/incentives/{id}/disable` (admin)

- **Purpose**: Disable an incentive.
- **Auth**: Bearer JWT (admin) with `X-Audit-Reason`.
- **Response (200)**: the disabled incentive.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `driver-service` | GET | /v1/drivers/{id}/rating | rating for eligibility | 200ms | 1 | yes |
| `driver-service` | GET | /v1/drivers/{id}/trip-count | trip count for eligibility | 200ms | 1 | yes |
| `driver-earnings-service` | POST | /v1/earnings/accrue | post the earned amount | 500ms | 2 | yes |

## 3. Produced Events

### 3.1 `driver.incentive.earned.v1`

- **Topic**: `driver.incentive.earned`.
- **Partition key**: `driver_id`.
- **Consumers**: `driver-earnings-service` (post), `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.incentive.earned.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "incentive_id": "...",
      "driver_id": "...",
      "trip_id": "...",
      "amount_minor": 10000,
      "currency": "AED",
      "rule_fired": { ... },
      "earned_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `driver.incentive.earned.v1`

- **Producer**: this service.
- **Topic**: `driver.incentive.earned`.
- **Trigger**: A driver earns an incentive (quest completion, bonus, surge guarantee).
- **Schema version**: 1.
- **Partition key**: `driver_id`.
- **Consumers**: `driver-earnings-service`, `notification-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "driver.incentive.earned.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `driver.incentive.earned.dlq`.


### 3.3 `driver.incentive.quest_created.v1`

- **Producer**: this service.
- **Topic**: `driver.incentive.quest`.
- **Trigger**: An admin creates a new quest.
- **Schema version**: 1.
- **Partition key**: `quest_id`.
- **Consumers**: `notification-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "driver.incentive.quest_created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `driver.incentive.quest.dlq`.


### 3.4 `driver.incentive.quest_disabled.v1`

- **Producer**: this service.
- **Topic**: `driver.incentive.quest`.
- **Trigger**: An admin disables a quest.
- **Schema version**: 1.
- **Partition key**: `quest_id`.
- **Consumers**: `notification-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "driver.incentive.quest_disabled.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `driver.incentive.quest.dlq`.



## 4. Consumed Events

### 4.1 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: evaluate and earn.
- **Handler**: for each active incentive for the driver, evaluate
  eligibility; if earned, insert `incentive_earning` row and post
  to `driver-earnings-service`. Idempotent.
- **Deduplication**: inbox on `event_id`; UNIQUE on
  `idempotency_key`.
- **Retry**: 3; failure → DLQ.

### 4.2 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: Trigger incentive evaluation.
- **Handler**: Evaluate active quests.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Quest rules changed.
- **Handler**: Reload quest config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `driver.suspended.v1`

- **Producer**: `driver-service`.
- **Reason**: Suspended driver is ineligible.
- **Handler**: Mark ineligible.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: outbound 200–500ms; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `driver_incentive.outbox` table.
- **Inbox**: `driver_incentive.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks
  for `incentive_earnings.posted_to_earnings = false` older than
  1 hour and pages on-call.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id
  for the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per evaluation. `traceparent` is
propagated. Sample rate: 100% for errors, 10% for successes in
production.


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
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

