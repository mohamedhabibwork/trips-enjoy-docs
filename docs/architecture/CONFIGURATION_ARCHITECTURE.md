# Configuration Architecture

The platform is **highly configurable**. Fares, fees, taxes, zones,
ride types, feature flags, order limits, eligibility rules — none of
these are hard-coded. They live in `configuration-service` (which
absorbed the flags sub-aggregate per the 58 → 20 consolidation),
are exposed via REST and Kafka, and are consumed by every service.


```mermaid
flowchart TB
  subgraph Source["Source of truth"]
    git["Git repo<br/>(config + flags)"]
    cfg["configuration-service<br/>(business rules, numbers)"]
    ff["`configuration-service` (flags<br/>+ lookup admin)<br/>(rollouts, segments)"]
  end
  subgraph Delivery["Delivery"]
    rest["REST API<br/>(GET /v1/config/<key>)"]
    bus["Spring Cloud Bus event<br/>(on commit, hot reload)"]
    cache["Local cache<br/>(TTL + bus invalidation)"]
  end
  subgraph Hierarchy["Override hierarchy (highest wins)"]
    h1["env-specific override<br/>(K8s ConfigMap)"]
    h2["configuration-service value"]
    h3["built-in default"]
  end
  subgraph Consumers["Consumers"]
    s1["Domain services<br/>(pricing, fees, zones)"]
    s2["Edge / api-gateway<br/>(rate limits, WAF rules)"]
    s3["All services<br/>(feature flags)"]
  end
  git --> cfg & ff
  cfg --> rest --> cache --> Consumers
  cfg --> bus --> cache
  ff --> s3
  h1 --> h2 --> h3
```

## Goals

1. **No redeploy to change a business rule.** Operators edit
   configuration; the change reaches all consumers within seconds.
2. **Per-tenant, per-region, per-city, per-merchant, per-restaurant
   overrides.** Configuration is hierarchical with explicit precedence.
3. **Audit and review.** Every change is versioned, attributed, and
   can be rolled back.
4. **Safe rollout.** Changes can be staged to a cohort (region,
   merchant) before global.
5. **Type safety at the edge.** Consumers can validate the config they
   read.

## Two Services, Two Concerns

| Concern | Service | Data model | Audience |
|---------|---------|------------|----------|
| Business rules and numerical values | `configuration-service` | Versioned documents keyed by `(scope, key)` | All services |
| Rollouts, kill switches, A/B, lookup administration | `configuration-service` (flags + lookup sub-aggregates) | Flag definitions + lookup rows with rules | All services |

Both live in the same `configuration-service` binary (absorbed
the flags service per the 58 → 20 consolidation) and share the
same operational shape (long-poll, cache, push on change).

## Configuration Hierarchy (Precedence, Highest to Lowest)

A configuration lookup is resolved in this order. The first non-null
value wins.

| # | Scope | Example |
|---|-------|---------|
| 1 | User segment (specific user) | `user:01H… → free_ride_credit = 50` |
| 2 | Restaurant (specific restaurant) | `restaurant:01H… → min_order_amount = 20 EUR` |
| 3 | Branch (specific branch) | `branch:01H… → prep_time_minutes = 25` |
| 4 | Merchant (specific merchant) | `merchant:01H… → commission_pct = 12` |
| 5 | Ride type (specific ride type) | `ride_type:premium → multiplier = 1.4` |
| 6 | Zone (specific zone) | `zone:eu-west-amsterdam → surge_cap = 3.0` |
| 7 | City | `city:amsterdam → base_fare = 2.50 EUR` |
| 8 | Country | `country:NL → currency = EUR, language = nl` |
| 9 | User segment (segment) | `segment:new_customer → first_ride_discount = 5 EUR` |
| 10 | Tenant | `tenant:global` |
| 11 | Global default | (fallback) |

The lookup key is composed of:

- The configuration key (e.g. `pricing.base_fare`).
- The evaluation context (e.g. `{ city: amsterdam, ride_type: premium,
  customer_segment: new_customer }`).

The service returns the resolved value, the matched scope, and the
version, so the consumer can audit which rule applied.

## Versioning

- Every configuration document has a `version` (monotonically
  increasing integer per key).
- Writes create a new version; the old version is retained for
  rollback and for historical price snapshots.
- Services consume the latest version, but `pricing-service` captures
  the version used in each `PriceQuote` so historical orders remain
  reproducible.
- Writes go through the admin console; the write is gated by RBAC and
  emits `configuration.updated.v1` with the diff.

## Delivery to Consumers

Each service reads configuration at startup and on
`configuration.updated.v1` events.

Two modes:

- **Pull (long-poll)**: the service keeps an open HTTP/2 connection to
  `GET /v1/configurations/stream?service=...` and receives updates as
  they happen. Default for most services.
- **Push (event)**: services subscribe to `configuration.updated.v1`
  and invalidate their cache. Useful for fanout to many consumers.

Both modes are safe: the in-memory cache is keyed by `(key, version)`,
and the consumer falls back to the previous version if the new
version is invalid.

## Categories of Configurable Values

| Category | Examples | Source of truth |
|----------|----------|------------------|
| Countries, cities, currencies, languages, time zones | `country:NL`, `city:amsterdam`, `currency:EUR` | `configuration-service` |
| Service areas, zones | `zone:eu-west-amsterdam` | `geolocation-service` (zones sub-aggregate, overlaid on `configuration-service`) |
| Ride categories, vehicle categories | `ride_type:economy`, `ride_type:xl` | `configuration-service` |
| Delivery zones | `delivery_zone:amsterdam-center` | `geolocation-service` (zones sub-aggregate) |
| Base fares, distance rates, time rates, minimum fares | `pricing.base_fare`, `pricing.per_km`, `pricing.per_min` | `configuration-service` (read by `pricing-service`) |
| Surge rules | `pricing.surge.max_multiplier`, `pricing.surge.step` | `configuration-service` |
| Cancellation rules | `pricing.cancellation.fee_after_minutes`, `pricing.cancellation.fee_amount` | `configuration-service` |
| Waiting fees | `pricing.waiting.per_min_after` | `configuration-service` |
| Service fees, delivery fees | `pricing.fee.service_pct`, `pricing.fee.delivery_base` | `configuration-service` |
| Platform commissions, merchant commissions, courier commissions | `commission.platform_pct`, `commission.merchant_pct`, `commission.courier_pct` | `configuration-service` |
| Taxes | `tax.vat.nl.standard`, `tax.service_pct` | `pricing-service` (tax sub-aggregate) |
| Tips | `tip.suggested_pcts = [10, 15, 20]` | `configuration-service` |
| Payment methods | `payment.method.allowed = [card, wallet, cash]` | `configuration-service` |
| Promotion rules | (delegated to `pricing-service` (promotion sub-aggregate)) | `pricing-service` (promotion sub-aggregate) |
| Restaurant operating rules | `restaurant.max_active_orders`, `restaurant.prep_time_default` | `configuration-service` |
| Driver eligibility | `driver.min_age`, `driver.vehicle_class_required` | `configuration-service` |
| Courier eligibility | `courier.vehicle_type.allowed`, `courier.min_age` | `configuration-service` |
| Order limits | `order.min_amount`, `order.max_items`, `order.max_delivery_distance_km` | `configuration-service` |
| Maximum delivery distance | `delivery.max_distance_km` | `configuration-service` |
| Feature flags | (delegated to `configuration-service` (flags sub-aggregate)) | `configuration-service` (flags sub-aggregate) |
| Rollout percentages | (delegated to `configuration-service` (flags sub-aggregate)) | `configuration-service` (flags sub-aggregate) |
| Customer eligibility | `customer.min_age`, `customer.allowed_countries` | `configuration-service` |

## Feature Flags

`configuration-service` (flags sub-aggregate) owns a different
shape: a flag has a key, a default, and zero or more rules. Rules
can match on user, segment, region, percentage, or time.

Example:

```yaml
flag: new_dispatch_v2
default: off
rules:
  - when: { region: eu-west, percentage: 10 }
    value: on
  - when: { driver_id: 01H… }
    value: on
```

Resolution: rules are evaluated in order; first match wins. The
service returns the resolved value and the matched rule id.

The same precedence rules apply for scope-based feature flags.

Flags are categorized as:

- **Release** (long-lived, on for most users) — e.g. `new_pricing_v2`.
- **Operational** (kill switches) — e.g. `disable_cash_payments`.
- **Experiment** (A/B) — short-lived, paired with a metric.
- **Permission** (gated) — for partner-only features.

Experiments are owned by the analytics team; their results are
tracked in `reporting-service` (data lake).

## Storing Configuration Per Service

- Each service has a typed configuration client that loads its known
  keys at startup and provides typed accessors
  (`config.baseFare(cityId, rideType)`).
- The client validates types at startup. A misconfiguration causes
  the service to **fail to start** — not to silently use defaults.
- The client also exposes a "snapshot" of the values used in a given
  request, captured for audit (`X-Config-Version-Snapshot`).

## Change Workflow

1. Admin opens `admin-service` → "Configuration" → selects a key.
2. Admin sets new value, scope, and reason. Preview shows which
   services will reload.
3. On save, the new version is committed; `configuration.updated.v1`
   is emitted.
4. The audit log records the change with the admin's identity and the
   diff.
5. Rollback is a one-click "revert to version N" action.

## Historical Snapshots

When a service records a state transition that depends on
configuration (price, tax, fees, eligibility), it MUST capture a
**snapshot** of the configuration values used:

```json
{
  "config_snapshot": {
    "version": 4123,
    "values": {
      "pricing.base_fare": 250,
      "pricing.per_km": 120,
      "tax.vat": 21
    }
  }
}
```

This snapshot is stored alongside the entity (e.g. on `Trip`,
`FoodOrder`, `PriceQuote`) and ensures that the historical record
remains correct even if the configuration is later changed or
deleted.

## Edge / Channel Configuration

- The customer/driver/courier mobile apps download their
  configuration at launch and on `configuration.updated.v1`.
- The configuration payload for the client is a **filtered subset**:
  client apps only see what they need (e.g. ride types, copy, theme).
- This subset is computed by `configuration-service` per channel.

## Anti-Patterns Explicitly Avoided

- Hard-coding fares or fees in code.
- Configuration that lives in environment variables of a service
  (use `configuration-service` for runtime values; env vars are for
  build-time only).
- Untyped configuration (every key has a schema).
- Configuration without a documented owner.
- Configuration that is a different shape per environment without
  guardrails.