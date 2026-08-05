# payment-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/payment-intents`

- **Purpose**: Create a payment intent.
- **Auth**: Bearer JWT — service-to-service (`payment.write`).
- **Idempotency**: `Idempotency-Key` required
  (`food:<order_id>:auth` or `ride:<ride_id>:auth`).
- **Request**:
  ```json
  {
    "customer_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
    "amount_minor": 2350,
    "currency": "EUR",
    "gateway_id": "stripe",
    "gateway_region": "eu-west",
    "gateway_token": "tok_01HZX…",
    "capture_mode": "manual",
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "description": "Order #12345",
    "metadata": { "channel": "mobile" },
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
  > `gateway_id` is OPTIONAL; when omitted the gateway registry
  > resolves one using the precedence defined in
  > [`GATEWAYS.md` §6](./GATEWAYS.md#6-resolution-precedence).
  > The 46 supported `gateway_id`s are enumerated in
  > [`GATEWAYS.md`](./GATEWAYS.md).
- **Response (201)**:
  ```json
  {
    "payment_intent_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "created",
    "amount_minor": 2350,
    "currency": "EUR",
    "gateway_id": "stripe",
    "gateway_region": "eu-west",
    "gateway_intent_id": "pi_…",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401, 403, 409 (duplicate idempotency), 422.
    New per-gateway codes: `GATEWAY_NOT_ENABLED`,
    `GATEWAY_REGION_MISMATCH`, `GATEWAY_AMOUNT_TOO_LARGE`,
    `GATEWAY_CURRENCY_UNSUPPORTED` (see SRS §13).

### 1.2 `POST /v1/payment-intents/{id}/authorize`

- **Auth**: Bearer JWT — service (`payment.write`).
- **Idempotency**: required.
- **Request**: `{}`
- **Response (200)**:
  ```json
  {
    "payment_intent_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "authorized",
    "amount_minor": 2350,
    "currency": "EUR",
    "authorized_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 401, 403, 404, 409, 422, 502 (gateway).

### 1.3 `POST /v1/payment-intents/{id}/capture`

- **Auth**: Bearer JWT — service (`payment.write`).
- **Idempotency**: required
  (`food:<order_id>:capture` or `ride:<ride_id>:capture`).
- **Request**:
  ```json
  { "amount_minor": 2350 }
  ```
  (omit for full capture)
- **Response (200)**:
  ```json
  {
    "payment_intent_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "captured",
    "captured_minor": 2350,
    "currency": "EUR",
    "captured_at": "2026-07-29T11:08:21.183Z"
  }
  ```
- **Errors**: 401, 403, 404, 409, 422, 502 (gateway).

### 1.4 `POST /v1/payment-intents/{id}/void`

- **Auth**: Bearer JWT — service (`payment.write`).
- **Idempotency**: required
  (`food:<order_id>:void` or `ride:<ride_id>:void`).
- **Request**: `{}`
- **Response (200)**:
  ```json
  {
    "payment_intent_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "voided",
    "voided_at": "2026-07-29T11:00:00.000Z"
  }
  ```
- **Errors**: 401, 403, 404, 409 (`STATE_INVALID` if not `authorized`), 422, 502.

### 1.5 `POST /v1/payment-intents/{id}/refund`

- **Auth**: Bearer JWT — service (`payment.write`) OR support.
- **Idempotency**: required
  (`food:<order_id>:refund:<reason>` or `ride:<ride_id>:refund:<reason>`).
- **Request**:
  ```json
  {
    "amount_minor": 500,
    "reason": "quality"
  }
  ```
- **Response (202)**:
  ```json
  {
    "refund_id": "01HZX…",
    "payment_intent_id": "01HZX…",
    "state": "initiated",
    "amount_minor": 500,
    "currency": "EUR"
  }
  ```
- **Errors**: 401, 403, 404, 409, 422 (`REFUND_EXCEEDS_CAPTURED`,
  `REFUND_WINDOW_EXPIRED`), 502.

### 1.6 `POST /v1/payment-intents/{id}/admin-refund`

- **Auth**: Bearer JWT — admin (`payment.admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "amount_minor": 500,
    "reason": "goodwill",
    "audit_note": "..."
  }
  ```
- **Response (202)**: same as 1.5.

### 1.7 `POST /v1/payment-intents/{id}/force-capture`

- **Auth**: Bearer JWT — admin.
- **Idempotency**: required.
- **Request**:
  ```json
  { "audit_note": "..." }
  ```
- **Response (200)**: same as 1.3.

### 1.8 `GET /v1/payment-intents/{id}`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**: full intent record with state history.

### 1.9 `GET /v1/payment-intents/{id}/attempts`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**: list of attempts.

### 1.10 `POST /v1/webhooks/gateway/{gateway_id}`

- **Purpose**: Receive a gateway webhook. The `{gateway_id}` path
  parameter is one of the 46 in [`GATEWAYS.md`](./GATEWAYS.md);
  the per-gateway `signature_scheme` (HMAC-SHA256, HMAC-SHA512,
  RSA-SHA256, MD5, SHA-256, PayPal SDK, PayMob HMAC, Kashier
  HMAC, or none) is looked up from the registry and the
  corresponding driver verifies the payload.
- **Auth**: per-gateway signature in the gateway-specific header
  (e.g. `X-Webhook-Signature` for HMAC-SHA256,
  `BinancePay-Signature` for Binance, `X-MAC-Signature` for
  NowPayments, `ac_hash` for Volet). The full header-name
  convention is documented per row in
  [`GATEWAYS.md`](./GATEWAYS.md).
- **Idempotency**: dedup on `(gateway_id, gateway_event_id)` in
  `webhook_events`.
- **Request** (varies by gateway; example for Stripe):
  ```json
  {
    "id": "evt_…",
    "type": "payment_intent.succeeded",
    "data": { "object": { "id": "pi_…", "amount": 2350, "currency": "eur", "status": "succeeded" } }
  }
  ```
- **Response (200)**: `{}` (the gateway requires 2xx within
  5s; 5xx triggers retry).
- **Errors**: 401 (`WEBHOOK_SIGNATURE_INVALID`), 422
  (`WEBHOOK_UNPROCESSABLE`).
- **Backward compatibility**: the legacy `/v1/webhooks/provider`
  endpoint is preserved as a redirect to
  `/v1/webhooks/gateway/{default_gateway_id}` for one release
  cycle; callers should migrate before deprecation.

### 1.11 `POST /v1/payouts`

- **Auth**: Bearer JWT — service (`payment.write`).
- **Idempotency**: required (`payout:<payout_id>`).
- **Request**:
  ```json
  {
    "recipient_type": "merchant",
    "recipient_id": "01HZX…",
    "amount_minor": 188000,
    "currency": "EUR",
    "payment_method_token": "pm_…",
    "idempotency_key": "payout:01HZX…"
  }
  ```
- **Response (202)**:
  ```json
  {
    "payout_id": "01HZX…",
    "state": "pending",
    "amount_minor": 188000,
    "currency": "EUR"
  }
  ```
- **Errors**: 401, 403, 404, 409, 422, 502 (gateway).

### 1.12 `GET /v1/payouts/{id}`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**: full payout record.

### 1.13 `POST /v1/payouts/{id}/cancel`

- **Auth**: Bearer JWT — admin.
- **Idempotency**: required.
- **Response (200)**: `{ "payout_id": "...", "state": "cancelled" }`

## 2. Outbound APIs

### 2.1 Gateway manifest

This service makes **46 outbound gateway calls** — one per driver
in [`GATEWAYS.md`](./GATEWAYS.md). The full per-gateway manifest
(timeout, retry budget, bulkhead size, probe URL, signature
scheme, verify style) lives in the gateway registry rows
(`payment_gateways.metadata` JSONB) and the service config
(`application.yml` → `platform.outbounds.payment-service.*`). The
manifest row is the single source of truth — see
[`TECH.md` §5.3](./TECH.md#53-outbound-manifest) for the Kotlin
shape and the platform-wide convention in
[`architecture/SERVICE_ISOLATION.md` §"Configuration knobs"](../../architecture/SERVICE_ISOLATION.md).

Per-gateway defaults (apply unless overridden by `payment.gateway.<id>.*`):

| Field | Default |
|---|---|
| Timeout | 5s |
| Retry | 3 attempts (exp backoff 100ms / 400ms / 1.6s; never on 4xx; never on signature errors) |
| Circuit breaker | 5 consecutive failures or 50% over 30s; half-open after 60s; **state persisted** |
| Bulkhead | per-gateway connection pool (≥ BEST-EFFORT floor: 25 in-flight, 50 queue, 500ms timeout) |
| Probe URL | `payment.gateway.<id>.health_url` |

### 2.2 Inter-service outbound calls

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | `/v1/customers/{id}` | enrich | 1s | 3 | yes |
| `fraud-risk-service` | POST | `/v1/risk/score` | score the attempt | 500ms | 2 | yes |
| `configuration-service` | GET | `/v1/configurations/stream` + `GET /v1/configurations/{key}` | gateway catalog reload | 2s | 3 | yes |

## 3. Produced Events

### 3.1 `payment.attempted.v1`

- **Topic**: `payment.attempted`
- **Trigger**: every attempt.
- **Partition key**: `payment_intent_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "payment.attempted.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "aggregate_type": "PaymentIntent",
    "aggregate_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "data": {
      "payment_intent_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
      "customer_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
      "amount_minor": 2350,
      "currency": "EUR",
      "action": "capture",
      "gateway_id": "stripe",
      "outcome": "success",
      "latency_ms": 412
    }
  }
  ```
- **Consumers**: `fraud-risk-service`, `audit-service`.
- **DLQ**: `payment.attempted.dlq`.

### 3.2 `payment.authorized.v1`

- **Topic**: `payment.authorized`
- **Trigger**: provider authorized.
- **Partition key**: `payment_intent_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.authorized.v1",
    "data": {
      "payment_intent_id": "01HZX…",
      "customer_id": "...",
      "amount_minor": 2350,
      "currency": "EUR",
      "gateway_id": "stripe",
      "authorized_at": "..."
    }
  }
  ```
- **Consumers**: ``payment-service` (ride saga)`,
  ``payment-service` (food saga)`, ``payment-service` (wallet)`.

### 3.3 `payment.captured.v1`

- **Topic**: `payment.captured`
- **Trigger**: provider captured.
- **Partition key**: `payment_intent_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.captured.v1",
    "data": {
      "payment_intent_id": "01HZX…",
      "customer_id": "...",
      "amount_minor": 2350,
      "currency": "EUR",
      "captured_at": "...",
      "gateway_id": "stripe"
    }
  }
  ```
- **Consumers**: ``payment-service` (ride saga)`,
  ``payment-service` (food saga)`, ``payment-service` (wallet)`,
  `ledger-service`, `audit-service`.

### 3.4 `payment.failed.v1`

- **Topic**: `payment.failed`
- **Trigger**: attempt failed (declined, error, timeout).
- **Partition key**: `payment_intent_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.failed.v1",
    "data": {
      "payment_intent_id": "01HZX…",
      "customer_id": "...",
      "amount_minor": 2350,
      "currency": "EUR",
      "reason": "card_declined",
      "gateway_id": "stripe",
      "gateway_code": "do_not_honor",
      "platform_code": "PAYMENT_CARD_DECLINED"
    }
  }
  ```
- **Consumers**: ``payment-service` (ride saga)`,
  ``payment-service` (food saga)`, `notification-service`.

### 3.5 `payment.refund.initiated.v1`

- **Topic**: `payment.refund.initiated`
- **Trigger**: refund started.
- **Partition key**: `payment_intent_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.refund.initiated.v1",
    "data": {
      "payment_intent_id": "01HZX…",
      "refund_id": "01HZX…",
      "amount_minor": 500,
      "currency": "EUR",
      "reason": "quality"
    }
  }
  ```
- **Consumers**: ``payment-service` (wallet)`, `ledger-service`, `audit-service`.

### 3.6 `payment.refund.completed.v1`

- **Topic**: `payment.refund.completed`
- **Trigger**: refund confirmed.
- **Partition key**: `payment_intent_id`
- **Schema**: similar to 3.5 with `state: "succeeded"`,
  `provider_refund_id`, `completed_at`.

### 3.7 `payment.refund.failed.v1`

- **Topic**: `payment.refund.failed`
- **Trigger**: refund failed.
- **Partition key**: `payment_intent_id`
- **Schema**: similar to 3.5 with `state: "failed"`,
  `error_message`.

### 3.8 `payment.payout.completed.v1`

- **Topic**: `payment.payout.completed`
- **Trigger**: payout confirmed by provider.
- **Partition key**: `recipient_id`

### 3.9 `payment.payout.failed.v1`

- **Topic**: `payment.payout.failed`
- **Trigger**: payout failed after retries.
- **Partition key**: `recipient_id`

### 3.10 `payment.method.saved.v1`

- **Topic**: `payment.method.saved`
- **Trigger**: new payment method tokenised.
- **Partition key**: `customer_id`
- **Consumers**: `customer-service` (history).

### 3.11 `payment.audit.attempt_logged.v1`

- **Topic**: `payment.audit.attempt_logged`
- **Trigger**: every attempt (1/100 sampled).
- **Partition key**: `payment_intent_id`
- **Consumers**: `audit-service`.

### 3.12 `payment.gateway.activated.v1`

- **Topic**: `payment.gateway.activated`
- **Trigger**: an admin POSTs `/admin/v1/gateways/{id}/activate`
  and the catalog row transitions to `state='enabled'`.
- **Partition key**: `gateway_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.gateway.activated.v1",
    "data": {
      "gateway_id": "paymob",
      "regions": ["mena"],
      "supported_currencies": ["EGP"],
      "supported_methods": ["card", "wallet"],
      "activated_by": "01HZX…"
    }
  }
  ```
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`.

### 3.13 `payment.gateway.deactivated.v1`

- **Topic**: `payment.gateway.deactivated`
- **Trigger**: an admin POSTs `/admin/v1/gateways/{id}/disable`
  (after 0 in-flight intents).
- **Partition key**: `gateway_id`
- **Schema**: similar to 3.12 with `reason`, `deactivated_by`.

### 3.14 `payment.gateway.drained.v1`

- **Topic**: `payment.gateway.drained`
- **Trigger**: an admin POSTs `/admin/v1/gateways/{id}/drain`;
  the gateway stops accepting new intents but continues serving
  existing ones.
- **Partition key**: `gateway_id`

### 3.15 `payment.gateway.health.changed.v1`

- **Topic**: `payment.gateway.health.changed`
- **Trigger**: a synthetic probe transition (`healthy` ↔
  `degraded` ↔ `unreachable`).
- **Partition key**: `gateway_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.gateway.health.changed.v1",
    "data": {
      "gateway_id": "stripe",
      "from": "healthy",
      "to": "degraded",
      "error_class": "timeout",
      "occurred_at": "2026-08-01T10:42:11.183Z"
    }
  }
  ```
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`. **Drives
  the auto-resolution skip in [`GATEWAYS.md` §6](./GATEWAYS.md#6-resolution-precedence).**

### 3.16 `payment.gateway.error.translated.v1`

- **Topic**: `payment.gateway.error.translated`
- **Trigger**: a vendor-native error code was translated to a
  platform code via `payment_gateway_error_mapping`. Emitted so
  operators can audit translation behaviour without searching
  per attempt.
- **Partition key**: `gateway_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "payment.gateway.error.translated.v1",
    "data": {
      "gateway_id": "stripe",
      "vendor_code": "do_not_honor",
      "vendor_message_pattern": null,
      "platform_code": "PAYMENT_CARD_DECLINED",
      "is_terminal": true,
      "attempt_id": "..."
    }
  }
  ```
- **Consumers**: `audit-service`.

## 4. Consumed Events

### 4.1 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: block future payment attempts.
- **Handler**: mark customer `payments_blocked=true`; future
  attempts return 403 `CUSTOMER_PAYMENTS_BLOCKED`.

### 4.2 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload thresholds.
- **Handler**: refresh in-memory config.

### 4.3 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: A suspended customer cannot pay.
- **Handler**: Reject capture.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `payment.retry_requested.v1`

- **Producer**: `internal`.
- **Reason**: A failed capture needs to be retried.
- **Handler**: Re-attempt.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Provider / fraud rules changed.
- **Handler**: Reload config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

The platform-wide isolation pattern is in
[`architecture/SERVICE_ISOLATION.md` §"Mandatory 5-layer pattern"](../../architecture/SERVICE_ISOLATION.md).
For `payment-service` this is applied **per gateway** (46-way fan-out),
not per provider. The isolation principle (L20–22 of
`SERVICE_ISOLATION.md`) is binding: **a service may never fail
because a downstream service is slow, unavailable, or returning
errors. The downstream's problems must be contained at the boundary.**

### 5.1 Shared platform isolation (applied to every gateway call)

- **Timeouts**: outbound to gateway 5s; default 1s.
- **Retries**: 3 with exponential backoff (100ms, 400ms, 1.6s) on
  transient errors. Permanent errors are NOT retried.
- **Circuit breakers**: every gateway call wrapped; opens at 5
  consecutive failures or 50% over 30s; half-open after 60s;
  **state persisted** so restarts don't re-trip a known-bad
  gateway.
- **Bulkheads**: per-gateway connection pool. The full 5-layer
  pattern is mandatory on every outbound call per
  [`SERVICE_ISOLATION.md` L49–51](../../architecture/SERVICE_ISOLATION.md):
  "These are not optional on any outbound call. Every service
  MUST apply all five to every outbound call by default.
  Exemptions require an ADR."

### 5.2 Per-gateway fallback

When one gateway's circuit opens, the next-priority gateway in
the same region per the registry's `priority` ASC absorbs the
traffic. If all gateways in the region are down, the
`authorize` call is CRITICAL and fails 504 `DEPENDENCY_TIMEOUT`;
the integration service compensates.

### 5.3 Outbox / Inbox / DLQ / Reconciliation

- **Outbox**: yes — every event payload written in the same DB
  transaction as the state mutation.
- **Inbox**: yes — consumer-side dedup on `event_id`.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: a daily per-gateway job
  (`reconciliation_runs` rows, one per `(run_date, gateway_id)`)
  compares this service's `payment_intents` state against each
  gateway's report; drift is repaired via the admin
  `/admin/v1/payments/{id}/force-state` endpoint or
  `payment.gateway.error.translated.v1` for code translation
  gaps.

## 6. Gateway error mapping

This is the section referenced by the anchor sentence in
[`architecture/DOWNSTREAM_ERROR_CATALOG.md` §5 L289–291](../../architecture/DOWNSTREAM_ERROR_CATALOG.md#5-propagation-rules):

> **The translation table is per-vendor and lives in the
> service's `INTEGRATION.md` (e.g. `services/payment-service/INTEGRATION.md`
> § "Provider error mapping").**

### 6.1 Schema

The translation table is the `payment_gateway_error_mapping`
table ([`ERD.md` §3](./ERD.md)). For each of the 46 gateways,
it maps a vendor-native error code / status string to a platform
error code (one of those in
[`architecture/DOWNSTREAM_ERROR_CATALOG.md` §4.2](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)).

### 6.2 Example rows

Selected translations (the full set lives in the seed migration
`V047__seed_payment_gateway_error_mapping.sql`; this is an
illustrative subset):

| `gateway_id` | `vendor_code` | `vendor_message_pattern` | `platform_code` | `is_terminal` |
|---|---|---|---|---|
| `stripe` | `do_not_honor` | — | `PAYMENT_CARD_DECLINED` | true |
| `stripe` | `insufficient_funds` | — | `PAYMENT_INSUFFICIENT_FUNDS` | true |
| `stripe` | `expired_card` | — | `PAYMENT_CARD_DECLINED` | true |
| `paypal` | `INSTRUMENT_DECLINED` | — | `PAYMENT_CARD_DECLINED` | true |
| `paypal` | `PAYER_ACTION_REQUIRED` | — | `PAYMENT_PROVIDER_REDIRECT` | false |
| `paymob` | `success` | `success=='false'` | `PAYMENT_FAILED` | true |
| `fawry` | `paymentStatus=='PAID'` | `paymentStatus!='PAID'` | `PAYMENT_FAILED` | true |
| `hyperpay` | `000.200.100` | — | `PAYMENT_FAILED` | true |
| `hyperpay` | `000.400.101` | — | `PAYMENT_CARD_DECLINED` | true |
| `paymob_wallet` | `WALLET_USER_INPUT_INVALID` | — | `PAYMENT_PROVIDER_REDIRECT` | false |
| `binance` | `PAY_FAILED` | — | `PAYMENT_FAILED` | true |
| `now_payments` | `AMOUNT_MINIMAL_ERROR` | — | `GATEWAY_AMOUNT_TOO_LARGE` | true |
| `paytabs` | `response_status!='A'` | — | `PAYMENT_FAILED` | true |
| `tap` | `CAPTURED` | `status!='CAPTURED'` | `PAYMENT_FAILED` | true |
| `perfect_money` | `V2_HASH` mismatch | — | `WEBHOOK_SIGNATURE_INVALID` | true |
| `payeer` | `m_status!='success'` | — | `PAYMENT_FAILED` | true |

### 6.3 Behaviour

- On every gateway response, the driver's `verify` method
  extracts the vendor code (or status string) and the platform
  translation layer looks up `payment_gateway_error_mapping`. If
  found, the `platform_code` is recorded on
  `payment_attempts.platform_code` and emitted in
  `payment.failed.v1` / `payment.refund.failed.v1`. The
  `payment.gateway.error.translated.v1` audit event (§3.16)
  fires on every translation.
- If no row matches, the gateway code is recorded as-is on
  `payment_attempts.gateway_code` with `platform_code=NULL` and
  a `GATEWAY_ERROR_MAPPING_MISSING` warning is emitted (operator
  is expected to add the missing row via
  `POST /admin/v1/gateways/{id}/error-mapping`).
- Per
  [`architecture/DOWNSTREAM_ERROR_CATALOG.md` §5 "Decision flow"](../../architecture/DOWNSTREAM_ERROR_CATALOG.md#5-propagation-rules),
  the translated platform code is what the integration services
  see and react to. Integration services MUST NOT branch on
  `gateway_code`; they remain gateway-agnostic.

## 7. Correlation IDs

## 7. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope.

## 8. Distributed Tracing

OpenTelemetry; root span per attempt; child spans for gateway
calls, DB writes, outbox publish. `traceparent` propagated
through Kafka headers. Per-gateway spans carry a `gateway_id`
attribute for filtering in Jaeger/Tempo.


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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``payment-service` (food saga)`](../`payment-service` (food saga)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (merchant settlement)`](../`payment-service` (merchant settlement)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (ride saga)`](../`payment-service` (ride saga)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../`admin-service` (support module)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (wallet)`](../`payment-service` (wallet)/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (checkout)`](../`food-order-service` (checkout)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../`notification-service` (provider ACL)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../`courier-service` (dispatch)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../`payment-service` (courier earnings)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../`courier-service` (delivery)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../`payment-service` (driver earnings)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../`payment-service` (food saga)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (inventory)`](../`restaurant-service` (inventory)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``pricing-service` (loyalty rules) / `customer-service` (account)`](../`pricing-service` (loyalty rules) / `customer-service` (account)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (merchant settlement)`](../`payment-service` (merchant settlement)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (history)`](../`trip-service` (history)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 5 more_ | |

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
- [`GATEWAYS.md`](./GATEWAYS.md) — full registry of the 46 supported gateways
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

