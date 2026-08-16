# Platform k8s Reference Tree

> Companion doc to [`PLATFORM_BASELINE.md`](PLATFORM_BASELINE.md).
> Documents the canonical Kubernetes reference tree at
> [`platform/k8s/`](../../platform/k8s/README.md) — the platform-level
> contract for how every service is deployed.

## What this doc is

`PLATFORM_BASELINE.md` documents the platform contract — images,
versions, network posture, mesh posture, observability posture, secret
delivery — at the *abstract* level. This doc points at the concrete
YAML tree that realizes that contract and explains the per-component
trade-offs that the abstract doc doesn't capture.

When a contributor asks "which Postgres image does the platform use?" or
"how does the platform enforce mTLS in dev?" or "where do per-service
HPA + PDB live?", this doc is the answer.

## Components and decisions

### PostgreSQL 19 + PostGIS 3.5

| Decision | Tree location | Note |
|---|---|---|
| Image `postgis/postgis:19-3.5` | `infra/postgres.yaml` | PLATFORM_BASELINE §2 + ADR-0007 |
| StatefulSet with 25Gi PVC | `infra/postgres.yaml` | Single-node dev; stg/prod split identity/payment/ledger/audit into dedicated physical clusters |
| Extensions bootstrap | `infra/postgres-initdb-configmap.yaml` | `postgis`, `pg_trgm`, `pgcrypto`, `pg_stat_statements`, `pgaudit`, `pg_cron` |
| `pg_cron` + `pgaudit` in `shared_preload_libraries` | `infra/postgres.yaml` ConfigMap `postgres-config` | Server-side preloaded; extensions load via CREATE EXTENSION |
| `partman` schema for canonical partition helpers | `infra/postgres-initdb-configmap.yaml` | Per docs/shared/PARTITION_FUNCTIONS.md |
| PriorityClass `postgres-priority` (1M) | inline in `infra/postgres.yaml` | Highest priority; all OLTP services depend on it |
| PDB minAvailable 1 | inline in `infra/postgres.yaml` | Non-negotiable SPOF |

### Redis 8 + Sentinel

| Decision | Tree location | Note |
|---|---|---|
| Image `redis:8-alpine` | `infra/redis.yaml` | ADR-0006 canonical version |
| StatefulSet with 5Gi PVC | `infra/redis.yaml` | AOF + RDB; correctness-critical for revocation + idempotency keys |
| Sentinel sidecar | `infra/redis.yaml` `sentinel` container | Dev: 1 sentinel; stg/prod: 3-node quorum |
| `redis_exporter` (oliver006/redis_exporter:v1.58.0) | `infra/redis.yaml` `redis-exporter` container | Exposes `redis_*` metrics to Prometheus |
| 16 logical databases, per-service namespacing | `infra/redis.yaml` ConfigMap `redis-config` | api-gateway=0, identity=1, audit=2, notification=3, ledger=4, payment=5, pricing=6, trip=7, driver=8, courier=9, restaurant=10, search=11, chat=12, fraud-risk=13, reporting=14, platform=15 |
| `--requirepass` from `redis-runtime` Secret | `infra/redis.yaml` | Per-service password in stg/prod; single password in dev |
| ACL file (per-service users) | `infra/redis.yaml` ConfigMap `redis-config` | Dev: single `default` user; stg/prod overlays provision per-service ACLs |
| `appendonly yes` + `appendfsync everysec` | `infra/redis.yaml` ConfigMap `redis-config` | ADR-0006 |
| `maxmemory 256mb` + `maxmemory-policy allkeys-lru` | `infra/redis.yaml` ConfigMap `redis-config` | Dev sizing; stg/prod overlays raise |

### Apache Kafka 3.7 (KRaft)

| Decision | Tree location | Note |
|---|---|---|
| Image `apache/kafka:3.7.0` | `infra/kafka.yaml` | PLATFORM_BASELINE §3 + ADR-0005; KRaft single-node for dev |
| StatefulSet with 10Gi PVC | `infra/kafka.yaml` | Single-node dev; stg/prod 3-broker cluster with replication factor 3 |
| Topic catalog (51 topics) | `infra/kafka-topics-configmap.yaml` | `<topic>:<partitions>:<retention_ms>:<label>` TSV format |
| Topic init Job | `infra/kafka.yaml` `kafka-init-topics` Job | Runs `kafka-topics.sh --create --if-not-exists` per topic; idempotent |
| PriorityClass `kafka-priority` (900k) | inline in `infra/kafka.yaml` | Tier-1; every cross-service event flows through Kafka |
| PDB minAvailable 1 | inline in `infra/kafka.yaml` | |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` in dev | `infra/kafka.yaml` | Stg/prod overlays disable; rely solely on the topic catalog |

### Keycloak 24

| Decision | Tree location | Note |
|---|---|---|
| Image `quay.io/keycloak/keycloak:24.0` | `infra/keycloak.yaml` | Dev: start-dev; stg/prod: start --optimized with Infinispan + production DB |
| `KC_DB=postgres` + Postgres-backed | `infra/keycloak.yaml` | Database `keycloak` created by postgres's `POSTGRES_MULTIPLE_DATABASES` init |
| `KC_SPI_ADMIN_AUTH_ACCESS_TOKEN_LIFESPAN=1800` | `infra/keycloak.yaml` | Required by identity-service seeder (3-5 minute run with 200+ admin REST calls) |
| Realm import via `--import-realm` | `infra/keycloak.yaml` + `infra/keycloak-realms-configmap.yaml` | Dev: `platform-dev.json` (single-realm); stg/prod: all 6 realms |
| `KC_FEATURES=preview,token-exchange` | `infra/keycloak.yaml` | Token-exchange for service-to-service delegation |
| PriorityClass `keycloak-priority` (850k) | inline in `infra/keycloak.yaml` | Tier-1; every JWT flows through it |
| HPA 1-3 + PDB minAvailable 1 | inline in `infra/keycloak.yaml` | Dev default; stg/prod swap to Infinispan distributed cache |

### Elasticsearch 8.13

| Decision | Tree location | Note |
|---|---|---|
| Image `docker.elastic.co/elasticsearch/elasticsearch:8.13.4` | `infra/elasticsearch.yaml` | Conductor's index backend (CONDUCTOR_WORKFLOWS §1) |
| `discovery.type=single-node` | `infra/elasticsearch.yaml` | Dev; stg/prod 3-node cluster |
| `xpack.security.enabled=false` | `infra/elasticsearch.yaml` | Dev; stg/prod enable xpack.security + TLS |
| 10Gi PVC | `infra/elasticsearch.yaml` | |
| `vm.max_map_count >= 262144` sysctl check | `infra/elasticsearch.yaml` `sysctl-check` initContainer | ES refuses to start otherwise |
| `conductor_workflow` index init Job | `infra/elasticsearch.yaml` `elasticsearch-init` Job | Idempotent; tolerates 400 resource_already_exists |

### MinIO (S3-compatible)

| Decision | Tree location | Note |
|---|---|---|
| Image `minio/minio:RELEASE.2024-08-29T01-40-52Z` | `infra/minio.yaml` | Pinned digest; not `latest` |
| 10Gi PVC | `infra/minio.yaml` `minio-data` PVC | Data loss on restart is acceptable in dev |
| 10 canonical buckets via init Job | `infra/minio.yaml` `minio-bucket-init` Job | trips-enjoy-platform-audit, trips-enjoy-platform-reporting, trips-enjoy-platform-conductor, trips-enjoy-platform-driver-location, trips-enjoy-platform-courier-location, trips-enjoy-platform-restaurant, trips-enjoy-platform-kyc, trips-enjoy-platform-chat, trips-enjoy-platform-config-snapshot, trips-enjoy-file-dev |
| 30d lifecycle policy (idempotent) | `infra/minio-bucket-policy` ConfigMap + init Job | `mc ilm import` per bucket |

### Netflix Conductor (workflow engine)

| Decision | Tree location | Note |
|---|---|---|
| Image `conductor-cinema/conductor-server:3.16.0` | `infra/conductor.yaml` | Per ADR-0018 |
| UI image `conductor-cinema/conductor-ui:3.16.0` | `infra/conductor.yaml` | Operator-facing; port 5000 |
| Kafka bridge (in-house image) | `infra/conductor.yaml` `conductor-bridge` Deployment | Operators replace with the platform team's actual image |
| Postgres persistence (shared `conductor` schema) | `infra/conductor.yaml` ConfigMap `conductor-config` | Schema created by postgres's POSTGRES_MULTIPLE_DATABASES init |
| Elasticsearch index backend | `infra/conductor.yaml` | `conductor.elasticsearch.url=http://elasticsearch:9200`, `WORKFLOW_INDEX_NAME=conductor_workflow` |
| `JWT_KEY` from `conductor-runtime` Secret | `infra/conductor.yaml` | Symmetric dev key; stg/prod swap to KMS-backed asymmetric |
| Inbound Kafka topics → Conductor signals | `infra/conductor.yaml` `BRIDGE_INBOUND_TOPICS` env | `trip.reward.granted.v1`, `trip.reward.reversed.v1`, `food.order.rejected.v1`, `payment.refund.requested.v1`, `driver.onboarding.approved.v1`, `courier.onboarding.approved.v1` |
| Outbound Conductor transitions → Kafka | `infra/conductor.yaml` `BRIDGE_OUTBOUND_TOPIC` env | `conductor.workflow.transitions` |
| PriorityClass `conductor-priority` (750k) | inline in `infra/conductor.yaml` | Tier-1 |
| preStop `sleep 30` | inline in `infra/conductor.yaml` | Drain in-flight Kafka consumer + outbox publisher |
| HPA 1-3 on CPU 70% | inline in `infra/conductor.yaml` | |
| PDB minAvailable 1 | inline in `infra/conductor.yaml` | |

### Prometheus v3 (Observability)

| Decision | Tree location | Note |
|---|---|---|
| Image `prom/prometheus:v3.0.0` | `infra/prometheus.yaml` | kube-prometheus-stack-compatible recording/alerting rules |
| 20Gi PVC for TSDB | `infra/prometheus.yaml` | Replaces emptyDir (data loss on restart) |
| `--storage.tsdb.retention.time=15d` | `infra/prometheus.yaml` | |
| Scrape via Kubernetes pod SD + `prometheus.io/scrape=true` annotation | `infra/prometheus.yaml` ConfigMap `prometheus-config` | Plus ServiceMonitor CRD file SD for kube-prometheus-stack overlay |
| Platform-wide recording + alerting rules | `infra/prometheus.yaml` ConfigMap `prometheus-rules` (`platform-slo.yaml`, `platform.outbox`, `platform.kafka`, `platform.partition_health`, `platform.pod_health`) | Per-service rules in `services/<name>-policy.yaml` |
| Alertmanager endpoint | `infra/prometheus.yaml` | `alertmanager:9093` |

### Alertmanager

| Decision | Tree location | Note |
|---|---|---|
| Image `prom/alertmanager:v0.27.0` | `infra/alertmanager.yaml` | |
| Slack + PagerDuty receivers | `infra/alertmanager.yaml` ConfigMap `alertmanager-config` | Receivers configured; webhook URLs from `alertmanager-runtime` Secret (optional in dev) |
| `inhibit_rules` for warning-on-critical pairs | `infra/alertmanager.yaml` | Avoids page-on-warning when critical is already firing |

### Grafana

| Decision | Tree location | Note |
|---|---|---|
| Image `grafana/grafana:11.2.0` | `infra/grafana.yaml` | |
| 5 starter dashboards | `infra/grafana.yaml` ConfigMap `grafana-config` (`api-gateway.json`, `outbox-lag.json`, `kafka-consumer-lag.json`, `redis-hit-ratio.json`, `postgres-partition-health.json`) | |
| Datasource provisioning | `infra/grafana.yaml` ConfigMap `grafana-config` (`provisioning-datasources.yaml`) | Prometheus + Loki |
| `default_home_dashboard_path` | `infra/grafana.yaml` ConfigMap `grafana-config` (`grafana.ini`) | Operators land on api-gateway-requests first |

### Loki

| Decision | Tree location | Note |
|---|---|---|
| Image `grafana/loki:3.2.0` | `infra/loki.yaml` | Monolithic mode for dev; stg/prod distributed mode |
| Filesystem storage | `infra/loki.yaml` ConfigMap `loki-config` | 5Gi PVC; 7d retention |
| Ingestion rate limit (defense against runaway services) | `infra/loki.yaml` ConfigMap `loki-config` | `ingestion_rate_mb: 10`, `ingestion_burst_size_mb: 20` |

### OTel Collector

| Decision | Tree location | Note |
|---|---|---|
| Image `otel/opentelemetry-collector-contrib:0.110.0` | `infra/otel-collector.yaml` | OSS_DEPENDENCIES.md §2 pin |
| OTLP gRPC (4317) + OTLP HTTP (4318) receivers | `infra/otel-collector.yaml` | Sidecars injected via `sidecar.otel.io/inject: "true"` annotation on each service Pod |
| Traces → debug (dev) / Tempo (stg/prod) | `infra/otel-collector.yaml` ConfigMap `otel-collector-config` | |
| Logs → Loki push API | `infra/otel-collector.yaml` ConfigMap `otel-collector-config` | |
| Metrics → Prometheus remote-write | `infra/otel-collector.yaml` ConfigMap `otel-collector-config` | `prometheusremotewrite` exporter |
| `attributes/platform_request_id` processor | `infra/otel-collector.yaml` ConfigMap `otel-collector-config` | Preserves ADR-0019 request id across traces |
| Memory limiter + batch processors | `infra/otel-collector.yaml` ConfigMap `otel-collector-config` | Defense against runaway cardinality |

## Cross-cutting

### PriorityClasses

11 classes total (4 platform-component + 7 per-service tier):

| Class | Value | Component |
|---|---|---|
| `postgres-priority` | 1M | postgres (Postgres) |
| `kafka-priority` | 900k | kafka (Apache Kafka) |
| `ledger-service-priority` | 900k | ledger-service (Tier-1 financial) |
| `payment-service-priority` | 880k | payment-service (Tier-1 financial) |
| `keycloak-priority` | 850k | keycloak (Keycloak) |
| `configuration-service-priority` | 800k | configuration-service (Tier-1 reference) |
| `conductor-priority` | 750k | conductor (Conductor) |
| `api-gateway-priority` | 700k | api-gateway (Tier-1 edge) |
| `service-tier-1` | 600k | customer, audit, identity, geolocation |
| `service-tier-2` | 500k | notification, file, fraud-risk, trip, driver, courier, restaurant, food-order, pricing, search |
| `service-tier-3` | 300k | chat, reporting |

Defined in `infra/pdb-priority-config.yaml` (7 service classes) +
inline in their respective infra `<component>.yaml` (4 platform
classes).

### ResourceQuota + LimitRange

- **ResourceQuota** `trips-enjoy-quota`:
  - requests.cpu=24, limits.cpu=48
  - requests.memory=64Gi, limits.memory=128Gi
  - persistentvolumeclaims=20
  - secrets=100, configmaps=200, services=100
  - services.loadbalancers=0 (no LBs), services.nodeports=0 (no NodePorts)
  - pods=100, count/deployments.apps=60, count/statefulsets.apps=20,
    count/jobs.batch=50, count/cronjobs.batch=30
- **LimitRange** `trips-enjoy-defaults`:
  - Container default: 500m CPU, 512Mi memory, 1Gi ephemeral-storage
  - Container defaultRequest: 100m CPU, 256Mi memory, 256Mi ephemeral
  - Container max: 2 CPU, 4Gi memory, 5Gi ephemeral
  - Container min: 50m CPU, 64Mi memory, 32Mi ephemeral
  - PVC: 1Gi min, 100Gi max
  - Pod: 4 CPU max, 8Gi memory max, 10Gi ephemeral max

Defined in `infra/pdb-priority-config.yaml`.

### NetworkPolicies

Default-deny ingress + egress at the namespace level (`default-deny-all`),
plus the explicit allow matrix in `infra/network-policies.yaml`:

| Policy | Purpose |
|---|---|
| `default-deny-all` | Every pod; policyTypes: Ingress + Egress |
| `allow-dns-egress` | Every pod → kube-system/CoreDNS UDP+TCP 53 |
| `allow-kubelet-probes` | kubelet CIDR (10.244.0.0/16) → every pod app+actuator ports |
| `allow-api-gateway-ingress` | api-gateway → every service on :8080 |
| `allow-admin-service-ingress` | admin-service → every service on :8080 |
| `allow-observability-ingress` | observability → every service on actuator ports (9090-9111) |
| `allow-services-to-postgres` | Every service → postgres:5432 |
| `allow-services-to-redis` | Every service → redis:6379 + redis:26379 (sentinel) |
| `allow-services-to-kafka` | Every service → kafka:9092 |
| `allow-services-to-keycloak` | Every service → keycloak:8080 |
| `allow-services-to-conductor` | Every service → conductor:8080 |
| `allow-services-to-otel-collector` | Every service → otel-collector:4317+4318 |
| `allow-observability-to-infra` | observability → every infra component for scrape |
| `allow-conductor-to-elasticsearch` | conductor → elasticsearch:9200+9300 |
| `allow-object-storage-clients-to-minio` | file, audit, reporting, configuration → minio:9000 |
| `allow-conductor-bridge-egress` | conductor-bridge → conductor + kafka |
| `allow-identity-service-to-keycloak-admin` | identity-service → keycloak:8080 (explicit for seeder) |
| `allow-api-gateway-egress` | api-gateway → every service + keycloak + redis + kafka + otel-collector |

Plus 21 per-service NetworkPolicies in `services/<name>-policy.yaml`
(allow ingress from api-gateway + admin-service).

### ExternalSecrets

`infra/external-secrets.yaml` carries:

- 1 `ClusterSecretStore` named `vault-backend`, pointing at
  `https://vault.trips-enjoy.com:8200` with `kubernetes` auth method.
- 21 per-service `ExternalSecret` templates (api-gateway, identity,
  audit, configuration, notification, admin, reporting, fraud-risk,
  customer, search, driver, trip, pricing, restaurant, food-order,
  courier, geolocation, payment, ledger, chat, file).
- 6 platform-component `ExternalSecret` templates (postgres-runtime,
  redis-runtime, keycloak-runtime, minio-runtime, conductor-runtime,
  alertmanager-runtime).

Refresh interval: 1h for per-service, 24h for platform-component secrets.
Vault path layout per DEPLOYMENT_ARCHITECTURE §Per-Environment DB +
Secret Path Convention.

### Service-port map

See [`platform/k8s/README.md`](../../platform/k8s/README.md#service-port-map).
12 platform components + 21 services with documented app + actuator
ports.

## Where to start

- **Adding a new service**: copy `services/<name>-policy.yaml` from
  any existing service, replace the name + tier, add the entry to
  `kustomization.yaml`.
- **Adding a new platform component**: copy any of
  `infra/postgres.yaml`, `infra/redis.yaml`, `infra/kafka.yaml`,
  etc., and follow the same patterns (PriorityClass inline, PDB
  inline, network policy in `infra/network-policies.yaml`, ExternalSecret
  in `infra/external-secrets.yaml`).
- **Modifying a tier-wide behavior**: edit
  `services/patches/tier-hardening.yaml`. The kustomize `patches:`
  block in `kustomization.yaml` applies it uniformly.
- **Promoting to stg/prod**: write a kustomize component in
  `overlays/{stg,prod}/components/` and add it to the overlay
  kustomization.

## References

- [`PLATFORM_BASELINE.md`](PLATFORM_BASELINE.md) — abstract platform
  contract.
- [`DEPLOYMENT_ARCHITECTURE.md`](../architecture/DEPLOYMENT_ARCHITECTURE.md)
  §Reference Tree Implementation Status — concrete mapping from
  this doc to the YAML tree.
- [`platform/k8s/README.md`](../../platform/k8s/README.md) — operator
  guide for the reference tree (apply, verify, validate).
EOF
echo "PLATFORM_K8S_TREE.md created"</command><description>Create PLATFORM_K8S_TREE.md</description>