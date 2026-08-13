# Roadmap

What the shared library ships today, what's in the next minor, and
what's planned further out. Updated every sprint.

---

## 1. Now (4.1.x line)

All 11 auto-configurations documented in
[`AUTO_CONFIG.md`](./AUTO_CONFIG.md) are live:

- ✅ Web (RFC 7807, correlation, request logging, PII redaction)
- ✅ Security (Keycloak resource server, RBAC, admin-port contract)
- ✅ Data JPA (BaseEntity, auditing, soft-delete, optimistic lock)
- ✅ Money (value class, arithmetic, JSON, JPA, FX)
- ✅ Caching (Redis Lettuce, JSON serializer, consistent key prefix)
- ✅ Messaging (Kafka producer/consumer, outbox, DLQ, schema registry)
- ✅ Observability (OTel, Micrometer, JSON logs, health indicators)
- ✅ Audit (`audit.api.request.v1` + `audit.admin.<service>.v1`)
- ✅ Error model (RFC 7807 + i18n EN/AR)
- ✅ API docs (SpringDoc with platform defaults)
- ✅ Test (`BaseIntegrationTest`, Testcontainers, JWT minting, slices)

Adoption: all 15 Spring Boot 4 services in the platform (out of 21
active services) consume the
starter. Per-service `TECH.md` 6 references the library for the
admin-port contract.

---

## 2. Next (4.2.x — Q4 2026)

- **WebFlux / coroutines helper**: `BaseCoroutineIntegrationTest`
  with `WebTestClient` extensions; `@WithSpan` for suspend functions.
- **Multi-tenancy**: a `tenantId` MDC key populated from JWT; a
  `tenant-scoped` repository flavour.
- **Rate limiting**: Redis-backed token-bucket filter, configurable
  per route.
- **API key auth**: alternative to JWT for service-to-service calls
  (replaces mTLS in some cases).
- **Outbox UI**: actuator endpoint showing outbox lag, oldest pending
  event, throughput. Useful for ops dashboards.
- **Feature flag integration**: out-of-the-box evaluation of flags
  from ``configuration-service` (flags)` in route annotations.

---

## 3. Later (5.0.x — TBD)

- **Spring Boot 5 + Kotlin 3.0** when they GA.
- **Hibernate 8** migration (when stable).
- **JDK 25 baseline** (currently 21 min, 25 recommended).
- **Native image (GraalVM)** for selected services — pilot with
  `api-gateway` and ``geolocation-service` (ETA/routing)` first.
- **Service mesh integration**: linkerd-specific helpers (e.g.
  propagate `l5d-dst-override` for canary routing).
- **Distributed locks**: Redis-backed lock with lease + fencing token
  (replaces the ad-hoc Redis SETNX in current services).

---

## 4. Out of scope (permanently)

Things that will *not* go into the shared library:

- **Domain entities** — `PaymentIntent`, `Driver`, `Restaurant`. Each
  service owns its own domain.
- **State machines** — Spring Statemachine configs are service-specific.
- **External SDK adapters** — payment provider, map provider, FCM/APNs.
- **Per-service business config** — tariff rules, surge zones,
  cuisine categories. Lives in `configuration-service`.
- **Frontend / BFF code** — Vue 3 + HeadlessUI Vue + TanStack Query for Vue + Nuxt 3 (or Vike/Vite SSR) for the web frontend; **Kotlin + Spring Boot 4** (or Go for edge / hot path) for the BFF — the platform's backend apps are Go, Kotlin, or Python only, no Node.js on the backend. The Nuxt 3 SSR shell + the Vue 3 component library still need Node for frontend tooling, but the BFF API is not Node-based. Lives
  in the web repo.
- **Infrastructure as code** — Terraform, Helm charts. Lives in
  `infra/`.

The library's job is **machinery, not content**. The content lives in
the services and in `configuration-service`.

---

## 5. How to influence the roadmap

- File an issue: `[shared] <feature>`.
- Discuss in `#platform-shared-lib`.
- For a feature already in the library: open a PR. The platform team
  reviews within 1 sprint.
- For a new module: open an RFC. The platform team triages in the
  weekly platform sync.

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary
