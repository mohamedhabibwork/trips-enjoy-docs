# Ledger Service Guide

This is a Kotlin 2.4 / Spring Boot 4.1 service running on Java 25. Read the
matching documentation in `../../docs/services/ledger-service/` before changes.

- Keep controllers, application services, persistence, integrations, and
  configuration separated under `src/`.
- Treat financial postings as immutable and auditable; preserve documented
  balancing, idempotency, reconciliation, and retention requirements.
- Use Flyway migrations for schema changes. The service owns its PostgreSQL
  schema and must not use cross-service foreign keys.
- Preserve correlation IDs, idempotency, Kafka event versioning, and resilient
  downstream behavior required by the platform documentation.
- Run `./gradlew test` for relevant changes; use `./gradlew bootRun` only when
  local dependencies and configuration are available.
- Do not edit `build/`, `.gradle/`, or `.kotlin/`.

## Source layout

- `src/main/kotlin/com/trips_enjoy/ledger/LedgerServiceApplication.kt` —
  `@SpringBootApplication` + `@EnableScheduling`. The application boots with
  the platform's standard config wiring (Keycloak resource server, Kafka,
  Flyway, JPA, observability).
- `src/main/kotlin/com/trips_enjoy/ledger/domain/` — JPA entities
  (`Account`, `Posting`, `PostingEntry`, `AccountBalance`, `JournalEntry`,
  `ReconciliationRun`, `OutboxEvent`, `InboxEvent`) and `Repositories.kt`
  with Spring Data interfaces. `AccountRepository.lockCurrentByCode` is the
  single point of row-level locking for the chart-of-accounts row (SRS §14).
- `src/main/kotlin/com/trips_enjoy/ledger/application/` — application
  services and scheduled jobs:
  - `PostingService` — the core write kernel (validates the double-entry
    invariant, locks account rows, persists posting + entries + balances +
    outbox row + (for manual) journal_entry, all in one transaction).
  - `ReportService` — read models for the report endpoints.
  - `OutboxPublisher` — polls unpublished outbox rows and ships them to
    Kafka (1 s cadence).
  - `PartitionMaintenanceJob` — pre-creates the next 12 monthly child
    partitions for `postings` + `posting_entries` (advisory-lock leader-
    elected; 02:00 UTC daily).
  - `ReconciliationJob` — daily reconciliation against the operational
    layers (04:00 UTC). Emits `ledger.audit.reconciled.v1` (matched) or
    `ledger.audit.reconciliation_drift.v1` (drift).
  - `InboxCleanup` — drops inbox rows older than 30 days.
  - `AdminAuditPublisher` — emits `audit.admin.ledger.v1` for every admin
    call.
- `src/main/kotlin/com/trips_enjoy/ledger/api/` — controllers + DTOs +
  ApiException handler.
  - `LedgerController` — the v1 ledger API (mounted at `/v1`):
    `POST /v1/postings`, `POST /v1/journal-entries`, `GET /v1/postings/{id}`,
    `GET /v1/postings`, `GET /v1/accounts`, `GET /v1/accounts/{code}`,
    `GET /v1/accounts/{code}/balance`, `GET /v1/reports/trial-balance`,
    `GET /v1/reports/balance-sheet`, `GET /v1/reports/income-statement`.
  - `admin/AdminLedgerController` — admin endpoints (mounted at
    `/admin/v1/ledger`):
    `POST /admin/v1/ledger/reconciliation/run`,
    `GET /admin/v1/ledger/reconciliation/last`,
    `GET /admin/v1/ledger/accounts/{code}/balance`.
- `src/main/kotlin/com/trips_enjoy/ledger/integration/events/` — Kafka
  consumer (`MoneyMovementConsumer`) for the money-movement topics.
- `src/main/kotlin/com/trips_enjoy/ledger/config/` — Spring Security
  (`SecurityConfiguration`), Jackson, OpenAPI, Kafka consumer / error
  handler, request-correlation filter (ADR-0019).
- `src/main/resources/db/migration/` — Flyway migrations (run on every
  boot unless overridden by the dev profile):
  - `V1__create_ledger_schema.sql` — schema only.
  - `V2__create_ledger_tables.sql` — full ERD (accounts, postings,
    posting_entries, account_balances, journal_entries, reconciliation_runs,
    outbox, inbox) + monthly RANGE partitions + append-only triggers +
    per-row validation trigger.
  - `V3__seed_chart_of_accounts.sql` — the default chart of accounts
    (assets, liabilities, equity, revenue, expense + payouts) inserted
    as version-1 rows.
  - `V4__seed_dimensions_currency_account_type_tenant.sql` — dimensional
    seeders: `currencies` (ISO 4217 + base currency + rounding mode),
    `account_types` (statement-section + affects_pl/balance_sheet flags),
    `tenants` (multi-tenant config + fiscal year), and `exchange_rates`
    (today's reference rates for dev).
  - `V5__gl_account_mapping.sql` — bridges operational financial
    services' money-movement events to the ledger's chart-of-accounts
    codes. Versioned (`effective_from` / `effective_to`).
  - `V6__accounting_periods.sql` — open / closing / closed / locked
    monthly periods for every active tenant. Period close workflow
    drives the application-layer `PeriodCloseJob`.
  - `V7__reconciliation_rules.sql` — declarative reconciliation rules
    (`equality`, `sum_equality`, `ratio`) with tolerance and severity.
    The daily reconciliation job reads these rules as data so adding a
    new layer doesn't require a code change.
  - `V8__retention_policy.sql` — per-table retention bands (forever /
    regulatory / operational / transient) with the purge strategy. The
    application-layer `RetentionService` reads this table as the single
    source of truth.
- `src/main/resources/db/dev/` — dev-only migrations (run only when the
  active profile is `dev`):
  - `V9__dev_seed_account_balances_and_sample_postings.sql` — baseline
    account balances for every account + 6 sample postings across the
    last 30 days + 2 sample reconciliation runs. Gives a developer a
    coherent baseline so the trial balance, balance sheet, income
    statement, and reconciliation endpoints return useful data.

## Migrations

Flyway runs on every boot via `spring-boot-starter-flyway`; the application
role has INSERT-only on `postings` / `posting_entries` and the database
trigger `ledger.deny_posting_mutation` rejects UPDATE / DELETE / TRUNCATE
(belt-and-suspenders per SEC--002). Append a `V<n>__*.sql` for new schema
changes; never edit a committed migration.

The monthly partition pre-creation job (`PartitionMaintenanceJob`) follows
the canonical template (CREATE TABLE IF NOT EXISTS … PARTITION OF … plus
`pg_inherits` + `relpartbound` verification) per
`docs/architecture/DATABASE_ARCHITECTURE.md`.

## Deployment

- `Dockerfile` — multi-stage build (Gradle 9 / JDK 21 build, Temurin 25 JRE
  final, non-root user, JRE-only).
- `k8s/kustomization.yaml` — env-agnostic base; overlays in
  `k8s/overlays/{dev,stg,prod}` patch replicas / image tag / ConfigMap
  values per environment. Apply with `kubectl apply -k k8s/overlays/<env>`.
- `k8s/ledger-service-config.yaml` — `ConfigMap` (non-secret knobs:
  profile, retention years, cron schedules, log levels, JVM_OPTS) +
  `Secret` skeleton with placeholder values (real values injected via
  Vault / ExternalSecrets Operator).
- `k8s/ledger-service-policy.yaml` — `NetworkPolicy` (egress: DNS / PG /
  Kafka / Keycloak / OTel; ingress: gateway, admin, monitoring),
  `PodDisruptionBudget` (50% base, 75% in prod overlay),
  `ServiceAccount` (linkerd-inject annotation), `Role`/`RoleBinding`
  (ConfigMap + Secret reads), `PriorityClass` (Tier-1, value 900000),
  `Service` + headless `Service` for in-cluster leader election.
- `k8s/ledger-service.yaml` — `Deployment` (6 replicas, rolling update,
  60s `terminationGracePeriodSeconds` for graceful Kafka drain,
  `readOnlyRootFilesystem`, securityContext dropping ALL capabilities,
  topologySpread across nodes), `HorizontalPodAutoscaler` (CPU 70% +
  `ledger_outbox_oldest_unpublished_seconds` +
  `ledger_consumer_lag_messages`, 6–12 in base, 6–18 in prod overlay),
  Helm pre-install Job for Flyway (`helm.sh/hook: pre-install,pre-upgrade`),
  CronJob for daily retention at 03:00 UTC, CronJob for daily
  reconciliation at 04:00 UTC, Ingress for `ledger.trips-enjoy.com`.
- `k8s/overlays/dev/kustomization.yaml` — single replica, dev profile,
  reduced retention, CronJobs suspended (in-process scheduler runs them).
- `k8s/overlays/stg/kustomization.yaml` — 3 replicas, stg profile, 1-year
  retention.
- `k8s/overlays/prod/kustomization.yaml` — 6+ replicas (HPA 6–18), prod
  profile, full 10-year retention, stricter PDB (75% minAvailable),
  doubled CPU/memory requests + limits.

## Observability

- `monitoring/ledger-service-servicemonitor.yaml` — `ServiceMonitor` +
  `PodMonitor` (Prometheus Operator) scraping `/actuator/prometheus`
  every 15s, plus a `PrometheusRule` with **recording rules** (12
  precomputed series the dashboards consume: `ledger:posting_p99_seconds`,
  `ledger:error_ratio_5m`, `ledger:outbox_lag_seconds_max`, etc.) and
  **alert rules** across 3 groups (SLO, correctness, operations) for 14
  high-signal conditions.
- `monitoring/ledger-service-dashboard.json` — Grafana dashboard with 13
  panels: SLO burn-rate (multi-window), postings rate, posting latency
  p50/p95/p99, outbox lag, reconciliation drift, trial-balance drift,
  JVM heap gauge, Kafka consumer lag, 5xx error ratio, balance read
  p99, report p99, JVM memory, request rate by status.
- `monitoring/ledger-service-slo.md` — SLO inventory (6 SLOs with SLIs,
  windows, error budgets), burn-rate guide, monthly review process.
- `monitoring/ledger-service-runbook.md` — operational triage for the 8
  high-impact alert paths: posting latency, reconciliation drift,
  outbox lag, Kafka consumer lag, JVM heap, reconciliation job
  missing, partition maintenance missing, pod crashlooping.

## Security and Quality Gate

- Never commit secrets, tokens, private keys, production data, or unredacted
  PII; redact sensitive values from logs, errors, and test fixtures. Use
  `.env` (gitignored) for local dev secrets — never commit them.
- Authenticate callers, authorize every operation, validate inputs, and keep
  data access parameterized. Do not disable security checks to pass tests.
- The double-entry invariant is enforced in `PostingService` and verified
  by the database trigger on `posting_entries` (per-row validation). The
  trial-balance invariant is enforced by the daily reconciliation job.
- Run `./gradlew test`; run configured formatting or lint tasks when present,
  and report checks that cannot run.

## Tests

- `src/test/kotlin/com/trips_enjoy/ledger/unit/PostingServiceBalanceTest.kt`
  — pins the double-entry invariants: balanced posting accepted;
  unbalanced / single-entry rejected with `UNBALANCED_POSTING`; mixed
  currency rejected with `CURRENCY_MISMATCH`; missing account rejected with
  `ACCOUNT_NOT_FOUND`; clock-skew > 5 min rejected with
  `TIMESTAMP_OUT_OF_BOUNDS`; short `audit_note` rejected with
  `AUDIT_NOTE_REQUIRED`.
- `src/test/kotlin/com/trips_enjoy/ledger/unit/ApiExceptionHandlerTest.kt`
  — verifies the platform error envelope (RFC 7807 + code + correlationId).
- `src/test/kotlin/com/trips_enjoy/ledger/unit/RequestCorrelationFilterTest.kt`
  — verifies ADR-0019: X-Request-Id / X-Correlation-Id propagation and
  fallback.
- The integration test (`LedgerServiceApplicationTests`) uses Testcontainers
  (PostgreSQL, Kafka, Redis) and exercises the application context.
