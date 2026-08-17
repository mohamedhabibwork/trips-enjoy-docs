# Platform k8s Reference Tree

Single reference tree for the Trips Enjoy platform on Kubernetes. This is
the canonical dev/CI layout for **docker-desktop**. Stg/prod overlay it
with [ArgoCD](https://argo-cd.readthedocs.io) or
[kustomize components](https://kubectl.docs.kubernetes.io/references/kustomize/components/)
to bump replicas, point images at the production registry, swap secrets
for Vault-backed ExternalSecret, scale infra to multi-replica StatefulSets,
and tighten NetworkPolicy.

## What's wired in the reference tree

- **12 platform components**, all `type: ClusterIP`:
  - PostgreSQL 19 + PostGIS 3.5 (single-node StatefulSet, extensions
    bootstrapped via init ConfigMap)
  - Redis 8 + Sentinel (single-node StatefulSet, AOF + RDB, per-service
    ACL ready)
  - Apache Kafka 3.7 KRaft single-node (StatefulSet) + topic catalog
    init Job
  - Keycloak 24 (Postgres-backed, realm import via ConfigMap)
  - Elasticsearch 8.13 (single-node dev, Conductor's index backend)
  - MinIO (S3-compatible) + bucket-init Job (10 canonical buckets)
  - Conductor 3.16 server + UI + Kafka bridge (3 sibling Deployments)
  - Prometheus v3 (hand-rolled, kube-prometheus-stack-compatible
    rules) + Alertmanager + Grafana + Loki + OTel Collector
- **21 bounded-context services**, each with a multi-document companion
  file (`services/<name>-policy.yaml`) carrying:
  - HorizontalPodAutoscaler (CPU + per-service custom metric)
  - PodDisruptionBudget
  - NetworkPolicy (allow ingress from api-gateway + admin-service)
  - ServiceMonitor (kube-prometheus-stack-compatible)
  - PrometheusRule (per-service SLO + outbox-lag alerts)
  - migrate Job (Spring Boot `migrate` / Go `migrate up` / Python
    `alembic upgrade head`)
- **Cross-cutting**:
  - PriorityClasses (T1 / T2 / T3 + platform-component priorities)
  - ResourceQuota (bounds the namespace)
  - LimitRange (default + max container resources)
  - NetworkPolicies (default-deny + per-pair allow matrix)
  - ExternalSecrets (21 per-service + 6 platform-component ClusterExternalSecret)
- **Stg / prod overlay skeletons** with replicas-bumped, image-pinned,
  Vault-prod-paths, and tightened NetworkPolicies components.

## Layout

```
platform/k8s/
├── namespace.yaml                       # trips-enjoy (PSS: baseline/audit: restricted)
├── kustomization.yaml                   # root: 12 infra + 21 services × 2 files (deployment + policy)
├── infra/                               # 12 platform components (all ClusterIP)
│   ├── pdb-priority-config.yaml         # PriorityClasses + ResourceQuota + LimitRange
│   ├── postgres.yaml                    # postgis/postgis:19-3.5 (PostGIS + pgcrypto + pg_cron + ...)
│   ├── postgres-initdb-configmap.yaml   # extension bootstrap SQL
│   ├── redis.yaml                       # redis:8-alpine + Sentinel sidecar + redis_exporter
│   ├── kafka.yaml                       # apache/kafka:3.7.0 KRaft + init-topics Job
│   ├── kafka-topics-configmap.yaml      # 51 canonical topics
│   ├── keycloak.yaml                    # quay.io/keycloak/keycloak:24.0 (Postgres-backed, realm import)
│   ├── keycloak-realms-configmap.yaml   # platform-dev.json (single-realm) + multi-realm overlays
│   ├── conductor.yaml                   # conductor-server 3.16.0 + conductor-ui + conductor-bridge
│   ├── elasticsearch.yaml               # docker.elastic.co/elasticsearch/elasticsearch:8.13.4
│   ├── minio.yaml                       # minio/minio + minio-bucket-init Job (10 buckets)
│   ├── prometheus.yaml                  # prom/prometheus:v3.0.0 + recording/alerting rules
│   ├── alertmanager.yaml                # prom/alertmanager:v0.27.0
│   ├── grafana.yaml                     # grafana/grafana:11.2.0 (5 starter dashboards)
│   ├── loki.yaml                        # grafana/loki:3.2.0 monolithic
│   ├── otel-collector.yaml              # otel/opentelemetry-collector-contrib:0.110.0
│   ├── network-policies.yaml            # default-deny + per-pair allow matrix
│   └── external-secrets.yaml            # ClusterSecretStore + 27 ExternalSecret templates
├── services/                            # 21 bounded-context services × 2 files
│   ├── api-gateway.yaml                 # Deployment + Service
│   ├── api-gateway-policy.yaml          # HPA + PDB + NetPol + ServiceMonitor + PrometheusRule
│   ├── identity-service.yaml
│   ├── identity-service-policy.yaml     # + migrate Job
│   ├── ...                              # 19 more
│   └── file-service-policy.yaml
├── services/patches/
│   └── tier-hardening.yaml              # uniform patch: linkerd, OTel, seccompProfile, preStop, ...
├── overlays/
│   ├── stg/kustomization.yaml           # T1=3 / T2=2 / T3=2 replicas; Vault stg paths
│   └── prod/kustomization.yaml          # T1=6 / T2=3 / T3=2 replicas; Vault prod paths; full HA
├── policy/
│   └── conftest.rego                    # ClusterIP, runAsNonRoot, capabilities drop ALL, ...
└── scripts/
    └── gen-service-companions.sh        # regenerates services/<name>-policy.yaml files
```

**Total: 65 YAML files** (1 namespace + 1 kustomization + 17 infra + 42
services + 1 patch + 2 overlay + 1 conftest).

## Conventions

- **All Services are `type: ClusterIP`** — never NodePort, LoadBalancer,
  Ingress, or Istio `Gateway`. Operators access services via
  `kubectl port-forward` or `kubectl exec`.
- **`runAsNonRoot: true`** + `runAsUser >= 10001` on every container.
  The five platform images that ship with a fixed UID (postgres, redis,
  keycloak, prometheus) carry `runAsUser >= 999` and are exempted from
  the 10001 rule in the conftest policy.
- **`seccompProfile.type: RuntimeDefault`** on every Pod spec.
- **`securityContext.capabilities.drop: ["ALL"]`** on every container.
- **`securityContext.readOnlyRootFilesystem: true`** on every container.
  Containers mount `emptyDir` at `/tmp` and `/var/tmp` to satisfy this.
- **`linkerd.io/inject: enabled`** annotation on every service Pod.
  The linkerd control plane is **not** shipped in this tree; operators
  install linkerd via the cluster's bootstrap pipeline (stg/prod
  overlays).
- **`sidecar.otel.io/inject: "true"`** annotation on every service Pod.
  The OTel collector Deployment in `infra/otel-collector.yaml` accepts
  OTLP gRPC + HTTP from these sidecars.
- **PriorityClasses** — per-tier (service-tier-1/2/3) + per-platform-component
  (postgres-priority 1M, kafka-priority 900k, keycloak-priority 850k,
  ledger-service-priority 900k, payment-service-priority 880k, conductor-priority 750k, ...).
- **PreStop hook** on every service Pod (`sleep 15`; ledger overrides
  to `sleep 30`).
- **Rolling strategy**: `maxUnavailable: 0`, `maxSurge: 1` on every
  Deployment (applied via `services/patches/tier-hardening.yaml`).
- **Topology spread** across hostnames + zones (applied via the same patch).
- **Prometheus annotations**: `prometheus.io/scrape=true` +
  `prometheus.io/port=<actuator>` + `prometheus.io/path=/actuator/prometheus`
  on every service Pod template.
- **Per-service Secret** named `<service>-runtime`, populated by the
  ExternalSecret templates in `infra/external-secrets.yaml` (Vault in
  stg/prod). The dev tree uses the hand-written `trips-enjoy-runtime`
  Secret as a fallback.
- **Image registry**: `registry.trips-enjoy.com/<service>:<tag>`. For
  local docker-desktop dev:
  ```
  docker tag identity-service:dev-k8s registry.trips-enjoy.com/identity-service:dev-k8s
  ```
- **Namespace**: `trips-enjoy`. PSS `enforce: baseline`, `audit: restricted`.

## Apply

```bash
# Create the namespace first (kustomize doesn't apply namespaces by default).
kubectl apply -f platform/k8s/namespace.yaml

# Create the hand-written dev Secret fallback.
# Operators in stg/prod install the ExternalSecrets Operator (Helm chart
# from external-secrets.io) which mirrors these into Vault-backed Secrets.
kubectl -n trips-enjoy create secret generic trips-enjoy-runtime \
  --from-literal=POSTGRES_PASSWORD=postgres \
  --from-literal=IDENTITY_DB_PASSWORD=postgres \
  --from-literal=REDIS_PASSWORD=redis \
  --from-literal=MINIO_ROOT_USER=minio \
  --from-literal=MINIO_ROOT_PASSWORD=minio123 \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD=admin \
  --from-literal=KC_DB_PASSWORD=postgres \
  --from-literal=JWT_KEY=change-me-in-prod \
  --from-literal=ALERTMANAGER_SLACK_WEBHOOK='' \
  --from-literal=ALERTMANAGER_PAGERDUTY_ROUTING_KEY=''

# Apply everything else.
kubectl apply -k platform/k8s/
```

The first apply starts everything in dependency order via the Helm
pre-install/pre-upgrade hooks (postgres init → postgres PVC → keycloak +
realm import → kafka → topic init → redis → minio + bucket-init →
elasticsearch + index init → conductor + bridge → prometheus + alertmanager
+ grafana + loki + otel-collector → 21 services × migrate Job →
Deployment).

## Verify

```bash
# Every Service is ClusterIP.
kubectl get svc -n trips-enjoy -o custom-columns=NAME:.metadata.name,TYPE:.spec.type

# All 12 platform components healthy.
kubectl get pods -n trips-enjoy -l app.kubernetes.io/part-of=trips-enjoy

# Prometheus scrapes all 21 services (21 entries in the targets page).
kubectl -n trips-enjoy port-forward svc/prometheus 9090:9090 &
open http://localhost:9090/targets

# Conductor UI accessible.
kubectl -n trips-enjoy port-forward svc/conductor-ui 5000:5000 &
open http://localhost:5000

# Grafana dashboards.
kubectl -n trips-enjoy port-forward svc/grafana 3000:3000 &
open http://localhost:3000   # admin/admin (dev only)

# Network policies enforced — test from inside a pod.
kubectl -n trips-enjoy exec deploy/api-gateway -- \
  curl -s http://customer-service:8089/actuator/health  # OK
kubectl -n trips-enjoy exec deploy/api-gateway -- \
  curl -s http://postgres:5432 || echo "expected: protocol error (HTTP-to-PG)"
```

## Validation

```bash
# Conftest policy check (must pass before merge).
make k8s-lint

# Kustomize build the full tree.
make k8s-build

# Kustomize build the stg overlay.
make k8s-build-stg

# Kustomize build the prod overlay.
make k8s-build-prod

# promtool check the platform-wide + per-service recording/alerting rules.
make k8s-validate-platform-rules
```

## Local dev on docker-desktop

The reference tree runs on docker-desktop k8s out of the box. Resource
sizing note: 12 infra + 21 services × ~500Mi memory ≈ 24 GiB minimum.
Docker Desktop defaults to 8 GiB total; bump to **16 GiB** in
Docker Desktop → Settings → Resources if pods get OOMKilled.

For Elasticsearch, ensure `vm.max_map_count >= 262144` on the host kernel
(macOS Docker Desktop: Preferences → Docker Engine → add
`"default-runtime": "runc"` and `sysctl -w vm.max_map_count=262144`
inside the VM via `docker run --privileged alpine sysctl -w ...`).

Build + tag every service image:
```bash
# Example: identity-service. Repeat for every service.
cd apps/identity-service
./gradlew --no-daemon bootJar
docker build --platform linux/arm64 -f Dockerfile.dev -t identity-service:dev-k8s .
docker tag identity-service:dev-k8s registry.trips-enjoy.com/identity-service:dev-k8s
```

For services that have **no Dockerfile yet** (currently 14 of 21), the
pod stays in `ImagePullBackOff` until the operator builds + tags the
image. The 12 infra Deployments pull their public images directly.

## Stg/prod overlays

Use kustomize components or ArgoCD ApplicationSet to layer changes on
top of this tree:

```yaml
# overlays/stg/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: trips-enjoy-stg
resources: [../../]
components:
  - components/stg-replicas.yaml          # T1=3 / T2=2 / T3=2
  - components/stg-images.yaml           # registry.trips-enjoy.com/<svc>:v<semver>
  - components/stg-network-policies.yaml # tight default-deny
  - components/stg-vault-secrets.yaml    # Vault stg paths
  - components/stg-resources.yaml        # production-SLA sizing
```

```yaml
# overlays/prod/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: trips-enjoy-prod
resources: [../stg/]
components:
  - components/prod-replicas.yaml        # T1=6 / T2=3 / T3=2
  - components/prod-images.yaml          # registry.trips-enjoy.com/<svc>:v<semver> (Cosign-signed)
  - components/prod-vault-secrets.yaml   # Vault prod paths
  - components/prod-resources.yaml       # production-SLA sizing (T1: 500m/2Gi req + 1/4Gi lim)
  - components/prod-network-policies.yaml # strict default-deny + per-pair allow
  - components/prod-observability.yaml   # 30d Prometheus retention, full Loki, Alertmanager HA
```

The stg/prod components are documented in the overlay kustomization
files; operators add the actual patch YAML files under
`overlays/{stg,prod}/components/` per their environment.

## Service-port map

| Service | App | Actuator |
|---|---|---|
| api-gateway | 8080 | 9090 |
| identity-service | 8082 | 9092 |
| audit-service | 8083 | 9093 |
| configuration-service | 8084 | 8081 |
| notification-service | 8085 | 9095 |
| admin-service | 8086 | 9096 |
| reporting-service | 8087 | 9097 |
| fraud-risk-service | 8088 | 9098 |
| customer-service | 8089 | 9099 |
| search-service | 8090 | 9100 |
| driver-service | 8091 | 9101 |
| trip-service | 8092 | 9102 |
| pricing-service | 8093 | 9103 |
| restaurant-service | 8094 | 9104 |
| food-order-service | 8095 | 9105 |
| courier-service | 8096 | 9106 |
| geolocation-service | 8097 | 9107 |
| payment-service | 8098 | 9108 |
| ledger-service | 8099 | 9109 |
| chat-service | 8100 | 9110 |
| file-service | 8101 | 9111 |

## Per-service companion shape

Every `services/<name>-policy.yaml` follows the same shape (lifted from
`apps/ledger-service/k8s/ledger-service.yaml` + the platform conventions):

```yaml
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: <service> }
spec:
  scaleTargetRef: { kind: Deployment, name: <service> }
  minReplicas: <t1=3|t2=2|t3=2>
  maxReplicas: <t1=8|t2=20|t3=6>
  metrics: [CPU + custom outbox-lag / kafka-lag / in-flight]
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: <service> }
spec:
  minAvailable: <t1=2|t2=2|t3=1>
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: <service> }
spec:
  podSelector: { matchLabels: { app.kubernetes.io/name: <service> } }
  policyTypes: [Ingress]
  ingress:
    - from: [api-gateway, admin-service]
      ports: [<app-port>]
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata: { name: <service> }
spec:
  selector: { matchLabels: { app.kubernetes.io/name: <service> } }
  endpoints: [{ port: actuator, path: /actuator/prometheus, interval: 15s }]
---
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata: { name: <service> }
spec:
  groups:
    - name: <service>.slo
      rules: [<per-service SLO + outbox-lag alerts>]
---
# (Optional) For services with DB migrations:
apiVersion: batch/v1
kind: Job
metadata:
  name: <service>-migrate
  annotations:
    "helm.sh/hook": pre-install,pre-upgrade,pre-rollback
spec:
  template:
    spec:
      containers:
        - name: migrate
          image: registry.trips-enjoy.com/<service>:dev-k8s
          args: [migrate, --spring.main.web-application-type=none]   # Spring
          # args: [migrate, up]                                     # Go
          # args: [alembic, upgrade, head]                          # Python
```

## References

- `docs/architecture/ARCHITECTURE.md` — 4-layer taxonomy + bounded contexts
- `docs/architecture/DEPLOYMENT_ARCHITECTURE.md` — Kubernetes topology,
  resource limits, health probes, ingress, configuration, secrets,
  DB connectivity, migrations, rolling deploys, zero-downtime, rollback,
  environments, multi-region, disaster recovery
- `docs/architecture/DATABASE_ARCHITECTURE.md` — schema-per-service isolation
- `docs/architecture/KEYCLOAK_ARCHITECTURE.md` — realm topology
- `docs/architecture/SECURITY_ARCHITECTURE.md` — network policies,
  secrets, defense in depth
- `docs/architecture/OBSERVABILITY.md` — Prometheus + Loki + Tempo +
  OTel collector
- `docs/architecture/adrs/0007-postgis-for-geospatial.md` — PostGIS
  requirement
- `docs/architecture/adrs/0012-kubernetes-orchestration.md` — Kubernetes
  decision
- `docs/architecture/adrs/0018-workflow-engine-conductor.md` — Conductor
- `docs/shared/CONDUCTOR_WORKFLOWS.md` — 17 workflows + 15 participating services
- `docs/shared/PLATFORM_K8S_TREE.md` — companion doc to PLATFORM_BASELINE.md
