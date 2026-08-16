# configuration-service — SLOs

This document defines the Service Level Objectives (SLOs) and their
error budgets for `configuration-service`. The alerts in
`monitoring/configuration-service.yaml` are wired directly to these
SLOs; any breach consumes error budget.

The service is **Tier 1** per `docs/architecture/DEPLOYMENT_ARCHITECTURE.md`
because every other service consumes its read path on every request
(every `trip-service` quote, every `pricing-service` calculation, every
`payment-service` gateway lookup).

Authoritative references:

- `docs/architecture/OBSERVABILITY.md` — observability mandate
- `docs/services/configuration-service/SRS.md` — NFR-001..NFR-011
- `docs/architecture/DEPLOYMENT_ARCHITECTURE.md` — tier sizing

---

## 1. Availability SLO

| Tier | Target | Window | Error budget | Burn-rate alerts |
|------|--------|--------|-------------|------------------|
| T1 | **99.95%** successful | 30 days | ~21 minutes 54 seconds | `ConfigurationServiceHighErrorRate` (>2% over 10m) |

The availability SLO measures the proportion of successful HTTP
responses (status 200-399) over all HTTP responses to the public
endpoints under `/v1/configurations/**` and `/v1/channels/**`.
Internal `/actuator/**` and `/admin/v1/config/**` endpoints are
excluded.

### 1.1 Burn-rate policy

- **2% burn in 1h** → page oncall (P1)
- **5% burn in 6h** → page oncall (P2)
- **50% burn in 3 days** → open a Sev-2 incident, escalate to
  `configuration-service-eng@trips-enjoy.com`

The current alert set (1 rule, 10m window) catches the 1h burn at
the 2x threshold; the platform's P99 latency alert catches long-tail
degradation that doesn't trip the 5xx ratio.

---

## 2. Latency SLOs

| Path | Statistic | Target | Source NFR | Alert |
|------|-----------|--------|-----------|-------|
| `GET /v1/configurations/{key}` (resolved read) | p99 | **< 200ms** | NFR-001 | `ConfigurationServiceReadLatencyP99` |
| `POST /v1/configurations`, `PUT /v1/configurations/{key}/versions`, `POST /v1/configurations/{key}/rollback`, `POST /v1/configurations/{key}/deprecate` | p99 | **< 500ms** | NFR-002 | `ConfigurationServiceWriteLatencyP99` |
| `GET /v1/configurations/{key}` cache hit | p99 | **< 50ms** | NFR-001 (cache hit path) | covered by `ConfigurationServiceReadLatencyP99` + cache-hit-rate metric |
| `GET /v1/configurations/{key}` cache miss (DB) | p99 | **< 250ms** (200ms target + 50ms DB allowance) | NFR-001 | covered by `ConfigurationServiceReadLatencyP99` |

Long-poll (`GET /v1/configurations/stream`) is **not** in the latency
SLO — the API contract is to hold the connection open for up to
`LONGPOLL_MAX_WAIT_SECONDS` (default 25s) per FR-009.

---

## 3. Freshness SLO

> "99% of consumers reload within 5 seconds of a write" (NFR-010)

This is enforced via the **outbox lag** metric: the elapsed time
between the write transaction committing and the `configuration.updated.v1`
event being acked by the broker.

| Metric | Target | Source NFR | Alert |
|--------|--------|-----------|-------|
| `configuration_service:outbox_oldest_unpublished_seconds:max` (p50 over 5m) | **< 1s** | NFR-010 (median 2s) | `ConfigurationServiceOutboxLag` (>5s warning, >60s critical) |

The platform-wide freshness promise is **5 seconds end-to-end**. The
outbox lag covers 95% of the path (DB → Kafka broker). The remaining
~5s is the consumer-side reload latency (each consumer is responsible
for their own reload-time SLO).

### 3.1 Median freshness target

NFR-010 also calls out a **median 2s** target. The recording rule
`configuration_service:outbox_oldest_unpublished_seconds:max` is the
worst-case; the median over 5m windows should be reported via a
Grafana panel (`histogram_quantile(0.50, …)` over the raw histogram).

---

## 4. Durability SLO

> "Zero data loss on regional outage" (NFR-007)

Targets:

| Concern | Target | Source NFR | Alert |
|---------|--------|-----------|-------|
| RPO (recovery point objective) | **5 minutes** | NFR-007 | `ConfigurationServiceSnapshotJobMissing` (recovery point is stale by >26h) |
| RTO (recovery time objective) | **30 minutes** | NFR-007 | No alert — measured via game days |
| Audit chain preservation | **100%** writes attributed | NFR-011 / SEC-007 | covered by daily audit reconciliation in `audit-service` |
| Partition availability | **100%** of writes succeed | FR-007 | `ConfigurationServicePartitionMaintenanceStalled` (>25h) |

The audit-service's hash-chain reconciliation (`audit-service/PartitionMaintenanceJob`)
is the cross-service integrity check. The configuration-service
contributes by ensuring the local `audit_log` table never has a gap.

---

## 5. Maintainability SLO

> "MTTR < 15 minutes" (NFR-006)

| Metric | Target | Source NFR |
|--------|--------|-----------|
| Time from alert firing to mitigation started | **< 15 minutes** | NFR-006 |
| Time from alert firing to root cause identified | **< 30 minutes** | (derived) |

These are tracked via the `platform-oncall` PagerDuty incident
lifecycle. The alert runbook
(`monitoring/configuration-service-runbook.md`) is the mechanism
that makes this SLO achievable.

---

## 6. Observability SLO

> "100% of requests have a trace and a structured log line" (NFR-009)

Targets:

- Every inbound HTTP request emits at least one log line with
  `correlationId` / `requestId` (RequestCorrelationFilter).
- Every inbound HTTP request emits an OpenTelemetry span
  (`GET /v1/configurations/{key}` etc.).
- Every Kafka publish/consume emits an OTel span with
  `traceparent` propagated.

The CI test `ApiExceptionHandlerTest` + `UuidV7Test` enforce the
trace-id format. The OTel coverage is verified by the
`build/libs/configuration-service-*.jar` instrumentation.

---

## 7. Tier placement & upgrade policy

| Attribute | Value |
|-----------|-------|
| Tier | **T1** (per DEPLOYMENT_ARCHITECTURE.md §2) |
| Default replicas | 6 |
| HPA min/max replicas | 6 / 12 |
| PDB | `minAvailable: 50%` (3 of 6 always up) |
| Resource requests | 500m CPU, 1Gi memory |
| Resource limits | 1 CPU, 2Gi memory |
| PriorityClass value | 800000 (below ledger-service's 900000) |

Rolling upgrade policy:

- **Max surge:** 50% (3 new pods before terminating 3 old ones)
- **Max unavailable:** 25% (1 pod can be down during rollout)
- **preStop hook:** `sleep 30` — drains the Kafka consumer + outbox
  publisher before SIGKILL (preserves exactly-once event publication)

---

## 8. SLO summary card

```
SERVICE: configuration-service
TIER:    T1
=============================================
AVAILABILITY   99.95%   (30d, ~21m54s budget)
READ P99       < 200ms
WRITE P99      < 500ms
FRESHNESS P50  < 2s
FRESHNESS P99  < 5s
RPO            < 5 minutes
RTO            < 30 minutes
AUDIT          100% writes attributed
LONG-POLL      ≤ 25s (LONGPOLL_MAX_WAIT_SECONDS)
MTTR           < 15 minutes
=============================================
```