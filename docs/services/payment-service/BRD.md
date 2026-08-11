# payment-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for **what** the payment service
does. It is read by finance, product, security, operations,
support, and engineering. It defines the platform's contract with
the payment provider.

## 2. Business Context

Money is the most-regulated part of the platform. The
`payment-service` exists to be the **only** point of contact
between the platform and the payment provider. It tokenises
payment methods, orchestrates authorize / capture / refund / void
flows, reconciles webhooks, and emits lifecycle events for the
rest of the platform.

The service MUST operate within PCI-DSS SAQ-A scope: the
platform never sees a card number, CVV, or full track data. All
card data is handled by the provider's hosted fields / SDK; the
platform only receives a tokenised reference.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Operate within PCI-DSS SAQ-A scope | 100% of card data via provider-hosted fields |
| BR--002 | Make every state-changing operation idempotent | 100% idempotency coverage |
| BR--003 | Reconcile gateway webhooks within 30 seconds | webhook → state update p99 |
| BR--004 | Make every attempt audit-traceable | 100% attempts in `payment_attempts` |
| BR--005 | Support multi-currency (every monetary value carries ISO 4217) | 100% |
| BR--006 | Provide a clear, testable interface to the gateway | 100% of gateway calls behind this service |
| BR--007 | Honour per-region gateway selection | per-region routing via the gateway registry |
| BR--008 | Make gateway failures recoverable | retries; circuit breaker; reconciliation |
| BR--009 | Be the source of truth for `payment_intent` state | 100% of state changes via this service |
| BR--046 | Maintain a gateway registry as the source of truth for all 46 supported gateways. | 100% of `payment_gateways.id` values enumerated in [`GATEWAYS.md`](./GATEWAYS.md); every outbound call is gateway-routed |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Finance | downstream consumer | exact money math; reconciliation |
| Security | governance | PCI scope; audit |
| Product (Food / Ride) | owns the marketplace | smooth customer experience |
| Operations | city ops | failed payment handling |
| Support | tier-2 | payment method management |
| Engineering (Financial Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Customer** — provides card data via the provider's hosted
  UI; the platform only sees the resulting token.
- **Integration services** (``payment-service` (food saga)`,
  ``payment-service` (ride saga)`) — drive the
  authorize / capture / refund lifecycle.
- **Wallet service** — receives credits on capture.
- **Ledger service** — receives postings on capture / refund.
- **Fraud-risk service** — scores attempts.
- **Support agent** — investigates failed payments; manual
  refunds.

## 6. Business Capabilities

- Create a payment intent (with a tokenised payment method).
- Authorize a payment intent.
- Capture a payment intent.
- Void an authorization.
- Refund a captured payment (full or partial).
- Save a payment method (tokenised; no PAN stored).
- Receive and reconcile provider webhooks.
- Execute merchant / courier payouts.
- Multi-currency: every monetary value carries ISO 4217.
- Idempotency: every state-changing operation is idempotent on
  `Idempotency-Key`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only service in the platform that talks to any gateway. | MUST | Security |
| BR--011 | The service MUST NOT store PAN, CVV, or full track data. | MUST | PCI-DSS |
| BR--012 | The service MUST store only tokenised references returned by the gateway. | MUST | PCI-DSS |
| BR--013 | The service MUST require `Idempotency-Key` on every state-changing operation. | MUST | Architecture |
| BR--014 | The service MUST emit `payment.authorized.v1` on a successful authorization. | MUST | Architecture |
| BR--015 | The service MUST emit `payment.captured.v1` on a successful capture. | MUST | Architecture |
| BR--016 | The service MUST emit `payment.failed.v1` on a failed attempt. | MUST | Architecture |
| BR--017 | The service MUST emit `payment.refund.completed.v1` on a successful refund. | MUST | Architecture |
| BR--018 | The service MUST verify gateway webhooks using the per-gateway signature scheme declared in `payment_gateways.signature_scheme` (NOT a single fixed HMAC). | MUST | Security |
| BR--019 | The service MUST support multi-currency. | MUST | Product |
| BR--020 | The service MUST retry transient gateway failures with backoff (per-gateway retry budget in `payment_gateways.metadata`). | MUST | Operations |
| BR--021 | The service MUST support a manual refund (admin). | MUST | Support |
| BR--022 | The service MUST support merchant / courier / driver payouts. | MUST | Settlement |
| BR--023 | The service MUST be Tier-1 SLO (99.95%). | MUST | Architecture |
| BR--024 | The service MUST honour `customer.suspended.v1` by blocking future attempts. | MUST | Risk |
| BR--025 | The service MUST be the source of truth for `payment_intent` state. | MUST | Architecture |
| BR--026 | The service MUST translate every gateway-native error code to a platform code via `payment_gateway_error_mapping` before emitting lifecycle events (per `architecture/DOWNSTREAM_ERROR_CATALOG.md` 5). | MUST | Risk |
| BR--027 | The service MUST isolate each gateway with its own circuit breaker, bulkhead, and probe so one gateway's outage does not cause service-wide degradation (per `architecture/SERVICE_ISOLATION.md`). | MUST | Reliability |
| BR--028 | The service MUST support a per-gateway activation / drain / disable lifecycle (`payment.gateway.activated.v1`, `payment.gateway.drained.v1`, `payment.gateway.deactivated.v1`) and refuse new intents when `state='disabled'`. | MUST | Operations |
| BR--029 | The service MUST support per-tenant, per-region, per-currency, and per-payment-method gateway overrides (`payment.gateway.override.<scope>.<id>`). | MUST | Product |
| BR--030 | The service MUST run a per-gateway daily reconciliation (`reconciliation_runs` rows keyed by `(run_date, gateway_id)`); drift is repaired via the admin force-state endpoint. | MUST | Finance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A `payment_intent` is created with `state=created`; the first state transition is `authorized` or `failed`. | |
| BR--031 | An authorization can be `voided` if not yet captured. | |
| BR--032 | A captured payment can be `refunded` (full or partial). | |
| BR--033 | The `Idempotency-Key` is `(client_id, key)`; replays return the original response. | |
| BR--034 | A refund is bounded by the captured amount. | |
| BR--035 | The `currency` is fixed at intent creation; conversions are explicit operations, not implicit. | |
| BR--036 | The gateway's `payment_intent_id` is stored as `gateway_intent_id` and mirrored in `payment_gateway_intent_registry` for reconciliation. | |
| BR--037 | All money values are in minor units + ISO 4217 currency. | |
| BR--040 | The gateway registry (`payment_gateways`) is the source of truth for the driver list. 46 rows are seeded; new rows are added via the standard config-write + driver-package flow. | |
| BR--041 | Gateway resolution precedence (highest first): `payment_intent.gateway_pin` → `payment.gateway.override.tenant.*` → `payment.gateway.override.region.*` → `payment.gateway.override.currency.*` → `payment.gateway.override.payment_method.*` → `payment.gateway.default` → first `state='enabled'` gateway matching region/currency/method sorted by `priority` ASC. | |
| BR--042 | A gateway in `state='draining'` MUST NOT be selected for new intents but MUST continue to serve existing intents. | |
| BR--043 | A gateway in `state='disabled'` MUST NOT be selected for new intents AND MUST NOT respond to webhooks (the dispatcher 404s). | |
| BR--044 | A disabled gateway cannot be re-enabled by a config change alone — operators MUST also POST `/admin/v1/gateways/{id}/activate`. | |
| BR--045 | A gateway with `health='unreachable'` MUST be excluded from the auto-resolution path; existing intents on that gateway continue (the gateway is not disabled — it may recover). | |
| BR--046 | Per-gateway credentials live at exactly one Vault path: `secret/payment-service/gateway/<id>/<env>`. | |

## 9. Assumptions

- The provider's hosted fields / SDK is used for all card data
  collection; the platform never sees a raw card number.
- The provider's webhook delivery is reliable enough that the
  reconciliation job is a safety net, not the primary path.
- The integration services send `Idempotency-Key` derived from
  their business operation id (e.g. `request:{request_id}:payment:capture`).
- Multi-currency support is per-provider; the platform treats
  currency as a first-class field on every monetary value.

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST NOT store PAN, CVV, or full track data.
- The service MUST be the only service that talks to the
  payment provider.
- The service MUST be within PCI-DSS SAQ-A scope.
- All provider calls MUST be over TLS 1.3.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Gateway registry (`payment_gateways`; 46 rows in [`GATEWAYS.md`](./GATEWAYS.md)) | external | the single source of truth for which gateways the platform can talk to; each row maps to one driver package |
| ``payment-service` (food saga)` | consumer | capture / refund / void |
| ``payment-service` (ride saga)` | consumer | capture / refund / void |
| ``payment-service` (wallet)` | consumer | credit / debit on capture / refund |
| `ledger-service` | consumer | double-entry on capture / refund |
| `fraud-risk-service` | consumer | risk score on attempt |
| `customer-service` | service | customer profile (read) |
| ``restaurant-service` (merchant)` | service | merchant profile (read) |
| `courier-service` | service | courier profile (read) |
| ``payment-service` (merchant settlement)` | consumer | payout |
| ``payment-service` (courier earnings)` | consumer | payout |
| `notification-service` | service | customer-facing |
| ``admin-service` (support module)` / `admin-service` | service | admin tools |
| `configuration-service` | service | owner of the `payment.gateway.*` config-key family |

## 12. Business Workflows

- Authorize at checkout (food / ride).
- Capture at delivery / trip completion.
- Refund (full) on cancellation.
- Refund (partial) on quality / goodwill.
- Void on capture failure.
- Webhook reconciliation.
- Payout to merchant / courier.
- Manual refund (admin).

## 13. Exception Workflows

- **Provider returns a transient error**: retry with backoff; on
  persistent failure, surface to the integration service (which
  compensates).
- **Provider returns a permanent error** (decline): the
  integration service is notified; the customer is informed.
- **Webhook is lost or out-of-order**: the reconciliation job
  compares the platform's `payment_intents` state against the
  provider's reports; any drift is repaired.
- **Provider's hosted fields are down**: the customer is told
  to use an alternative method; the integration service
  compensates.
- **Chargeback**: the provider sends a webhook; the service
  records a `dispute` and emits an event for support.

## 14. Success Criteria

- 99.95% availability over 30 days.
- 0 PAN / CVV / full track data in the platform.
- 100% of attempts have a `payment_attempts` row.
- 100% of refunds are idempotent.
- 0 untracked provider-side state changes (verified by
  reconciliation).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Authorize p99 | ≤ 2s | from request to gateway response |
| Capture p99 | ≤ 2s | from request to gateway response |
| Refund p99 | ≤ 2s | from request to gateway response |
| Webhook reconciliation lag p99 | ≤ 30s | from webhook receipt to state update |
| Idempotency hit rate | ≥ 5% | replays / total (proxy for retry hygiene) |
| Per-gateway error rate | < 1% | 5xx / total per `gateway_id` per region (measured per gateway, not aggregated) |
| Gateway activation time | ≤ 60s | from `payment.gateway.<id>.enabled=true` write to first `payment_intent` resolved to that gateway |
| Gateway outage blast radius | 0 customer-visible failures | per-gateway circuit-open MUST NOT cascade to other gateways |

## 16. Acceptance Criteria

- The service operates within PCI-DSS SAQ-A scope (verified by
  external QSA).
- A load test sustains 200 capture / second with p99 ≤ 2s.
- A chaos test (kill provider) shows the service surfaces the
  failure to the integration service within 5s.
- A double-`capture` test shows no double-charge.
- The reconciliation job reports zero drift over 7 days.
- The service never logs PAN / CVV (verified by log audit).

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`GATEWAYS.md`](./GATEWAYS.md) — full registry of the 46 supported gateways
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

