# ledger-service (Kotlin / Spring Boot 4)

This is the **apps/** scaffold for `ledger-service`, the platform's
authoritative double-entry financial ledger. It implements every contract
documented in `../../docs/services/ledger-service/` (README, BRD, SRS, ERD,
INTEGRATION, WORKFLOWS, TECH).

## Quickstart

```bash
# 1. Start the shared `trips_enjoy` Postgres + Keycloak + Kafka stack.
make db-init

# 2. Copy the env template and fill in any non-default values.
cp .env.example .env

# 3. Build + run.
./gradlew bootRun
```

The service listens on **port 8087**.

- `/health`, `/ready`, `/started` — actuator probes
- `/openapi.json`, `/docs` — Swagger UI
- `/actuator/prometheus` — Prometheus metrics
- `/v1/postings`, `/v1/accounts`, `/v1/reports/*` — public API (JWT bearer)
- `/admin/v1/ledger/*` — admin endpoints (Keycloak `ledger.admin` /
  `platform.admin` / `platform.super_admin`)

## Implementation surface

```
src/main/kotlin/com/trips_enjoy/ledger/
├── LedgerServiceApplication.kt     # @SpringBootApplication + @EnableScheduling
├── api/
│   ├── ApiException.kt             # Platform error envelope (RFC 7807)
│   ├── ApiExceptionHandler.kt
│   ├── LedgerController.kt         # /v1/postings, /v1/accounts, /v1/reports/*
│   ├── LedgerDtos.kt               # Request / response DTOs + mappers
│   └── admin/
│       └── AdminLedgerController.kt # /admin/v1/ledger/*
├── application/
│   ├── PostingService.kt           # Double-entry invariant + write kernel
│   ├── ReportService.kt            # Trial balance, balance sheet, P&L
│   ├── OutboxPublisher.kt          # Polls outbox → Kafka
│   ├── PartitionMaintenanceJob.kt  # 12-month rolling partitions (02:00 UTC)
│   ├── ReconciliationJob.kt        # Daily reconciliation (04:00 UTC)
│   ├── InboxCleanup.kt             # Inbox 30-day retention (05:30 UTC)
│   └── AdminAuditPublisher.kt      # audit.admin.ledger.v1 emission
├── config/
│   ├── SecurityConfiguration.kt    # JWT resource server + roles / scopes
│   ├── JacksonConfiguration.kt     # Primary Jackson 2 ObjectMapper
│   ├── OpenApiConfiguration.kt     # OpenAPI 3 + bearer auth
│   ├── KafkaConsumerConfiguration.kt # DLQ + exponential backoff
│   └── RequestCorrelationFilter.kt # ADR-0019 X-Request-Id propagation
├── domain/
│   ├── Account.kt, Posting.kt, PostingEntry.kt, AccountBalance.kt,
│   ├── JournalEntry.kt, ReconciliationRun.kt, OutboxEvent.kt, InboxEvent.kt
│   └── Repositories.kt             # Spring Data interfaces + custom queries
├── integration/events/
│   └── MoneyMovementConsumer.kt    # Async posting path (Kafka)
└── util/UuidV7.kt                  # Time-ordered UUIDs

src/main/resources/
├── application.yml                 # Spring Boot 4 baseline
├── application-dev.yml             # localhost defaults (includes db/dev)
├── application-stg.yml             # stg env (no defaults; uses Vault)
├── application-prod.yml            # prod env (no defaults; uses Vault)
├── application.properties          # Spring property overrides (commented)
├── db/migration/                   # Flyway migrations (always applied)
│   ├── V1__create_ledger_schema.sql
│   ├── V2__create_ledger_tables.sql             # full ERD + partitions + triggers
│   ├── V3__seed_chart_of_accounts.sql           # 76 accounts
│   ├── V4__seed_dimensions_currency_account_type_tenant.sql
│   ├── V5__gl_account_mapping.sql               # 28 event → account mappings
│   ├── V6__accounting_periods.sql               # 13 monthly periods × 6 tenants
│   ├── V7__reconciliation_rules.sql             # 8 declarative rules
│   └── V8__retention_policy.sql                 # 15 per-table retention bands
└── db/dev/                         # dev-only seed (profile=dev only)
    └── V9__dev_seed_account_balances_and_sample_postings.sql

Dockerfile                          # multi-stage Gradle 9 → Temurin 25 JRE

k8s/                                # Kubernetes manifests (production-grade)
├── kustomization.yaml              # env-agnostic base
├── ledger-service-config.yaml      # ConfigMap (non-secret) + Secret skeleton
├── ledger-service-policy.yaml      # NetworkPolicy + PDB + SA + Role/RoleBinding
│                                  # + PriorityClass + Service + Headless Service
├── ledger-service.yaml             # Deployment (6 replicas) + HPA (CPU + outbox-lag
│                                  # + consumer-lag) + Helm migrate Job + retention
│                                  # CronJob + reconciliation CronJob + Ingress
└── overlays/
    ├── dev/                        # 1 replica, dev profile, CronJobs suspended
    ├── stg/                        # 3 replicas, stg profile, 1-year retention
    └── prod/                       # 6+ replicas, prod profile, full 10-year retention
                                    # + stricter PDB (75% minAvailable)

monitoring/                         # Observability stack
├── ledger-service-servicemonitor.yaml  # ServiceMonitor + PodMonitor (Prometheus
│                                        # Operator) + PrometheusRule (recording rules
│                                        # + 14 alert rules)
├── ledger-service-dashboard.json  # Grafana dashboard (13 panels: SLO burn-rate,
│                                  # posting rate, latency, outbox lag, reconciliation,
│                                  # trial-balance drift, heap, consumer lag, balance
│                                  # read, report latency, JVM memory, 5xx ratio)
├── ledger-service-slo.md          # SLOs / SLIs / error-budget doc
└── ledger-service-runbook.md      # Operational triage for 8 high-impact alert paths
```

## Validation

```bash
# Run the unit + integration test suite.
./gradlew test

# Build the production jar without starting the app.
./gradlew bootJar
```

Tests cover the **double-entry invariants** with 100% pin coverage per
`docs/services/ledger-service/SRS §23`:

- balanced posting accepted
- unbalanced / single-entry → `UNBALANCED_POSTING`
- mixed currency → `CURRENCY_MISMATCH`
- unknown account → `ACCOUNT_NOT_FOUND`
- clock-skew > 5 min → `TIMESTAMP_OUT_OF_BOUNDS`
- `audit_note` < 10 chars → `AUDIT_NOTE_REQUIRED`

The integration test (`LedgerServiceApplicationTests`) uses Testcontainers
(PostgreSQL, Kafka, Redis) and exercises the Spring application context.

## Operational notes

- The append-only invariant on `postings` / `posting_entries` is enforced
  by a database trigger (`ledger.deny_posting_mutation`) **and** by
  revoking UPDATE/DELETE privileges from the application role.
- Monthly partitions for `postings` and `posting_entries` are pre-created
  by `PartitionMaintenanceJob` (02:00 UTC, advisory-lock leader-elected).
  Drop is by retention policy (default 10 years).
- The reconciliation job (`04:00 UTC`) compares the operational layers'
  totals against the ledger. Drift emits
  `ledger.audit.reconciliation_drift.v1` and increments the
  `ledger_reconciliation_drift` Prometheus counter.

See `../../docs/services/ledger-service/` for the authoritative contracts
this scaffold implements.
