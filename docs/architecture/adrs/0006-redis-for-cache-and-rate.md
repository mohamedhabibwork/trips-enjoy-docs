# ADR-0006: Redis for Cache, Sessions, and Rate Limiting

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: cache, redis, sessions, rate-limiting, performance

## Context and Problem Statement

The platform has three orthogonal needs that are best served by a
fast in-memory data store with rich data structures: (a) read-through
caching of hot data (e.g. driver profile in `trip-service`
(ride-request sub-aggregate), restaurant profile in
`food-order-service` (cart sub-aggregate), configuration in every
service), (b) session and token-state storage (Keycloak session
mirrors at the gateway, the gateway's token-revocation set, the
gateway's idempotency cache), and (c) rate limiting (per-token,
per-IP, per-route counters at the gateway, and per-OTP-phone
counters at the `identity-service` OTP endpoint). A single
technology that handles all three keeps the operational surface
small. The decision is which in-memory store to standardize on,
and how to use it without becoming a single point of failure for
the platform.

## Decision Drivers

- Sub-millisecond read latency for cache hits on hot paths.
- Rich data structures: strings (caching), hashes (token-revocation
  set with TTL), sorted sets (sliding-window rate limit), lists
  (idempotency-key cache), streams (optional).
- TTL with eviction policies (`allkeys-lru`, `volatile-lru`,
  `volatile-ttl`) for cache hygiene.
- Atomic operations (`INCR`, `EVAL` for Lua scripts) for rate
  limiting without races.
- Persistence option (`RDB` + `AOF`) so the rate-limit and
  token-revocation sets survive a restart without long warm-up.
- Pub/Sub for cross-instance cache invalidation
  (`configuration.updated.v1` invalidation, idempotency-key
  invalidation).
- Cluster mode for horizontal scaling; per-service logical databases
  (`db 0`, `db 1`, …) for tenant separation in shared deployments.
- Operational maturity: Sentinel for HA, Cluster for sharding,
  mature monitoring, mature client libraries.

## Considered Options

- **Redis 8 (with Cluster and Sentinel)** — the chosen option.
- **Memcached** — the other widely deployed in-memory cache.
- **Hazelcast** — distributed in-memory data grid with strong
  consistency.
- **In-process cache (Caffeine, Guava)** — no network round-trip,
  but no cross-instance consistency.

## Decision Outcome

Chosen option: "**Redis 8 (Cluster + Sentinel)**", because (a) it
gives us the three data shapes we need in one engine (caching,
session/token state, rate limiting), (b) its data structures
(strings, hashes, sorted sets, lists) and atomic operations (`INCR`,
`EVAL` for Lua scripts) make sliding-window rate limiting and
token-revocation sets straightforward, (c) TTL with eviction
policies is built in (cache hygiene is automatic), (d) Cluster mode
gives us horizontal scaling, and Sentinel (or Cluster's own HA)
gives us high availability, and (e) the operational maturity
(monitoring via Redis Exporter, backup via `RDB`/`AOF`, mature
client libraries in every language) is what we need. We run one
Redis cluster per region; per-service logical databases (Redis
`db 0..15`) for tenant separation in shared deployments; physical
isolation for the noisiest workloads (rate limiting at the
gateway, location cache).

### Consequences

- Good: One in-memory engine for cache, sessions, and rate limits.
  The platform team's on-call runbook covers one technology.
- Good: Rich data structures and atomic operations. Sliding-window
  rate limit is a sorted-set + Lua script; token-revocation set is
  a hash with TTL; idempotency cache is a string with TTL.
- Good: TTL + eviction policies. Cache hygiene is automatic; we do
  not grow unbounded.
- Good: Cluster mode for horizontal scaling. We can add shards
  without downtime.
- Good: Persistence (`RDB` + `AOF`) for the rate-limit and
  token-revocation sets; cache-only databases can be ephemeral.
- Good: Pub/Sub for cross-instance cache invalidation.
- Good: Mature client libraries (Lettuce, Jedis, redis-py, go-redis)
  with connection pooling and automatic reconnection.
- Bad: An additional piece of infrastructure to operate. (Mitigation:
  a dedicated platform team that owns the Redis cluster; a
  per-region runbook; quarterly DR drills.)
- Bad: In-memory data is lost on a hard failure unless persisted.
  We accept this for caches (cold cache is a soft failure) and
  require persistence for rate-limit and token-revocation sets
  (these are correctness-critical).
- Bad: Cluster mode has operational constraints (no multi-key
  transactions across slots; resharding is a planned operation).
  We mitigate with hash-tagging for the few cross-key operations
  we need and by sizing the cluster for peak with headroom.
- Neutral: Per-service logical databases are an organizational
  choice, not a security boundary; cross-database access is
  controlled by the service's credentials, not by the database
  number.

### Confirmation

- Redis cluster availability ≥ 99.95% per region (Tier-1 SLO).
- Cache hit rate: per-service `cache_hit_ratio` metric; target ≥ 90%
  for the dominant hot keys (driver profile, restaurant profile,
  configuration).
- Rate-limit effectiveness: the gateway's per-token, per-IP,
  per-route rate limits are exercised in load tests; the 429
  response rate is within the documented threshold.
- Token-revocation set: time from `identity.session.revoked.v1` to
  Redis-set update < 1 second.
- Memory pressure: cluster-wide memory utilization < 70%; alert at
  80%.

## Pros and Cons of the Options

### Redis 8 (Cluster + Sentinel)

The chosen option. In-memory data structures with TTL, persistence,
Pub/Sub, and Cluster mode for horizontal scaling.

- Good: One engine for cache, sessions, and rate limits.
- Good: Rich data structures (strings, hashes, sorted sets, lists,
  streams, bitmaps, hyperloglog) and atomic operations.
- Good: TTL with eviction policies; cache hygiene is automatic.
- Good: Pub/Sub for cross-instance cache invalidation.
- Good: Cluster mode for horizontal scaling; Sentinel for HA.
- Good: Mature client libraries in every language; mature
  monitoring (Redis Exporter, `redis-cli`).
- Bad: An additional infrastructure component to operate.
- Bad: In-memory data loss on hard failure (mitigated by
  persistence for correctness-critical sets).
- Bad: Cluster constraints (no multi-key transactions across slots;
  resharding is planned).

### Memcached

A simple, fast, in-memory key-value cache.

- Good: Very fast; simple to operate.
- Good: Widely deployed; well-understood.
- Bad: No rich data structures (only strings). Rate limiting would
  require a separate store; session state would require a separate
  store; we'd run two technologies.
- Bad: No persistence; rate-limit and token-revocation sets would
  be lost on restart.
- Bad: No Pub/Sub for cross-instance cache invalidation.
- Bad: No atomic increment with TTL (rate limiting needs a Lua
  script equivalent or a separate store).

### Hazelcast

Distributed in-memory data grid with strong consistency.

- Good: Distributed; strong consistency options.
- Good: Rich data structures (map, queue, topic, ringbuffer).
- Good: Embedded mode (no separate cluster for some use cases).
- Bad: We have no in-house Hazelcast expertise.
- Bad: Operational maturity is lower than Redis for our use cases.
- Bad: Cluster scaling and rebalancing is more operationally
  complex than Redis Cluster.
- Bad: Embedding it in JVM services locks us to a single language
  runtime; the platform is polyglot.

### In-process cache (Caffeine, Guava)

No network round-trip; the cache is in the service's process.

- Good: Zero network latency; no additional infrastructure.
- Good: No serialization cost.
- Bad: No cross-instance consistency. A configuration change in
  one instance is not visible to another until the local TTL
  expires; a token revocation must propagate to every instance.
- Bad: Limited memory budget (the service's heap). We would
  evict under pressure exactly when we least want to.
- Bad: Every service must reinvent the same patterns (rate limit,
  idempotency cache, token-revocation set). The platform team
  cannot provide a shared implementation.
- Bad: We already need a cross-instance cache for the gateway's
  state; an in-process cache does not solve that.

## References

- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — Redis per service for
  cache/session/rate, in the layered view.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) —
  token-revocation set in Redis; rate limiting; defense in depth.
- [`API_STANDARDS.md`](../API_STANDARDS.md) — rate-limit headers
  and the `429 RATE_LIMITED` error code.
- [`KEYCLOAK_ARCHITECTURE.md`](../KEYCLOAK_ARCHITECTURE.md) —
  logout and session revocation at the gateway; the Redis-backed
  revocation set.
- [`DEPLOYMENT_ARCHITECTURE.md`](../DEPLOYMENT_ARCHITECTURE.md) —
  Redis as a platform component in the data layer.
- Redis documentation — Cluster, Sentinel, persistence (`RDB` +
  `AOF`), eviction policies, Lua scripting, Pub/Sub.
