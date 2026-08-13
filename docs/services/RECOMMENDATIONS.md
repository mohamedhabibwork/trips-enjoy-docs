# Service Technology Recommendations

> **Scope.** Every service in `docs/services/` gets one recommended
> **language + framework** pair, justified by its workload profile.
> The choice respects the locked baseline in `main.md` (PostgreSQL 19,
> Keycloak, REST, Kafka/RabbitMQ, Redis, Docker, Kubernetes, OpenAPI 3.x)
> and only addresses the *implementation* language and web/data framework,
> which `main.md` deliberately leaves open.
>
> **Open-source attribution.** This document is the platform's
> *version pin*. The matching SPDX license catalogue (what each
> library is licensed under, NOTICE / THIRD-PARTY-LICENSES
> generation, license compatibility matrix) lives in
> [`../shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md).
> Every service's `TECH.md` 11 *Open-source bundle* references
> that catalogue.

---

## 1. Selection philosophy

We pick the language/framework that best matches the **workload profile**
of the service, not the personal preference of the team. Five profiles
dominate the catalog:

| Profile | What it means | Default pick |
|---|---|---|
| **Edge / hot path** | TLS termination, JWT validation, request routing, location ingestion, ETA calls — thousands-to-millions of req/s, sub-10 ms p99, small memory footprint, fast cold start. | **Go** + a thin framework |
| **Business core** | CRUD over PostgreSQL, transactional workflows, REST APIs, RBAC, integration with Keycloak. Needs strong typing, mature ORM, robust concurrency, ACID discipline. | **Kotlin** + Spring Boot 4 |
| **Financial / correctness** | Money movement, double-entry ledger, tax math, idempotent state machines. Needs immutability, exhaustive pattern matching, decimal precision, and battle-tested data integrity. | **Kotlin** + Spring Boot 4 (with `BigDecimal` + `jOOQ`/`Exposed`) |
| **Math / scoring / ML** | Real-time risk scoring, dispatch matching, route math. Heavy CPU, mature math/ML libraries, acceptable to run slightly slower per request. | **Python** + FastAPI (with NumPy/scikit-learn) — or **Rust** for the inner loop |
| **Streaming / event ingest** | High-throughput Kafka consumer/producer, schema evolution, Parquet landing. Throughput + backpressure + exactly-once. | **Kotlin** + Spring Kafka **or** **Go** + `segmentio/kafka-go` |

Rule of thumb that follows from the table:

- **Go** for the things that *scale by request count*: edge, ingest, fan-out.
- **Kotlin / Spring** for the things that *scale by domain complexity*: business cores, financial cores.
- **Python** for the things that *scale by model complexity*: scoring, ML, analytics, reporting UIs.
- **Rust** reserved for a *single inner loop* (e.g. dispatch scoring kernel) only if profiling shows JVM/Python is the bottleneck — not as a default.

Every service ships a REST API in OpenAPI 3.x, uses Keycloak for
authentication, PostgreSQL 19 for state, Kafka for async, and Redis for
caching. The choices below are the *implementation layer*, not the
contract.

---

## 2. Master recommendation table

Sorted by service directory. **`L`** = language, **`F`** = framework,
**`Libs`** = the 2–4 most important concrete dependencies (beyond the
framework itself), **`DB`** = PostgreSQL 19 schema name, **`Cache`** =
Redis pattern, **`External`** = third-party SDK / vendor, **`HPA`** =
horizontal-pod-autoscaler signal + replica range + p99 target. "Profile"
maps to 1. The per-service detail (container image, ORM choice, build
commands, full library list, etc.) lives in each service's
`TECH.md`, linked from the **`File`** column.

> **Scaffolding.** Every Kotlin + Spring Boot 4 service is
> scaffolded from [Spring Initializr](https://start.spring.io/)
> using the canonical recipe in
> [`SPRING_INITIALIZR.md`](./SPRING_INITIALIZR.md) (per
> [ADR-0023](../architecture/adrs/0023-spring-initializr-scaffolding.md)).
> The Initializr scaffold supplies the generic Spring Boot 4
> build + the standard dependency set; the service then adds
> `com.trips-enjoy.platform:spring-boot-starter` (per
> [`../shared/INTEGRATION.md`](../shared/INTEGRATION.md)) for the
> cross-cutting concerns.

| # | Service | Profile | L | F | Libs | DB | Cache | External | HPA | File |
|---:|---|---|---|---|---|---|---|---|---|---|
| 1 | ``customer-service` (addresses)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `address` | Redis — by-user list (TTL 1h) | — | CPU 60%, 2–10, p99 < 200ms | [TECH](./customer-service/TECH.md) |
| 2 | `admin-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Security 7 · MapStruct | `admin` | — | aggregates internal services | CPU 60%, 2–5, p99 < 500ms | [TECH](./admin-service/TECH.md) |
| 3 | ``reporting-service` (data lake)` | Streaming / event ingest | Kotlin | Spring Boot 4 + Spring Kafka | Spring Kafka 4 · Apicurio (Avro) · AWS SDK v2 (S3) · Snowflake JDBC | `analytics_meta` (offsets only) | — | S3 · Snowflake/BigQuery/Redshift | Kafka consumer lag, 3–15, 50k evt/s | [TECH](./reporting-service/TECH.md) |
| 4 | `api-gateway` | Edge / hot path | **Go** | `net/http` + `chi` | `go-chi/chi v2` · `coreos/go-oidc v3` · `go-redis/redis v9` · `prometheus/client_golang` | — (stateless) | Redis — rate-limit counters, JWKS cache | Keycloak JWKS | RPS, 5–100, p99 < 5ms | [TECH](./api-gateway/TECH.md) |
| 5 | `audit-service` | Streaming / event ingest | Kotlin | Spring Boot 4 + Spring Kafka | Spring Kafka 4 · Spring Data JPA · Flyway | `audit` (append-only, monthly partition) | — | S3 (cold archive) | Kafka consumer lag, 2–8, 20k evt/s | [TECH](./audit-service/TECH.md) |
| 6 | ``restaurant-service` (branch)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · Flyway | `branch` | Redis — country/region tree (TTL 24h) | — | CPU 60%, 2–5, p99 < 100ms | [TECH](./restaurant-service/TECH.md) |
| 7 | ``food-order-service` (cart)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Data Redis · MapStruct · Flyway | `cart` | Redis — active cart (TTL 30m) | — | CPU 60%, 2–10, p99 < 200ms | [TECH](./food-order-service/TECH.md) |
| 8 | ``food-order-service` (checkout)` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Statemachine 5 · MapStruct | `checkout` | Redis — idempotency, distributed lock | pricing · payment · ledger · tax | CPU 60%, 3–15, p99 < 500ms | [TECH](./food-order-service/TECH.md) |
| 9 | ``notification-service` (provider ACL)` | Edge / hot path | **Go** | `net/http` + `chi` | `go-chi/chi v2` · `go-redis/redis v9` · `segmentio/kafka-go` · `prometheus/client_golang` | — (stateless) | Redis — delivery receipts, dedup | FCM · APNs · Twilio · AWS SES | RPS, 3–50, p99 < 100ms | [TECH](./notification-service/TECH.md) |
| 10 | `configuration-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · Flyway | `configuration` | Redis — long-poll / push-invalidate | — | CPU 60%, 2–5, p99 < 50ms | [TECH](./configuration-service/TECH.md) |
| 11 | ``courier-service` (dispatch)` | Math / scoring | **Python** | FastAPI + NumPy | FastAPI 0.115+ · Pydantic 2 · NumPy 2 · `aiokafka` · `asyncpg` | `courier_dispatch` | Redis — match attempts (TTL 5m) | — | RPS, 2–8, p99 < 200ms | [TECH](./courier-service/TECH.md) |
| 12 | ``payment-service` (courier earnings)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Batch · MapStruct · Flyway | `courier_earnings` | — | ledger (read) | CPU 60%, 2–5, p99 < 1s | [TECH](./payment-service/TECH.md) |
| 13 | `courier-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Security 7 · MapStruct · Flyway | `courier` | Redis — claim cache (TTL 5m) | identity (Keycloak) | CPU 60%, 2–5, p99 < 200ms | [TECH](./courier-service/TECH.md) |
| 14 | ``courier-service` (tracking)` | Edge / hot path | **Go** | `net/http` + WebSocket | `coder/websocket` · `go-redis/redis v9` (Redis GEO) · `prometheus/client_golang` | — (fan-out in Redis) | Redis — courier geo index, last-N trail | — | RPS, 3–30, p99 < 5ms | [TECH](./courier-service/TECH.md) |
| 15 | `customer-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `customer` | Redis — profile (TTL 5m) | identity | CPU 60%, 2–10, p99 < 200ms | [TECH](./customer-service/TECH.md) |
| 16 | ``courier-service` (delivery)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Statemachine 5 · MapStruct · Flyway | `delivery` | Redis — state mirror | — | CPU 60%, 2–10, p99 < 200ms | [TECH](./courier-service/TECH.md) |
| 17 | ``driver-service` (dispatch)` | Math / scoring | Kotlin | Spring Boot 4 (WebFlux) | Spring WebFlux · Spring Statemachine 5 · Spring Data R2DBC · MapStruct | `dispatch` (R2DBC) | Redis — match attempts, eligibility ring | driver-location · eta-routing | RPS + queue depth, 3–30, p99 < 100ms | [TECH](./driver-service/TECH.md) |
| 18 | ``driver-service` (availability)` | Edge / hot path | **Go** | `net/http` + `chi` | `pgx v5` · `go-redis/redis v9` · `prometheus/client_golang` | `driver_availability` | Redis — online/availability state | — | RPS, 2–20, p99 < 20ms | [TECH](./driver-service/TECH.md) |
| 19 | ``payment-service` (driver earnings)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Batch · MapStruct · Flyway | `driver_earnings` | — | ledger (read) | CPU 60%, 2–5, p99 < 1s | [TECH](./payment-service/TECH.md) |
| 20 | ``driver-service` (incentives)` | Math / scoring | **Python** | FastAPI + NumPy | FastAPI 0.115+ · Pydantic 2 · NumPy 2 · Pandas 2 · `aiokafka` | `driver_incentive` | Redis — active bonuses (TTL 5m) | — | RPS, 2–8, p99 < 200ms | [TECH](./driver-service/TECH.md) |
| 21 | ``driver-service` (location)` | Edge / hot path | **Go** | `net/http` + `chi` | `pgx v5` · `go-redis/redis v9` (Redis GEO) · `prometheus/client_golang` | `driver_location` (daily partition) | Redis — current location GEO index | — | RPS, 5–80, p99 < 5ms | [TECH](./driver-service/TECH.md) |
| 22 | `driver-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Security 7 · MapStruct · Flyway | `driver` | Redis — profile (TTL 5m) | identity | CPU 60%, 2–10, p99 < 200ms | [TECH](./driver-service/TECH.md) |
| 23 | ``geolocation-service` (ETA/routing)` | Edge / hot path | **Go** | `net/http` + `chi` | `go-redis/redis v9` · `resty` (HTTP client) · `prometheus/client_golang` | — (cache only) | Redis — ETA + route cache (TTL 60s, surge-aware) | map provider (Google/Mapbox/HERE) | RPS, 3–40, p99 < 50ms | [TECH](./geolocation-service/TECH.md) |
| 24 | ``configuration-service` (flags)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `feature_flag` | Redis — flag evaluation (TTL 30s, push-invalidate) | — | CPU 60%, 2–8, p99 < 20ms | [TECH](./configuration-service/TECH.md) |
| 25 | `file-service` | Edge / hot path | **Go** | `net/http` + `chi` | `aws-sdk-go-v2` (S3) · `go-redis/redis v9` · `prometheus/client_golang` | `file` (metadata only) | Redis — upload session state | S3 · ClamAV | RPS, 3–30, p99 < 100ms | [TECH](./file-service/TECH.md) |
| 26 | `food-order-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Statemachine 5 · MapStruct · Flyway | `food_order` | Redis — cart/order dedup | restaurant · menu | CPU 60%, 2–10, p99 < 300ms | [TECH](./food-order-service/TECH.md) |
| 27 | ``payment-service` (food saga)` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Statemachine 5 · Spring Data JPA (read) | `food_payment_integration` | Redis — saga state, idempotency | payment · ledger | CPU 60%, 3–15, p99 < 500ms | [TECH](./payment-service/TECH.md) |
| 28 | `fraud-risk-service` | Math / scoring / ML | **Python** | FastAPI + scikit-learn | FastAPI 0.115+ · Pydantic 2 · scikit-learn 1.6 · NumPy 2 · MLflow client · `asyncpg` | `fraud_risk` | Redis — device fingerprint, blocklists | device fingerprint · threat intel | RPS + model latency, 3–20, p99 < 100ms | [TECH](./fraud-risk-service/TECH.md) |
| 29 | `geolocation-service` | Edge / hot path | **Go** | `net/http` + `chi` | `pgx v5` (PostGIS) · `go-redis/redis v9` · `resty` | `geolocation` (PostGIS) | Redis — geocode (TTL 30d), last-city (TTL 7d) | map provider (Google/Mapbox/HERE) | RPS, 3–30, p99 < 30ms (cache hit) | [TECH](./geolocation-service/TECH.md) |
| 30 | `identity-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Security 7 (Keycloak) · Spring Cache | `identity` (Keycloak mirror) | Redis — claim cache (TTL 5m, event-invalidate) | Keycloak | CPU 60%, 2–8, p99 < 50ms | [TECH](./identity-service/TECH.md) |
| 31 | ``restaurant-service` (inventory)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `inventory` | Redis — stock counts (TTL 1m, push-invalidate) | menu | CPU 60%, 2–8, p99 < 200ms | [TECH](./restaurant-service/TECH.md) |
| 32 | `ledger-service` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Data JPA (read) · MapStruct | `ledger` (append-only, monthly partition, `BigDecimal`) | — | — | CPU 60%, 3–15, p99 < 500ms | [TECH](./ledger-service/TECH.md) |
| 33 | ``pricing-service` (loyalty rules) / `customer-service` (account)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Statemachine 5 · MapStruct · Flyway | `loyalty` | Redis — points balance (TTL 1m) | — | CPU 60%, 2–5, p99 < 200ms | [TECH — pricing](./pricing-service/TECH.md) · [TECH — customer](./customer-service/TECH.md) |
| 34 | ``restaurant-service` (menu)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache (Caffeine + Redis) · Flyway | `menu` (`pg_trgm`) | Redis — menu tree (TTL 1h, push-invalidate) | file (photos) | CPU 60%, 2–10, p99 < 200ms | [TECH](./restaurant-service/TECH.md) |
| 35 | ``restaurant-service` (merchant)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `merchant` | Redis — profile (TTL 5m) | identity | CPU 60%, 2–5, p99 < 200ms | [TECH](./restaurant-service/TECH.md) |
| 36 | `notification-service` | Business core | Kotlin | Spring Boot 4 + Spring Kafka | Spring Data JPA · Spring Kafka 4 · Spring Cache · Flyway | `notification` | Redis — dedup window, suppression rules | communication-gateway | CPU 60% + Kafka lag, 3–20, p99 < 500ms | [TECH](./notification-service/TECH.md) |
| 37 | `payment-service` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Statemachine 5 · Spring Data JPA (read) · MapStruct | `payment` | Redis — idempotency, webhook dedup | payment provider (Stripe/Adyen/Hyperpay) | CPU 60% + RPS, 3–20, p99 < 500ms | [TECH](./payment-service/TECH.md) |
| 38 | `pricing-service` | Math / scoring | Kotlin | Spring Boot 4 (coroutines) | Spring WebFlux (coroutines) · Spring Data R2DBC · MapStruct | `pricing` | Redis — tariff snapshot (TTL 60s, push-invalidate) | configuration | RPS, 3–30, p99 < 50ms | [TECH](./pricing-service/TECH.md) |
| 39 | ``pricing-service` (promotion)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `promotion` | Redis — active promotion set (TTL 5m) | — | CPU 60%, 2–5, p99 < 200ms | [TECH](./pricing-service/TECH.md) |
| 40 | `reporting-service` | Streaming / event ingest | **Python** | FastAPI + Pandas | FastAPI 0.115+ · Pydantic 2 · Pandas 2 · `aiokafka` · SQLAlchemy 2.0 (async) | `reporting` (read models) | Redis — query result cache | S3 (export) | CPU 60% + Kafka lag, 2–8, p99 < 2s | [TECH](./reporting-service/TECH.md) |
| 41 | ``food-order-service` (queue)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Statemachine 5 · MapStruct · Flyway | `restaurant_order_mgmt` | Redis — kitchen ticket state | — | CPU 60%, 2–10, p99 < 200ms | [TECH](./food-order-service/TECH.md) |
| 42 | `restaurant-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `restaurant` (`pg_trgm`) | Redis — profile (TTL 5m) | — | CPU 60%, 2–10, p99 < 200ms | [TECH](./restaurant-service/TECH.md) |
| 43 | ``payment-service` (merchant settlement)` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Batch · Spring Data JPA (read) · MapStruct | `restaurant_settlement` | Redis — daily totals (TTL 5m) | ledger (read) | CPU 60%, 2–5, p99 < 5s (batch) | [TECH](./payment-service/TECH.md) |
| 44 | ``restaurant-service` (staff)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `restaurant_staff` | — | identity | CPU 60%, 2–5, p99 < 200ms | [TECH](./restaurant-service/TECH.md) |
| 45 | ``trip-service` / `food-order-service` / `search-service` (review projections)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `review_rating` | Redis — aggregate ratings (TTL 1h) | — | CPU 60%, 2–5, p99 < 300ms | [TECH — trip](./trip-service/TECH.md) · [TECH — food](./food-order-service/TECH.md) · [TECH — search](./search-service/TECH.md) |
| 46 | ``trip-service` (history)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `ride_history` (monthly partition) | Redis — recent trips (TTL 5m) | — | CPU 60%, 2–8, p99 < 500ms | [TECH](./trip-service/TECH.md) |
| 47 | ``payment-service` (ride saga)` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Statemachine 5 · Spring Data JPA (read) | `ride_payment_integration` | Redis — saga state | payment · wallet · ledger | CPU 60%, 3–15, p99 < 500ms | [TECH](./payment-service/TECH.md) |
| 48 | ``trip-service` (ride-request)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Statemachine 5 · MapStruct · Flyway | `ride_request` | Redis — quote hold, idempotency | pricing · dispatch | CPU 60%, 3–20, p99 < 200ms | [TECH](./trip-service/TECH.md) |
| 49 | ``trip-service` (safety)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `ride_safety` | Redis — active trip-share sessions | communication-gateway · file | CPU 60%, 2–5, p99 < 300ms | [TECH](./trip-service/TECH.md) |
| 50 | ``trip-service` (scheduled)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Scheduling (Quartz) · MapStruct · Flyway | `scheduled_ride` | Redis — soon-to-fire sorted set | ride-request | CPU 60%, 2–5, p99 < 500ms | [TECH](./trip-service/TECH.md) |
| 51 | `search-service` | Business core | Kotlin | Spring Boot 4 + Spring Data OpenSearch | Spring Data OpenSearch 6 · Spring Data JPA · OpenSearch Java client | `search` (small metadata) | Redis — query result cache (TTL 30s) | OpenSearch (in-cluster) | CPU 60%, 3–15, p99 < 200ms | [TECH](./search-service/TECH.md) |
| 52 | ``admin-service` (support module)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `support` | Redis — agent presence | file · communication-gateway | CPU 60%, 2–5, p99 < 300ms | [TECH](./admin-service/TECH.md) |
| 53 | ``pricing-service` (tax)` | Math / scoring | Kotlin | Spring Boot 4 (coroutines) | Spring WebFlux (coroutines) · Spring Data R2DBC · MapStruct | `tax` | Redis — rule snapshot (TTL 24h, push-invalidate) | configuration | CPU 60%, 2–8, p99 < 100ms | [TECH](./pricing-service/TECH.md) |
| 54 | `trip-service` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Statemachine 5 · MapStruct · Flyway | `trip` | Redis — active trip state | ride-request | CPU 60%, 2–10, p99 < 200ms | [TECH](./trip-service/TECH.md) |
| 55 | ``customer-service` (cross-persona profile)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `user_profile` | Redis — profile (TTL 5m) | identity · file | CPU 60%, 2–10, p99 < 200ms | [TECH](./customer-service/TECH.md) |
| 56 | ``driver-service` (vehicles)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · MapStruct · Flyway | `vehicle` | Redis — vehicle (TTL 5m) | file | CPU 60%, 2–5, p99 < 200ms | [TECH](./driver-service/TECH.md) |
| 57 | ``payment-service` (wallet)` | Financial / correctness | Kotlin | Spring Boot 4 + `jOOQ` | jOOQ 3.20 · Spring Data JPA (read) · Spring Statemachine 5 · MapStruct | `wallet` | Redis — balance (TTL 30s, push-invalidate) | ledger (read) | CPU 60% + RPS, 3–20, p99 < 200ms | [TECH](./payment-service/TECH.md) |
| 58 | ``geolocation-service` (zones)` | Business core | Kotlin | Spring Boot 4 | Spring Data JPA · Spring Cache · Hibernate Spatial (PostGIS) · Flyway | `zone` (PostGIS) | Redis — active polygons (TTL 1h, push-invalidate) | file (zone shape imports) | CPU 60%, 2–5, p99 < 200ms | [TECH](./geolocation-service/TECH.md) |
| 59 | **`chat-service`** *(Phase 7.7)* | **Edge / hot path** | **Go** | `net/http` + `chi` | `coder/websocket` · `pgx v5` · `go-redis/redis v9` (Pub/Sub) · `segmentio/kafka-go` · `golang-migrate v4` · `coreos/go-oidc v3` | `chat` (monthly partition on `chat.messages`) | Redis — presence, typing, rate-limit, fan-out | Keycloak (JWT) · `file-service` (attachments) | WS connections > 5 000 / replica, 6–40, p99 < 200 ms send, < 80 ms fan-out | [TECH — chat](./chat-service/TECH.md) |

### Tally

| Language | Count | Where it dominates |
|---|---:|---|
| Kotlin (Spring Boot 4) | 46 | Business cores, financial cores, integration sagas, rules engines |
| Go (net/http + chi) | 9 | Edge, hot path, ingest, fan-out (incl. `chat-service` Phase 7.7) |
| Python (FastAPI) | 4 | Math, ML, scoring, analytics, reporting |
| Rust | 0 as a default, 1 as an optional inner loop | Reserved for `fraud-risk-service` scoring kernel if profiling demands |
| **Total** | **59** | |

---

## 3. Per-cluster rationale

### 3.1 Edge & hot path — Go

**Services:** `api-gateway`, ``driver-service` (location)`, ``courier-service` (tracking)`,
``driver-service` (availability)`, ``geolocation-service` (ETA/routing)`, `geolocation-service`,
`file-service`, ``notification-service` (provider ACL)`, **`chat-service`** *(Phase 7.7)*.

These nine services have one thing in common: they are **bound by request
count and latency**, not by domain logic. A Go binary gives us:

- **Memory footprint ~10–30 MB** vs 150–300 MB for a JVM service — lets us
  run 5–10× the pod count on the same node pool.
- **Sub-millisecond p99** for cache-hit paths (geolocation, ETA, file
  pre-signed URL mint).
- **Goroutines** model fan-out (one per WebSocket driver, one per
  notification push) without the cost of a thread per connection.
- **Fast cold start** — matters for HPA scale-out during traffic spikes
  (e.g. Friday-evening ride surge).

Framework choice is deliberately minimal: `net/http` + `chi` (or
`gorilla/mux` if the team prefers). We do **not** need a full Spring
Boot–equivalent for a service that mostly routes, validates a JWT, and
forwards headers. `pgx` for PostgreSQL, `segmentio/kafka-go` for Kafka,
`go-redis/redis` for Redis — all from the same vendor-aligned family.

### 3.2 Business core — Kotlin + Spring Boot 4

**Services:** 35+ services across customers, drivers, restaurants,
merchants, orders, carts, checkouts, search, support, etc.

These are the **domain-heavy** services. Their bottleneck is *developer
velocity against domain complexity*, not raw throughput. Kotlin + Spring
Boot 3 wins because:

- **Strong static typing + null safety** — fewer NPEs in business logic.
- **Coroutines + WebFlux** available for the few that need it
  (``driver-service` (dispatch)`, `pricing-service`); the rest stay on classic MVC.
- **Mature ecosystem**: Spring Data JPA / `jOOQ`, Spring Security with
  Keycloak resource server, Spring Kafka, Spring Cache, Spring Mail,
  Spring Statemachine — every problem in this catalog has a Spring
  answer that is one major version old and battle-tested.
- **Hibernate / `jOOQ` / Exposed** all speak PostgreSQL 19 natively,
  including JSONB, range types, and partitioning.
- **Test ergonomics**: JUnit 5, MockK, Testcontainers — straightforward
  in CI.

If the team later wants to migrate some of these to **Ktor**, that is
fine — but Spring Boot 4 is the *default* because every new hire has
seen it and every Stack Overflow answer is in Java/Kotlin.

### 3.3 Financial / correctness — Kotlin + Spring Boot 4 + `jOOQ`

**Services:** `payment-service`, ``payment-service` (wallet)`, `ledger-service`,
``food-order-service` (checkout)`, ``payment-service` (food saga)`,
``payment-service` (ride saga)`, ``payment-service` (merchant settlement)`,
``pricing-service` (tax)` (tax math), ``pricing-service` (promotion)` (redemption accounting).

Same stack as business cores, but with three **non-negotiable
additions**:

1. **`jOOQ` for SQL** instead of (or alongside) JPA. Financial
   correctness demands seeing the exact SQL that hits the database;
   `jOOQ`'s type-safe DSL is the right tool.
2. **`BigDecimal` everywhere** for money, with a project-wide
   `Money` value class to prevent accidental `double` usage.
3. **Database-enforced invariants** — `CHECK` constraints for
   non-negative balances, foreign keys within the service's own
   schema, and idempotency tables with unique constraints on the
   provider's request id.

These services get **double review** on any schema migration and are
the only services allowed to write to the `ledger-service` via its
documented command API.

> **Platform margin doctrine (locked 2026-08-07).** Three of the
> services in this cluster — `payment-service`, `ledger-service`, and
> `pricing-service` — jointly implement the platform's financial
> doctrine documented in
> [`../shared/TYPE_CATALOG.md` 8.7](../shared/TYPE_CATALOG.md#87-platform-margin-doctrine--20--1currency--dynamic-multiplier):
>
> - **Driver payout is calculated on `gross_fare`** (pre-discount), not
>   on the customer-facing `net_fare`. Discounts MUST NOT be netted
>   against `driver_payable`.
> - **All customer-facing discounts are 100% platform-borne** (loyalty,
>   promo, geo-override, surge-capped, OD-corridor). They post as
>   **platform expense** lines (`6310_promotion_discount`, the proposed
>   `6311_loyalty_discount`), not as a contra to `driver_payable` or as a
>   revenue reducer on `4100_commission_revenue`.
> - **Platform commission** = `0.20 × gross_fare + 1 {currency}`. The
>   `+ 1 {currency}` flat surcharge is declared per-currency in
>   `pricing.commission.flat_minor.{currency}`.
>
> The two locked config keys — `pricing.commission.base = gross` and
> `pricing.discount_bearer = platform` — are **immutable until an ADR
> ratifies a flip** (canonical via
> [`../architecture/adrs/0001-microservices-architecture.md`](../architecture/adrs/0001-microservices-architecture.md)).
> Treat the doctrine as the **authoritative intent** for new code and
> the **target state** for migration of existing code.

### 3.4 Math / scoring / ML — Python + FastAPI

**Services:** `fraud-risk-service`, ``courier-service` (dispatch)`,
``driver-service` (incentives)`, `reporting-service` (with Pandas).
(``pricing-service` (tax)` uses the same approach — pure rules over `BigDecimal` —
but its workload is dominated by *transactional* integration with
`pricing-service` and ``food-order-service` (checkout)`, so it ships on Kotlin +
Spring Boot 4 in the default stack; see 3.3.)

We pick Python when the **workload is dominated by math, models, or
data wrangling**, not by request count. FastAPI is fast enough for
the request volumes in this catalog and gives us:

- **First-class ML libraries**: scikit-learn, XGBoost, LightGBM,
  statsmodels, Prophet — all in the fraud-risk and incentive services.
- **Numerical libraries**: NumPy, Pandas — coupon/incentive math,
  reporting rollups, BI exports.
- **Jupyter-notebook friendly** for data scientists iterating on
  risk models and incentive curves.
- **Native Kafka clients** (`confluent-kafka-python`, `aiokafka`).

If profiling shows a hot inner loop (e.g. dispatch matching scoring
kernel) where Python's GIL is the bottleneck, that **single loop** is
re-implemented as a Rust extension (PyO3) or as a small standalone
Rust microservice. The default is **not** Rust for the whole service —
the productivity cost is too high for a domain with this much churn.

### 3.5 Streaming / event ingest — Kotlin + Spring Kafka

**Services:** ``reporting-service` (data lake)`, `audit-service`, `notification-service`
(outbox + Kafka producer side). `reporting-service` is the read-model
side of this group and ships in Python (see 3.4).

These services are **driven by the event bus, not by user requests**.
Spring Kafka (or `segmentio/kafka-go` for the Go ingest services) gives
us:

- **Consumer groups**, **exactly-once semantics** (when paired with
  PostgreSQL outbox + transactional Kafka producer), and **DLQ** out
  of the box.
- **Schema evolution** via the platform's Confluent/Apicurio registry.
- **Backpressure** and **batch consumption** for high-throughput topics
  (`driver.location.v1`, `audit.api.request.v1`).

The consumer/producer framework is the *only* reason these services
might choose a language other than Kotlin — and we keep them on Kotlin
for the same reasons as business cores (PostgreSQL writes, RBAC,
OpenAPI for any synchronous read APIs the service exposes).

### 3.6 Search & coordination

**Service:** `search-service`.

`search-service` is a **coordination authority** over OpenSearch — it
indexes domain events, exposes a query DSL, and reindexes on schema
change. It is **not** a search engine. So the language choice follows
the same rule as business cores: Kotlin + Spring Boot 4 + Spring Data
OpenSearch. The actual search engine is OpenSearch itself; we only
write the code that talks to it.

---

## 4. Why not … ?

The choices above are the **default** for the platform. Listed here are
the alternatives we deliberately reject as defaults, with the reason.

### Why not Java instead of Kotlin?

Kotlin is a strict superset of Java's expressiveness: null safety, data
classes, sealed classes for state machines, coroutines, value classes
(`@JvmInline value class Money(val minor: Long)`), and concise syntax.
Every Java library works in Kotlin, every Java developer onboards to
Kotlin in a week, and the JVM toolchain is identical. The only
disadvantage — slightly less Stack Overflow volume — is more than made
up for by Kotlin's own docs and the modern Spring guides being
Kotlin-first.

### Why not Node.js / TypeScript for the backend?

The platform's backend apps are **Go, Kotlin, or Python only** — there is no
Node.js / TypeScript on the backend. The reasoning is the same as
above for the business cores (significant CPU work + JVM-only
libraries like Hibernate spatial, Spring Statemachine, JDK 25
virtual threads) plus a tighter "one stack per layer" rule: backend
= Go, Kotlin, or Python; edge / hot path = Go; business + financial cores =
Kotlin + Spring Boot 4; streaming / ML = Python + FastAPI; frontend
tooling (Vue 3 component library, Nuxt 3 SSR, Vite) is the only
Node.js consumer and lives in the web repo, not in the backend.

### Why not Go for the business cores?

Go is excellent at scale, but its type system (no generics for sum
types, no exhaustive `when`, no value classes) makes modelling complex
aggregates — payment intents, ledger entries, ride state machines —
painful. The productivity loss on the ~50% of services that are
domain-heavy is not worth the throughput gain we don't need.

### Why not Rust as a default for hot path?

Rust is faster than Go and uses less memory, but the **team size and
hiring pipeline** for Rust is roughly an order of magnitude smaller
than for Go or Kotlin. For the eight hot-path services, Go gets us to
within 1.3× of Rust's throughput at a fraction of the engineering
cost. We keep Rust on the menu for **single hot inner loops** that
empirically need it (e.g. `fraud-risk-service` scoring kernel),
delivered as a Python extension or a standalone microservice.

### Why not Python everywhere?

Python is slow per request and uses a lot of memory under load. For
the 53 services that are not model-driven, the productivity win from
Python does not offset the runtime cost on Kubernetes.

### Why not Elixir / Erlang?

Genuinely excellent for high-concurrency, but the **hiring market** and
**library ecosystem** in our geography are thin. PostgreSQL drivers,
Keycloak clients, payment-provider SDKs, and observability agents are
all first-class in Go and Kotlin; Elixir often requires wrapping a
Java library or writing a NIF. The cost-benefit is not there for this
stack.

### Why not a single language?

Because the **workload profiles are genuinely different** and pretending
they aren't leads to either over-engineering (Rust for everything,
hiring pain) or under-engineering (Node for everything, CPU-bound
pricing math becomes a bottleneck). A microservices platform is
*exactly* the place where the right tool per job pays off.

---

## 5. Cross-cutting tooling (language-agnostic)

These choices are made once at the platform level and apply to every
service regardless of language. **All versions are pinned to the
current latest stable release as of this document's revision** —
bump on the first day of every month, no exceptions. The platform
team owns the `gradle/libs.versions.toml`, `go.mod` template, and
`pyproject.toml` template that every service inherits.

### 5.1 Version baseline (pinned, latest stable)

> **License attribution.** The versions listed below are the platform's
> *version pin*. The SPDX license identifier for each library, the
> license-text URL, and the standard NOTICE / THIRD-PARTY-LICENSES
> practice every service must follow is in
> [`../shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md).
> Bump a version here; update the catalogue there only if the
> license *class* changes.

#### JVM / Kotlin stack (Spring Boot 4 line)

| Component | Pinned version | Notes |
|---|---|---|
| JDK | **25 (LTS)** | First-class virtual threads + structured concurrency; minimum 21 supported. |
| Kotlin | **2.2.x** | K2 compiler default; value classes, context receivers, exhaustive `when`. |
| Spring Boot | **4.x (latest 4.1.x)** | Built on Spring Framework 7, Jakarta EE 11, Hibernate 7. |
| Spring Framework | **7.x** | Pulled in by Spring Boot 4. |
| Spring Security | **7.x** | New `oauth2-resource-server` DSL aligned with Spring Boot 4. |
| Spring Kafka | **4.x** | Aligned with Spring Boot 4; transactional outbox + EOS by default. |
| Spring Statemachine | **5.x** | Aligned with Spring Boot 4 / Spring Framework 7. |
| Spring Data OpenSearch | **6.x** | Aligned with Spring Boot 4. |
| Hibernate ORM | **7.x** | Default JPA provider in Spring Boot 4. |
| `jOOQ` | **3.20.x** (or 4.x if available) | Type-safe SQL for the 7 financial services. |
| Exposed | **1.0.x** (or latest 0.5x) | Lightweight DSL alternative to JPA; choose per service. |
| Flyway | **11.x** | Versioned migrations targeting PostgreSQL 19. |
| Testcontainers | **1.21.x** | JUnit 5 integration for ephemeral PostgreSQL 19 / Kafka / Redis / Keycloak. |
| JUnit 5 | **5.11.x** | Jupiter + Vintage. |
| MockK | **1.13.x** | Kotlin-first mocking. |
| Gradle | **9.x** (Kotlin DSL) | Wrapper bundled in every service template. |
| `ktlint` | **1.5.x** | Single source of Kotlin formatting truth. |
| `detekt` | **1.23.x** | Static analysis on top of `ktlint`. |

> **Platform-margin configuration keys (locked 2026-08-07, JVM stack
> concern).** The financial / correctness services — `pricing-service`,
> `payment-service`, `ledger-service` — read four locked configuration
> keys via `configuration-service`. These keys live in
> `pricing.commission.*` and `pricing.discount_bearer` and are
> **immutable until an ADR ratifies a flip**. See
> [`../shared/TYPE_CATALOG.md` 8.7](../shared/TYPE_CATALOG.md#87-platform-margin-doctrine--20--1currency--dynamic-multiplier)
> for the full contract.
>
> | Key | Type | Locked value | Owner |
> |---|---|---|---|
> | `pricing.commission.pct` | decimal | `0.20` | `pricing-service` |
> | `pricing.commission.flat_minor.{currency}` | int (minor units) | `{currency: 100}` minor per currency (e.g. `100` = 1.00 SAR) | `pricing-service` |
> | `pricing.commission.base` | enum | **`gross`** (locked) | `pricing-service` |
> | `pricing.discount_bearer` | enum | **`platform`** (locked) | `pricing-service` |
>
> The JVM stack does **not** need a new library to support these keys —
> they are plain `application.yml` / `configuration-service` values read
> at startup and on `configuration.updated.v1` reload events. The
> financial-correctness contract is in the **call sites** (see
> 3.3 above), not in the dependency choice.

#### Go stack

| Component | Pinned version | Notes |
|---|---|---|
| Go | **1.25.x** | Stable generics + range over functions; toolchain directive in `go.mod`. |
| `go-chi/chi` | **v2 (5.x)** | Router for `net/http`. |
| `pgx` | **v5** | PostgreSQL 19 driver; preferred over `database/sql` + `lib/pq`. |
| `segmentio/kafka-go` | **latest** | Kafka producer / consumer for the 8 hot-path services. |
| `go-redis/redis` | **v9** | Redis 8+ client. |
| `coreos/go-oidc` | **v3** | Keycloak OIDC verification. |
| `prometheus/client_golang` | **v1.20+** | Metrics. |
| `golang-migrate` | **v4** | SQL migrations; mirrors Flyway semantics. |
| `golangci-lint` | **v1.62+** | Meta-linter (errcheck, govet, staticcheck, etc.). |

#### Python stack

| Component | Pinned version | Notes |
|---|---|---|
| Python | **3.14** | Free-threaded build opt-in for the scoring services. |
| FastAPI | **0.115+** (or 1.0 if released) | Async REST framework. |
| Pydantic | **2.x** | Validation + serialization; FastAPI 0.115+ default. |
| SQLAlchemy | **2.0.x** | Async ORM for the read-side services. |
| Alembic | **1.13+** | SQLAlchemy migrations. |
| `confluent-kafka-python` | **2.6+** | Confluent's librdkafka-backed client. |
| `aiokafka` | **0.12+** | Pure-async consumer (matches FastAPI event loops). |
| `authlib` | **latest** | Keycloak OAuth2 / OIDC client. |
| `scikit-learn` | **1.6+** | Risk scoring, incentive curves. |
| `pandas` | **2.2+** (or 3.0 if out) | Reporting rollups, BI exports. |
| `numpy` | **2.x** | Vectorised math for matching / scoring. |
| `pytest` | **8.x** | Test runner; `pytest-asyncio` for the async services. |
| `ruff` | **0.7+** | Linter + formatter (replaces black + flake8 + isort). |
| `mypy` | **1.13+** | Strict mode for the financial-adjacent Python services. |

#### Optional Rust inner loop (only if profiling demands)

| Component | Pinned version | Notes |
|---|---|---|
| Rust | **1.83+ (stable)** | Edition 2024. |
| `axum` | **0.7+** | Tiny standalone microservice for the scoring kernel. |
| `pyo3` | **0.22+** | Python extension for in-process Rust kernels. |

### 5.2 Tooling matrix (language-agnostic)

| Concern | Choice |
|---|---|
| Build | Gradle 9 (Kotlin DSL) for JVM services, `go build` (Go 1.25 toolchain) for Go, `uv` + `pyproject.toml` (Python 3.14) for Python |
| Container base | `gcr.io/distroless/static-debian12:nonroot` (Go), `eclipse-temurin:25-jre-jammy` (Kotlin), `python:3.14-slim` (Python) |
| Lint / format | `ktlint 1.5` + `detekt 1.23` (Kotlin), `golangci-lint v1.62+` (Go), `ruff 0.7+` + `mypy 1.13+ --strict` (Python) |
| Test | JUnit 5.11 + Testcontainers 1.21 (Kotlin), `testing` + Testcontainers Go (Go), `pytest 8` + Testcontainers Python (Python) |
| Migrations | Flyway 11 (Kotlin), `golang-migrate v4` (Go), Alembic 1.13+ (Python) — all targeting PostgreSQL 19 |
| API contract | OpenAPI 3.1 generated from code; contract tests with Pact |
| Auth | Keycloak resource server: Spring Security 7 (Kotlin), `coreos/go-oidc v3` (Go), `authlib` (Python) |
| Tracing | OpenTelemetry SDK 1.40+ in all three, OTLP export |
| Logs | Structured JSON to stdout, shipped by the Kubernetes node agent |

---

## 6. Admin endpoints & RBAC

Every service in the platform exposes an **admin endpoint namespace**
at `/admin/v1/...` for the `admin-service` BFF and for direct operator
calls. Admin endpoints are how the platform does *operations* —
inspecting internal state, clearing caches, replaying sagas, forcing
state transitions, looking up PII with a reason code, managing
blocklists — that the public API deliberately refuses.

This section is the **platform-wide contract** for the admin surface.
The per-service `TECH.md` documents the *per-service* specifics: which
Keycloak admin roles this service accepts, which service-specific
admin endpoints it adds, and which data classes are visible to which
roles.

### 6.1 The two surfaces, side by side

| Concern | Public API (`/v1/...`) | Admin API (`/admin/v1/...`) |
|---|---|---|
| Auth | Customer / driver / courier JWT (Keycloak) | Operator JWT (Keycloak `platform.*` or `<service>.*` role) |
| Port | `8080` (public ingress) | `8081` (cluster-internal only) |
| Network reachability | Public | Cluster-internal + bastion only (NetworkPolicy + sidecar) |
| Rate limit | Per-user, strict | Per-actor, looser; throttled by actor |
| Audit | `audit.api.request.v1` (every call) | `audit.admin.<service>.v1` with `reason_code` (every call) |
| PII | Scrubbed by default (masked) | Full PII, with reason recorded in audit |
| Force operations (`force-state`, `force-capture`, `replay`) | **Forbidden** | **Allowed** |
| Cache clear / reindex | **Forbidden** | **Allowed** |
| Trust boundary | Internet (WAF + edge) | Cluster-internal + mTLS (linkerd) |

Both surfaces share the same database, but they have different access
patterns, different audit trails, and different trust boundaries.

### 6.2 Keycloak admin role hierarchy

The platform defines a single hierarchy of Keycloak roles for admin
access. A higher role inherits everything below it.

| Role | Scope | Data access |
|---|---|---|
| `platform.super_admin` | All services, all data | All, including PII and (with break-glass) secrets |
| `platform.admin` | All services, all data | All, including PII; secrets never |
| `platform.ops` | All services, operational | Operational data; PII via scrubbed view |
| `platform.support` | All services, read | Read-only with `reason_code`; PII redacted by default |
| `platform.finance` | Financial services | Read/write on `payment`, `wallet`, `ledger`, `restaurant-settlement`, `ride-payment-integration`, `food-payment-integration` |
| `platform.engineering` | All services, meta only | Health, metrics, logs, config (no business data) |
| `platform.data_eng` | All services, read | Read on operational data (no PII) for warehouse / analysis |
| `<service>.admin` | One service | Full operational access to that service |
| `<service>.support` | One service | Read with `reason_code` on that service |
| `<service>.finance` | One service | Read/write on financial aspects of that service |

The `<service>.*` roles are service-specific. Example: `payment.admin`
grants full access to `payment-service`; `payment.support` grants
read-with-reason. A service that has no financial concerns
(e.g. `geolocation-service`) does not define `<service>.finance`.

**Permission presets.** The platform also exposes **permission
presets** as a documentation + operator-UI convenience. The current
set is enumerated at `GET /v1/admin/presets` on `admin-service`. The
only preset today is `SUPER_ADMIN`:

> `SUPER_ADMIN` = `platform.super_admin` + the 58 `<service>.admin`
> scopes (one per service in `docs/services/`, including
> `api_gateway.admin`).

The realm roles are the source of truth for enforcement; the preset
is a fixed, enumerable bundle the operator UI can grant or revoke
atomically via `POST/DELETE /v1/admin/identity/(grant|revoke)-super-admin`.
Adding a new service to the platform requires updating the
`SUPER_ADMIN` preset (one new role added to the catalog at
`GET /v1/admin/services`) in the same release.

### 6.2a SUPER_ADMIN preset membership

> **Locked 2026-08-12 (Phase 7.7 addendum).** The `SUPER_ADMIN`
> preset is exactly **22 realm roles**: 1 × `platform.super_admin`
> + 21 × `<service>.admin`. The 38 removed services
> (pre-consolidation `<service>.admin` roles like `address.admin`,
> `cart.admin`, `branch.admin`, `loyalty.admin`, etc.) have been
> absorbed into the 15 surviving services and are **not** part of
> the preset.

The 20 services whose `<service>.admin` role is part of the
`SUPER_ADMIN` preset, in directory order:

| # | Service | `<service>.admin` role | TECH.md 10.7 |
|---:|---|---|---|
| 1 | `api-gateway` | `api_gateway.admin` | ✅ |
| 2 | `identity-service` | `identity.admin` | ✅ |
| 3 | `customer-service` | `customer.admin` | ✅ |
| 4 | `driver-service` | `driver.admin` | ✅ |
| 5 | `trip-service` | `trip.admin` | ✅ |
| 6 | `pricing-service` | `pricing.admin` | ✅ |
| 7 | `restaurant-service` | `restaurant.admin` | ✅ |
| 8 | `food-order-service` | `food_order.admin` | ✅ |
| 9 | `courier-service` | `courier.admin` | ✅ |
| 10 | `geolocation-service` | `geolocation.admin` | ✅ |
| 11 | `payment-service` | `payment.admin` | ✅ |
| 12 | `ledger-service` | `ledger.admin` | ✅ |
| 13 | `configuration-service` | `configuration.admin` | ✅ |
| 14 | `notification-service` | `notification.admin` | ✅ |
| 15 | `file-service` | `file.admin` | ✅ |
| 16 | `audit-service` | `audit.admin` | ✅ |
| 17 | `admin-service` | `admin.admin` | ✅ |
| 18 | `reporting-service` | `reporting.admin` | ✅ |
| 19 | `fraud-risk-service` | `fraud_risk.admin` | ✅ |
| 20 | `search-service` | `search.admin` | ✅ |
| 21 | **`chat-service`** *(Phase 7.7)* | **`chat.admin`** | ✅ |

Every service declares its preset membership in its `TECH.md` 10.7;
the operator UI surfaces this via
`admin-service GET /v1/admin/services`. The full list is also
inlined in `admin-service/INTEGRATION.md` 1.13. Time-bounded aliases
(2026-08-06) live in [`../shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md).

### 6.2b Deal kernel participation (Make a Deal — Phase 7.5)

The platform's Make-a-Deal negotiation kernel (the InDriver-style
"rider proposes price, driver counters, fare-band-bounded" feature)
is **embedded per service** — there is no central `deal-service`
binary. The canonical contract lives in
[`../shared/DEAL_FEATURE.md`](../shared/DEAL_FEATURE.md). Every
participating service's `TECH.md` 12 references that hub.

The participation matrix below mirrors the format of 6.2a so the
two cross-service presets are surfaced the same way in the operator
UI.

| Service | Role | Participation |
|---|---|---|
| ``trip-service` (ride-request)` | Rider-side boundary (ride) | **Participates** — owns deal rows, `ride.deal.*.v1` events. |
| ``driver-service` (dispatch)` | Driver-side boundary (ride) | **Participates** — owns `DealBid` + `DealAttempt` rows, `dispatch.deal.*.v1` events. |
| `food-order-service` | Customer-side boundary (food) | **Participates** — owns deal rows, `food.deal.*.v1` events. |
| ``courier-service` (dispatch)` | Courier-side boundary (food) | **Participates** — owns `DealBid` + `DealAttempt` rows, `delivery.deal.*.v1` events. |
| `pricing-service` | Fare-band authority | **Participates** — adds `GET /v1/quotes/{id}/fairness-band` and the `max_fare_override` rule kind. |
| `configuration-service` | Config storage | **Participates** — hosts `deal.*` keys (see `INTEGRATION.md` 4.5.1). |
| `notification-service` | Outbound channel | **Participates** — adds 5 deal templates (1.13 of its `INTEGRATION.md`). |
| `audit-service` | Immutable audit | **Participates** — consumes all `*.deal.*.v1` and writes `audit.deal_transition.v1`. |
| ``geolocation-service` (zones)` | Geo authority | **Inherits** — referenced only via `POST /v1/zones/contains`. |
| ``configuration-service` (flags)` | Rollout gate | **Inherits** — hosts `deal.enabled.{city_id}.{ride_type}` per the existing flag pattern. |
| All other services (49) | — | **Inherits** — `TECH.md` 12 is a single line referencing the hub. |

Every service declares its deal participation in its `TECH.md` 12;
the operator UI surfaces this via the same `admin-service
GET /v1/admin/services` endpoint used for 6.2a. The full
participation matrix is also inlined in
[`../shared/DEAL_FEATURE.md`](../shared/DEAL_FEATURE.md) 10.

### 6.3 Endpoint convention

All admin endpoints live under `/admin/v1/`. A typical request flow:

1. Operator (or `admin-service` BFF) authenticates against Keycloak
   with one of the `platform.*` or `<service>.*` roles.
2. Request lands on the service's **admin port** (`8081`), with the
   JWT in the `Authorization: Bearer ...` header.
3. Spring Security 7 / `coreos/go-oidc` v3 / `authlib` validates the
   token and extracts the roles.
4. The framework enforcement point (Spring `@PreAuthorize` /
   `net/http` middleware / FastAPI dependency) checks the minimum
   role.
5. Service executes the operation, with the data-access policy
   applied per row (PII scrubbed unless the role allows it).
6. Service emits an `audit.admin.<service>.v1` event before returning.
7. Service returns the result to the caller.

### 6.4 Common admin endpoints (every service)

Every service exposes the following admin endpoints, all under
`/admin/v1/`. They are *inherited* — they do not need to be listed in
per-service `TECH.md` files.

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `GET` | `/admin/v1/health` | `platform.engineering` | Detailed health: deps, queues, Kafka lag, last error, DB pool stats |
| `GET` | `/admin/v1/config` | `platform.engineering` | Runtime config dump (secrets redacted) |
| `GET` | `/admin/v1/metrics` | `platform.engineering` | Internal metrics (Prometheus exposition) |
| `GET` | `/admin/v1/audit` | `platform.ops` | Recent admin actions on this service, filterable by actor / endpoint / time |
| `POST` | `/admin/v1/cache/clear` | `platform.admin` | Clear a specific cache key or `pattern:*` glob (body: `{key}` or `{pattern}`) |
| `POST` | `/admin/v1/reindex` | `platform.admin` | Trigger a reindex (search, geo, menu, etc.) |
| `POST` | `/admin/v1/replay` | `platform.admin` | Replay a saga step / retry a failed operation (saga services) |
| `POST` | `/admin/v1/force-state` | `platform.admin` | Force a state transition on an aggregate (state-machine services); emits a warning event |
| `GET` | `/admin/v1/services` | `platform.admin` | Service catalog (this service's own entry). The cross-service full catalog lives at `admin-service GET /v1/admin/services`; this per-service endpoint returns this service's accepted admin scopes (10.1) + `SUPER_ADMIN` preset membership (10.7) |

### 6.5 Data access by role (platform-wide)

| Data class | super_admin | admin | ops | support | finance | engineering | data_eng |
|---|---|---|---|---|---|---|---|
| Business data (orders, trips, rides) | ✓ | ✓ | ✓ | read+reason | — | — | ✓ |
| PII (name, email, phone, address) | ✓ | ✓ | scrubbed | scrubbed+reason | — | — | scrubbed |
| Financial data (payment, wallet, ledger) | ✓ | ✓ | — | — | ✓ | — | ✓ |
| Secrets (provider API keys, tokens) | break-glass | — | — | — | — | — | — |
| Config / metrics / logs | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Aggregated analytics | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

**Scrubbed** = `name` becomes `J*** D**`, `email` becomes
`j***@example.com`, `phone` becomes `+1-555-***-1234`. Full values
require a `reason` query/body parameter that is recorded in the audit
log. The combination of `support` role + `reason` triggers an extra
audit-flagged PII view.

### 6.6 Audit log

Every admin endpoint call emits an `audit.admin.<service>.v1` event
to the `audit-service` consumer (which writes to the immutable
`audit-service` database).

```json
{
  "audit_id": "uuid",
  "timestamp": "2026-07-29T07:12:34Z",
  "actor_id": "user-uuid",
  "actor_username": "alice@example.com",
  "roles": ["platform.admin", "payment.admin"],
  "service": "payment-service",
  "endpoint": "POST /admin/v1/payments/abc123/force-capture",
  "target_resource": "payment:abc123",
  "action": "force-capture",
  "reason_code": "support-ticket-#12345",
  "request_id": "...",
  "trace_id": "...",
  "result": "success | failure",
  "duration_ms": 234
}
```

Operators view admin history per service via
`GET /admin/v1/audit?actor=...&endpoint=...&from=...&to=...`.
Read access to the audit log itself is restricted to
`platform.admin` and above.

### 6.7 Implementation by stack

| Stack | Enforcement library / pattern |
|---|---|
| Kotlin (Spring Boot 4) | Spring Security 7 method security: `@PreAuthorize("hasRole('platform.admin')")`; `@RequestMapping("/admin/v1")` on a dedicated `@RestController` |
| Go | `net/http` middleware that reads the `coreos/go-oidc` v3 ID-token claims and rejects requests missing the required role; mount the admin mux on `:8081` separately from the public mux on `:8080` |
| Python (FastAPI) | FastAPI `APIRouter(prefix="/admin/v1")` with `Depends(require_role("platform.admin"))` per route; the dependency also emits the audit event before returning |

All three stacks use the same conceptual helper: `requireRole(min)`
which:

1. Extracts the JWT from the request.
2. Verifies the signature against Keycloak's JWKS.
3. Asserts the role (or any role higher in the inheritance chain).
4. Emits the `audit.admin.<service>.v1` event with `actor`, `roles`,
   `endpoint`, `reason_code`, `request_id`, `trace_id`.
5. Returns the parsed claims to the handler.

`requireRole("platform.super_admin")` is special: it always implies
break-glass co-signature + step-up MFA + super-admin IP allowlist,
in addition to the role check (per `SECURITY_ARCHITECTURE.md` 14).
Services MUST NOT weaken these gates when the JWT carries
`platform.super_admin`.

### 6.8 Per-service admin surface

The per-service `TECH.md` files document:

- Which Keycloak admin roles this service accepts (subset of 6.2).
- This service's `audit.admin.<service>.v1` key and the schema fields.
- The data access policy for this service's data classes
  (a per-service table that refines 6.5).
- The **service-specific** admin endpoints that this service adds on
  top of the common 8 in 6.4.

The common endpoints in 6.4 are inherited by every service and are
*not* repeated per file. Per-service `TECH.md` only lists the
*additional* endpoints that are unique to that service.

### 6.9 Network policy

- Admin endpoints listen on a **separate port** (`8081` vs `8080`).
- A Kubernetes `NetworkPolicy` allows ingress to `8081` only from:
  - the `admin-service` namespace,
  - the `platform-ops` namespace,
  - the `platform-engineering` namespace,
  - the bastion (for direct operator access via SSH + kubectl port-forward).
- Public ingress (the edge LB) does **not** route to `8081`.
- mTLS via linkerd sidecar — all admin traffic is encrypted in
  transit and authenticated service-to-service.
- The `api-gateway` does **not** proxy admin traffic; admin calls
  bypass the gateway and hit the service's admin port directly.

### 6.10 Worked example: `payment-service`

To make the pattern concrete, here is how an operator forces a
payment capture using this contract:

1. Operator authenticates to Keycloak as `alice@example.com` with
   roles `platform.admin` and `payment.admin`.
2. Operator calls:
   ```
   POST https://payment-service.platform-ops.svc.cluster.local:8081/admin/v1/payments/abc123/force-capture
   Authorization: Bearer <jwt>
   Content-Type: application/json

   { "reason_code": "support-ticket-#12345" }
   ```
3. `payment-service`:
   - Validates JWT against Keycloak JWKS.
   - Asserts `platform.admin` role is present.
   - Applies data access policy: PII fields (cardholder name, email)
     are scrubbed in the response because `support` role is *not*
     in the actor's roles.
   - Calls `payment-service`'s internal `forceCapture(paymentId)`
     command, which transitions the `payment_intent` aggregate from
     `authorized` → `captured` and writes a `payment.captured.v1`
     event.
   - Emits `audit.admin.payment.v1` with the full payload from 6.6.
   - Returns the captured payment to the operator.
4. Operator's `audit-service` timeline now shows the action; the
   `support-ticket-#12345` reason code links the action to the
   customer-facing ticket.

### 6.10.1 Worked example: granting the `SUPER_ADMIN` preset

To grant a Keycloak operator super-admin access to all 20 services
via the `SUPER_ADMIN` preset (1 × `platform.super_admin` + 58 ×
`<service>.admin`), the canonical flow is:

1. Caller authenticates to Keycloak as `alice@example.com` with
   roles `platform.super_admin` and a fresh step-up MFA claim
   (`mfa_step_up: true` in the JWT).
2. Caller first previews what the preset touches:

   ```
   GET https://admin-service.platform-ops.svc.cluster.local:8080/v1/admin/services
   GET https://admin-service.platform-ops.svc.cluster.local:8080/v1/admin/presets
   ```

   Both return 200 with the 20-service catalog and the 21-role list (post-ADR-0017 consolidation).
3. Caller submits:

   ```
   POST https://admin-service.platform-ops.svc.cluster.local:8080/v1/admin/identity/grant-super-admin
   Authorization: Bearer <jwt with platform.super_admin + mfa_step_up>
   X-Audit-Reason: ops-onboarding-#1234 — promoting bob@example.com to super admin
   X-Signature: t=1722940800,v1=<hmac>
   X-Break-Glass-Cosigner: <uuid of carol@example.com — a different platform.super_admin>
   Idempotency-Key: 01HAA...
   Content-Type: application/json

   {
     "user_id": "01HZX…",
     "preset": "SUPER_ADMIN",
     "reason": "ops-onboarding-#1234",
     "tenant_id": "global"
   }
   ```

4. `admin-service`:
   - Validates every gate from `SECURITY_ARCHITECTURE.md` 14
     (role, IP allowlist, MFA, signature, co-signer, time-of-day,
     tenant match, idempotency key).
   - Writes one row to `admin.super_admin_grant` (with
     `source_request_id = 01HAA…`, `break_glass = true`,
     `cosigner_id = <carol>`).
   - Fans out 59 calls to
     `identity-service POST /admin/v1/identities/{user_id}/roles/{role}`
     (1 × `platform.super_admin` + 58 × `<service>.admin`).
   - On success: emits `admin.super_admin.granted.v1`.
   - On partial failure: writes a compensating
     `super_admin_grant(action='revoke', compensation_id=…)` row,
     fans out compensating revokes, emits a compensating
     `admin.super_admin.revoked.v1` carrying the same
     `source_request_id`, returns `503 DEPENDENCY_UNAVAILABLE`.
5. `identity-service` writes 59 rows to
   `identity.role_assignment_history` (joined to the
   `super_admin_grant` row by `source_request_id`) and emits
   `identity.role.granted.v1` × 59 (each keyed by the granted role
   name).
6. `notification-service` consumes
   `admin.super_admin.granted.v1` and pages security on-call
   (`SEC--013`).
7. `audit-service` records the immutable timeline: one row per
   role in `role_assignment_history` + one row in `super_admin_grant`
   + the `admin.super_admin.granted.v1` event.

The co-signer's identity is on the `super_admin_grant` row and the
emitted event; `audit-service` exposes it via
`GET /admin/v1/audit?actor=alice@example.com&endpoint=...grant-super-admin`.

---

**Post-ADR-0017 consolidation (2026-08-06)**: SUPER_ADMIN = `platform.super_admin` + 20 `<service>.admin` (was 58 before the 58→20 consolidation). The canonical role list lives in `admin-service/INTEGRATION.md` 1.13.

## 7. Reviewing and changing a recommendation

A recommendation in this document is a **default**, not a contract.
To change one, open a PR that:

1. Names the service and the proposed language/framework.
2. Cites a concrete driver: profiling data, hiring market change, new
   library that solves a real problem, or a workload change (e.g.
   ``geolocation-service` (ETA/routing)` qps jumped 10× and the new hot-path profile
   argues for Go).
3. Updates this table and the affected service's `INTEGRATION.md` /
   `README.md` (deployment, build, run).
4. Updates the per-cluster rationale in 3 if the change affects more
   than one service.

The architectural baseline in `main.md` (PostgreSQL 19, Keycloak,
REST, Kafka, Redis, Docker, Kubernetes, OpenAPI 3.x) is **not**
changeable via this document — that's `main.md`'s job.

---

## 8. Shared library — `platform-spring-boot-starter`

The 46 Kotlin / Spring Boot 4 services in this platform all depend
on a single shared library: **`com.trips-enjoy.platform:spring-boot-starter`**.
It is the one place where cross-cutting concerns live, so that each
service is a one-line `implementation(...)` away from a consistent
web, security, data, observability, caching, messaging, audit,
error, money, and API-docs experience.

| | |
|---|---|
| **Maven coordinates** | `com.trips-enjoy.platform:spring-boot-starter` |
| **Current version** | tracks Spring Boot 4 — see [`RECOMMENDATIONS.md` 5](../services/RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic) |
| **Modules** | 11 sub-modules — `web`, `security`, `data`, `money`, `caching`, `messaging`, `observability`, `audit`, `error`, `api-docs`, `test` (+ umbrella starter + autoconfigure) |
| **Source** | `packages/platform-spring-boot/` |
| **Doc root** | [`../shared/README.md`](../shared/README.md) |

The full design lives in [`../shared/`](../shared/README.md):

| Doc | What's in it |
|---|---|
| [`../shared/README.md`](../shared/README.md) | Overview, coordinates, the one-line install, what you get out of the box |
| [`../shared/MODULES.md`](../shared/MODULES.md) | Sub-module breakdown and dependency graph |
| [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) | Error model, correlation IDs, audit, PII redaction, money, logging, naming |
| [`../shared/AUTO_CONFIG.md`](../shared/AUTO_CONFIG.md) | Every auto-configuration, defaults, override keys |
| [`../shared/INTEGRATION.md`](../shared/INTEGRATION.md) | How a service adopts the starter (build.gradle.kts, application.yml, package layout) |
| [`../shared/TESTING.md`](../shared/TESTING.md) | `BaseIntegrationTest`, JWT minting, outbox assertions, contract tests |
| [`../shared/VERSIONING.md`](../shared/VERSIONING.md) | SemVer policy, deprecation, upgrade process, CVE response |
| [`../shared/ROADMAP.md`](../shared/ROADMAP.md) | What's in / next / out of scope |
| [`../shared/TYPE_CATALOG.md`](../shared/TYPE_CATALOG.md) | **Platform-wide type vocabulary** — ride types (Enjoy Economy / VIP / XL / Comfort / Assist), courier vehicle types, food delivery types, customer and merchant segments; brand label → catalog key → CHECK → `pricing-service.rule_bindings` mapping. Also documents the locked platform-margin doctrine (8.7 — dynamic per-quote multiplier + 20% + 1{currency} + all discounts 100% platform-borne). Sibling to `LOOKUPS.md` (the mechanism). |

The shared library is the **single point of change** for any
cross-cutting concern. If a new cross-cutting feature is needed, it
lands in the library first, then is adopted by services via a version
bump. Services do not duplicate cross-cutting code; they import it.

The Go and Python services do not depend on the starter — they
implement the **same contracts** (RFC 7807 error model, audit topic
format, correlation headers, OpenAPI schemas) using each stack's
idiomatic tooling. The contracts are the platform's stability
boundary; the implementations are per-stack.
