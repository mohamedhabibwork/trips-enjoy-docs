# file-service — Technology Profile

> One-page technology reference for `file-service`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`INTEGRATION.md`](./INTEGRATION.md) · [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Edge / hot path |
| **Language** | Go 1.25.x |
| **Framework** | `net/http` + `go-chi/chi` v2 |
| **Build** | `go build` (Go 1.25.x toolchain) |
| **Container** | `gcr.io/distroless/static-debian12:nonroot` (multi-stage build, static binary) |

## 2. Key libraries

The service uses **per-driver SDKs** (one per Storage
Driver kind). Each driver is a separate Go package under
`internal/storage/drivers/<id>/`; adding a new driver is
a new package, no changes elsewhere.

- **`s3` driver** — `aws-sdk-go-v2` (service: `s3`),
  including pre-signed URLs (`s3.PresignClient`),
  multipart upload (`s3.NewUploadManager`), HeadBucket
  for the synthetic probe. Compatible with AWS S3, MinIO,
  Ceph RGW, Wasabi, Cloudflare R2 — any endpoint that
  speaks the S3 v4 protocol with whatever path-style
  the driver config has.
- **`azure_blob` driver** — `github.com/Azure/azure-sdk-for-go/sdk/azidentity`
  + `github.com/Azure/azure-sdk-for-go/sdk/storage/azblob`
  (managed-identity or service-principal auth; SAS URL
  signing via `BlobClient.GetSASURL`; GetServiceProperties
  for the probe).
- **`oracle_object_storage` driver** —
  `github.com/oracle/oci-go-sdk/v65/objectstorage` (native
  API: namespace, bucket, object; pre-authenticated
  request for signed URLs) **and** the S3-compatible
  endpoint via `aws-sdk-go-v2` with the OCI region (for
  multipart upload). `GetNamespace` for the probe.
- **`gcs` driver** —
  `cloud.google.com/go/storage` (native `SignedURL`,
  `Writer` for chunked, `Reader` for streaming,
  `BucketHandle` for the probe); optional S3 interop
  mode surfaced via `aws-sdk-go-v2`.
- **`local_fs` driver** — Go stdlib (`os`, `io`,
  `syscall.Statfs_t`) plus `spf13/afero` for testability.
  Signed-URL flow is implemented in-process as a short-
  lived reverse-proxy (`/local-fs-proxy/{upload,stream}`)
  inside the same binary, with the URL containing an
  HMAC-bound ticket.

Other libraries are unchanged from the previous tech
profile:

- `go-redis/redis` v9 — signed URL cache (key includes
  `driver_id`) and per-driver readiness flap
- `prometheus/client_golang` v1.20+ — metrics
- `golang-migrate` v4 — migrations (the schema is
  driver-agnostic; adding a new driver requires no
  migration)
- `google/uuid` (UUIDv7) — primary keys
- `prometheus/client_golang` + `opentelemetry-go` — metrics
  & tracing

## 3. Data layer

- **Database**: PostgreSQL 19, schema `file`.
  - **driver-agnostic** file metadata: `file.files`,
    `file.scans`, `file.access_log`,
    `file.retention_policies`,
    `file.retention_overrides`.
  - **driver catalog**: `file.storage_drivers`.
  - **driver history** (immutable audit):
    `file.driver_assignments`, `file.driver_history`,
    `file.driver_object_registry`,
    `file.driver_health_events`.
- **Migrations**: `golang-migrate` v4 (the schema is
  driver-agnostic; adding a new driver does not require a
  migration).
- **ORM / DSL**: `pgx` v5 (raw SQL + `pgxpool`).

The blob bytes themselves live on a **Storage Driver**
(`s3`, `azure_blob`, `oracle_object_storage`, `gcs`,
`local_fs`), identified per file by
`files.driver_id` with a `driver_id`-opaque
`driver_locator` JSON document that the driver
implementation can interpret.

## 4. Cache

Redis — upload session state, signed-URL cache
(keyed per `driver_id`), per-driver readiness flap

## 5. External integrations

- **Storage Driver layer** (one or more of): AWS S3 /
  S3-compatible (MinIO, Ceph RGW, Wasabi, Cloudflare
  R2) · Azure Blob Storage · Oracle Object Storage ·
  Google Cloud Storage · local POSIX filesystem (dev /
  CI / edge only).
- **Virus scan provider**: ClamAV, VirusTotal, or AWS
  GuardDuty Malware Protection.

## 5.1 StorageDriver interface (Go)

The `StorageDriver` interface in
`internal/storage/StorageDriver.go` is implemented by
each driver package; the rest of the service depends
**only** on this interface.

```go
type StorageDriver interface {
    // InitiateUpload reserves a place for the bytes and
    // returns an opaque upload handle whose URL or method
    // is driver-specific (S3 presigned PUT, Azure SAS, OCI
    // PAR, GCS signed PUT, local-FS signed-redirect).
    InitiateUpload(ctx context.Context, req InitiateUploadRequest) (InitiateUploadResponse, error)

    // CompleteUpload confirms a direct-to-driver upload:
    // HeadObject (or driver equivalent) to verify presence
    // and size, then return the canonical driver_locator.
    CompleteUpload(ctx context.Context, req CompleteUploadRequest) (driverLocator, error)

    // GetObject streams bytes for proxy download or
    // migration.
    GetObject(ctx context.Context, driverLocator) (io.ReadCloser, error)

    // PutObject streams bytes for proxy upload or
    // migration source-side buffering.
    PutObject(ctx context.Context, driverLocator, io.Reader, encryptionKeyID string) error

    // DeleteObject hard-deletes an object on this driver.
    DeleteObject(ctx context.Context, driverLocator) error

    // HeadObject returns size / ETag / opaque metadata
    // for a given driverLocator.
    HeadObject(ctx context.Context, driverLocator) (ObjectMetadata, error)

    // CreateSignedURL returns a time-bound, scoped URL to
    // GET the object via the driver's native signing
    // primitive.
    CreateSignedURL(ctx context.Context, driverLocator, ttl time.Duration, scope string) (string, error)

    // Probe is the synthetic health check. Drivers
    // implement a fast, side-effect-free operation here:
    // HeadBucket for S3, GetServiceProperties for Azure,
    // GetNamespace for OCI, GetBucket for GCS, statvfs for
    // local FS.
    Probe(ctx context.Context) (ProbeResult, error)

    // Shutdown drains SDK clients and flushes pending
    // writes on graceful shutdown.
    Shutdown(ctx context.Context) error
}
```

`driverLocator` is `map[string]any`, shape varies by
driver; the service treats it as opaque and stores /
passes it through JSONB.

## 6. Security

- **AuthN**: Keycloak resource server (Spring Security 7 for Kotlin, `coreos/go-oidc` v3 for Go, `authlib` for Python)
- **AuthZ**: RBAC (JWT scopes / roles)
- **Secrets**: external secret manager (Kubernetes External Secrets / Vault — no secret in env)
- **mTLS**: linkerd sidecar, all intra-cluster traffic

## 7. Observability

- **Tracing**: OpenTelemetry SDK 1.40+ → OTLP
- **Metrics**: `prometheus/client_golang` → Prometheus
- **Logs**: structured JSON to stdout (Loki)
- **Health**: `/healthz` (custom handler)

## 8. Scaling

- **HPA signal**: RPS, 3–30 replicas, p99 < 100ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: `go run ./cmd/file`
- **Test**: `go test ./...`
- **Compose profile**: `docker compose --profile file up`

## 10. Admin endpoints & RBAC

This service exposes `/admin/v1/...` endpoints for the `admin-service`
BFF and platform operators. The platform-wide admin pattern (roles,
audit format, network policy, common endpoints) is in
[`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac);
this section documents the **per-service specifics**.

### 10.1 Keycloak admin roles accepted

This service accepts admin calls from these Keycloak roles:

- `platform.super_admin`
- `platform.admin`
- `platform.ops`
- `file.admin`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.file.v1`
- **Consumer**: `audit-service` (writes to its immutable `audit` schema)
- **Fields**: `actor_id`, `actor_username`, `roles`, `endpoint`,
  `target_resource`, `action`, `reason_code` (required for PII access),
  `request_id`, `trace_id`, `result`, `duration_ms`

### 10.3 Data access policy (per-service)

The platform-wide policy table is in
[RECOMMENDATIONS.md 6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide).
This service refines it as follows:

| Data class | super_admin | admin | ops | support | finance | engineering | data_eng |
|---|---|---|---|---|---|---|---|
| File metadata (DB) | ✓ | ✓ | ✓ | read+reason | — | — | scrubbed |
| File blobs (any driver) | ✓ | ✓ | ✓ | — | — | — | — |
| Driver catalog (`storage_drivers`) | ✓ | ✓ | ✓ | — | — | read | scrubbed |
| Driver history / assignments | ✓ | ✓ | ✓ | read+reason | — | read | scrubbed |
| Driver health events | ✓ | ✓ | ✓ | — | — | read | — |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/files/{id}/purge` | `file.admin` | Hard-delete a file and its metadata (GDPR / retention); routed to the file's `driver_id` |
| `POST` | `/admin/v1/files/{id}/quarantine` | `file.admin` | Quarantine a file (move to cold storage; flag for review) |
| `POST` | `/admin/v1/files/{id}/rescan` | `file.admin` | Force a virus re-scan (e.g. after a ClamAV signature update) |
| `GET`  | `/admin/v1/drivers` | `file.admin` | List configured Storage Drivers with state, health, and synthetic probe result |
| `POST` | `/admin/v1/drivers/{id}` | `file.admin` | Update driver state (`enabled` / `draining` / `disabled`), priority, KMS key, signed URL TTL, max object size — recorded in `driver_history` |
| `POST` | `/admin/v1/drivers/{id}/pin` | `file.admin` | Pin a file (or all files of an owner) to a specific driver. HMAC + co-signature |
| `POST` | `/admin/v1/migrations` | `file.admin` | Enqueue a single-file or bulk migration between drivers. HMAC + co-signature |
| `GET`  | `/admin/v1/migrations/{id}` | `file.admin` | Migration progress |
| `POST` | `/admin/v1/migrations/{id}/cancel` | `file.admin` | Cancel an in-flight bulk migration |
| `GET`  | `/admin/v1/files/{id}/driver` | `file.admin` | Read `driver_id`, `driver_kind`, opaque `driver_locator` (introspection) |
| `GET`  | `/ready/drivers/{id}` | n/a | Per-driver readiness flag (no auth on the service internal port) |

### 10.5 Admin enforcement

- **Pattern**: `net/http` middleware that reads `coreos/go-oidc` v3 ID-token claims; admin mux mounted on `:8081` separately from public mux on `:8080`
- **Network**: admin port (`8081`) is reachable only from the
  `admin-service`, `platform-ops`, and `platform-engineering`
  namespaces + bastion. Public ingress is not routed to it.
- **mTLS**: linkerd sidecar on every admin call.

### 10.6 Local admin (dev only)

- **Run with admin port**: `ADMIN_PORT=8081 go run ./cmd/file`
- **Test admin endpoints**: `go test -run Admin ./...`
- **Mint a dev admin JWT**: `make admin-jwt ROLE=platform.admin`

### 10.7 Super Admin preset membership

This service is included in the platform's `SUPER_ADMIN` permission
preset. An operator granted the preset receives:

- The platform-wide guard role `platform.super_admin`.
- This service's per-service admin role `file.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` 14) and
emit `audit.admin.file.v1` (per 10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Edge / hot path — Go / `net/http` + `chi`.

**External vendor SDK.** S3 · ClamAV (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 4 *Go OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: `aws-sdk-go-v2` (S3) · `go-redis/redis v9` · `prometheus/client_golang`.

**Extractability.** This service can be lifted out of the platform and run as a
standalone project without code changes. The minimum dependency manifest
that demonstrates this is [`SKELETON.go.mod`](./SKELETON.go.mod)
(doc-only stub; not a runnable build). The split between platform-required
and swappable dependencies is:

| Dependency class | Platform-required | Swappable |
|---|---|---|
| Language runtime | — | JDK 25 / Go 1.25 / Python 3.14 (use whatever your env needs) |
| Web/framework | `platform-spring-boot-starter` (Kotlin) / `net/http` + `chi` (Go) / FastAPI (Python) | Replace with your preferred framework |
| Database | PostgreSQL 19 (per-service schema) | H2 (in tests) / any PostgreSQL 14+ compatible |
| Migrations | Flyway 11 (Kotlin) / `golang-migrate` v4 (Go) / Alembic (Python) | Any tool that produces the same SQL |
| Cache | Redis 8 (cluster) | Caffeine (in-process) / no cache |
| Messaging | Apache Kafka 3.9 | In-process `BlockingQueue` for tests |
| Identity | Keycloak | Stub JWT verifier (JWKS = a static fixture) |
| Observability | OpenTelemetry SDK → OTLP | Logback / logrus / structlog direct to stdout |
| External vendor SDK | (per the "External" column of [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) 2) | Swap or stub at the driver boundary (`PaymentGatewayDriver`, `MapProvider`, etc.) |

**Single source of truth.** The full licence catalogue (SPDX IDs,
license-text URLs, NOTICE / THIRD-PARTY-LICENSES generation tooling,
license compatibility matrix) is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
The version pin for every library is in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
Do not pin versions in this file.

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

---

> All pinned versions are in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
> Admin endpoints, roles, and audit conventions are pinned in
> [`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac).
> To bump versions or change the admin pattern, open a PR against the
> corresponding section — never pin versions directly in this file.
