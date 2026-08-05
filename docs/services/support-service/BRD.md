# support-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`support-service` must do for the business. It is read by
customer support leadership, the safety team, the fraud
team, the finance team, the compliance team, the platform
architecture team, and the service's engineering team. It
informs the support SLAs, the severity matrix, the refund
policy, the GDPR / PDPL data-subject-request flow, and the
human-in-the-loop integration with safety and fraud.

## 2. Business Context

Every user-facing incident — a wrong food order, a missed
trip, a lost item, a safety SOS, a chargeback, a GDPR
erasure request — eventually lands in a support ticket. The
platform's commitment to its users is that:

1. A safety incident gets a human response within minutes.
2. A chargeback gets a finance review within hours.
3. A general complaint gets an L1 response within a day.
4. A data subject request is honored within 30 days
   (GDPR / PDPL).
5. A refund decision is auditable and consistent.

`support-service` is the system of record for all of these
flows. Without it, refunds would be ad-hoc, safety
escalations would be informal, and the platform would have
no auditable trail of who decided what.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | First response on P1 safety tickets within 60 seconds | P95 first response ≤ 60s |
| BR--002 | First response on P2 tickets within 1 hour | P95 first response ≤ 1h |
| BR--003 | First response on P3 tickets within 24 hours | P95 first response ≤ 24h |
| BR--004 | Honor 100% of data subject requests (GDPR / PDPL) within 30 days | 0 missed DSARs |
| BR--005 | Refund decisions auditable and consistent | 100% of refunds recorded in audit log |
| BR--006 | Agent RBAC enforced (L1 / L2 / L3 / safety / fraud / finance) | 0 unauthorized actions |
| BR--007 | All safety incidents open a P1 ticket within 60 seconds | 100% of SOS → ticket within 60s |
| BR--008 | All chargebacks open a P1 ticket within 60 seconds | 100% of disputes → ticket within 60s |
| BR--009 | Provide a complete conversation history for every ticket | 100% of tickets have a full thread |
| BR--010 | Support agents can search across tickets (OpenSearch) | search P95 ≤ 200ms |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Customer Support leadership | owner | SLAs, agent capacity, queue health |
| Safety team | consumer + actor | P1 incidents, SOS |
| Fraud team | consumer + actor | chargebacks, suspicious activity |
| Finance team | consumer + actor | refunds, settlements, chargebacks |
| Compliance / Legal | consumer + actor | GDPR / PDPL DSAR, legal hold |
| Engineering (consumer services) | consumer | "did the user see the refund notification?" |
| Trust & Safety | consumer + actor | SOS, account re-instatement |
| Customer (rider / diner) | consumer | "I have a problem; how do I get help?" |
| Driver / Courier | consumer | "I have a problem; how do I get help?" |
| Merchant / Restaurant staff | consumer | "I have a problem; how do I get help?" |

## 5. Actors / Personas

- **Customer (rider / diner)**: opens a ticket via the app,
  adds messages, sees the agent's responses, marks as
  resolved.
- **Driver / Courier**: opens a ticket about a trip /
  delivery issue, an earnings issue, a vehicle issue.
- **Merchant / Restaurant staff**: opens a ticket about an
  order issue, a payout issue, a menu issue.
- **Support agent (L1)**: triages incoming tickets,
  resolves simple ones (refund < $20, re-send receipt),
  escalates the rest.
- **Support agent (L2)**: handles escalated tickets
  (refund < $100, re-instatement requests).
- **Support agent (L3)**: handles complex tickets (refund >
  $100, multi-party disputes).
- **Safety team**: handles P1 safety incidents; can
  reinstate a suspended driver, can refer to law
  enforcement.
- **Fraud team**: handles chargebacks, suspicious activity.
- **Finance team**: handles large refunds, settlement
  reversals.
- **Compliance officer**: handles DSARs, legal hold.

## 6. Business Capabilities

- **Ticket management** (open, assign, update, resolve,
  close, reopen).
- **Conversation management** (threaded messages between
  user and agent; internal notes between agents).
- **Severity matrix** (P1 / P2 / P3 / P4 with SLA timers).
- **Agent RBAC** (L1 / L2 / L3 / safety / fraud / finance
  / compliance / admin).
- **Escalation** (L1 → L2 → L3; L1 → safety / fraud /
  finance).
- **Refund initiation** (delegated to payment integration
  services; support owns the policy, not the execution).
- **Account re-instatement** (delegated to profile
  services).
- **Attachment handling** (linked to `file-service`).
- **Search** (across tickets; via `search-service`).
- **GDPR / PDPL data subject requests** (access / erasure).
- **Audit log** (every action by every agent).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only writer of support tickets, conversations, escalations, and audit records. | MUST | data ownership, platform architecture |
| BR--011 | The service MUST open a P1 ticket within 60 seconds of every `ride.safety.sos.v1`, `ride.safety.incident.v1`, and `payment.disputed.v1` event. | MUST | safety, fraud |
| BR--012 | The service MUST enforce the severity matrix and SLA timers per configuration. | MUST | customer support |
| BR--013 | The service MUST enforce agent RBAC (L1 / L2 / L3 / safety / fraud / finance / compliance / admin). | MUST | compliance, security |
| BR--014 | The service MUST enforce refund limits per agent and per ticket. | MUST | finance |
| BR--015 | The service MUST use `Idempotency-Key = ticket:<ticket_id>:refund:<N>` for refund calls to `food-payment-integration-service` and `ride-payment-integration-service`. | MUST | idempotency |
| BR--016 | The service MUST honor data subject access / erasure requests within 30 days of receipt. | MUST | GDPR, PDPL |
| BR--017 | The service MUST record every agent action (who, when, what) in an audit log. | MUST | compliance, audit |
| BR--018 | The service MUST emit `support.ticket.opened.v1`, `support.ticket.resolved.v1`, `support.action.performed.v1`, `support.incident.opened.v1`, `support.incident.resolved.v1`. | MUST | audit, analytics |
| BR--019 | The service MUST allow the user to reopen a resolved ticket within 7 days. | SHOULD | product |
| BR--020 | The service MUST support attachments via `file-service`. | MUST | product |
| BR--021 | The service MUST escalate a ticket when the SLA timer is breached (e.g. P1 with no response in 60s → page on-call; P2 with no response in 1h → L2). | MUST | customer support |
| BR--022 | The service MUST support a "co-signature" pattern for high-value actions (refund > $500, account re-instatement, legal hold). | MUST | finance, security |
| BR--023 | The service MUST respect "do not contact" preferences (if the user opts out of notifications, do not send ticket updates). | MUST | compliance |
| BR--024 | The service MUST support re-instatement requests for customers, drivers, couriers, and merchants. | MUST | safety, support |
| BR--025 | The service MUST support a "fraud hold" flag on tickets that prevents the user from performing high-value actions while the ticket is open. | MUST | fraud |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | P1 tickets auto-page on-call and require a safety team response. | |
| BR--021 | Refund ≤ $100 may be issued by L2; > $100 requires L3; > $500 requires co-signature. | |
| BR--022 | Re-instatement of a suspended account requires L3 + co-signature. | |
| BR--023 | DSAR access request: collect data from all services, return within 30 days, signed URL, encrypted package. | |
| BR--024 | DSAR erasure: erase PII from all services within 30 days; financial records retained per law but with identifying fields removed. | |
| BR--025 | A ticket can have multiple conversations (user↔agent, internal notes, system messages). | |
| BR--026 | SLA breach auto-escalates: P1 → safety on-call; P2 → L2; P3 → L3. | |
| BR--027 | A resolved ticket may be reopened by the user within 7 days. After 7 days, a new ticket must be opened. | |
| BR--028 | An agent cannot resolve a ticket they did not handle (segregation of duties for high-value refunds). | |

## 9. Assumptions

- The safety team is on-call 24/7.
- The fraud and finance teams are on-call during business
  hours; P1s after hours are handled by the on-call
  safety / support lead.
- The support platform integrates with the platform's
  payment, profile, and notification services.
- The volume of tickets is bursty (e.g. during a major
  incident) but bounded; we can scale horizontally.
- i18n is a hard requirement (en + ar from day one).

## 10. Constraints

- **Latency**: P1 ticket open within 60s of the trigger
  event.
- **Compliance**: GDPR / PDPL DSAR within 30 days;
  anti-spam (don't spam the user with ticket updates).
- **Auditability**: every action recorded with who, when,
  what.
- **PII**: ticket bodies, conversations, attachments are
  PII.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `customer-service` | service | profile read, re-instatement |
| `driver-service` | service | profile read, re-instatement |
| `courier-service` | service | profile read, re-instatement |
| `merchant-service` | service | profile read, re-instatement |
| `payment-service` | service | payment history |
| `food-payment-integration-service` | service | refund execution |
| `ride-payment-integration-service` | service | refund execution |
| `identity-service` | service | user verification, session revoke |
| `notification-service` | service | send ticket update |
| `file-service` | service | attachments |
| `search-service` | service | ticket search |
| `configuration-service` | service | severity matrix, SLA timers, refund limits |
| `ride-safety-service` | producer | `ride.safety.*.v1` |
| `payment-service` | producer | `payment.disputed.v1` |
| `customer-service` | producer | `customer.suspended.v1` |
| `identity-service` | producer | `identity.user.disabled.v1` |
| `notification-service` | producer | `notification.failed.v1` |
| `communication-gateway-service` | producer | `comms.send.failed.v1` |
| `audit-service` | consumer | reads `support.*.v1` events |
| `analytics-service` | consumer | reads `support.*.v1` events |
| PostgreSQL 18 | infra | core storage |
| Redis 7 | infra | queue snapshots, SLA timers |
| Kafka | infra | events |
| Vault | infra | signing keys |

## 12. Business Workflows

- **Open a P1 safety ticket from SOS** — see
  `WORKFLOWS.md` §1.
- **Triage and respond to a customer ticket** — see
  `WORKFLOWS.md` §2.
- **Issue a refund from a ticket** — see `WORKFLOWS.md` §3.
- **Re-instate a suspended account** — see `WORKFLOWS.md` §4.
- **Honor a data subject access request (DSAR)** — see
  `WORKFLOWS.md` §5.
- **Escalate on SLA breach** — see `WORKFLOWS.md` §6.

## 13. Exception Workflows

- **All downstream services unreachable**: the ticket is
  opened in `triage` state; the agent is notified; the
  action is retried on reconnection.
- **Refund fails**: a follow-up P1 ticket is opened
  (`food.payment.failed.v1` or
  `ride.payment.failed.v1`); the original ticket is
  annotated.
- **DSAR partial failure**: the parts that succeeded are
  delivered; the parts that failed are queued for retry;
  the user is informed within 30 days.

## 14. Success Criteria

- 100% of SOS events open a P1 ticket within 60s.
- 100% of chargebacks open a P1 ticket within 60s.
- 100% of DSARs honored within 30 days.
- 100% of refunds recorded in the audit log.
- 0 unauthorized agent actions.
- 0 SLA breaches for P1 (life-safety).
- 95% of P2 first responses within 1h.
- 90% of P3 first responses within 24h.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| P1 first response P95 | ≤ 60s | `ticket_first_response_seconds{severity=P1}` P95 |
| P2 first response P95 | ≤ 1h | same, P2 |
| P3 first response P95 | ≤ 24h | same, P3 |
| P1 resolution P95 | ≤ 4h | `ticket_resolution_seconds{severity=P1}` P95 |
| P2 resolution P95 | ≤ 24h | same, P2 |
| P3 resolution P95 | ≤ 7d | same, P3 |
| SLA breach rate | 0% for P1; < 5% for P2 | `sla_breaches_total / tickets_total` |
| Refund audit coverage | 100% | `refunds_total / refunds_in_audit` |
| DSAR within 30 days | 100% | `dsar_completed_total{on_time=true} / dsar_total` |
| Agent satisfaction (CSAT) | ≥ 4.0/5.0 | post-resolution survey |

## 16. Acceptance Criteria

- All 16 business requirements implemented and verified by
  automated tests.
- A `ride.safety.sos.v1` event in staging results in a P1
  ticket being opened within 60s and the on-call being
  paged.
- A `payment.disputed.v1` event in staging results in a P1
  ticket being opened within 60s.
- An agent without the `finance` role cannot issue a refund
  (verified by an integration test).
- A refund of $600 (above the L3 limit) requires
  co-signature (verified by an integration test).
- A DSAR access request results in a data package being
  delivered within 30 days.
- All agent actions are in the audit log.

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

