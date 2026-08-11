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
    "query_type": "best_fields",
    "fields": ["name^2", "description", "tags", "name_i18n.ar"],
    "match_operator": "or",
    "fuzziness": "AUTO",
    "phrase_slop": 0,
    "highlight": true,
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
  - **Full-text fields** (per FR--022): `query_type ∈
    {best_fields, most_fields, cross_fields, phrase, phrase_prefix}`,
    default `best_fields`. `fields` lists the OpenSearch field paths
    the `multi_match` runs over; per-field boost is encoded with
    `^N` syntax (e.g. `name^2`). Defaults are derived from the
    active `relevance_config.field_boosts` for `(vertical, locale)`.
  - **Phrase query**: a quoted substring in `query` (e.g.
    `"deep dish"`) is parsed as a phrase query; `phrase_slop`
    controls edit distance for the phrase (default `0`).
  - **Typo tolerance**: `fuzziness ∈ {AUTO, 0, 1, 2}`,
    default `AUTO` on `name`, default `0` (off) on `description`.
  - **Match operator**: `match_operator ∈ {and, or}`, default `or`.
  - **Highlighting**: `highlight: true` adds per-field
    `<em>...</em>` markup under `highlights` per result
    (per FR--026). Default `false`.
  - **Locale**: selects the analyzer (`en` → `english`,
    `ar` → `arabic_normalized`) and the locale-aware fields
    (per ERD.md §12).
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
        "score": 12.3,
        "highlights": {
          "name": ["<em>Pizza</em> Palace"],
          "description": ["best <em>pizza</em> in town"]
        }
      }
    ],
    "next_cursor": "eyJ...",
    "has_more": true,
    "total_estimated": 42,
    "took_ms": 35,
    "cache_hit": false
  }
  ```
  - **`score`**: the OpenSearch `_score` for the result
    (BM25, per FR--027, FR--031). Higher = more relevant.
    Ranking is by descending `score` by default.
  - **`highlights`**: per-field `<em>` markup, included only
    when `highlight=true` was sent in the request. Fields:
    `name`, `description`. Max 3 fragments, fragment size
    150 chars (per FR--026).
- **Errors**: 400 / 401 / 429 / 503 `CIRCUIT_OPEN` / 504.
- **Latency budget**: see SRS.md §16.1.

### 1.2 `POST /v1/search/menu-items`

Same as 1.1 for menu items.

### 1.3 `POST /v1/search/merchants`

Same as 1.1 for merchants.

### 1.4 `POST /v1/search/tickets`

Same as 1.1 for support tickets. Auth: `support_agent_l1+`
or `admin`.

### 1.5 `GET /v1/search/suggest/{vertical}`

- **Purpose**: Autocomplete. Backs the search-as-you-type field
  on `name` / `name_i18n.{locale}` (per FR--030).
- **Auth**: Bearer JWT.
- **Request (query)**: `?q=piz&locale=ar&limit=10`
  - `q`: 1..64 chars (per FR--030). Server applies `match_phrase_prefix`
    on the `name.search_as_you_type` sub-field (and
    `name_i18n.{locale}.search_as_you_type` when `locale` is set).
  - `locale`: selects the locale sub-field and the analyzer
    (`en` → `english`, `ar` → `arabic_normalized`).
  - `limit`: 1..20, default 10. No fuzziness (P99 constraint per
    NFR--003).
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

Every outbound call is authenticated with **client-credentials JWT**
(minted by `identity-service` via the `search-service` service
account, scope `search:outbound`) and carried over **linkerd mTLS**.
Every call propagates `X-Correlation-Id` and `traceparent` from the
inbound request (or generates them if absent). Rate limits are
enforced by the downstream; this service respects them and falls back
to cached/default values when the downstream is degraded
per `SERVICE_ISOLATION.md`.

The table below is the at-a-glance view; per-target contracts follow
in §2.1–§2.6.

| Group | Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|---|---|---|---|---|---|---|---|
| Index ops | OpenSearch | POST | `/{index}/_search` | search | 500ms | 1 | yes (per index) |
| Index ops | OpenSearch | POST | `/{index}/_doc/{id}` | index / upsert | 500ms | 2 | yes |
| Index ops | OpenSearch | POST | `/{index}/_delete_by_query` | delete | 1s | 1 | yes |
| Index ops | OpenSearch | POST | `/_aliases` | alias swap | 1s | 0 | yes |
| Index ops | OpenSearch | POST | `/{index}/_bulk` | bulk index (reindex) | 30s | 1 | yes |
| Backfill | `restaurant-service` | GET | `/v1/restaurants?cursor=...&limit=...` | backfill `restaurants` | 5s | 1 | yes |
| Backfill | ``restaurant-service` (menu)` | GET | `/v1/menu-items?cursor=...&limit=...` | backfill `menu_items` | 5s | 1 | yes |
| Backfill | ``restaurant-service` (merchant)` | GET | `/v1/merchants?cursor=...&limit=...` | backfill `merchants` | 5s | 1 | yes |
| Backfill | `admin-service` | GET | `/v1/support/tickets?cursor=...&limit=...` | backfill `tickets` (support module) | 5s | 1 | yes |
| Backfill | `geolocation-service` | GET | `/v1/zones?cursor=...&limit=...` | backfill `zone` (per `seeding for new region`) | 5s | 1 | yes |
| Hydration | `restaurant-service` | GET | `/v1/restaurants/{id}` | hydrate restaurant doc when event payload incomplete | 1s | 1 | yes |
| Hydration | ``restaurant-service` (menu)` | GET | `/v1/menu-items/{id}` | hydrate menu item when event payload incomplete | 1s | 1 | yes |
| Hydration | ``restaurant-service` (merchant)` | GET | `/v1/merchants/{id}` | hydrate merchant when event payload incomplete | 1s | 1 | yes |
| Hydration | `admin-service` | GET | `/v1/support/tickets/{id}` | hydrate ticket when event payload incomplete | 1s | 1 | yes |
| Hydration | `customer-service` | GET | `/v1/customers/{id}/preferences` | personalization (cuisine boost) — opt-in per A/B flag | 200ms | 0 | yes |
| Config | `configuration-service` | GET | `/v1/config/search` | read relevance / locale config | 500ms | 3 | yes |
| Config | ``configuration-service` (flags)` | GET | `/v1/flags/search.ab` | A/B routing | 300ms | 1 | yes |
| Auth | `identity-service` | POST | `/v1/oauth/token` | mint client-credentials JWT for outbound | 1s | 1 | yes |
| Auth | `identity-service` | GET | `/v1/tenants/{tenant_id}` | validate tenant (per `SECURITY_ARCHITECTURE.md` 16) | 500ms | 1 | yes |

### 2.1 Backfill endpoints (cursor-paginated)

All backfill endpoints are GETs against the owner service, paginated
with an opaque `cursor` and a `limit` (default 1000, max 1000). The
owner returns a `next_cursor` (null when exhausted); search-service
walks the cursor until exhausted. Per-batch retry is 1 (no
exponential backoff for backfill — the reindex job is cancelled on
persistent failure per `WORKFLOWS.md` §3.7).

```json
// request
GET /v1/restaurants?cursor=eyJ...&limit=1000&updated_since=2026-07-01T00:00:00Z

// response
{
  "items": [
    { "id": "...", "name": "...", "cuisine": "italian", "_links": {...} }
  ],
  "next_cursor": "eyJ...",
  "has_more": true
}
```

Pagination invariants:

- `next_cursor == null` (or `has_more == false`) ends the walk.
- If the cursor is invalid (4xx), the reindex job is marked `failed`
  and the operator must restart with a fresh cursor.
- Backfill is **per-vertical** — one walk per source service.

### 2.2 Hydration endpoints (single-doc fetch)

Hydration is triggered when an event is consumed but the payload
lacks fields the index needs (e.g. denormalized `cuisine` on a
`restaurant.updated.v1` event that only carries `id` + `name`).
Hydration is single-doc fetch with a 1s timeout and 1 retry. On
failure, the event is **DLQ'd** — the index will be reconciled by
the daily drift job (§5 *Reconciliation*).

Hydration is **skipped** when the event payload is self-sufficient
(configured per `(vertical, event)` in `search-service` config).

### 2.3 Personalization (search-time hydration)

Customer preferences are fetched **only** when the A/B flag
`search.ab.personalization` is enabled for the request cohort. The
fetch is parallel with the OpenSearch query (200ms timeout, **no
retry** — slow = no personalization for this request, not an error).
On `503` / timeout, the search returns with the un-personalized
result set; the caller sees the same result shape minus the
personalization boost.

### 2.4 Configuration endpoints

`configuration-service` is the **authoritative** source for relevance
config and locale support. The REST endpoint is the read path; the
events in §4.5 are the change signal. The service caches responses
in Redis with TTL = `search.cache.config.ttl_seconds` (default 300s).
On `503` / timeout, the cache is used (stale-while-revalidate); on
cold cache + downstream down, the **bootstrap default** is used
(defined in `TECH.md` §5).

### 2.5 Identity / tenant endpoints

`identity-service` is called for two reasons:

1. **Outbound auth** — `POST /v1/oauth/token` with the
   `client_credentials` grant, scope `search:outbound`. The token is
   cached in process until `exp - 60s`. 1 retry on 5xx.
2. **Tenant validation** — `GET /v1/tenants/{tenant_id}` for every
   authenticated request that carries a `tenant_id` claim. Multi-tenant
   paths are gated by FR--016 (SRS.md). 1 retry on 5xx; on failure
   the request is rejected with `TENANT_LOOKUP_FAILED` (503).

### 2.6 Error propagation

For every outbound call, the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
apply:

- **OpenSearch**: 5xx / timeout → `CIRCUIT_OPEN` / `DEPENDENCY_TIMEOUT`
  to the caller (per `SRS.md` §13). 4xx → propagation verbatim.
- **Backfill sources**: 5xx / timeout → retry once; on persistent
  failure, the reindex job is marked `failed` (audited via
  `search.reindex.failed.v1`).
- **Configuration**: 5xx / timeout → cache or default (§2.4).
- **Identity / tenant**: 5xx / timeout → reject with
  `TENANT_LOOKUP_FAILED` (per `SECURITY_ARCHITECTURE.md` 16).
- **Hydration**: 5xx / timeout → DLQ + daily reconciliation.
- **Personalization**: 5xx / timeout → degrade gracefully (off for
  this request, no error to caller).

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

All consumed events are processed via the standard inbox pattern:
dedup on `event_id`, 3 retries with exponential backoff, DLQ on
persistent failure. Every handler updates `index_health.last_event_at`
for its vertical on success.

### 4.1 `restaurant.updated.v1`

- **Producer**: `restaurant-service`.
- **Topic**: `restaurant.restaurant.updated`.
- **Reason**: project to the `restaurants` index.
- **Handler**:
  1. Inbox insert (`event_id`).
  2. Inspect event payload — if it carries the full restaurant
     document (`include=full` in the event), use it directly; else
     hydrate via `GET /v1/restaurants/{id}` (per §2.2).
  3. Resolve zone for the restaurant's geo point via
     `geolocation-service` if not in the payload.
  4. Denormalize: join with `merchant.updated.v1` snapshot (merchant
     name, rating) from the local relevance-config cache.
  5. Apply locale analyzer choice (`en` → `english`,
     `ar` → `arabic_normalized`) per `ERD.md` §12.3.
  6. Upsert to OpenSearch `restaurants` index with the active
     `relevance_config.field_boosts` (per `ERD.md` §12.2).
  7. Update `index_health.last_event_at` for `restaurants`.
  8. Inbox update (`processed_at`).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff (250ms / 1s / 4s).
- **Failure**: DLQ → `restaurant.restaurant.updated.dlq`.

### 4.2 `menu.updated.v1`

- **Producer**: ``restaurant-service` (menu)`.
- **Topic**: `restaurant.menu-item.updated`.
- **Reason**: project to the `menu_items` index.
- **Handler**:
  1. Inbox insert (`event_id`).
  2. Hydrate via `GET /v1/menu-items/{id}` if event payload
     incomplete (per §2.2).
  3. Denormalize: pull restaurant name + cuisine from the
     `restaurants` index (read-through, 200ms timeout).
  4. Upsert to OpenSearch `menu_items` index.
  5. Update `index_health.last_event_at` for `menu_items`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ → `restaurant.menu-item.updated.dlq`.

### 4.3 `merchant.updated.v1`

- **Producer**: ``restaurant-service` (merchant)`.
- **Topic**: `restaurant.merchant.updated`.
- **Reason**: project to the `merchants` index.
- **Handler**: same shape as 4.1 (hydrates from REST, denormalizes
  zone, upserts to OpenSearch). Target index: `merchants`.

### 4.4 `support.ticket.updated.v1`

- **Producer**: `admin-service` (support module; the support module
  absorbed the legacy `support-service` per `MIGRATION_HUB.md` 3.20).
- **Topic**: `admin.support.ticket.updated`.
- **Reason**: project to the `tickets` index.
- **Handler**:
  1. Inbox insert (`event_id`).
  2. AuthZ gate: the event's `actor_sub` must have role
     `support_agent_l1+` or `admin` for the ticket's tenant
     (defense in depth — even though the producer is internal).
  3. Hydrate via `GET /v1/support/tickets/{id}` if payload
     incomplete.
  4. PII filter: strip fields tagged `pii=true` from the indexed
     payload (e.g. customer email, phone, full ticket body if
     sensitive).
  5. Upsert to OpenSearch `tickets` index (authZ is re-checked
     at query time per FR--001 auth table).
  6. Update `index_health.last_event_at` for `tickets`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ → `admin.support.ticket.updated.dlq`.
- **Retention**: tickets are kept in the index for `search.tickets.retention_days`
  (default 365, configurable per tenant).

### 4.5 `zone.updated.v1`

- **Producer**: ``geolocation-service` (zones)`.
- **Topic**: `geo.zone.updated`.
- **Reason**: refresh geo filter (which restaurants / menu items
  are in which zone).
- **Handler**:
  1. Inbox insert (`event_id`).
  2. Compute the diff: list of `restaurant_id`s whose zone
     membership changed (old zone vs new zone).
  3. If the diff is non-empty, re-fetch and re-upsert the affected
     restaurant docs to update the `zone_id` field (and the
     derived geo filter).
  4. If the diff is empty (zone metadata changed but boundaries
     unchanged), skip the reindex — the geo index hasn't moved.
  5. Update `index_health.last_event_at` for `restaurants`
     (zone changes affect the `restaurants` index).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ → `geo.zone.updated.dlq`.

### 4.6 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Topic**: `config.search.updated`.
- **Reason**: relevance config, locale config, TTLs changed.
- **Handler**:
  1. Inbox insert (`event_id`).
  2. Compute the config hash (SHA-256 of the JSON payload); compare
     to the local cached hash.
  3. If the hash matches, no-op (idempotent).
  4. If the hash differs, reload the config into the in-process
     cache and the Redis hot cache (`cache:search:config:{key}`).
  5. If a reindex is required (mapping change), trigger the reindex
     workflow (`WORKFLOWS.md` §3) — but only for *new* indices;
     existing indices continue with the old config until their
     next natural reindex.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ → `config.search.updated.dlq`.

### 4.7 `feature_flag.updated.v1`

- **Producer**: ``configuration-service` (flags)`.
- **Topic**: `flag.search.updated`.
- **Reason**: A/B routing for relevance tests changed.
- **Handler**: same shape as 4.6 (idempotent hash compare, reload
  in-process + Redis cache). No reindex triggered by A/B flag
  changes — the flag is read at search time per request.

### 4.8 `review.submitted.v1`

- **Producer**: `trip-service` (ride reviews) / `food-order-service`
  (food reviews) / ``restaurant-service` (review projections)` —
  per `MIGRATION_HUB.md` 3.12.
- **Topic**: `review.review.submitted`.
- **Reason**: maintain `search.reviews` (per `README.md` Appendix A).
- **Handler**:
  1. Inbox insert (`event_id`).
  2. AuthZ gate: the event's `actor_sub` must equal the review's
     `customer_id` (only the actual reviewer can publish a review).
  3. Hydrate from owner service if event payload incomplete.
  4. Upsert to OpenSearch `reviews` index.
  5. Emit `search.review.projection.upserted.v1` to
     `reporting-service` (analytics) — outbox.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ → `review.review.submitted.dlq`.

### 4.9 `review.aggregated.v1`

- **Producer**: `trip-service` / `food-order-service`.
- **Topic**: `review.aggregated.updated`.
- **Reason**: maintain `search.rating_aggregates` (per
  `README.md` Appendix A).
- **Handler**:
  1. Inbox insert (`event_id`).
  2. Idempotent: aggregate is keyed by
     `(subject_kind, subject_id, locale)`; replace the existing
     aggregate row.
  3. Upsert to OpenSearch `rating_aggregates` index (or update
     the denormalized `rating` and `rating_count` fields on the
     parent index — `restaurants` / `menu_items` — depending on
     `search.aggregates.denormalize` config).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ → `review.aggregated.updated.dlq`.

### 4.10 Outbox / Inbox pattern

All consumed events flow through:

```
Kafka topic
   │
   ▼
search.inbox (PostgreSQL, dedup on event_id)
   │  retry 3x (250ms / 1s / 4s)
   ▼
handler (§4.1 – §4.9)
   │  per-step:
   │    - hydrate via REST (if event payload incomplete)
   │    - denormalize (zone, merchant, rating)
   │    - upsert to OpenSearch
   │    - update index_health
   ▼
search.inbox (mark processed_at)
   │
   ▼
DLQ on persistent failure
```

The inbox is partitioned by `vertical` (one partition per vertical
across all consumers) so a slow `tickets` consumer cannot back up
`restaurants` consumers.

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
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``configuration-service` (flags)`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``restaurant-service` (menu)`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``restaurant-service` (merchant)`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`audit-service`](../audit-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (inventory)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (menu)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``admin-service` (support module)`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

