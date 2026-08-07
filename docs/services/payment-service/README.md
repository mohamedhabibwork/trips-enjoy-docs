# payment-service

## 1. Purpose

`payment-service` is the **anti-corruption layer** between the
platform and the **gateway registry** — 46 payment gateways
enumerated in [`GATEWAYS.md`](./GATEWAYS.md) (Stripe, PayPal,
HyperPay, PayMob, Fawry, Binance, PerfectMoney, Volet, Payeer,
NowPayments, Thawani, Tap, Mamo, Ziina, KoraPay, OneLat, Telr,
ClickPay, Kashier, Paytabs, MyFatoorah, PaySky, OPay, XPay,
YallaPay, Fawaterak, Paylink, BigPay, Paycec, Payermax,
Payzink, Payzink-Direct, TotalPay, TotalPay-Direct, PayPal
Credit, Payrexx, Payop, Wise, NowPayments-Invoice, CoinPayments,
Cryptomus, Heleket, Enot, Changelly, Prime). The service owns
payment intents, gateway tokens (NEVER raw PAN), payment
attempts, refunds, voids, and the **gateway registry itself**.
It is the only service in the platform that talks to any
gateway; every other service that touches money goes through
this one.

## 2. Bounded Context

Bounded context: **Payment Gateway Integration**.

- **In scope**: payment intents (authorize / capture / void /
  refund), gateway tokenisation, webhooks, retries, multi-
  currency, idempotency, the per-gateway anti-corruption layer,
  the gateway registry (`payment_gateways`).
- **Out of scope**: the platform's chart of accounts (owned by
  `ledger-service`), wallet mechanics (owned by ``payment-service` (wallet)`),
  the food/ride payment sagas (owned by
  ``payment-service` (food saga)` and
  ``payment-service` (ride saga)`), merchant settlement
  (``payment-service` (merchant settlement)`), driver / courier earnings.

## 3. Responsibilities

- Expose a `payment_intents` aggregate that mirrors the
  gateway's model but is owned by the platform.
- **Maintain the gateway registry** — `payment_gateways` is the
  single source of truth for the 46 supported `gateway_id`s,
  mirrored from the `payment.gateway.*` config-key family
  in `configuration-service`.
- Tokenise payment methods via the gateway's hosted fields /
  SDK; the platform only stores the tokenised reference
  (`gateway_token`); **NO PAN, NO CVV, NO full track data is
  ever stored**.
- Authorize, capture, void, refund against the resolved gateway.
- Receive webhooks from any of the 46 gateways and reconcile
  state using the gateway's own signature scheme.
- Provide idempotency on every state-changing operation.
- Multi-currency: every monetary value carries a `currency`
  (ISO 4217).
- Emit lifecycle events for downstream consumers
  (`payment.authorized.v1`, `payment.captured.v1`,
  `payment.failed.v1`, `payment.refund.*.v1`) and
  gateway-lifecycle events (`payment.gateway.activated.v1`,
  `payment.gateway.deactivated.v1`,
  `payment.gateway.drained.v1`,
  `payment.gateway.health.changed.v1`,
  `payment.gateway.error.translated.v1`).
- Operate within **PCI-DSS SAQ-A scope** (gateway-hosted fields).

## 4. Explicitly NOT Owned

- The platform's chart of accounts — owned by `ledger-service`.
- Wallet mechanics — owned by ``payment-service` (wallet)`.
- The food / ride payment sagas — owned by the integration
  services.
- The provider's authentication (this service authenticates to
  the provider, but the user authenticates to the provider's
  hosted UI).
- The merchant's bank details (stored in
  `payment-service.payout_methods` as tokenised references, but
  the lifecycle of merchant onboarding is owned by
  ``restaurant-service` (merchant)`).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| ``payment-service` (food saga)` | system | calls capture, refund, void (write) |
| ``payment-service` (ride saga)` | system | calls capture, refund, void (write) |
| `customer-service` | system | reads payment methods, default method (read) |
| ``payment-service` (wallet)` | system | reads payment intents (read) |
| `payment-service` (webhook) | system | receives webhooks from provider |
| ``admin-service` (support module)` / `admin-service` | system | force-capture, manual refund (admin) |

## 6. Dependencies

### Synchronous (REST)

- **One of 46 gateways** — the resolved gateway's REST API (per
  the gateway registry; see [`GATEWAYS.md`](./GATEWAYS.md)).
  Per-gateway timeout / retry / circuit-breaker / bulkhead /
  probe; see [`TECH.md` 5](./TECH.md#5-external-integrations)
  and [`INTEGRATION.md` 5](./INTEGRATION.md#5-reliability).
- `customer-service` — `GET /v1/customers/{id}` to enrich — circuit
  breaker: yes.
- `fraud-risk-service` — `POST /v1/risk/score` for risk scoring
  — circuit breaker: yes (the score is advisory; this service
  decides to proceed or not).
- `configuration-service` — `GET /v1/configurations/{key}` and the
  long-poll stream for the `payment.gateway.*` family — circuit
  breaker: yes.

### Asynchronous (events consumed)

- `customer.suspended.v1` from `customer-service` — block
  future payment attempts — dedup: inbox.
- `configuration.updated.v1` from `configuration-service` —
  reload gateway registry + per-gateway config — dedup: inbox.

## 7. Technology Assumptions

- Runtime: Kotlin 2.2.x on Spring Boot 4.x (per [`TECH.md` 1](./TECH.md#1-runtime)).
- Database: PostgreSQL 18 (per-service schema `payment`).
- Cache: Redis (per-service) for the gateway's session cache
  and the `payment_id ↔ gateway_intent_id` mapping (the durable
  mirror is `payment_gateway_intent_registry`).
- Event broker: Kafka.
- Gateway SDK: one Kotlin package per gateway under
  `internal/payment/drivers/<gateway_id>/`; the service depends
  only on the `PaymentGatewayDriver` interface (see
  [`TECH.md` 5.1](./TECH.md#51-paymentgatewaydriver-interface-kotlin)).

## 8. Database Ownership

- Schema: `payment`
- Migrations: `services/payment-service/migrations/`.
- Soft delete: no (payment intents are immutable; the
  `payment_attempts` log is append-only).
- Partitioning: `payment_attempts` is range-partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/payment-intents` | bearer (service) | create a payment intent (`gateway_id` optional; resolved by the gateway registry) |
| GET | `/v1/payment-intents/{id}` | bearer (service / admin) | read |
| POST | `/v1/payment-intents/{id}/authorize` | bearer (service) | authorize |
| POST | `/v1/payment-intents/{id}/capture` | bearer (service) | capture |
| POST | `/v1/payment-intents/{id}/void` | bearer (service) | void |
| POST | `/v1/payment-intents/{id}/refund` | bearer (service / support) | refund |
| GET | `/v1/payment-intents/{id}/attempts` | bearer (service / admin) | read attempts |
| POST | `/v1/webhooks/gateway/{gateway_id}` | per-gateway signature | gateway webhook (signature scheme per `payment_gateways.signature_scheme`) |
| POST | `/v1/payouts` | bearer (service) | execute a payout (merchant / courier / driver) |
| GET | `/v1/payouts/{id}` | bearer (service / admin) | read payout |
| POST | `/v1/payouts/{id}/cancel` | bearer (admin) | cancel a payout |
| GET | `/v1/gateways` | bearer (service / admin) | list gateway catalog |
| GET | `/v1/gateways/{id}` | bearer (service / admin) | read a gateway row |

> The legacy `POST /v1/webhooks/provider` (singular, no path
> parameter, fixed HMAC-SHA256) is preserved as a redirect to
> `/v1/webhooks/gateway/{default_gateway_id}` for one release
> cycle; callers should migrate before deprecation.

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

Every event carries `data.gateway_id` (added non-breaking per
[`architecture/EVENT_ARCHITECTURE.md` "Schema Evolution"](../../architecture/EVENT_ARCHITECTURE.md)).

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `payment.attempted.v1` | every attempt | `fraud-risk-service`, `audit-service` |
| `payment.authorized.v1` | gateway authorized | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (wallet)` |
| `payment.captured.v1` | gateway captured | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (wallet)`, `ledger-service`, `audit-service` |
| `payment.failed.v1` | attempt failed | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, `notification-service` |
| `payment.refund.initiated.v1` | refund started | ``payment-service` (wallet)`, `ledger-service`, `audit-service` |
| `payment.refund.completed.v1` | refund confirmed by gateway | ``payment-service` (wallet)`, `ledger-service`, `audit-service` |
| `payment.refund.failed.v1` | refund failed | ``payment-service` (wallet)`, `notification-service`, `audit-service` |
| `payment.payout.completed.v1` | payout confirmed | downstream consumers |
| `payment.payout.failed.v1` | payout failed | downstream consumers |
| `payment.method.saved.v1` | new payment method | `customer-service` |
| `payment.audit.attempt_logged.v1` | every attempt (sampled) | `audit-service` |
| `payment.gateway.activated.v1` | admin activates a gateway | `audit-service`, ``reporting-service` (data lake)` |
| `payment.gateway.deactivated.v1` | admin disables a gateway | `audit-service`, ``reporting-service` (data lake)` |
| `payment.gateway.drained.v1` | admin drains a gateway | `audit-service`, ``reporting-service` (data lake)` |
| `payment.gateway.health.changed.v1` | synthetic probe transitions | `audit-service`, ``reporting-service` (data lake)` |
| `payment.gateway.error.translated.v1` | a vendor code was translated via `payment_gateway_error_mapping` | `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `customer.suspended.v1` | `customer-service` | block future attempts | mark customer `payments_blocked=true` |
| `configuration.updated.v1` | `configuration-service` | reload | refresh in-memory |

## 12. External Integrations

- **46 gateways** — see [`GATEWAYS.md`](./GATEWAYS.md) for the
  full list. Each gateway has its own driver package under
  `internal/payment/drivers/<gateway_id>/`; the service depends
  only on the `PaymentGatewayDriver` interface (see
  [`TECH.md` 5.1](./TECH.md#51-paymentgatewaydriver-interface-kotlin)).
  Per-gateway credentials live in Vault at
  `secret/payment-service/gateway/<gateway_id>/<env>` (one path
  per gateway per environment per
  [`architecture/SECURITY_ARCHITECTURE.md` 5](../../architecture/SECURITY_ARCHITECTURE.md#5-secrets)).
- Gateway webhooks are received at
  `POST /v1/webhooks/gateway/{gateway_id}` and verified with the
  per-gateway signature scheme declared in
  `payment_gateways.signature_scheme` (see
  [`GATEWAYS.md`](./GATEWAYS.md) column `signature_scheme`).

## 13. Configuration

The gateway catalog is configured via the `payment.gateway.*`
key family (owned by `configuration-service`, mirrored into
`payment_gateways` on `configuration.updated.v1`).

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `payment.gateway.default` | string (gateway_id) | `configuration-service` | env default gateway |
| `payment.gateway.<id>.enabled` | bool | `configuration-service` | catalog `state`; default `false` |
| `payment.gateway.<id>.priority` | int | `configuration-service` | lower wins; default 100 |
| `payment.gateway.<id>.regions` | array | `configuration-service` | eligible regions |
| `payment.gateway.<id>.supported_currencies` | array | `configuration-service` | ISO 4217 |
| `payment.gateway.<id>.supported_methods` | array | `configuration-service` | `card` / `wallet` / `bnpl` / `bank_transfer` / `crypto` |
| `payment.gateway.<id>.signature_scheme` | enum | `configuration-service` | `hmac_sha256` / `hmac_sha512` / `rsa_sha256` / `md5` / `sha256` / `paypal_sdk` / `paymob_hmac` / `kashier_hmac` / `none` |
| `payment.gateway.<id>.verify_style` | enum | `configuration-service` | `get_redirect` / `webhook_post` / `signed_webhook` / `cache_lookup` / `iframe_postback` |
| `payment.gateway.<id>.health_url` | string | `configuration-service` | synthetic probe URL |
| `payment.gateway.<id>.webhook_ttl_seconds` | int | `configuration-service` | default 5 |
| `payment.gateway.override.tenant.<tenant_id>` | string (gateway_id) | `configuration-service` | tenant pin |
| `payment.gateway.override.region.<region>` | string (gateway_id) | `configuration-service` | region default |
| `payment.gateway.override.currency.<iso4217>` | string (gateway_id) | `configuration-service` | currency default |
| `payment.gateway.override.payment_method.<method>` | string (gateway_id) | `configuration-service` | method default |
| `payment.idempotency_ttl_hours` | int | `configuration-service` | default 24 |
| `payment.refund.max_window_days` | int | `configuration-service` | default 90 |
| `payment.payout.max_retries` | int | `configuration-service` | default 3 |
| `payment.webhook.timeout_seconds` | int | `configuration-service` | default 5 |

## 14. Security

- **PCI-DSS SAQ-A** scope: card data is handled by the
  gateway's hosted fields / SDK; the platform only receives a
  tokenised reference.
- **NO PAN, NO CVV, NO full track data** is ever stored,
  processed, or transmitted by this service.
- All requests to any gateway are over TLS 1.3.
- Gateway webhooks are verified with the per-gateway signature
  scheme declared in `payment_gateways.signature_scheme` (HMAC-
  SHA256, HMAC-SHA512, RSA-SHA256, MD5, SHA-256, PayPal SDK,
  PayMob HMAC, Kashier HMAC, or none).
- Per-gateway credentials live at
  `secret/payment-service/gateway/<id>/<env>`; rotated quarterly.
- All state-changing endpoints require `Idempotency-Key`.
- All admin actions are audit-logged.
- New gateway activations require a PCI scope review by the
  security team; gateways that require raw card capture (no
  hosted fields / SDK) MUST NOT be enabled without QSA sign-off.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `payment_intent_id`,
  `attempt_id`, `tenant_id`, **`gateway_id`**. **NEVER log PAN or
  gateway responses that may contain it.**
- Metrics (per-gateway labels):
  - `payment_attempt_total{gateway_id,method,currency,outcome}`
  - `payment_capture_seconds{gateway_id}` (histogram)
  - `payment_failure_rate{gateway_id,reason}`
  - `payment_refund_total{gateway_id,currency,outcome}`
  - `payment_payout_total{gateway_id,currency,outcome}`
  - `payment_gateway_health{gateway_id}` (gauge; `healthy`/`degraded`/`unreachable`)
  - `payment_gateway_probe_latency_ms{gateway_id}` (histogram)
  - `payment_gateway_error_translated_total{gateway_id,platform_code}`
- Traces: OpenTelemetry; one root span per attempt; child spans
  for gateway calls (carrying a `gateway_id` attribute).
- Health: `/health`, `/ready` (returns 200 only if at least one
  enabled gateway in the current region reports `healthy`),
  `/started`.

## 16. Scalability

- Replicas: 8 (default) — HPA on `kafka_consumer_lag` and
  per-gateway `payment_attempt_total` rate.
- Hot path: the capture / refund API; throughput is bounded by
  the **per-gateway** rate limits. With 46 gateways in flight,
  the per-gateway bulkheads must be sized at least to the
  BEST-EFFORT floor (25 in-flight, 50 queue, 500ms timeout) —
  46 × 25 = ≥ 1150 in-flight goroutines per replica just for
  gateway calls.
- **A single gateway's outage does not cascade** — per-gateway
  circuit breaker + bulkhead + fallback to next-priority
  gateway in the same region.

## 17. Local Development

- `docker compose --profile payment up` brings up the service,
  PostgreSQL, Kafka, and a **mock gateway server** that emulates
  all 46 gateway drivers behind a uniform REST API (the
  `gateway_id` request header selects which gateway to mock;
  see [`TECH.md` 9](./TECH.md#9-local-dev)).
- Tests: `./gradlew test`, `./gradlew test:e2e`.

## 18. Deployment

- Image: `registry.platform.io/payment-service:{version}`.
- Replicas: 8 (per region).
- Resource limits: 1 vCPU / 1 GiB.
- Migrations: separate job.
- Per-gateway credentials: mounted from Vault at startup;
  rotated quarterly.


## 19. Accounting impact

`payment-service` is **not a tax or expense service**. Tax is
computed upstream by ``pricing-service` (tax)` and is integrated into the
`amount_minor` of the `payment.captured.v1` event. From an accounting
perspective `payment-service` produces the **money-side events** that
`ledger-service` consumes to derive its double-entry postings.

- **What money facts it owns:** `payment_intents`, gateway
  transactions, refunds, payouts, chargeback / dispute state, payment
  methods (tokenised). Owns the `gateway_token` lifecycle but
  never holds PAN / CVV (PCI-DSS SAQ-A; gateway tokenisation).
- **Revenue recognition:** on `payment.captured.v1`, the ledger
  records `cash` (asset) ↔ `revenue` (gross) + `tax_payable`
  (liability) — the tax split is preserved in the `amount_minor`
  integrated from the quote.
- **Expense recognition — gateway fees:** at capture, the net cash
  received from the gateway (after the gateway fee) is posted
  alongside the gross revenue; the difference is recorded as
  `6100_payment_processing_fees.<gateway_id>` (expense; the
  `<gateway_id>` suffix allows per-gateway fee accounting across
  the 46 supported gateways).
- **Expense recognition — refunds:** on `payment.refund.completed.v1`,
  the original revenue is partially or fully reversed and recorded
  as `6200_refunds` (expense). Closed-loop refunds debit the
  wallet.
- **Expense recognition — chargebacks:** gateway dispute webhooks
  trigger an immediate provisioning posting
  (`6400_chargeback_losses` ↔ `chargeback_reserve`); resolution
  reverses or settles the reserve against `cash`.
- **Expense recognition — payouts:** on `payment.payout.completed.v1`,
  the corresponding `*-payable` liability is settled against `cash`.
- **Reconciliation:** daily at 02:00 UTC against each gateway's
  report (one `reconciliation_runs` row per `(run_date, gateway_id)`);
  drift opens a P1 ticket via
  `payment.audit.reconciliation_drift.v1`.
- **Human operator path:** force-capture / force-refund / force-payout
  via admin console; per-gateway activate / drain / disable via
  `/admin/v1/gateways/{id}/{activate|drain|disable}`; requires
  `payment.admin` role; emits `admin.action.performed.v1`.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``payment-service` (wallet)` (customer
wallet, holds, top-ups), ``payment-service` (ride saga)` (ride
payment saga), ``payment-service` (food saga)` (food payment
saga), ``payment-service` (driver earnings)` (driver earnings, withdrawals),
``payment-service` (courier earnings)` (courier earnings, withdrawals), and
``payment-service` (merchant settlement)` (merchant payable, payout runs,
disputes) is now absorbed into this service. The canonical source
for these sections is [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md)
3.3 (courier-earnings), 3.8 (driver-earnings), 3.11 (restaurant-
settlement), 3.12 (food-payment-integration), 3.13 (ride-payment-
integration), 3.14 (wallet). Section numbering is preserved so
deep links into the predecessor READMEs continue to resolve.

### A.1 Bounded context (post-merger)

Payment intents + gateway registry (46 gateways) **plus** customer
wallet + ride payment saga + food payment saga + driver earnings +
courier earnings + merchant payable + payouts + disputes. The
service is the **only** writer of the `payment` schema. Out of
scope: chart of accounts (still `ledger-service`), pricing engine
(still `pricing-service`), tax rules (still ``pricing-service` (tax)`),
fraud risk scores (still `fraud-risk-service`).

### A.2 Absorbed responsibilities — wallet (from ``payment-service` (wallet)`)

- Maintain the wallet balance per user (minor units + ISO 4217).
- Apply holds (reservations); release on cancellation / completion.
- Credit / debit the wallet on `payment.captured.v1` /
  `payment.refund.completed.v1`.
- Top-up flow (charge via the gateway, credit on success).
- Consume `trip.reward.granted.v1` when
  `trip.reward.user.kind = wallet_credit`; consume
  `trip.reward.reversed.v1` to reverse.
- Statement view.
- Daily reconciliation against `ledger-service`.

### A.3 Absorbed responsibilities — ride payment saga (from ``payment-service` (ride saga)`)

- Consume `trip.completed.v1`; start a ride payment saga.
- Capture (and on failure, void / refund) via the gateway.
- Accrue driver earning via the embedded earnings ledger.
- Post the double-entry posting via `ledger-service`.
- Emit `ride.payment.completed.v1` / `ride.payment.failed.v1`.
- Compensate on failure.
- Saga state in `payment.ride_sagas` keyed by `trip_id`.

### A.4 Absorbed responsibilities — food payment saga (from ``payment-service` (food saga)`)

- Receive `delivery.completed.v1`; start a food payment saga.
- Authorize at checkout, capture at delivery completion.
- Trigger courier earning accrual (embedded).
- Trigger merchant settlement accrual (embedded).
- Post a double-entry to `ledger-service`.
- Handle refunds (partial, full, post-delivery).
- Saga state in `payment.food_sagas`.

### A.5 Absorbed responsibilities — driver earnings (from ``payment-service` (driver earnings)`)

- Accrue an earning on `ride.payment.completed.v1`,
  `trip.completed.v1` (tip / bonus), `trip.reward.granted.v1`
  (guaranteed top-up), `trip.reward.reversed.v1` (reverse), and
  `driver.incentive.earned.v1`.
- Maintain running balances per driver.
- Withdrawal requests to bank; ledger postings.
- Penalty postings from the ride payment saga (idempotency key
  `trip:{trip_id}:penalty:driver:{penalty_id}`).

### A.6 Absorbed responsibilities — courier earnings (from ``payment-service` (courier earnings)`)

- Accrue an earning on `delivery.completed.v1` (delivery fee),
  `food.payment.completed.v1` (tip + bonus), `courier.incentive.earned.v1`.
- Maintain balances; withdrawal requests; ledger postings.

### A.7 Absorbed responsibilities — restaurant settlement (from ``payment-service` (merchant settlement)`)

- Accrue merchant payable on `food.payment.completed.v1`.
- Apply merchant settlement adjustments (`merchant.settlement.created.v1`).
- Schedule payout runs; orchestrate bank transfers via the gateway.
- Disputes (chargebacks, quality disputes) debit the payable.
- Reconcile against `ledger-service` daily.
- Provide merchant statement view.

### A.8 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/wallets/me` | bearer (customer) | read balance |
| GET  | `/v1/wallets/me/statement` | bearer (customer) | statement |
| POST | `/v1/wallets/me/topup` | bearer (customer) | top up |
| POST | `/v1/wallets/{id}/holds` | bearer (service) | place a hold |
| POST | `/v1/wallets/{id}/holds/{hold_id}/capture` | bearer (service) | capture a hold |
| POST | `/v1/wallets/{id}/holds/{hold_id}/release` | bearer (service) | release a hold |
| GET  | `/v1/ride-payment/sagas/{trip_id}` | bearer (admin / support) | read ride saga |
| POST | `/v1/ride-payment/sagas/{trip_id}/retry` | bearer (admin) | retry ride saga |
| POST | `/v1/ride-payment/sagas/{trip_id}/compensate` | bearer (admin) | compensate ride saga |
| GET  | `/v1/food-payment/sagas/{food_order_id}` | bearer (admin / support) | read food saga |
| POST | `/v1/food-payment/sagas/{food_order_id}/retry` | bearer (admin) | retry food saga |
| POST | `/v1/food-payment/sagas/{food_order_id}/compensate` | bearer (admin) | compensate food saga |
| GET  | `/v1/drivers/{id}/earnings` | bearer (driver) | read earnings |
| POST | `/v1/drivers/{id}/withdrawals` | bearer (driver) | request withdrawal |
| GET  | `/v1/couriers/{id}/earnings` | bearer (courier) | read earnings |
| POST | `/v1/couriers/{id}/withdrawals` | bearer (courier) | request withdrawal |
| GET  | `/v1/merchants/{id}/payable` | bearer (merchant) | read payable |
| GET  | `/v1/merchants/{id}/statement` | bearer (merchant) | statement |
| POST | `/v1/merchants/{id}/payouts/schedule` | bearer (admin) | schedule payout |
| POST | `/v1/merchants/{id}/disputes` | bearer (admin) | open dispute |

### A.9 Absorbed events

**Produced** (same topic + schema version, by this service):

- `wallet.credited.v1`, `wallet.debited.v1`, `wallet.held.v1`,
  `wallet.released.v1`.
- `ride.payment.completed.v1`, `ride.payment.failed.v1`.
- `food.payment.completed.v1`, `food.payment.failed.v1`,
  `merchant.settlement.created.v1`.
- `driver.earning.accrued.v1`, `driver.withdrawal.requested.v1`,
  `driver.withdrawal.completed.v1`.
- `courier.earning.accrued.v1`, `courier.withdrawal.requested.v1`,
  `courier.withdrawal.completed.v1`.
- `merchant.settlement.accrued.v1`, `merchant.payout.scheduled.v1`,
  `merchant.payout.completed.v1`.

**Consumed** (in addition to pre-existing payment intents):

- `trip.completed.v1` (ride payment saga + tip accrual).
- `delivery.completed.v1` (food payment saga + courier fee accrual).
- `payment.captured.v1` (own producer).
- `payment.refund.completed.v1` (own producer).
- `trip.reward.granted.v1` / `trip.reward.reversed.v1`
  (wallet credit; driver guaranteed top-up).
- `courier.incentive.earned.v1` (courier earning accrual).
- `driver.incentive.earned.v1` (driver earning accrual).
- `merchant.suspended.v1` (hold payouts).

### A.10 Absorbed configuration keys

- `payment.wallet.topup_min_minor` (int, default 100).
- `payment.wallet.hold_ttl_seconds` (int, default 600).
- `payment.driver_earnings.idempotency_namespace` (text).
- `payment.courier_earnings.idempotency_namespace` (text).
- `payment.merchant_settlement.payout_cadence` (text, `weekly|monthly`).
- `payment.merchant_settlement.payout_min_minor` (int, default 10000).
- `payment.ride_saga.idempotency_namespace` (text).
- `payment.food_saga.idempotency_namespace` (text).

### A.11 Absorbed state machines

`RideSaga`:
```
started → authorized → captured → earning_accrued → ledger_posted → completed
       ↘ voided         ↘ refund_pending ↘ failed
```

`FoodSaga`:
```
started → authorized → captured → merchant_accrued → courier_accrued → ledger_posted → completed
                                                  ↘ tip_accrued → completed
       ↘ failed (with compensation)
```

`MerchantPayout`:
```
scheduled → processing → paid
                     ↘ failed (retry)
```

`DriverWithdrawal` / `CourierWithdrawal`:
```
requested → processing → paid
                       ↘ failed
```

### A.12 Accounting four-layer model preserved

- Layer 1 (customer wallet): inside this service.
- Layer 2 (provider side): inside this service (46-gateway registry).
- Layer 3 (double-entry ledger): `ledger-service` (independent).
- Layer 4 (settlement): inside this service.

Every payment, earning, payout, and wallet entry is a posting in
`ledger-service`. The chart of accounts is unchanged.

### A.13 Absorbed non-functional targets

- P95 ride-saga completion from `trip.completed.v1` ≤ 5 s.
- P95 food-saga completion from `delivery.completed.v1` ≤ 5 s.
- P95 wallet hold/capture/release ≤ 50 ms.
- 99.95% / 30 days SLO.

### A.14 Degraded mode (post-merger)

- If the gateway is unreachable, the saga waits with exponential
  backoff (max 10 minutes), then enters `failed` and a support
  ticket is opened.
- If `ledger-service` is unreachable, the saga writes to the
  embedded outbox and retries with idempotency key.
- If the embedded wallet is unreachable (sub-call within this
  service), the hold / release retries with backoff.

### A.15 Compatibility window

For at least six calendar months from 2026-08-05:

- `wallet.*.v1`, `ride.payment.*.v1`, `food.payment.*.v1`,
  `driver.earning.accrued.v1`, `driver.withdrawal.*.v1`,
  `courier.earning.accrued.v1`, `courier.withdrawal.*.v1`,
  `merchant.settlement.*.v1`, `merchant.payout.*.v1` are published
  under the same topic names and schema versions.
- `/v1/wallets/*`, `/v1/ride-payment/sagas/*`,
  `/v1/food-payment/sagas/*`, `/v1/drivers/{id}/earnings*`,
  `/v1/couriers/{id}/earnings*`, `/v1/merchants/{id}/payable`,
  `/v1/merchants/{id}/statement`, `/v1/merchants/{id}/payouts/*`,
  `/v1/merchants/{id}/disputes` continue to be served from this
  service.
- Old schema names `wallet.*`, `ride_payment_integration.*`,
  `food_payment_integration.*`, `driver_earnings.*`,
  `courier_earnings.*`, `restaurant_settlement.*` remain readable
  as views in the `payment` schema.

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

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)

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

- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (transaction, expense, chargeback, reconciliation)
