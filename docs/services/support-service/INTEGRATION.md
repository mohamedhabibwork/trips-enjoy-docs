# support-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md`.

### 1.1 `POST /v1/tickets`

- **Purpose**: Open a support ticket.
- **Auth**: Bearer JWT; the user can open a ticket for
  themselves; admin can open a ticket for anyone.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "subject": "Driver was rude",
    "body": "The driver was rude and refused to follow the route.",
    "category": "trip",
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB",
    "severity_hint": "P2"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "subject": "Driver was rude",
    "category": "trip",
    "severity": "P2",
    "status": "open",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "sla_first_response_due_at": "2026-07-29T11:42:11.183Z",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 400 / 401 / 403 / 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.2 `GET /v1/tickets/{id}`

- **Purpose**: Read a ticket.
- **Auth**: Bearer JWT; the user can read their own tickets;
  agent can read any ticket in their assigned queues.
- **Response (200)**: ticket shape (with body, conversations,
  attachments if the caller is authorized).

### 1.3 `PATCH /v1/tickets/{id}`

- **Purpose**: Update a ticket (assign, severity, status).
- **Auth**: Bearer JWT + agent role; `If-Match: <version>`
  required.
- **Idempotency**: required.
- **Request**: same fields as 1.1, any subset; plus
  `assignee_sub`, `severity`, `status`.
- **Response (200)**: ticket shape, new `version`.
- **Errors**: 409 `VERSION_MISMATCH` / 403 / 422.

### 1.4 `POST /v1/tickets/{id}/messages`

- **Purpose**: Add a message to a ticket.
- **Auth**: Bearer JWT; the user can message their own
  ticket; agent can message any ticket in their assigned
  queues.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "kind": "user",
    "body": "I want a refund please.",
    "attachment_ids": []
  }
  ```
  `kind ∈ {user, agent, internal_note, system}`.
- **Response (201)**: message shape.
- **Errors**: 403 / 409 / 422.

### 1.5 `POST /v1/tickets/{id}/escalate`

- **Purpose**: Escalate a ticket to L2/L3/safety/fraud/
  finance.
- **Auth**: Bearer JWT + agent role.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "to_role": "support_agent_l2",
    "reason": "complexity"
  }
  ```
- **Response (200)**: ticket shape, with new
  `assignee_role`.

### 1.6 `POST /v1/tickets/{id}/resolve`

- **Purpose**: Resolve a ticket.
- **Auth**: Bearer JWT + agent role.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "resolution_note": "Refund issued; trip cancelled."
  }
  ```
- **Response (200)**: ticket shape, `status=resolved`,
  `resolved_at`, `reopen_until`.

### 1.7 `POST /v1/tickets/{id}/refund`

- **Purpose**: Issue a refund from a ticket (delegated to
  the payment integration service).
- **Auth**: Bearer JWT + agent role; refund > $500
  requires co-signature.
- **Idempotency**: required; the value is
  `ticket:<ticket_id>:refund:<N>`.
- **Request**:
  ```json
  {
    "payment_id": "01HZX9C5S3B1L7K0P2F8V4T6YDD",
    "amount_minor": 5000,
    "currency": "USD",
    "reason": "Quality issue"
  }
  ```
- **Response (200)**:
  ```json
  {
    "refund_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "status": "initiated",
    "idempotency_key": "ticket:01HZX9C8W6K0G3V2Y5N1Q4R7PB:refund:1",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 403 / 409 `CO_SIGNATURE_REQUIRED` /
  422 `REFUND_LIMIT_EXCEEDED` / 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.8 `POST /v1/tickets/{id}/reinstate`

- **Purpose**: Re-instate a suspended account.
- **Auth**: Bearer JWT + L3 + co-signature (HMAC).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "user_type": "customer",
    "reason": "False positive fraud block",
    "co_signer_sub": "01HZX9C5G3V1L7K0P2F8V4T6DDX",
    "co_signer_signature": "..."
  }
  ```
- **Response (200)**: re-instatement shape.

### 1.9 `GET /v1/tickets`

- **Purpose**: List tickets (filter by `status`, `severity`,
  `assignee_sub`, `user_id`, etc.).
- **Auth**: Bearer JWT; the user can list their own;
  agent can list their assigned queue.
- **Response (200)**: paginated list.

### 1.10 `GET /v1/admin/tickets/queue`

- **Purpose**: Agent queue (sorted by SLA due time).
- **Auth**: Bearer JWT + agent role.
- **Response (200)**: paginated list, sorted by
  `sla_first_response_due_at ASC`.

### 1.11 `GET /v1/admin/tickets/sla`

- **Purpose**: SLA breach report.
- **Auth**: Bearer JWT + agent L2+.
- **Response (200)**: paginated list of breached tickets.

### 1.12 `POST /v1/admin/dsar`

- **Purpose**: Submit a data subject access / erasure
  request.
- **Auth**: Bearer JWT + compliance role; HMAC signed.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "kind": "access",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "user_type": "customer",
    "idempotency_key": "..."
  }
  ```
- **Response (202)**: DSAR shape with `deadline_at`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | POST | `/v1/customers/{id}/reinstate` | reinstate a customer | 2s | 2 | yes |
| `driver-service` | POST | `/v1/drivers/{id}/reinstate` | reinstate a driver | 2s | 2 | yes |
| `courier-service` | POST | `/v1/couriers/{id}/reinstate` | reinstate a courier | 2s | 2 | yes |
| `merchant-service` | POST | `/v1/merchants/{id}/reinstate` | reinstate a merchant | 2s | 2 | yes |
| `food-payment-integration-service` | POST | `/v1/refunds` | refund an order | 3s | 2 | yes |
| `ride-payment-integration-service` | POST | `/v1/refunds` | refund a trip | 3s | 2 | yes |
| `payment-service` | GET | `/v1/payments/{id}` | read payment history | 1s | 1 | yes |
| `notification-service` | POST | `/v1/notifications` | send ticket update | 2s | 1 | yes |
| `file-service` | GET | `/v1/files/{id}` | read attachment metadata | 1s | 1 | yes |
| `search-service` | POST | `/v1/search/tickets` | search across tickets | 500ms | 1 | yes |
| `identity-service` | GET | `/v1/identities/{sub}` | verify user | 500ms | 1 | no |
| `configuration-service` | GET | `/v1/config/support` | read severity matrix, SLA timers | 500ms | 3 | yes |

All outbound calls carry `X-Correlation-Id` and `traceparent`.

## 3. Produced Events

### 3.1 `support.ticket.opened.v1`

- **Producer**: `support-service`.
- **Topic**: `support.ticket.opened`.
- **Trigger**: every new ticket.
- **Partition key**: `ticket_id` (UUIDv7 keeps per-ticket
  ordering).
- **Schema (data)**:
  ```json
  {
    "ticket_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "subject": "Driver was rude",
    "category": "trip",
    "severity": "P2",
    "status": "open",
    "source": "user",
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDB",
    "sla_first_response_due_at": "2026-07-29T11:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `support.ticket.opened.dlq`.
- **Consumers**: `notification-service`, `audit-service`,
  `analytics-service`.

### 3.2 `support.ticket.resolved.v1`

Same as 3.1 with `status=resolved` and `resolution_note`.

### 3.3 `support.action.performed.v1`

- **Producer**: `support-service`.
- **Topic**: `support.action.performed`.
- **Trigger**: every agent action (refund, reinstate,
  escalate, etc.).
- **Partition key**: `ticket_id`.
- **Schema (data)**:
  ```json
  {
    "ticket_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "action": "refund_initiated",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "actor_role": "support_agent_l2",
    "result": "success",
    "refund_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "amount_minor": 5000,
    "currency": "USD",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `audit-service`, `analytics-service`.

### 3.4 `support.incident.opened.v1`

- **Producer**: `support-service`.
- **Topic**: `support.incident.opened`.
- **Trigger**: every P1 safety ticket (high-severity).
- **Partition key**: `ticket_id`.
- **Schema (data)**: similar to 3.1 with `severity=P1`,
  `category=safety`, `incident=true`.
- **Consumers**: `notification-service` (pages on-call),
  `audit-service`.

### 3.5 `support.incident.resolved.v1`

Same as 3.4 with `status=resolved`.

## 4. Consumed Events

### 4.1 `ride.safety.sos.v1`

- **Producer**: `ride-safety-service`.
- **Reason**: P1 safety ticket within 60s.
- **Handler**:
  1. Inbox insert.
  2. Open P1 ticket with `category=safety`, `source=event`,
     `user_id=<customer_id>`, `trip_id=<trip_id>`.
  3. Page on-call (via `notification-service`).
  4. Emit `support.ticket.opened.v1` and
     `support.incident.opened.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ; alert.

### 4.2 `ride.safety.incident.v1`

Same as 4.1 with category `safety` and `incident`.

### 4.3 `payment.disputed.v1`

- **Producer**: `payment-service`.
- **Reason**: P1 chargeback ticket within 60s.
- **Handler**: open P1 ticket with `category=payment`,
  `source=event`, `user_id`, `payment_id`. Emit
  `support.ticket.opened.v1` and `support.incident.opened.v1`.

### 4.4 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: P2 review ticket.
- **Handler**: open P2 ticket with `category=account`,
  `source=event`, `user_id`. No page.

### 4.5 `identity.user.disabled.v1`

Same as 4.4 with `category=account`.

### 4.6 `notification.failed.v1`

- **Producer**: `notification-service`.
- **Reason**: if the failed notification is a money event,
  open a P1 ticket.
- **Handler**: check `category` ∈ {payment, refund, payout};
  if yes, open P1 ticket. Otherwise, open P3.

### 4.7 `comms.send.failed.v1`

- **Producer**: `communication-gateway-service`.
- **Reason**: investigate a delivery failure.
- **Handler**: open P3 ticket if it's a money event,
  otherwise no-op.

### 4.8 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: severity matrix, SLA timers, refund limits
  changed.
- **Handler**: reload config.

## 5. Reliability

- **Timeouts**: 2s default; 3s for payment integration;
  500ms for configuration.
- **Retries**: 2 attempts with backoff on 5xx/timeout. Never
  on 4xx.
- **Circuit breakers**: per downstream; open on ≥ 3
  consecutive 5xx/timeout in 30s.
- **Outbox / Inbox**: standard pattern.
- **DLQ**: every topic has a paired `<topic>.dlq`.
- **Reconciliation**: a daily job verifies that every P1
  ticket has been responded to within 60s (catches missed
  notifications).

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event.
  - The `headers.correlation_id` of every outbox row.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments HTTP, Kafka, DB.
- One root span per agent action or per event consumption.
- Sample 100% of errors, 10% of successes in production.
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
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`communication-gateway-service`](../communication-gateway-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-safety-service`](../ride-safety-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`admin-service`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`reporting-service`](../reporting-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-settlement-service`](../restaurant-settlement-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`review-rating-service`](../review-rating-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 3 more_ | |

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

