# payment-service — Technology Profile

> One-page technology reference for `payment-service`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`GATEWAYS.md`](./GATEWAYS.md) · [`INTEGRATION.md`](./INTEGRATION.md) ·
> [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Financial / correctness |
| **Language** | Kotlin 2.2.x |
| **Framework** | Spring Boot 4.x |
| **Build** | Gradle 9 (Kotlin DSL) |
| **Container** | `eclipse-temurin:25-jre-jammy` (multi-stage build, JRE-only final stage, non-root user) |

## 2. Key libraries

- jOOQ 3.20.x (type-safe SQL, money in `BigDecimal`)
- Spring Statemachine 5 (payment intent state machine)
- Spring Data JPA (Hibernate 7) — read models
- MapStruct
- **Per-gateway SDK libraries** (one Gradle module per gateway;
  see 5.1):

  | Gateway | Module / SDK |
  |---|---|
  | `stripe` | `com.stripe:stripe-java` |
  | `paypal`, `paypal_credit` | `com.paypal:checkout-sdk` |
  | `myfatoorah`, `tap`, `kashier`, `paytabs`, `telr`, `clickpay`, `hyperpay`, `paysky`, `paymob`, `paymob_wallet`, `fawry`, `opay`, `thawani`, `paylink`, `mamo`, `ziina`, `fawaterak`, `xpay`, `yallapay`, `korapay`, `bigpay`, `paycec`, `payzink`, `payzink_direct`, `totalpay`, `totalpay_direct` | `org.springframework.boot:spring-boot-starter-webflux` (for the platform's own HTTP client) + per-gateway JSON contract — most MENA aggregators expose plain REST with bearer tokens, so no per-gateway SDK is needed beyond a `WebClient` configuration. |
  | `wise`, `payrexx`, `payop` | `org.springframework.boot:spring-boot-starter-webflux` + plain REST |
  | `now_payments`, `now_payments_invoice`, `binance`, `coin_payments`, `cryptomus`, `heleket`, `enot`, `changelly` | `org.springframework.boot:spring-boot-starter-webflux` + plain REST |
  | `perfect_money`, `volet`, `payeer` | e-currency gateways that need form-posting — `org.springframework.boot:spring-boot-starter-webflux` + custom Rijndael-256 codec (`payeer`) |

  Every gateway is "a separate Kotlin package under
  `internal/payment/drivers/<gateway_id>/`; adding a new gateway
  is a new package, no changes elsewhere." Mirrors file-service
  2.

## 3. Data layer

- **Database**: PostgreSQL 19, schema `payment` (monthly RANGE partitioned on `occurred_at`)
- **DB extras**: monthly partitions on `payment_attempts` and
  `payment_gateway_health_events`; pre-create 12 future months
- **Migrations**: Flyway 11.x
- **ORM / DSL**: jOOQ 3.20.x (writes) + Hibernate 7 (reads)

## 4. Cache

Redis — idempotency keys, webhook dedup, per-gateway `payment_id
↔ gateway_intent_id` mapping (the durable mirror is
`payment_gateway_intent_registry`).

## 5. External integrations

### 5.0 The gateway registry

This service talks to **46 payment gateways** through a single
driver abstraction. The full registry is in
[`GATEWAYS.md`](./GATEWAYS.md); the schema is in
[`ERD.md` 3 `PaymentGateway`](./ERD.md); the configuration
family is `payment.gateway.*` (see 10 below). This section
documents the driver interface and the per-driver SDK list.

### 5.1 `PaymentGatewayDriver` interface (Kotlin)

The `PaymentGatewayDriver` interface in
`internal/payment/PaymentGatewayDriver.kt` is implemented by
each gateway package; the rest of the service depends **only**
on this interface.

```kotlin
package internal.payment

interface PaymentGatewayDriver {

    /**
     * Create the gateway-side payment intent. Returns an
     * opaque [GatewayLocator] the driver will need for
     * subsequent authorize / capture / void / refund.
     */
    fun create(req: CreateRequest): GatewayLocator

    /** Authorize (hold funds). */
    fun authorize(req: AuthorizeRequest): GatewayResponse

    /** Capture (move funds). */
    fun capture(req: CaptureRequest): GatewayResponse

    /** Void (cancel authorization before capture). */
    fun void(req: VoidRequest): GatewayResponse

    /** Refund (partial or full, after capture). */
    fun refund(req: RefundRequest): GatewayResponse

    /**
     * Verify a gateway callback (browser redirect GET, server
     * webhook POST, or iframe postback — depending on
     * [PaymentGateway.verifyStyle]). Implementations MUST use
     * the per-gateway [PaymentGateway.signatureScheme].
     */
    fun verify(req: VerifyRequest): VerifyResult

    /** Execute a payout to a merchant / courier / driver wallet. */
    fun payout(req: PayoutRequest): PayoutResult

    /**
     * Synthetic health check. Drivers MUST implement a fast,
     * side-effect-free operation here (e.g. list accounts, get
     * gateway info, GET a known order). Result is rolled up to
     * [PaymentGateway.health].
     */
    fun probe(): ProbeResult

    /** Drain SDK clients and flush pending writes on graceful shutdown. */
    fun shutdown()
}
```

`GatewayLocator` is `Map<String, Any?>`; the shape varies per
driver (Stripe `payment_intent_id`, PayPal `order_id`, Paymob
`client_secret`, Payeer encrypted URL, etc.). The service treats
it as opaque and stores / passes it through JSONB in
`payment_intents.gateway_locator`.

### 5.2 Per-gateway SDK list

See [`GATEWAYS.md`](./GATEWAYS.md) for the per-gateway config keys,
modes, signature schemes, and verify styles. Most MENA aggregators
and crypto gateways expose plain REST with bearer tokens, so the
only required SDK is `spring-boot-starter-webflux` for the
`WebClient`. The non-plain-REST gateways are:

| Gateway | Library |
|---|---|
| `stripe` | `com.stripe:stripe-java` |
| `paypal`, `paypal_credit` | `com.paypal:checkout-sdk` |
| `payeer` | custom Rijndael-256 codec in `internal/payment/drivers/payeer/` (the upstream package uses mcrypt, which is removed in PHP 7.2+; the JVM driver re-implements) |
| `changelly` | disabled by default (see [`GATEWAYS.md` 5](./GATEWAYS.md#5-disabled--broken-gateways)) |
| `prime` | disabled by default (BigPay fallback) |
| `payrexx` | disabled until verify path is fixed |

### 5.3 Outbound manifest

The platform's outbound-call manifest (per
[`architecture/SERVICE_ISOLATION.md` "Configuration knobs"](../../architecture/SERVICE_ISOLATION.md))
declares **46 gateway entries** plus the three inter-service
entries (`ledger-service`, `fraud-risk-service`,
`customer-service`). For Kotlin/Spring Boot the manifest is
under `application.yml` → `platform.outbounds.payment-service.*`;
each gateway row carries its class (CRITICAL for authorize/capture,
DEGRADABLE for read), per-gateway timeout, retry budget, bulkhead
size, and probe URL.

## 6. Security

- **AuthN**: Keycloak resource server (Spring Security 7 for Kotlin, `coreos/go-oidc` v3 for Go, `authlib` for Python)
- **AuthZ**: RBAC (JWT scopes / roles)
- **Secrets**: external secret manager (Kubernetes External Secrets / Vault — no secret in env)
  - Per-gateway credentials live at
    `secret/payment-service/gateway/<gateway_id>/<env>` (one
    Vault path per gateway per environment, per
    [`architecture/SECURITY_ARCHITECTURE.md` 5](../../architecture/SECURITY_ARCHITECTURE.md#5-secrets)
    constraint that "API keys (provider credentials for
    payment, SMS, etc.) are issued per environment per
    provider account").
- **mTLS**: linkerd sidecar, all intra-cluster traffic

## 7. Observability

- **Tracing**: OpenTelemetry SDK 1.40+ → OTLP; one root span per attempt; child spans for each gateway call
- **Metrics**: Micrometer → Prometheus. Per-gateway labels:
  - `payment_attempt_total{gateway_id,method,currency,outcome}`
  - `payment_capture_seconds{gateway_id}` (histogram)
  - `payment_failure_rate{gateway_id,reason}` (counter)
  - `payment_refund_total{gateway_id,currency,outcome}`
  - `payment_payout_total{gateway_id,currency,outcome}`
  - `payment_gateway_health{gateway_id}` (gauge; `healthy`/`degraded`/`unreachable`)
  - `payment_gateway_probe_latency_ms{gateway_id}` (histogram)
  - `payment_gateway_error_translated_total{gateway_id,platform_code}` (counter; emits whenever a vendor code was translated via `payment_gateway_error_mapping`)
- **Logs**: structured JSON to stdout (Loki). **NEVER log PAN or
  gateway responses that may contain it.**
- **Health**: `/actuator/health` (Spring Boot Actuator 4). `/ready`
  returns `200` only if at least one enabled gateway in the
  current region reports `healthy`.

## 8. Scaling

- **HPA signal**: CPU 60% + RPS, 3–20 replicas, p99 < 500ms (per-gateway p99 measured separately)
- **Pod resources**: requests/limits set per service (see `k8s/base/payment-service/deployment.yaml`)
- **Bulkhead sizing**: per-gateway connection pool sized for peak
  RPS × per-gateway p99 latency × 2; 46 × BEST-EFFORT floor
  (25/50/500ms) = ≥ 1150 in-flight per replica just for gateway
  calls.

## 9. Local dev

- **Run**: ./gradlew bootRun
- **Test**: ./gradlew test
- **Compose profile**: `docker compose --profile payment up`
- **Mock gateways**: a single `payment-mock-gateway` container
  emulates all 46 gateway drivers behind a uniform REST API;
  selecting which gateway to mock is via the `gateway_id`
  request header. The 4 disabled gateways
  (`changelly`, `prime`, `payrexx`, `paymob.refund` —
  see [`GATEWAYS.md` 5](./GATEWAYS.md#5-disabled--broken-gateways))
  return the upstream-broken behaviour on request so the
  platform's disabled-state code paths are exercised.

## 10. Admin endpoints & RBAC

This service exposes `/admin/v1/...` endpoints for the `admin-service`
BFF and platform operators. The platform-wide admin pattern (roles,
audit format, network policy, common endpoints) is in
[`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac);
this section documents the **per-service specifics**.

### 10.1 Keycloak admin roles accepted

This service accepts admin calls from these Keycloak roles:

- `platform.super_admin`
- `platform.admin`
- `platform.finance`
- `payment.admin`
- `payment.finance`
- `payment.support`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.payment.v1`
- **Consumer**: `audit-service` (writes to its immutable `audit` schema)
- **Fields**: `actor_id`, `actor_username`, `roles`, `endpoint`,
  `target_resource`, `action`, `reason_code` (required for PII access),
  `request_id`, `trace_id`, `result`, `duration_ms`

### 10.3 Data access policy (per-service)

The platform-wide policy table is in
[RECOMMENDATIONS.md 6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide).
This service refines it as follows:

| Data class | super_admin | admin | ops | support | finance | engineering | data_eng |
|---|---|---|---|---|---|---|---|
| Payment intents (money) | ✓ | ✓ | — | — | ✓ | — | read |
| Gateway tokens | break-glass | — | — | — | — | — | — |
| PII (cardholder name, email) | ✓ | ✓ | scrubbed | scrubbed+reason | scrubbed | — | scrubbed |
| Gateway catalog (`payment_gateways`) | ✓ | ✓ | read | read | read | read | read |
| Gateway credential Vault paths (names only; no values) | break-glass | — | — | — | — | — | — |
| Per-gateway health events | ✓ | ✓ | read | read | read | read | read |
| Per-gateway error-mapping table | ✓ | ✓ | — | — | read | read | — |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/payments/{id}/force-capture` | `payment.admin` | Force a payment from `authorized` → `captured` (operational override; with audit + warning event) |
| `POST` | `/admin/v1/payments/{id}/refund` | `platform.finance` | Issue an out-of-band refund (links to `ledger-service`) |
| `GET` | `/admin/v1/payments/{id}/gateway-state` | `payment.support` | Raw gateway state (e.g. Stripe dashboard view) |
| `POST` | `/admin/v1/payments/{id}/force-state` | `payment.admin` | Force the payment state machine to a target state (with warning event) |
| `GET` | `/admin/v1/gateways` | `payment.support` | List the gateway catalog (`payment_gateways` rows) |
| `GET` | `/admin/v1/gateways/{id}` | `payment.support` | Read a single gateway row + last 10 health events |
| `POST` | `/admin/v1/gateways/{id}/activate` | `payment.admin` | Set `state='enabled'` (with audit + `payment.gateway.activated.v1`) |
| `POST` | `/admin/v1/gateways/{id}/drain` | `payment.admin` | Set `state='draining'` (no new intents assigned; existing intents finish) |
| `POST` | `/admin/v1/gateways/{id}/disable` | `payment.admin` | Set `state='disabled'` (with audit + `payment.gateway.deactivated.v1`; requires 0 in-flight intents) |
| `POST` | `/admin/v1/payments/{id}/pin-gateway/{gateway_id}` | `payment.admin` | Pin a payment intent to a specific gateway (recorded in `payment_gateway_assignments` with `source='gateway_pin'`) |
| `GET` | `/admin/v1/gateways/{id}/health-events` | `payment.support` | Read the partitioned `payment_gateway_health_events` for one gateway |
| `GET` | `/admin/v1/gateways/{id}/error-mapping` | `payment.support` | Read the per-gateway vendor-code → platform-code mapping |
| `POST` | `/admin/v1/gateways/{id}/error-mapping` | `payment.admin` | Add or update a vendor-code → platform-code row (with audit) |

### 10.5 Admin enforcement

- **Pattern**: Spring Security 7 method security (`@PreAuthorize("hasRole('platform.admin')")`) on `@RestController` mounted at `/admin/v1`
- **Network**: admin port (`8081`) is reachable only from the
  `admin-service`, `platform-ops`, and `platform-engineering`
  namespaces + bastion. Public ingress is not routed to it.
- **mTLS**: linkerd sidecar on every admin call.

### 10.6 Local admin (dev only)

- **Run with admin port**: ./gradlew bootRun --admin.port=8081
- **Test admin endpoints**: ./gradlew test --tests *AdminController*
- **Mint a dev admin JWT**: `make admin-jwt ROLE=platform.admin`

### 10.7 Super Admin preset membership

This service is included in the platform's `SUPER_ADMIN` permission
preset. An operator granted the preset receives:

- The platform-wide guard role `platform.super_admin`.
- This service's per-service admin role `payment.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` 14) and
emit `audit.admin.payment.v1` (per 10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Financial / correctness — Kotlin / Spring Boot 4 + `jOOQ`.

**External vendor SDK.** payment provider (Stripe/Adyen/Hyperpay) (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 3 *Kotlin / Spring Boot OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: jOOQ 3.20 · Spring Statemachine 5 · Spring Data JPA (read) · MapStruct.

**Extractability.** This service can be lifted out of the platform and run as a
standalone project without code changes. The minimum dependency manifest
that demonstrates this is [`SKELETON.gradle.kts`](./SKELETON.gradle.kts)
(doc-only stub; not a runnable build). The split between platform-required
and swappable dependencies is:

| Dependency class | Platform-required | Swappable |
|---|---|---|
| Language runtime | — | JDK 25 / Go 1.25 / Python 3.14 (use whatever your env needs) |
| Web/framework | `platform-spring-boot-starter` (Kotlin) / `net/http` + `chi` (Go) / FastAPI (Python) | Replace with your preferred framework |
| Database | PostgreSQL 19 (per-service schema) | H2 (in tests) / any PostgreSQL 14+ compatible |
| Migrations | Flyway 11 (Kotlin) / `golang-migrate` v4 (Go) / Alembic (Python) | Any tool that produces the same SQL |
| Cache | Redis 8 (cluster) | Caffeine (in-process) / no cache |
| Messaging | Apache Kafka 3.9 | In-process `BlockingQueue` for tests |
| Identity | Keycloak | Stub JWT verifier (JWKS = a static fixture) |
| Observability | OpenTelemetry SDK → OTLP | Logback / logrus / structlog direct to stdout |
| External vendor SDK | (per the "External" column of [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) 2) | Swap or stub at the driver boundary (`PaymentGatewayDriver`, `MapProvider`, etc.) |

**Single source of truth.** The full licence catalogue (SPDX IDs,
license-text URLs, NOTICE / THIRD-PARTY-LICENSES generation tooling,
license compatibility matrix) is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
The version pin for every library is in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
Do not pin versions in this file.

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`GATEWAYS.md`](./GATEWAYS.md) — full registry of the 46 supported gateways
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

---

> All pinned versions are in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
> Admin endpoints, roles, and audit conventions are pinned in
> [`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac).
> To bump versions or change the admin pattern, open a PR against the
> corresponding section — never pin versions directly in this file.

## Conductor SDK

This service participates in Conductor workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).

- **SDK**: `io.conductor:conductor-client:3.x (Kotlin/Spring)`
- **License**: Apache-2.0 (Netflix Conductor OSS)
- **Worker registration model**: workers are colocated in this service's binary; each task implementation is annotated `@ConductorTask(<task_name>)` and registers at startup with the Conductor server via `ConductorClient.startWorkers(...)`.
- **Connection settings** (Helm-injected, per env):
  - `conductor.server.url` — e.g. `https://conductor.prod.uber.io`
  - `conductor.task.<task_name>.timeout_seconds` — default 30s
  - `conductor.task.<task_name>.retry_count` — default 3
  - `conductor.worker.heartbeat_interval_seconds` — default 5s
  - `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration
- **Operational references**: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 8 (runbook), 7 (observability); [`MASTER_TASK.md`](../../MASTER_TASK.md) 7-9 for per-service task IDs.
