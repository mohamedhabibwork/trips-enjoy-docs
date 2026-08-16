# audit-service

`audit-service` is the platform's **immutable audit log**. It consumes every
audit-relevant event from every service, persists them in an append-only,
hash-chained PostgreSQL table, and exposes a strict-RBAC search API for
compliance and security teams. It also emits operational events for the
nightly S3 export, the daily hash-chain verification, and the periodic
consumer-lag metric.

The full design contract is in `../../docs/services/audit-service/`. This
file documents the **deployable Kotlin/Spring Boot service**: build, run,
configure, monitor, and ship to Kubernetes.

## Local development

```bash
# 1. Run the migrations against the shared `trips_enjoy` database
java -jar build/libs/audit-service-0.0.1-SNAPSHOT.jar migrate

# 2. Boot the service against the local dev profile
./gradlew bootRun

# 3. Or build the executable JAR and run it
./gradlew bootJar
java -jar build/libs/audit-service-0.0.1-SNAPSHOT.jar
```

The service listens on port 8083 (the platform-reserved public port for
`audit-service` per the implementation choice; see `TECH.md` §1). Admin
traffic flows through the same port but is restricted by RBAC at the
controller layer (Spring Security `@PreAuthorize("hasRole('audit.admin')")`).

## Endpoints

| Method | URI | Auth | Purpose |
|---|---|---|---|
| `POST` | `/v1/audit/search` | bearer (audit.read) | paginated search across the audit log |
| `GET`  | `/v1/audit/events/{id}` | bearer (audit.read) | read a single event including hash + prev_hash |
| `GET`  | `/v1/audit/verify/{id}` | bearer (audit.admin) | verify the hash chain up to the given event id |
| `POST` | `/v1/audit/litigation-hold` | bearer (audit.admin) | create an append-only litigation hold |
| `GET`  | `/v1/audit/litigation-hold` | bearer (audit.admin) | list active litigation holds |
| `POST` | `/admin/v1/audit/search` | bearer (platform.admin) | admin search with audit.admin.audit.v1 self-emission |
| `POST` | `/admin/v1/audit/export` | bearer (platform.admin) | ad-hoc S3 export |
| `POST` | `/admin/v1/audit/reindex` | bearer (audit.admin) | rebuild the audit search index |
| `GET`  | `/health` `/ready` `/started` | open | k8s probe paths |

OpenAPI 3.1 contract is exposed at `/openapi.json`; Swagger UI at
`/docs`.

## Configuration

All runtime configuration comes from environment variables (or the
Vault-injected `audit-service-runtime` Kubernetes Secret in production).
The dev defaults are in `.env.example`. The Spring property names live
in `src/main/resources/application.yml`.

Key env vars:

| Var | Default | Purpose |
|---|---|---|
| `AUDIT_SERVICE_DB_URL` | `jdbc:postgresql://0.0.0.0:5432/trips_enjoy?currentSchema=audit` | PostgreSQL JDBC URL |
| `AUDIT_SERVICE_KAFKA_BOOTSTRAP_SERVERS` | `http://81.208.166.110:9092` | Kafka bootstrap |
| `AUDIT_SERVICE_KEYCLOAK_JWKS_URI` | `http://0.0.0.0:8181/realms/platform-services/protocol/openid-connect/certs` | JWT validation |
| `AUDIT_RETENTION_FINANCIAL_YEARS` | `7` | retention for financial events |
| `AUDIT_RETENTION_DEFAULT_YEARS` | `1` | retention for non-financial events |
| `AUDIT_HASH_ALGO` | `sha256` | hash chain algorithm |
| `AUDIT_EXPORT_CRON` | `0 0 4 * * *` | daily S3 export cron (04:00 UTC) |
| `AUDIT_PARTITION_CRON` | `0 0 2 * * *` | monthly partition pre-create cron (02:00 UTC) |

## Tests

```bash
./gradlew test
```

31 unit tests across 9 suites; all pass without Docker. The two
`AuditServiceApplicationTests`/`TestAuditServiceApplication` context tests
require Docker (Testcontainers); they pass in CI but are skipped locally
when no daemon is running.

## Observability

### Metrics

Every metric emitted by this service carries the standard platform tags:

- `service=audit-service`
- `env=${SPRING_PROFILES_ACTIVE}`
- `region=${REGION}` (defaults to `local`)
- `tenant=global` (audit-service is platform-global, not tenant-scoped)

Scrape endpoint: `GET /actuator/prometheus` (HTTP 200; ~100 KB payload).

Audit-service-specific metrics mandated by `docs/services/audit-service/SRS.md` §22:

| Metric | Type | Labels | Source |
|---|---|---|---|
| `audit_events_ingested_total` | counter | `topic` | `AuditIngestService.ingest()` after successful insert; 14 bootstrap topics pre-registered |
| `audit_consumer_lag_seconds` | gauge | `topic, partition` | `IngestionMetrics.recordOffset()` after each Kafka consumer ack |
| `audit_hash_chain_status` | gauge | (none) | `AuditVerifyService.verify()` — 1 = verified, 0 = tamper |
| `audit_hash_chain_verify_seconds` | summary | (none) | `ScheduledJobs.dailyHashChainVerification()` |
| `audit_export_total` | counter | `status=success|error` | `ExportService.exportDay()` |
| `audit_export_seconds` | summary | (none) | `ExportService.exportDay()` wall-clock |
| `audit_outbox_oldest_unpublished_seconds` | gauge | (none) | `OutboxPublisher.publishPending()` — used by the outbox-lag alert |

Plus the standard RED/USE metrics from Spring Boot Actuator
(`http_server_requests_seconds_*`, `jvm_*`, `process_*`,
`db_connections_*`).

### Alerts

`monitoring/audit-service-alerts.yaml` ships **6 Prometheus alert rules**
that satisfy the alerting policy in `docs/services/audit-service/SRS.md` §22:

| Alert | Severity | Signal |
|---|---|---|
| `AuditServiceHighErrorRate` | critical | 5xx > 2% for 10 min (T2 SLO burn rate) |
| `AuditServiceConsumerLagHigh` | warning | max lag by (topic, partition) > 30s for 5 min |
| `AuditServiceHashChainMismatch` | critical | `audit_hash_chain_status < 1` (pages security on-call) |
| `AuditServiceExportFailure` | warning | `audit_export_total{status="error"}` increased in last 1h with no successes |
| `AuditServiceOutboxLag` | warning | `audit_outbox_oldest_unpublished_seconds > 5` for 5 min |
| `AuditServicePartitionMaintenanceStalled` | warning | partition job hasn't run in 25h |

Standard labels: `severity`, `service`, `slo`. The
`AuditServiceHashChainMismatch` alert additionally carries `paging:
security-oncall` so the platform's Alertmanager route sends it to the
security team's paging rotation.

## Kubernetes

`k8s/audit-service.yaml` ships **8 Kubernetes resources** in the canonical
identity-service-derived layout:

| # | Kind | Name | Notes |
|---|---|---|---|
| 1 | ServiceAccount | `audit-service` | namespace `platform-services` |
| 2 | Service | `audit-service` | ClusterIP, port 8083 → `http` |
| 3 | Deployment | `audit-service` | 6 replicas, image `registry.trips-enjoy.com/audit-service:REPLACE_WITH_SHA`; rolling update 2/1; pod anti-affinity; securityContext `runAsNonRoot` |
| 4 | HorizontalPodAutoscaler | `audit-service` | min 2 / max 8; CPU 70% primary; consumer-lag External metrics block **commented out** with TODO (requires Prometheus Adapter) |
| 5 | PodDisruptionBudget | `audit-service` | `minAvailable: 50%`, `maxUnavailable: 1` |
| 6 | NetworkPolicy | `audit-service` | default-deny + explicit allow; port 8083 ingress restricted to `ingress`, `api-gateway`, `admin-service`, `platform-ops`, `platform-engineering`, `kube-system`, and the bastion pod; egress to PostgreSQL/Kafka/Redis, Keycloak, Vault, S3 |
| 7 | PodMonitor | `audit-service` | Prometheus Operator scrape, interval 30s, path `/actuator/prometheus` |
| 8 | Job | `audit-service-migrate` | Helm `pre-install,pre-upgrade` hook; runs `migrate` subcommand with `web-application-type=none`; same image as the Deployment |

### Why no separate admin port at the runtime layer?

`TECH.md` §10.5 specifies a separate admin port (8081) reached only from
specific operator namespaces. Spring Boot 4 removed the
`addAdditionalTomcatConnectors` API and the alternative API isn't exposed
in the version pinned by `build.gradle.kts`. We achieve the same security
posture with **defense in depth**:

1. **NetworkPolicy** (k8s layer) restricts ingress to port 8083 from the
   `admin-service`, `platform-ops`, `platform-engineering`, `kube-system`
   namespaces plus the bastion pod. Public ingress from `ingress` and the
   `api-gateway` is also allowed because api-gateway forwards admin calls
   only after RBAC validation; admin traffic from the public internet is
   blocked at the LB.
2. **Spring Security** (`api/admin/AdminAuditController.kt`) gates every
   `/admin/v1/...` route with `@PreAuthorize("hasAnyAuthority('ROLE_platform.admin',
   'ROLE_platform.super_admin', 'ROLE_audit.admin')")`. Unauthenticated
   callers get 401; authenticated callers without the role get 403.
3. **Self-audit** — every admin call emits `audit.admin.audit.v1` to the
   outbox, which this service writes back into its own immutable
   `audit.events` table on consume.

When Spring Boot 4 exposes a stable second-port API, a follow-up PR can
move the admin port to 8081 (matching the prose in `TECH.md` §10.5 and
`RECOMMENDATIONS.md` §6.9 verbatim). Until then, port 8083 is the only
listen port and the security posture is enforced at the network and
application layers.

### HPA on Kafka consumer lag

The HPA ships with **CPU primary** (matches `apps/identity-service/k8s/identity-service.yaml`)
because the platform's Prometheus Adapter (kube-prometheus-stack
component) is not installed on every cluster. The manifest contains a
**commented-out External metrics block** ready to enable once the
adapter is present:

```yaml
- type: External
  external:
    metric:
      name: audit_consumer_lag_seconds
    target:
      type: AverageValue
      averageValue: "30"
```

To switch: install `kube-prometheus-stack`, uncomment the block, and
remove the CPU block. The behavior change is zero-downtime (HPA
smoothly swaps signals).

### Deploy

```bash
helm upgrade --install audit-service ./apps/audit-service/k8s \
  --values values.yaml \
  --set image.tag=$(git rev-parse --short HEAD)
```

The Helm `pre-install,pre-upgrade` hook runs the migration Job first; the
Deployment only rolls out after migrations succeed.

## See also

- `docs/services/audit-service/README.md` — platform documentation
- `docs/services/audit-service/{SRS, ERD, INTEGRATION, WORKFLOWS, TECH, PLAN}.md`
- `docs/architecture/OBSERVABILITY.md` — platform metric + alert conventions
- `docs/architecture/DEPLOYMENT_ARCHITECTURE.md` — tier + k8s conventions
- `apps/identity-service/k8s/identity-service.yaml` — canonical k8s template
- `apps/identity-service/monitoring/identity-service-alerts.yaml` — canonical alerts template
