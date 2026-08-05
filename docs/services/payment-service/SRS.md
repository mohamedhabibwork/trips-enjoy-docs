# payment-service — Software Requirements Specification

## 1. Introduction

This document specifies the software behaviour of
`payment-service`. It is the engineering source of truth for the
provider integration, the anti-corruption layer, idempotency, and
the webhook reconciliation.

## 2. Scope

- In scope: payment intents, authorize / capture / void / refund,
  provider tokenisation, webhooks, retries, multi-currency,
  idempotency, payouts (merchant / courier).
- Out of scope: the platform's chart of accounts (owned by
  `ledger-service`), wallet mechanics, the food / ride payment
  sagas, merchant settlement mechanics.

## 3. System Context

```mermaid
flowchart LR
    FPI[`payment-service` (food saga)] -- capture/refund/void --> PS[payment-service]
    RPI[`payment-service` (ride saga)] -- capture/refund/void --> PS
    PS -- payment.authorized.v1 --> FPI
    PS -- payment.captured.v1 --> FPI
    PS -- payment.refund.completed.v1 --> FPI
    PS -- payment.captured.v1 --> WLT[`payment-service` (wallet)]
    PS -- payment.captured.v1 --> LD[ledger-service]
    PS -- POST/GET --> EXT[Resolved Gateway]
    EXT -- webhook --> PS
    REG[Gateway Registry] -- payment.gateway.activated.v1 etc. --> PS
    PS -- payment.attempted.v1 --> FR[fraud-risk-service]
    CS[customer-service] -- customer.suspended.v1 --> PS
    PS -- payment.method.saved.v1 --> CS
    PS -- POST /v1/payouts --> EXT
```

## 4. Actors

- ``payment-service` (food saga)` (system actor).
- ``payment-service` (ride saga)` (system actor).
- ``payment-service` (wallet)` (system actor).
- `ledger-service` (system actor).
- `fraud-risk-service` (system actor).
- `customer-service` (system actor).
- ``restaurant-service` (merchant)`, `courier-service` (system actors; profile
  read).
- Provider (external).
- `admin-service` / ``admin-service` (support module)` (Keycloak
  `platform-internal`).

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | Accept `POST /v1/payment-intents` with `(customer_id, amount_minor, currency, gateway_id, gateway_token, idempotency_key, capture_mode)`. `gateway_id` is optional; when omitted the gateway registry resolves one. | MUST |
| FR--002 | Insert a `payment_intent` row in `state=created`; call the resolved gateway's `create` driver method. | MUST |
| FR--003 | On gateway success, transition to `state=authorized` (or `captured` if `capture_mode=auto`); emit `payment.authorized.v1` (and `payment.captured.v1`). | MUST |
| FR--004 | On gateway failure, transition to `state=failed`; emit `payment.failed.v1` with the translated `platform_code` from `payment_gateway_error_mapping` and the raw `gateway_code`. | MUST |
| FR--005 | Accept `POST /v1/payment-intents/{id}/capture` (Idempotency-Key required). | MUST |
| FR--006 | Accept `POST /v1/payment-intents/{id}/void` (Idempotency-Key required). | MUST |
| FR--007 | Accept `POST /v1/payment-intents/{id}/refund` (full or partial; Idempotency-Key required). | MUST |
| FR--008 | Reject a refund that exceeds the captured amount. | MUST |
| FR--009 | Reject a refund if the `refund.max_window_days` has passed (default 90). | MUST |
| FR--010 | Receive gateway webhooks at `POST /v1/webhooks/gateway/{gateway_id}`; verify the per-gateway signature using the scheme declared in `payment_gateways.signature_scheme`. | MUST |
| FR--011 | On webhook, look up the `payment_intent` by `(gateway_id, gateway_intent_id)` via `payment_gateway_intent_registry`; update state; emit the appropriate event. | MUST |
| FR--012 | Make every state-changing operation idempotent on `Idempotency-Key`. | MUST |
| FR--013 | Store every attempt in `payment_attempts` (append-only) with `request`, `response` (sanitised), `outcome`, `latency_ms`, `gateway_id`, `gateway_code`, `platform_code`. | MUST |
| FR--014 | Support multi-currency; every monetary value carries ISO 4217 `currency`. | MUST |
| FR--015 | Reject any state transition that violates the state machine (409 `STATE_INVALID`). | MUST |
| FR--016 | Reject a `capture` if the intent is not `authorized`. | MUST |
| FR--017 | Reject a `void` if the intent is not `authorized`. | MUST |
| FR--018 | Support `POST /v1/payouts` for merchant / courier / driver payouts. | MUST |
| FR--019 | Support `POST /v1/payouts/{id}/cancel` (admin). | MUST |
| FR--020 | Run a daily per-gateway reconciliation against each gateway's reports; repair drift. | MUST |
| FR--021 | Honour `customer.suspended.v1` by marking the customer `payments_blocked=true`. | MUST |
| FR--022 | Resolve the gateway via the gateway registry + per-tenant / per-region / per-currency / per-method overrides; selection precedence defined in [`GATEWAYS.md` §6](./GATEWAYS.md#6-resolution-precedence). | MUST |
| FR--023 | Never log PAN, CVV, or full track data. | MUST |
| FR--024 | Allow admin manual refund (`POST /v1/payment-intents/{id}/admin-refund`) with audit note. | MUST |
| FR--025 | Allow admin force-capture (`POST /v1/payment-intents/{id}/force-capture`) with audit note. | MUST |
| FR--046 | The gateway registry (`payment_gateways`) is the single source of truth for the driver list. The 46 supported `gateway_id`s are enumerated in [`GATEWAYS.md`](./GATEWAYS.md). | MUST |
| FR--047 | Adding a new gateway is a config-only change: drop a new driver package under `internal/payment/drivers/<id>/`, add a `payment.gateway.<id>.*` block to `configuration-service`, and add the row to `payment_gateways`. The core schema (`payment_intents`, `payment_attempts`, `refunds`, `payouts`, `webhook_events`) is unchanged. | MUST |
| FR--048 | Emit `payment.gateway.activated.v1` / `payment.gateway.deactivated.v1` / `payment.gateway.drained.v1` / `payment.gateway.health.changed.v1` / `payment.gateway.error.translated.v1` for every lifecycle and translation event. | MUST |
| FR--049 | Provide admin endpoints `/admin/v1/gateways/{id}/activate|drain|disable`, `/admin/v1/payments/{id}/pin-gateway/{gateway_id}`, and the per-gateway error-mapping CRUD at `/admin/v1/gateways/{id}/error-mapping`. | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | Authorize p99 | ≤ 2s |
| NFR--002 | performance | Capture p99 | ≤ 2s |
| NFR--003 | performance | Refund p99 | ≤ 2s |
| NFR--004 | performance | Webhook → state update p99 | ≤ 30s |
| NFR--005 | availability | Service uptime | 99.95% / 30d |
| NFR--006 | scalability | Sustained capture throughput | 200 rps |
| NFR--007 | scalability | Concurrent intents | ≥ 100k |
| NFR--008 | maintainability | MTTR | ≤ 30 min |
| NFR--009 | observability | End-to-end trace per attempt | 100% |
| NFR--010 | consistency | No double-charge under any failure mode | MUST |
| NFR--011 | security | No PAN / CVV ever stored | MUST |
| NFR--012 | operability | Gateway activation (config write → `state='enabled'` and resolvable by `/v1/payment-intents`) takes effect within 60 seconds. | MUST |
| NFR--013 | observability | Per-gateway p99 measured and alerted (one metric per `gateway_id`). | MUST |
| NFR--014 | availability | A single gateway's outage MUST NOT cause service-wide degradation; per-gateway bulkhead + circuit + fallback to next-priority gateway in the same region. | MUST |

## 7. API Requirements

- All non-idempotent `POST` endpoints require `Idempotency-Key`.
- All provider webhooks are verified with HMAC-SHA256.
- All responses use the standard error envelope.
- All endpoints validate input with JSON Schema.
- Full contracts: `INTEGRATION.md`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | `payment_intent.id` is a UUIDv7. | |
| DATA--002 | `customer_id`, `merchant_id`, `courier_id`, `city_id` are stored as UUID columns WITHOUT database FKs. | |
| DATA--003 | `provider_token` is the provider's tokenised reference; NO PAN / CVV is stored. | |
| DATA--004 | `provider_intent_id` is stored for reconciliation. | |
| DATA--005 | The `payment_attempts` table is append-only and range-partitioned by month. | |
| DATA--006 | Money values are `amount_minor BIGINT` + `currency CHAR(3)`. | |

## 9. Validation Rules

- `amount_minor > 0` for intents, captures, refunds.
- `currency` is a valid ISO 4217 code.
- `capture_mode` is `auto` or `manual`.
- A refund's `amount_minor` MUST be ≤ captured amount.
- The webhook signature MUST verify.

## 10. State Transitions

```
created → authorized (or failed)
authorized → captured (capture call)
authorized → voided (void call)
authorized → failed (provider cancel)
captured → refunded (refund call)
captured → disputed (provider dispute webhook)
refunded → [*]
disputed → [*] (after resolution)
failed → [*]
voided → [*]
```

## 11. Authorization Requirements

- Service-to-service callers require `payment.write` or
  `payment.read` in the `payment-service` client.
- Admin endpoints require `payment.admin`.
- The webhook endpoint is public but signature-verified.

## 12. Configuration Requirements

- Reads `payment.*` from `configuration-service` at startup and
  on `configuration.updated.v1`.
- All numeric config validated against min/max bounds.
- Provider credentials mounted from Vault at startup.

## 13. Error Handling

| Error | Response |
|-------|----------|
| State machine violation | 409 `STATE_INVALID` |
| Idempotency-Key reuse with different body | 422 `IDEMPOTENCY_KEY_REUSED` |
| Refund exceeds captured | 422 `REFUND_EXCEEDS_CAPTURED` |
| Refund window expired | 422 `REFUND_WINDOW_EXPIRED` |
| Gateway timeout | retry; on exhaustion, 504 `DEPENDENCY_TIMEOUT` |
| Gateway declined | 422 `PROVIDER_DECLINED` (translated by `payment_gateway_error_mapping` to a platform code) |
| Webhook signature invalid | 401 `WEBHOOK_SIGNATURE_INVALID` |
| Customer suspended | 403 `CUSTOMER_PAYMENTS_BLOCKED` |
| Gateway disabled in catalog | 422 `GATEWAY_NOT_ENABLED` |
| Gateway does not support region | 422 `GATEWAY_REGION_MISMATCH` |
| Gateway does not support currency | 422 `GATEWAY_CURRENCY_UNSUPPORTED` |
| Amount above gateway's max | 422 `GATEWAY_AMOUNT_TOO_LARGE` |
| Missing error-mapping row | 200 with `gateway_code` only (no `platform_code`); emits `GATEWAY_ERROR_MAPPING_MISSING` warning event |

## 14. Concurrency Requirements

- A row-level lock on the `payment_intent` row is acquired at
  every state transition.
- The state machine uses optimistic concurrency (`updated_at`
  predicate).
- The webhook handler is idempotent on `event_id`.

## 15. Idempotency Requirements

- All `POST` endpoints require `Idempotency-Key`.
- The `Idempotency-Key` is `(client_id, key)`; the response is
  cached for `idempotency_ttl_hours` (default 24).
- Replays return the original response.
- The webhook handler dedup's on `event_id`.

## 16. Performance

- Dominant path: receive request, call provider, store attempt,
  update state, emit event.
- P50 / P95 / P99: see NFRs.
- Hot spot: provider latency. Mitigations: connection pooling,
  per-region provider selection, circuit breaker.

## 17. Scalability

- Horizontal: stateless; HPA on `payment_attempt_total` rate.
- Vertical: bounded by PostgreSQL connection pool and provider
  rate limits.

## 18. Availability

- SLO: 99.95% over 30 days.
- Maintenance: rolling deploys only.
- Degraded mode: if the provider is down, the service returns
  504; the integration service compensates.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All endpoints require JWT bearer validated at the gateway (except `/v1/webhooks/gateway/{gateway_id}`). | |
| SEC--002 | Gateway webhooks verified with the per-gateway signature scheme declared in `payment_gateways.signature_scheme` (one of `hmac_sha256`, `hmac_sha512`, `rsa_sha256`, `md5`, `sha256`, `paypal_sdk`, `paymob_hmac`, `kashier_hmac`, `none`). | |
| SEC--003 | NO PAN, CVV, or full track data is ever stored. | Provider-hosted fields. |
| SEC--004 | All gateway calls over TLS 1.3. | |
| SEC--005 | Per-gateway credentials in Vault at `secret/payment-service/gateway/<id>/<env>`; rotated quarterly; one key per gateway per environment per `architecture/SECURITY_ARCHITECTURE.md` §5. | |
| SEC--006 | All admin actions are audit-logged. | `admin.action.performed.v1`. |
| SEC--007 | Rate limit per `Idempotency-Key`. | At the service. |
| SEC--008 | All attempts are logged with `actor_id` and `correlation_id`. | |
| SEC--009 | PCI scope review on every new gateway activation; gateways that require raw card capture (no hosted fields / SDK) MUST NOT be enabled without QSA sign-off. | |

## 20. Privacy

- PII stored: `customer_id` only (no name, email, phone).
- The provider's tokenised reference is not PII (per PCI).
- Retention: 7 years (financial).
- Erasure: not applicable (no PII to erase).

## 21. Auditability

- Every attempt is in `payment_attempts` (append-only).
- Every state transition is in `payment_intent_state_history`.
- Admin actions emit `admin.action.performed.v1`.

## 22. Observability

- Logs: JSON; fields include `correlation_id`, `payment_intent_id`,
  `attempt_id`, `tenant_id`. **NEVER log PAN or provider
  responses that may contain it.**
- Metrics: `payment_attempt_total{method,currency,outcome}`,
  `payment_capture_seconds`,
  `payment_failure_rate{method,reason}`,
  `payment_refund_total{method,currency,outcome}`,
  `payment_payout_total{currency,outcome}`.
- Traces: OpenTelemetry; root span per attempt; child spans
  for provider calls.
- Alerts: SLO burn-rate; provider error rate > 1%; webhook lag
  > 1 min.

## 23. Maintainability

- Code style: Kotlin official code style (ktlint / ktlint-gradle
  with the platform ruleset).
- Test coverage: ≥ 80% line, ≥ 75% branch; 100% on the state
  machine, idempotency, and the per-gateway error-mapping table.
- Provider SDK is in a separate module; swapping providers is
  bounded.
- The gateway registry is the single source of truth for the
  driver list.
- Documentation: this folder + `WORKFLOWS.md` diagrams.

## 24. Disaster Recovery

- RPO: 5 minutes (the intents table is replicated to standby
  region).
- RTO: 30 minutes (stateless service; replay from outbox +
  reconciliation against provider).

## 25. Acceptance Criteria

- All FR/NFR are met and verified by automated tests.
- All SEC are met and verified by an external QSA.
- A load test sustains 200 capture / second with p99 ≤ 2s.
- A chaos test (kill provider) shows the service surfaces the
  failure within 5s.
- A double-`capture` test shows no double-charge.
- The reconciliation job reports zero drift over 7 days.
- Log audit confirms no PAN / CVV is ever logged.

---

## Appendix A — Predecessor SRS absorbed (wallet + ride-payment-integration + food-payment-integration + driver-earnings + courier-earnings + restaurant-settlement)

The functional and non-functional requirements below were migrated
from the six predecessor SRSs as part of
[ADR-0016](../../architecture/adrs/0016-service-domain-consolidation.md).
The canonical source is [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md)
§3.3, §3.8, §3.11, §3.12, §3.13, §3.14.

### A.1 Functional requirements (from wallet)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-W-001 | Maintain wallet balance per user (minor units + ISO 4217). | MUST |
| FR-W-002 | Apply holds (reservations); release on cancellation / completion. | MUST |
| FR-W-003 | Credit / debit on `payment.captured.v1` / `payment.refund.completed.v1`. | MUST |
| FR-W-004 | Top-up flow (charge via gateway; credit on success). | MUST |
| FR-W-005 | Consume `trip.reward.granted.v1` for `wallet_credit`. | MUST |
| FR-W-006 | Daily reconciliation against `ledger-service`. | MUST |

### A.2 Functional requirements (from ride-payment-integration)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-RP-001 | On `trip.completed.v1`, start ride saga. | MUST |
| FR-RP-002 | Capture (and on failure, void / refund) via gateway. | MUST |
| FR-RP-003 | Accrue driver earning (embedded). | MUST |
| FR-RP-004 | Post double-entry via `ledger-service`. | MUST |
| FR-RP-005 | Emit `ride.payment.completed.v1` / `…failed.v1`. | MUST |
| FR-RP-006 | Compensate on failure. | MUST |

### A.3 Functional requirements (from food-payment-integration)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-FP-001 | On `delivery.completed.v1`, start food saga. | MUST |
| FR-FP-002 | Authorize at checkout; capture at delivery completion. | MUST |
| FR-FP-003 | Trigger courier + merchant accrual (embedded). | MUST |
| FR-FP-004 | Handle partial / full / post-delivery refunds. | MUST |
| FR-FP-005 | Emit `food.payment.completed.v1` / `…failed.v1`. | MUST |

### A.4 Functional requirements (from driver-earnings)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-DE-001 | Accrue earning on `ride.payment.completed.v1`, `trip.completed.v1` (tip), `trip.reward.granted.v1` (guaranteed top-up), `trip.reward.reversed.v1`, `driver.incentive.earned.v1`. | MUST |
| FR-DE-002 | Maintain running balance. | MUST |
| FR-DE-003 | Withdrawal to bank; ledger postings. | MUST |
| FR-DE-004 | Penalty postings from ride saga. | MUST |

### A.5 Functional requirements (from courier-earnings)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-CE-001 | Accrue on `delivery.completed.v1` (fee), `food.payment.completed.v1` (tip + bonus), `courier.incentive.earned.v1`. | MUST |
| FR-CE-002 | Maintain balance; withdrawal; ledger. | MUST |

### A.6 Functional requirements (from restaurant-settlement)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-RS-001 | Accrue merchant payable on `food.payment.completed.v1`. | MUST |
| FR-RS-002 | Compute payable on cadence; schedule payout. | MUST |
| FR-RS-003 | Orchestrate bank transfer via gateway. | MUST |
| FR-RS-004 | Disputes (chargeback, quality) debit the payable. | MUST |
| FR-RS-005 | Daily reconciliation against `ledger-service`. | MUST |

### A.7 Idempotency keys (predecessor)

- `courier:{courier_id}:delivery:{delivery_id}:earning`
- `courier:{courier_id}:tip:{delivery_id}`
- `courier:{courier_id}:withdrawal:{withdrawal_id}`
- `driver:{driver_id}:earning:{trip_id}`
- `driver:{driver_id}:tip:{trip_id}`
- `driver:{driver_id}:withdrawal:{withdrawal_id}`
- `trip:{trip_id}:reward:driver:grant` (guaranteed top-up)
- `trip:{trip_id}:penalty:driver:{penalty_id}` (penalty)
- `trip:{trip_id}:saga:ride:{saga_step}` (ride saga)
- `delivery:{delivery_id}:saga:food:{saga_step}` (food saga)
- `wallet:{user_id}:topup:{topup_id}` (wallet top-up)
- `wallet:{user_id}:hold:{hold_id}` (wallet hold)

### A.8 Non-functional requirements (predecessor)

| ID | Category | Target |
|----|----------|--------|
| NFR-RP-001 | performance | P95 ride-saga completion ≤ 5 s |
| NFR-FP-001 | performance | P95 food-saga completion ≤ 5 s |
| NFR-W-001 | performance | P95 wallet hold/release ≤ 50 ms |
| NFR-DE-001 | performance | P95 accrual latency ≤ 200 ms |
| NFR-RS-001 | availability | 99.95% / 30 d |

### A.9 Acceptance criteria (predecessor)

- Ride saga is idempotent on re-run (no double-charge, no double-accrual).
- Food saga compensates on partial failure.
- Wallet top-up reflects in ledger within 5 s.
- Merchant statement matches `ledger-service` postings within 24 h.

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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

