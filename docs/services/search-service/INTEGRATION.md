# search-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md`.

### 1.1 `POST /v1/search/restaurants`

- **Purpose**: Search restaurants.
- **Auth**: Bearer JWT; any authenticated principal.
- **Request**:
  ```json
  {
    "query": "pizza",
    "filter": {
      "city_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "cuisine": ["italian", "american"],
      "open_now": true
    },
    "geo": { "lat": 24.7136, "lon": 46.6753, "radius_m": 5000 },
    "sort": [{ "field": "_score", "order": "desc" }],
    "limit": 20,
    "cursor": null,
    "locale": "ar"
  }
  ```
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
        "name": "Pizza Palace",
        "name_i18n": { "en": "Pizza Palace", "ar": "قصر البيتزا" },
        "cuisine": "italian",
        "rating": 4.5,
        "geo": { "lat": 24.7136, "lon": 46.6753 },
        "open_now": true,
        "_score": 12.3
      }
    ],
    "next_cursor": "eyJ...",
    "has_more": true,
    "total_estimated": 42,
    "took_ms": 35,
    "cache_hit": false
  }
  ```
- **Errors**: 400 / 401 / 429 / 503 `CIRCUIT_OPEN` / 504.

### 1.2 `POST /v1/search/menu-items`

Same as 1.1 for menu items.

### 1.3 `POST /v1/search/merchants`

Same as 1.1 for merchants.

### 1.4 `POST /v1/search/tickets`

Same as 1.1 for support tickets. Auth: `support_agent_l1+`
or `admin`.

### 1.5 `GET /v1/search/suggest/{vertical}`

- **Purpose**: Autocomplete.
- **Auth**: Bearer JWT.
- **Request (query)**: `?q=piz&locale=ar&limit=10`
- **Response (200)**:
  ```json
  {
    "items": [
      { "text": "Pizza Palace", "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB" },
      { "text": "Pizzeria Roma", "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC" }
    ],
    "took_ms": 12
  }
  ```

### 1.6 `POST /v1/admin/reindex`

- **Purpose**: Trigger a reindex for a vertical.
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`;
  HMAC signed.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "vertical": "restaurants",
    "kind": "full",
    "new_index_name": "restaurants_v2"
  }
  ```
- **Response (202)**:
  ```json
  {
    "job_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "status": "pending",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 1.7 `GET /v1/admin/reindex/{id}`

- **Auth**: Bearer JWT + role `admin`.
- **Response (200)**: reindex job shape with `documents_indexed`,
  `documents_failed`, `progress_percent`.

### 1.8 `POST /v1/admin/relevance/update`

- **Purpose**: Update relevance config for a vertical /
  locale.
- **Auth**: Bearer JWT + role `admin` or `data_analyst`;
  HMAC signed.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "vertical": "restaurants",
    "locale": "ar",
    "field_boosts": {
      "name": 2.0,
      "description": 1.0,
      "tags": 1.5
    },
    "function_score": {
      "functions": [
        { "filter": { "term": { "open_now": true } }, "weight": 1.2 }
      ]
    },
    "synonyms": {
      "pizza": ["pizzeria", "بيتزا"]
    }
  }
  ```
- **Response (200)**: relevance config shape, `status=active`.

### 1.9 `GET /v1/admin/queries/top`

- **Purpose**: Top queries (for analytics and relevance
  tuning).
- **Auth**: Bearer JWT + role `admin` or `data_analyst`.
- **Request (query)**: `?vertical=restaurants&from=2026-07-01&to=2026-07-29&limit=100`
- **Response (200)**:
  ```json
  {
    "items": [
      { "query_hash": "...", "count": 1234, "result_count_avg": 5.2, "zero_result_rate": 0.02 }
    ]
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| OpenSearch | POST | `/{index}/_search` | search | 500ms | 1 | yes (per index) |
| OpenSearch | POST | `/{index}/_doc/{id}` | index / upsert | 500ms | 2 | yes |
| OpenSearch | POST | `/{index}/_delete_by_query` | delete | 1s | 1 | yes |
| OpenSearch | POST | `/_aliases` | alias swap | 1s | 0 | yes |
| `restaurant-service` | GET | `/v1/restaurants?cursor=...&limit=...` | backfill source | 5s | 1 | yes |
| ``restaurant-service` (menu)` | GET | `/v1/menu-items?cursor=...&limit=...` | backfill source | 5s | 1 | yes |
| ``restaurant-service` (merchant)` | GET | `/v1/merchants?cursor=...&limit=...` | backfill source | 5s | 1 | yes |
| `configuration-service` | GET | `/v1/config/search` | read relevance | 500ms | 3 | yes |
| ``configuration-service` (flags)` | GET | `/v1/flags/search.ab` | A/B routing | 300ms | 1 | yes |

All outbound calls carry `X-Correlation-Id` and `traceparent`.

## 3. Produced Events

### 3.1 `search.query.executed.v1`

- **Producer**: `search-service`.
- **Topic**: `search.query.executed`.
- **Trigger**: every search.
- **Partition key**: `query_hash` (so all executions of the
  same query are ordered for analytics).
- **Schema (data)**:
  ```json
  {
    "query_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "vertical": "restaurants",
    "query_hash": "...",
    "result_count": 12,
    "cache_hit": false,
    "latency_ms": 45,
    "user_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "tenant_id": null,
    "locale": "ar",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `search.query.executed.dlq`.
- **Consumers**: ``reporting-service` (data lake)`.

### 3.2 `search.reindex.started.v1`

- **Producer**: `search-service`.
- **Topic**: `search.reindex.started`.
- **Trigger**: reindex begins.
- **Partition key**: `vertical`.
- **Schema (data)**:
  ```json
  {
    "job_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "vertical": "restaurants",
    "kind": "full",
    "from_alias": "restaurants_v1",
    "to_alias": "restaurants_v2",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `audit-service`.

### 3.3 `search.reindex.completed.v1`

Same as 3.2 with `documents_indexed`, `documents_failed`,
`completed_at`.

## 4. Consumed Events

### 4.1 `restaurant.updated.v1`

- **Producer**: `restaurant-service`.
- **Topic**: `restaurant.restaurant.updated`.
- **Reason**: project to the `restaurants` index.
- **Handler**:
  1. Inbox insert (`event_id`).
  2. Fetch the latest restaurant from `restaurant-service`
     (if not in the event payload).
  3. Upsert to OpenSearch (with the relevance config for
     the vertical / locale).
  4. Update `index_health.last_event_at`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `menu.updated.v1`

Same as 4.1 for the `menu_items` index.

### 4.3 `merchant.updated.v1`

Same as 4.1 for the `merchants` index.

### 4.4 `zone.updated.v1`

- **Producer**: ``geolocation-service` (zones)`.
- **Reason**: refresh geo filter (which restaurants /
  menu items are in which zone).
- **Handler**: re-validate the geo filter; no document
  re-index unless the zone boundaries changed.

### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: relevance config, locale config, TTLs
  changed.
- **Handler**: reload config (idempotent; config hash
  compared).

### 4.6 `feature_flag.updated.v1`

- **Producer**: ``configuration-service` (flags)`.
- **Reason**: A/B routing for relevance tests changed.
- **Handler**: reload A/B config.

## 5. Reliability

- **Timeouts** (defaults):
  - OpenSearch: 500ms (search), 1s (alias swap).
  - Backfill source REST: 5s.
  - `configuration-service`: 500ms.
- **Retries**: 1-2 attempts with backoff. Never on 4xx.
- **Circuit breakers** per OpenSearch index: open on
  ≥ 3 consecutive 5xx/timeout in 30s.
- **Outbox / Inbox**: standard pattern.
- **DLQ**: every topic has a paired `<topic>.dlq`.
- **Reconciliation**: a daily job compares the index to
  the source (via `restaurant-service` /
  ``restaurant-service` (menu)` / ``restaurant-service` (merchant)`); flags drift.

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event.
  - The `headers.correlation_id` of every outbox row.
  - The `correlation_id` column of every query log row.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments HTTP, Kafka, DB,
  OpenSearch (custom span).
- One root span per search; OpenSearch call as child
  span; suggest call as child span.
- Sample 100% of errors, 10% of successes in production;
  100% in staging.
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
| [``reporting-service` (data lake)`](../`reporting-service` (data lake)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``configuration-service` (flags)`](../`configuration-service` (flags)/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``restaurant-service` (menu)`](../`restaurant-service` (menu)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (zones)`](../`geolocation-service` (zones)/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`audit-service`](../audit-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../`restaurant-service` (branch)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (inventory)`](../`restaurant-service` (inventory)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (menu)`](../`restaurant-service` (menu)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``admin-service` (support module)`](../`admin-service` (support module)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../`geolocation-service` (zones)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

