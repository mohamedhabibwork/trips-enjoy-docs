# file-service

## 1. Purpose

`file-service` is the platform's **file and media storage
abstraction**. It owns the metadata for every file the
platform stores — KYC documents, restaurant menu photos,
driver vehicle photos, customer support attachments, safety
recording chunks, profile avatars — and provides a stable
API for upload, virus scan, and signed URL issuance.

The service is the *only* component in the platform that
talks to **any** underlying object store, block store, or
filesystem. Storage access is hidden behind a **Storage
Driver** abstraction so that the rest of the platform, the
deployment topology, the on-call runbook, and every
internal code path are identical regardless of which
backend carries the bytes:

| Driver id | Backend family | Use cases |
|-----------|----------------|-----------|
| `s3` | AWS S3, MinIO, Ceph RGW, Wasabi, Cloudflare R2, any S3-protocol-compatible store | Production default; multi-region; hybrid cloud |
| `azure_blob` | Azure Blob Storage (Hot / Cool / Archive tiers) | Azure-native deployments; sovereign-cloud EU/US-Gov |
| `oracle_object_storage` | Oracle Cloud Infrastructure (OCI) Object Storage (Swift-compatible, S3-compatible endpoint) | OCI-native deployments; Oracle Cloud@Customer |
| `gcs` | Google Cloud Storage (S3-compatible interoperability mode supported) | GCP-native deployments |
| `local_fs` | POSIX filesystem mounted into the pod (NVMe, EBS, CephFS, NFS) | local-dev, CI, edge deployments without object store |

The driver is **per file** (`files.driver_id`), not just
per environment, so a single tenant may live in one region /
cloud while the platform's default lives in another, and
migrations can run gradually file-by-file without a
cutover.

## 2. Bounded Context

**Bounded Context**: *File / media storage abstraction*
with a generic *Storage Driver* port.

In scope:

- File metadata (name, size, mime, owner, status, scan
  result, retention class, **driver id + driver-opaque
  storage locator**).
- **Storage Driver abstraction**: a Go interface that
  every backend (S3-compatible, Azure Blob, OCI Object
  Storage, GCS, local FS) implements, so the upload /
  download / sign / delete path is identical.
- Upload (presigned / SAS / signed-redirect URL for
  direct-to-driver, or proxy upload for small files).
- Virus scan integration (sync or async).
- Time-bound URL issuance (per driver — S3 presign, Azure
  SAS, OCI pre-authenticated request, GCS v4 signed URL,
  local FS short-lived reverse proxy).
- Retention (per file class; e.g. KYC = until account
  closure + 5y; support attachments = 1y).
- Soft delete (files are never hard-deleted immediately;
  the retention job purges them later).
- Audit log of every access (download, delete, driver
  resolution failures).
- **Driver migration**: per-file driver pinning,
  background rehydration between drivers, dual-write
  windows.

Out of scope:

- The bytes themselves — stored by the active driver; the
  service stores only metadata and a driver-opaque locator.
- CDN / image transformation — a separate
  `image-service` (not yet documented here) handles CDN
  and transformation; this service provides the source
  URL (which is itself a driver-issued signed URL).
- User identity / KYC — owned by profile services.
- The application's use of the file — e.g. a menu photo
  is rendered by the restaurant app; the file is just
  stored here.

## 3. Responsibilities

- Maintain `file.files`, `file.scans`, `file.access_log`,
  `file.retention_policies`,
  `file.storage_drivers`, `file.driver_migrations`.
- Resolve, for every request, which **Storage Driver**
  handles the file (`files.driver_id`) and forward all I/O
  to that driver through the shared `StorageDriver`
  interface.
- Provide `POST /v1/files` (initiate upload — the response
  shape is identical regardless of driver; only the URL
  format differs and is opaque to the caller), `POST
  /v1/files/{id}/complete` (notify upload done), `GET
  /v1/files/{id}` (read metadata), `DELETE
  /v1/files/{id}` (soft delete), `POST
  /v1/files/{id}/signed-url` (issue a signed URL via the
  driver's signing primitive).
- Provide `GET /v1/files/{id}/download` (proxy download
  for small files; for large files, redirect to a driver-
  signed URL).
- Integrate with a virus scan provider (e.g. ClamAV,
  VirusTotal, AWS GuardDuty Malware Protection) — sync
  scan for small files, async for large.
- Emit `file.uploaded.v1`, `file.scanned.v1`,
  `file.deleted.v1` for downstream services (events are
  driver-agnostic; they carry `driver_id` for downstream
  routing but never the raw URL).
- Honor right-to-erasure: soft-delete the user's files;
  the retention job hard-deletes them on the same driver
  the file was stored on.
- Enforce retention policies (per file class).
- Manage the per-tenant / per-file driver catalog and run
  driver migrations (background job that copies bytes from
  the source driver to the destination driver, verifies
  the SHA-256, flips the canonical `driver_id`).

## 4. Explicitly NOT Owned

- **The bytes themselves** — owned by the active Storage
  Driver (S3-compatible, Azure Blob, OCI, GCS, local FS);
  this service stores only metadata + the driver-opaque
  locator.
- **CDN / image transformation** — `image-service` (a
  separate concern; this service provides the driver-
  signed source URL).
- **User identity, KYC** — `identity-service`, profile
  services.
- **The application's use of the file** — e.g. the menu
  service stores the `file_id` of each photo; the photo is
  rendered by the app via the driver-signed URL.
- **Driver-specific key namespace allocation** — each
  driver owns its own key/prefix scheme (`s3_key`, `blob
  name`, `oci object-name`, `gcs object`, `local path`).
  The service speaks in `(driver_id, locator)` tuples.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `customer-service` | system | upload (KYC, avatar), read own |
| `driver-service` | system | upload (vehicle, license), read own |
| `courier-service` | system | upload (vehicle), read own |
| `merchant-service` | system | upload (legal docs), read own |
| `restaurant-service` | system | upload (menu photos), read own |
| `support-service` | system | upload (attachments), read ticket files |
| `ride-safety-service` | system | upload (safety recording chunks) |
| End user (via app) | human | upload (profile photo), read own |
| `admin-service` | system | admin operations, per-file driver pinning, migration triggers |
| Operations (admin) | human | retention overrides, manual virus scan re-run, driver migration approval |
| Virus scan provider | external | system |
| **Storage Driver** (S3-compatible / Azure Blob / OCI / GCS / local FS) | infra | external — the bytes |
| Driver config / secrets (Vault) | infra | per-driver credentials |

## 6. Dependencies

### Synchronous (REST) — outbound to drivers

- **Storage Driver layer** — a single internal interface
  with multiple implementations:
  - **S3 driver family** (`s3` driver id) → AWS S3 API,
    MinIO (S3v4), Ceph RGW, Wasabi, Cloudflare R2 — via
    `aws-sdk-go-v2`.
  - **Azure Blob driver** (`azure_blob` driver id) → via
    `Azure SDK for Go` (`azblob`).
  - **Oracle Object Storage driver**
    (`oracle_object_storage` driver id) → via OCI Go SDK
    (`objectstorage`) and the S3-compatible endpoint where
    enabled.
  - **GCS driver** (`gcs` driver id) → via
    `cloud.google.com/go/storage` (native) **and** the S3
    interoperability mode (configurable).
  - **Local FS driver** (`local_fs` driver id) → via
    `os`/`afero` against a mounted volume.
- **Virus scan provider** — scan a file — SLO 99.9% —
  circuit breaker: yes.
- `configuration-service` — read retention policies and
  the driver catalog — SLO 99.95% — circuit breaker: yes.

### Asynchronous (events consumed)

- `configuration.updated.v1` from `configuration-service` —
  retention, mime allowlist, max size, **and driver catalog
  updates** changed.
- `storage.driver.health.changed.v1` (optional, future) —
  per-driver health flap signals for adaptive routing.

### Asynchronous (events produced)

- `file.uploaded.v1` — every successful upload + scan
  (carries `driver_id`).
- `file.scanned.v1` — every scan result (clean or
  infected).
- `file.deleted.v1` — every soft delete (carries
  `driver_id`).
- `file.migrated.v1` — when a file is physically moved
  from one driver to another (`from_driver_id`,
  `to_driver_id`).

## 7. Technology Assumptions

- Runtime: Go 1.25.x (per `TECH.md`).
- Database: PostgreSQL 18 in schema `file` (files, scans,
  access log, retention policies, **driver catalog**,
  **migration ledger**).
- Cache: Redis 7 (per-service) for signed URL cache and
  scan dedup (signed URLs are cached **per driver +
  locator**, not as a single key — cache key is
  `driver:{driver_id}:signed_url:{file_id}:{purpose}`).
- Event broker: Kafka.
- **Storage Driver** is implemented as a Go interface in
  the service (one binary, many compile-time-selected
  drivers); each driver has its own SDK dependency.
- Virus scan: ClamAV (self-hosted), VirusTotal (third-
  party), or AWS GuardDuty Malware Protection.

## 8. Database Ownership

- Schema: `file`
- Migrations: `services/file-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: yes (files; hard delete by retention job,
  routed through the file's `driver_id`).
- Partitioning: yes — `file.access_log` partitioned by
  month (high volume).

## 9. API Overview

The public API is **driver-agnostic** — the same endpoints
return the same shape regardless of which Storage Driver
backs the file. Internally the service resolves the
driver, but the URL issued to clients is opaque (it may be
`https://…s3…X-Amz-Signature=…`, an Azure SAS
`https://…blob.core.windows.net/…?sv=…&sig=…`, an OCI
PAR, a GCS v4 signed URL, or a `/files/{id}/stream`
reverse-proxy URL for the local FS driver).

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/files | bearer / service | initiate upload (returns driver-issued presigned/SAS/PAR URL, or accepts body for the proxy flow) |
| POST | /v1/files/{id}/upload | bearer / service | proxy upload (server streams bytes to the resolved driver) |
| POST | /v1/files/{id}/complete | bearer / service | notify upload complete (triggers scan) |
| GET | /v1/files/{id} | bearer (own) / service | read metadata (includes `driver_id`) |
| POST | /v1/files/{id}/signed-url | bearer (own) / service | issue a driver-signed URL |
| GET | /v1/files/{id}/download | bearer (own) / service | download (proxy or redirect) |
| DELETE | /v1/files/{id} | bearer (own) / service | soft delete |
| GET | /v1/files/{id}/scan | bearer / service | read scan result |
| GET | /v1/admin/drivers | admin | list configured drivers + health |
| POST | /v1/admin/drivers/{id}/pin | admin | pin a file or all files of an owner to a specific driver |
| POST | /v1/admin/migrations | admin | enqueue a file (or bulk) migration to a target driver |
| GET | /v1/admin/migrations/{id} | admin | read migration status |
| POST | /v1/admin/retention/run | admin | run retention job manually |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `file.uploaded.v1` | every successful upload + scan (carries `driver_id`) | owner service, `audit-service` |
| `file.scanned.v1` | every scan result | owner service, `support-service` (if infected) |
| `file.deleted.v1` | every soft delete (carries `driver_id`) | `audit-service` |
| `file.migrated.v1` | a file is physically moved from `from_driver_id` to `to_driver_id` (after SHA-256 verified) | `audit-service`, owner service |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | retention policies changed **and/or** driver catalog / default driver changed | reload config (idempotent; config hash compared before swap) |
| `storage.driver.health.changed.v1` (optional, future) | driver health monitor | a driver is degraded | pause new uploads to that driver; let in-flight finish; reschedule migrations away |

## 12. External Integrations

- **Storage Driver layer** — every byte read or written
  passes through `StorageDriver.{InitiateUpload,
  CompleteUpload, GetObject, DeleteObject,
  CreateSignedURL}`. Implementations live in
  `internal/storage/drivers/{s3,azure_blob,
  oracle_object_storage,gcs,local_fs}/`.
- **Virus scan provider** — ClamAV, VirusTotal, or
  equivalent. Driver-agnostic; credentialed from Vault.
- **Vault** — per-driver credentials (S3 access keys, Azure
  service-principal secret, OCI user OCID + fingerprint
  + private key, GCS service-account JSON, local-FS
  root path + read-only key).

## 13. Configuration

### 13.1 Per-driver configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `file.driver.default` | string | configuration-service | driver id used when no per-file / per-tenant / per-class override applies (`s3` in most production envs) |
| `file.driver.<id>.kind` | enum | configuration-service | `s3`, `azure_blob`, `oracle_object_storage`, `gcs`, `local_fs` |
| `file.driver.<id>.enabled` | bool | configuration-service | admin can disable a driver (drain) |
| `file.driver.<id>.priority` | int | configuration-service | order in the resolver; lower wins |
| `file.driver.<id>.health_url` | URL | configuration-service | synthetic probe (S3 HeadBucket, Azure GetServiceProperties, OCI GetNamespace, GCS GetBucket, local-FS statvfs) |
| `file.driver.<id>.region` / `location` | string | configuration-service | for cloud drivers |
| `file.driver.<id>.bucket` / `container` | string | configuration-service | the bucket/container name |
| `file.driver.<id>.endpoint` | URL | configuration-service | for S3-compatible only; `null` for AWS-native |
| `file.driver.<id>.path_style` | bool | configuration-service | `true` for MinIO, Wasabi, R2; `false` for AWS |
| `file.driver.<id>.kms_key_id` | string | configuration-service | per-driver CMK (KYC) |
| `file.driver.<id>.signed_url_ttl_seconds` | int | configuration-service | default 900 (15 min); override per request |
| `file.driver.<id>.max_object_size_bytes` | int | configuration-service | per-driver cap |
| `file.driver.<id>.multipart_threshold_bytes` | int | configuration-service | when to switch from single PUT to multipart (S3/OCI/Azure; n/a for local FS / GCS) |

### 13.2 Driver overrides

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `file.driver.override.tenant.<tenant_id>` | string | configuration-service | pin a tenant to a driver id |
| `file.driver.override.retention_class.<class>` | string | configuration-service | pin a class (e.g. `kyc` → always `s3` with KMS) |
| `file.driver.override.owner.<owner_type>` | string | configuration-service | pin an owner type (e.g. `safety_recording` always to `s3`) |

### 13.3 General (driver-agnostic)

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `file.scan.provider` | string | configuration-service | `clamav`, `virustotal`, `gd_malware` |
| `file.scan.sync_max_size_bytes` | int | configuration-service | default 5MB |
| `file.retention.kyc` | duration | configuration-service | `until_account_closure + 5y` |
| `file.retention.support_attachments` | duration | configuration-service | `1y` |
| `file.retention.avatar` | duration | configuration-service | `while_user_active + 30d` |
| `file.max_upload_size_bytes` | int | configuration-service | default 100MB; enforced against the resolved driver's own cap |
| `file.allowed_mime_types` | array | configuration-service | deny by default |
| `file.migration.enabled` | bool | configuration-service | gate the migration worker |
| `file.migration.max_concurrent_per_driver` | int | configuration-service | throttle per-source, per-dest |
| `file.migration.verify_sha256` | bool | configuration-service | default `true` |
| `file.migration.dual_write_window_days` | int | configuration-service | default `7` |

## 14. Security

- **AuthN**: bearer JWT (validated at gateway); internal
  calls use client-credentials tokens.
- **AuthZ**: user can read/write their own files; service
  can read/write on behalf of users (for system uploads);
  admin for retention overrides **and** per-file driver
  pinning / migrations.
- **Secrets**: per-driver credentials in Vault, rotated
  quarterly. Each driver has its own Vault path
  (`secret/file-service/drivers/<id>`).
- **PII**: KYC documents, vehicle photos, profile photos
  are PII (Sensitive for KYC, Confidential for the rest).
  Encrypted at rest using whichever primitive the active
  driver provides — S3 SSE-KMS, Azure CMK, OCI KMS,
  GCS CMEK, local-FS LUKS / dm-crypt on the mounted
  volume — **plus** per-object KMS for KYC on cloud
  drivers.
- **Virus scan**: 100% of files are scanned before being
  marked `available` regardless of the driver; infected
  files are quarantined.
- **Signed-URL scoping**: driver-issued URLs are scoped to
  the requested verb (GET only vs full control), bound to
  a `file_id` + `purpose` claim in their query string, and
  recorded in `file.access_log`.

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`,
  `trace_id`, `file_id`, `owner_id`, `mime_type`,
  `size_bytes`, `scan_status`, **`driver_id`,
  `driver_operation`, `driver_latency_ms`**,
  `latency_ms`.
- **Metrics**: RED (per route) + business:
  - `files_uploaded_total{owner_type, mime_class,
    upload_method, driver_id}`
  - `files_scanned_total{result}` (clean, infected,
    error)
  - `file_scan_seconds` (histogram)
  - `file_signed_url_seconds{result, driver_id}`
    (histogram; `result` = `cache_hit`, `cache_miss`,
    `driver_error`)
  - `file_storage_bytes{driver_id, retention_class}`
    (gauge)
  - `files_deleted_total{reason, driver_id}` (user,
    retention, admin)
  - **Per driver**:
    `storage_driver_requests_total{driver_id,
    operation, outcome}`,
    `storage_driver_request_seconds{driver_id,
    operation}` (histogram),
    `storage_driver_errors_total{driver_id,
    operation, error_class}` (throttled, dns, timeout,
    5xx, auth),
    `storage_driver_health{driver_id}` (0/1 gauge,
    drained via the synthetic probe),
    `storage_driver_signed_urls_active{driver_id}`
    (Redis-tracked outstanding count).
  - **Migrations**:
    `file_migrations_in_flight{driver_from, driver_to}`,
    `file_migrations_completed_total{driver_from,
    driver_to}`,
    `file_migrations_failed_total{driver_from,
    driver_to, reason}`,
    `file_migration_bytes_total{driver_from,
    driver_to}`.
- **Traces**: OpenTelemetry; root span per upload;
  driver call as child span (`storage.driver` span name
  convention; `driver.id`, `driver.operation`, `object.key`
  attributes); virus scan as child span.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka +
  every **enabled** driver reachable + virus scan
  provider reachable), `/started`. Per-driver readiness
  is reported individually (`/ready/drivers/{id}`); if
  the default driver is down, `/ready` returns 503, but if
  only a non-default driver is down, the service stays
  ready and traffic is routed to a healthy driver.

## 16. Scalability

- **Replicas**: default 6.
- **HPA**: CPU 60%, custom metric
  `files_uploads_per_second > 50` per replica.
- **Hot path**: `POST /v1/files/{id}/signed-url`. P99 ≤
  50ms (cache hit), ≤ 200ms (driver sign — measured per
  driver).
- **Driver fan-out**: each driver has its own connection
  pool, circuit breaker, and back-pressure signal; one
  slow driver cannot starve the others because the worker
  pool is partitioned per driver.

## 17. Local Development

- `docker compose up file-service` brings up the service,
  its DB, Redis, Kafka, **five driver sandboxes**
  running in parallel:
  - **MinIO** (`s3`-compatible),
  - **Azurite** (`azure_blob`),
  - a **`local_fs`** volume mount,
  - **fake-s3 / moto** for an isolated `s3` test bucket,
  - OCI / GCS via `fake-gcs-server` + a fake OCI emulator
    (or `tflocal` for GCS).
  Each driver is configured in `config/dev.yaml` and a
  default driver is selected via `file.driver.default`.
- Seed: 100 sample files (images, PDFs) with mock scan
  results, distributed evenly across drivers so the
  migration workflow has something to move.

## 18. Deployment

- **Image**: `ghcr.io/uber/file-service:<git-sha>`.
- **Replicas**: 6 in production.
- **Resource limits**: see deployment-arch (`cpu: 500m`,
  `memory: 768Mi` requests; 1 CPU, 1.5Gi limits).
- **Migrations**: run as a Kubernetes Job on deploy.
- **Driver buckets / containers**: pre-created per
  environment and per driver; naming convention
  `<env>-<region>-<purpose>` (e.g.
  `prod-eu1-kyc`, `prod-eu1-avatar`).
- **Virus scan**: ClamAV cluster (3 replicas) for sync
  scans; VirusTotal for the rare async deep scan.
- **Migration worker**: separate Deployment
  (`file-service-migrator`) with HPA on queue depth; runs
  the same binary with `--role=migrator`.

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships, **driver catalog, migration ledger**)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas, **driver resolution**)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes, **driver migration**)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC, **driver SDKs**)

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`identity-service`](../identity-service/README.md), [`merchant-service`](../merchant-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`support-service`](../support-service/README.md)
- **Depended on by**: [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-service`](../driver-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`support-service`](../support-service/README.md), [`user-profile-service`](../user-profile-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)