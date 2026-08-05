# support-service

## 1. Purpose

`support-service` is the platform's **support ticket and
investigation authority**. It owns the lifecycle of every
customer / driver / courier / merchant support ticket, the
conversations (agent + customer messages), the attachments,
the escalations to specialized teams (safety, fraud, finance),
and the integration with downstream actions (refunds,
re-instatements, account reactivation). The service is also
the platform's "human-in-the-loop" layer for safety incidents,
GDPR/PDPL data subject requests, and provider-initiated
chargebacks.

## 2. Bounded Context

**Bounded Context**: *Support tickets*.

In scope:

- Ticket lifecycle (open, triage, in_progress, awaiting_customer,
  awaiting_internal, escalated, resolved, closed).
- Severity matrix (P1 life-safety, P2 urgent, P3 normal, P4 low).
- Agent RBAC (L1, L2, L3, safety, fraud, finance, admin).
- Conversations (agent ↔ customer / driver / courier /
  merchant, threaded).
- Attachments (linked to `file-service`).
- Escalations (to safety / fraud / finance teams).
- Refund initiation (delegates to
  `food-payment-integration-service` for execution).
- Data subject access / erasure (GDPR / PDPL).
- Account re-instatement (after a suspension or block).
- Audit log of every action (who, when, what).

Out of scope:

- Customer / driver / courier / merchant profile data — owned
  by their respective services.
- Payment execution — `food-payment-integration-service`,
  `ride-payment-integration-service`.
- Safety incident detection (e.g. SOS detection) —
  `ride-safety-service`; this service consumes the
  `ride.safety.*.v1` events.
- Fraud scoring — `fraud-risk-service`; this service consumes
  the `fraud.*.v1` events.

## 3. Responsibilities

- Maintain `support.tickets`, `support.conversations`,
  `support.attachments`, `support.escalations`,
  `support.actions`.
- Provide `POST /v1/tickets` (open a ticket),
  `GET /v1/tickets/{id}`, `PATCH /v1/tickets/{id}`,
  `POST /v1/tickets/{id}/messages` (add a message),
  `POST /v1/tickets/{id}/escalate`,
  `POST /v1/tickets/{id}/resolve`,
  `POST /v1/tickets/{id}/refund`,
  `POST /v1/tickets/{id}/reinstate`.
- Provide `GET /v1/tickets` (list, filtered by status,
  severity, assignee, etc.).
- Provide admin / agent operations
  (`GET /v1/admin/tickets/queue`, etc.).
- Consume `ride.safety.sos.v1` and `ride.safety.incident.v1`
  to open P1 tickets within 60s.
- Consume `payment.disputed.v1` to open a P1 ticket.
- Consume `customer.suspended.v1` and `identity.user.disabled.v1`
  to open a ticket for review.
- Initiate refunds via `food-payment-integration-service`
  or `ride-payment-integration-service` with
  `Idempotency-Key = ticket:<ticket_id>:refund:<N>`.
- Initiate account re-instatement via `customer-service`,
  `driver-service`, `courier-service`, `merchant-service`.
- Honor data subject access / erasure requests per GDPR /
  PDPL.
- Emit `support.ticket.opened.v1`, `support.ticket.resolved.v1`,
  `support.action.performed.v1` for `notification-service`,
  `audit-service`, `analytics-service`.

## 4. Explicitly NOT Owned

- **Customer / driver / courier / merchant profile** — owned
  by their respective services.
- **Payment execution** — `food-payment-integration-service`,
  `ride-payment-integration-service`.
- **Fraud scoring** — `fraud-risk-service`.
- **Safety detection** — `ride-safety-service`.
- **The user's "unread" inbox** — client app + the
  `customer-service` history.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer (via app / web) | human | open ticket, view own ticket, add message |
| Driver / Courier (via app) | human | open ticket, view own ticket, add message |
| Merchant / Restaurant staff (via portal) | human | open ticket, view own ticket, add message |
| Support agent (L1, L2, L3) | human | triage, assign, message, resolve |
| Safety team (L3 + safety role) | human | handle P1 safety tickets |
| Fraud team (L3 + fraud role) | human | handle fraud / chargeback tickets |
| Finance team (L3 + finance role) | human | handle refunds, settlements |
| Compliance officer | human | handle data subject requests, legal hold |
| `admin-service` | system | admin actions on tickets |
| `ride-safety-service` | system | producer of `ride.safety.*.v1` |
| `payment-service` | system | producer of `payment.disputed.v1` |
| `customer-service`, `driver-service`, `courier-service` | system | profile reads, re-instatement writes |
| `food-payment-integration-service`, `ride-payment-integration-service` | system | refund execution |
| `identity-service` | system | user verification, session revoke |
| `notification-service` | consumer | sends ticket updates |
| `audit-service` | consumer | reads `support.*.v1` events |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — read profile, write re-instatement —
  SLO 99.9% — circuit breaker: yes.
- `driver-service` — read profile, write re-instatement —
  SLO 99.9% — circuit breaker: yes.
- `courier-service` — read profile, write re-instatement —
  SLO 99.9% — circuit breaker: yes.
- `merchant-service` — read profile, write re-instatement —
  SLO 99.9% — circuit breaker: yes.
- `payment-service` — read payment history — SLO 99.95% —
  circuit breaker: yes.
- `food-payment-integration-service` — refund execution — SLO
  99.9% — circuit breaker: yes.
- `ride-payment-integration-service` — refund execution — SLO
  99.9% — circuit breaker: yes.
- `identity-service` — user verification, session revoke —
  SLO 99.95% — circuit breaker: no.
- `notification-service` — send ticket update to user — SLO
  99.9% — circuit breaker: yes.
- `file-service` — attachment metadata — SLO 99.9% —
  circuit breaker: yes.
- `configuration-service` — read severity matrix, SLA timers
  — SLO 99.95% — circuit breaker: yes.

### Asynchronous (events consumed)

- `ride.safety.sos.v1` from `ride-safety-service` — P1
  safety ticket.
- `ride.safety.incident.v1` from `ride-safety-service` —
  P1 ticket.
- `payment.disputed.v1` from `payment-service` — P1
  chargeback ticket.
- `customer.suspended.v1` from `customer-service` — review
  ticket.
- `identity.user.disabled.v1` from `identity-service` —
  review ticket.
- `notification.failed.v1` from `notification-service` —
  open a P1 ticket if it's a money event.
- `comms.send.failed.v1` from `communication-gateway-service`
  — investigate a delivery failure.
- `configuration.updated.v1` from `configuration-service` —
  severity matrix, SLA timers, refund limits.

### Asynchronous (events produced)

- `support.ticket.opened.v1` — every new ticket.
- `support.ticket.resolved.v1` — every resolution.
- `support.action.performed.v1` — every agent action
  (refund, reinstate, escalate, etc.).
- `support.incident.opened.v1` — every P1 safety incident
  (high-severity, pages on-call).
- `support.incident.resolved.v1` — every resolution of a P1.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript) — fast iteration, good
  i18n support.
- Database: PostgreSQL 18 in schema `support` (tickets,
  conversations, attachments, escalations, actions, audit).
- Cache: Redis 7 (per-service) for ticket queue snapshots,
  SLA timers, agent presence.
- Event broker: Kafka.
- Search: OpenSearch via `search-service` (ticket search).

## 8. Database Ownership

- Schema: `support`
- Migrations: `services/support-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (tickets, conversations; we never hard
  delete until the retention window).
- Partitioning: yes — `support.actions` (audit) partitioned
  by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/tickets | bearer | open a ticket |
| GET | /v1/tickets/{id} | bearer (own) / agent | get a ticket |
| PATCH | /v1/tickets/{id} | agent | update ticket (assign, severity, etc.) |
| POST | /v1/tickets/{id}/messages | bearer (own) / agent | add a message |
| POST | /v1/tickets/{id}/escalate | agent | escalate to L2/L3/safety/fraud/finance |
| POST | /v1/tickets/{id}/resolve | agent | resolve the ticket |
| POST | /v1/tickets/{id}/refund | finance | initiate a refund |
| POST | /v1/tickets/{id}/reinstate | admin | reinstate a suspended account |
| GET | /v1/tickets | bearer (own) / agent | list tickets |
| GET | /v1/admin/tickets/queue | agent | ticket queue (filterable) |
| GET | /v1/admin/tickets/sla | agent | SLA breach report |
| POST | /v1/admin/dsar | compliance | data subject access / erasure |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `support.ticket.opened.v1` | every new ticket | `notification-service`, `audit-service`, `analytics-service` |
| `support.ticket.resolved.v1` | every resolution | same |
| `support.action.performed.v1` | every agent action (refund, reinstate, escalate) | `audit-service`, `analytics-service` |
| `support.incident.opened.v1` | every P1 safety incident | `notification-service` (pages on-call), `audit-service` |
| `support.incident.resolved.v1` | every P1 resolution | same |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `ride.safety.sos.v1` | `ride-safety-service` | P1 safety ticket | open P1 within 60s |
| `ride.safety.incident.v1` | `ride-safety-service` | P1 ticket | open P1 |
| `payment.disputed.v1` | `payment-service` | P1 chargeback ticket | open P1 |
| `customer.suspended.v1` | `customer-service` | review ticket | open P2 ticket |
| `identity.user.disabled.v1` | `identity-service` | review ticket | open P2 ticket |
| `notification.failed.v1` | `notification-service` | money event failure | open P1 if money event |
| `comms.send.failed.v1` | `communication-gateway-service` | investigate | open P3 |
| `configuration.updated.v1` | `configuration-service` | severity matrix, SLA timers | reload config |

## 12. External Integrations

- **Vault** — agent signing keys, admin co-signature keys.
- **File storage** — via `file-service`.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `support.severity_matrix` | object | configuration-service | maps event types to severity |
| `support.sla.p1.first_response_seconds` | int | configuration-service | default 60 |
| `support.sla.p2.first_response_seconds` | int | configuration-service | default 3600 (1h) |
| `support.sla.p3.first_response_seconds` | int | configuration-service | default 86400 (24h) |
| `support.refund.max_per_agent_minor` | int | configuration-service | default 10000 ($100) |
| `support.refund.max_per_ticket_minor` | int | configuration-service | default 50000 ($500) |
| `support.escalation.l1_to_l2_seconds` | int | configuration-service | default 1800 (30 min) |
| `support.escalation.l2_to_l3_seconds` | int | configuration-service | default 3600 (1h) |

## 14. Security

- **AuthN**: bearer JWT (validated at gateway); internal
  calls use client-credentials tokens.
- **AuthZ**: RBAC roles (`customer`, `driver`, `courier`,
  `merchant_staff`, `support_agent_l1`, `support_agent_l2`,
  `support_agent_l3`, `safety`, `fraud`, `finance`,
  `compliance`, `admin`). Resource-level: a customer can
  only see / message their own tickets; an agent can see
  all tickets in their assigned queues.
- **Secrets**: agent signing keys in Vault; rotated
  quarterly.
- **PII**: ticket body, conversation, attachments are
  PII (Confidential). Encrypted at rest (`pgcrypto`).

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`, `trace_id`,
  `ticket_id`, `agent_sub`, `severity`, `action`, `latency_ms`.
- **Metrics**: RED (per route) + business:
  `tickets_opened_total{severity, source}`,
  `tickets_resolved_total{severity, source}`,
  `tickets_open{severity}` (gauge),
  `ticket_first_response_seconds` (histogram),
  `ticket_resolution_seconds` (histogram),
  `sla_breaches_total{severity, sla_type}`,
  `agent_actions_total{action}`.
- **Traces**: OpenTelemetry; root span per agent action.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka
  reachable; downstream critical paths OK), `/started`.

## 16. Scalability

- **Replicas**: default 6.
- **HPA**: CPU 60%, custom metric
  `tickets_in_flight > 500` per replica.
- **Hot path**: `GET /v1/tickets/{id}` and
  `POST /v1/tickets/{id}/messages`.

## 17. Local Development

- `docker compose up support-service` brings up the service,
  its DB, Redis, Kafka, and mocks for downstream services.
- Seed: 100 tickets across severities, statuses, and queues.

## 18. Deployment

- **Image**: `ghcr.io/uber/support-service:<git-sha>`.
- **Replicas**: 6 in production.
- **Resource limits**: see deployment-arch (`cpu: 500m`,
  `memory: 768Mi` requests; 1 CPU, 1.5Gi limits).
- **Migrations**: run as a Kubernetes Job on deploy.

## 19. Accounting impact

`support-service` is the **customer-facing operator entry point** for
refund / chargeback / dispute tickets. It does not write to the
ledger directly but routes every refund / chargeback action through
the operational financial services.

- **What money facts it owns:** tickets, ticket state, agent
  notes, customer-visible refund / dispute status.
- **Refund flow:** an agent-approved refund ticket triggers
  `payment-service.refund` via the agent console; the resulting
  `payment.refund.*.v1` events flow to `wallet-service` (closed-loop
  debit) and `ledger-service` (reversal posting) automatically.
- **Chargeback / dispute flow:** a dispute opened by a customer
  surfaces to `payment-service`, which provisions the
  `6400_chargeback_losses` ↔ `chargeback_reserve` posting via
  `fraud-risk-service` and tracks resolution state.
- **Reconciliation drift:** tickets opened by `reporting-service` on
  reconciliation drift (`reconciliation.drift.found.v1`) are routed
  here for investigation; resolution may result in an admin journal
  entry (`admin-service`) or a manual fix in the source service.
- **Human operator path:** agent actions are audit-logged via
  `platform-spring-boot-starter` (`audit.admin.support.v1`).

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`merchant-service`](../merchant-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`search-service`](../search-service/README.md)
- **Depended on by**: [`admin-service`](../admin-service/README.md), [`api-gateway`](../api-gateway/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-settlement-service`](../restaurant-settlement-service/README.md), [`review-rating-service`](../review-rating-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/REFUND_WORKFLOWS.md`](../../workflows/REFUND_WORKFLOWS.md) — refund orchestration
- [`../../workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) — SOS, fraud, emergency response
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (refund / chargeback tickets; reconciliation drift investigation)
