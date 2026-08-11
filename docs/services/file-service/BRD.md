# file-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`file-service` must do for the business. It is read by
the platform architecture team, the security team
(virus scan, encryption, retention), the operations team
(S3 bucket management, retention), the service's
engineering team, and any auditor verifying the platform's
file-handling practices. It informs the retention policy,
the virus scan policy, the encryption policy, the
right-to-erasure flow for files, and the integration with
KYC and support.

## 2. Business Context

The platform stores millions of files: customer KYC
documents, driver licenses and vehicle photos, courier
vehicle photos, restaurant menu photos, customer support
attachments, safety recording chunks, profile avatars.
Each of these has a different retention requirement, a
different access pattern, and a different sensitivity
level. The platform also needs to:

1. **Scan every file for viruses** before it's available.
2. **Encrypt sensitive files** at rest on whatever backend
   the bytes happen to live on.
3. **Issue time-bound signed URLs** for downloads, using
   whatever signing primitive the active backend exposes
   (S3 v4 signature, Azure SAS, OCI pre-authenticated
   request, GCS v4 signed URL, short-lived reverse-proxy
   ticket for local FS).
4. **Honor right-to-erasure** requests (GDPR / PDPL).
5. **Stay under object-storage cost** with intelligent
   tiering and retention.
6. **Run on multiple backends simultaneously** (AWS S3,
   MinIO, Azure Blob, Oracle Object Storage, GCS, a
   local POSIX filesystem) without rewriting the platform.

`file-service` is the abstraction. Without the Storage
Driver layer, every service that needs to store a file
would embed a backend SDK, get retention wrong, and lock
the platform into a single vendor. With the driver layer,
the rest of the platform speaks one dialect
(`file_id`, `driver_id`, opaque signed URL) and a new
backend is one more driver implementation away.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a single, vendor-neutral API for upload, download, signed URL, and delete | 100% of file storage flows through this service |
| BR--002 | 100% of uploaded files are virus-scanned before being available | 0% of files served without a clean scan |
| BR--003 | KYC documents encrypted with per-tenant KMS key | 100% of KYC files use a per-tenant key |
| BR--004 | Right-to-erasure honored within 24h of request | 100% within 24h |
| BR--005 | Signed URL issuance P99 ≤ 200ms (cache hit ≤ 50ms) | API P95/P99 measured |
| BR--006 | Per-class retention enforced automatically | 0% of files retained past their class retention |
| BR--007 | Stay under object-storage cost with intelligent tiering and retention on every cloud driver | per-class cost trending down |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Platform Architecture | owner | vendor abstraction, SLO |
| Security | reviewer | virus scan, encryption, retention |
| Compliance | reviewer | GDPR / PDPL, KYC retention |
| Operations | consumer | retention overrides, manual scan |
| Customer Support | consumer | ticket attachments |
| Engineering (consumer services) | consumer | "store this file" / "give me a signed URL" |
| Finance | reviewer | object-storage cost (per driver) |
| Trust & Safety | consumer | safety recording chunks |

## 5. Actors / Personas

- **Customer (rider / diner)**: uploads a profile photo,
  KYC document.
- **Driver / Courier**: uploads vehicle photos, license.
- **Merchant / Restaurant staff**: uploads menu photos,
  legal documents.
- **Support agent**: uploads / reads ticket attachments.
- **Safety team**: ingests safety recording chunks.
- **Operations (admin)**: runs retention manually, re-scans
  a file.

## 6. Business Capabilities

- **Storage Driver abstraction** — one service, many
  backends: AWS S3 and any S3-protocol-compatible store
  (MinIO, Ceph RGW, Wasabi, Cloudflare R2), Azure Blob
  Storage, Oracle Cloud Infrastructure (OCI) Object
  Storage, Google Cloud Storage, and a local
  POSIX filesystem for edge / dev / CI. The rest of the
  platform does not know which driver backs a file.
- **Upload** (driver-issued upload URL for the direct
  flow, or proxy upload for small files).
- **Virus scan** (sync for small, async for large).
- **Signed URL** issuance (time-bound, scoped, using the
  active driver's native signing primitive).
- **Download** (proxy or redirect).
- **Soft delete** + retention-based hard delete (always
  routed through the file's `driver_id`).
- **Per-class retention** policy.
- **Encryption** at rest, using whichever primitive the
  active driver provides; per-tenant KMS for KYC on cloud
  drivers.
- **Right-to-erasure** for user files.
- **Access audit log** (every download, on every driver).
- **Driver selection & override** — default driver at the
  environment level, with per-tenant, per-retention-class,
  and per-file pinning overrides.
- **Driver migration** — move bytes from one driver to
  another on demand, verify SHA-256, flip the canonical
  `driver_id`, emit `file.migrated.v1`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only writer of file metadata and the only component that talks to any backend object store, block store, or filesystem. | MUST | data ownership, platform architecture |
| BR--011 | The service MUST virus-scan 100% of uploaded files before they are marked `available`, regardless of which Storage Driver backs the file. | MUST | security |
| BR--012 | The service MUST support a direct-to-driver upload flow (presigned URL, SAS, pre-authenticated request, GCS v4 signed URL, or signed-redirect) for files > 5MB. | MUST | performance, cost |
| BR--013 | The service MUST support a proxy upload flow for files ≤ 5MB. | MUST | DX |
| BR--014 | The service MUST issue time-bound signed URLs (default 15 min, configurable), using the active driver's native signing primitive. | MUST | security |
| BR--015 | The service MUST support per-class retention (KYC, support, avatar, etc.) with automatic purging on the same driver the file was stored on. | MUST | compliance |
| BR--016 | The service MUST honor right-to-erasure within 24h of request, across every driver the file may live on. | MUST | GDPR, PDPL |
| BR--017 | The service MUST encrypt all files at rest using the active driver's encryption primitive (S3 SSE-KMS, Azure CMK, OCI KMS, GCS CMEK, local-FS LUKS), with per-tenant KMS for KYC on cloud drivers. | MUST | security |
| BR--018 | The service MUST emit `file.uploaded.v1`, `file.scanned.v1`, `file.deleted.v1`, and `file.migrated.v1` (the latter carries `from_driver_id` and `to_driver_id`). | MUST | audit, integration |
| BR--019 | The service MUST enforce a per-mime-type allowlist (deny by default). | MUST | security |
| BR--020 | The service MUST log every download in an access log, regardless of driver. | MUST | audit |
| BR--021 | The service MUST support a quarantine state for infected files (no signed URL issued, on any driver). | MUST | security |
| BR--022 | The service MUST support admin override for retention (e.g. legal hold extends retention), per-file or per-driver. | MUST | compliance |
| BR--023 | The service MUST support image transformations (resize, format conversion) via a future `image-service`; this service stores the source. | SHOULD | product |
| BR--030 | The service MUST support multiple concurrent Storage Drivers (S3 / S3-compatible, Azure Blob, Oracle Object Storage, GCS, local FS) selectable per file. | MUST | multi-cloud, no vendor lock-in |
| BR--031 | The service MUST select the active driver per file using a documented precedence (per-file pin → per-tenant → per-owner-type → per-retention-class → env default). | MUST | determinism |
| BR--032 | The service MUST be able to migrate a file (or bulk of files) from one driver to another without changing the public `file_id`. | MUST | portability |
| BR--033 | The service MUST verify SHA-256 after every migration before flipping the canonical `driver_id`. | MUST | integrity |
| BR--034 | The service MUST keep a per-file `driver_history` audit row for every migration / pinning change. | MUST | audit |
| BR--035 | The service MUST disable new uploads to a driver that is marked `drained` while letting in-flight uploads and reads complete. | MUST | safe driver rollout |
| BR--036 | The public API MUST be identical regardless of the active driver (callers never see driver-specific URL shapes, query parameters, or signing headers). | MUST | consumer decoupling |
| BR--037 | The service MUST allow local development and CI without any cloud object store (the `local_fs` driver is sufficient end-to-end). | MUST | developer productivity |
| BR--038 | The service SHOULD expose per-driver observability (latency, error rate, signed URLs outstanding) so degraded drivers do not bring down the service. | SHOULD | reliability |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | Files are not available for download until the virus scan is `clean`. | driver-agnostic |
| BR--021 | A signed URL is valid for ≤ 15 min by default. | configurable per request and per driver |
| BR--022 | Retention is enforced by a daily job; the job soft-deletes (sets `deleted_at`) and then hard-deletes (purges from the file's `driver_id`) after a grace period. | grace period: 7 days |
| BR--023 | Right-to-erasure soft-deletes immediately; the bytes are purged on the file's driver within 1h. | faster than the normal retention job |
| BR--024 | A legal hold on a file prevents hard delete on any driver. | |
| BR--025 | Per-tenant KMS keys for KYC: on cloud drivers the key is per-tenant; on the local-FS driver encryption uses a per-tenant data key managed by `identity-service`. | driver-agnostic policy |
| BR--026 | Driver resolution precedence: **per-file pin** → **per-tenant override** → **per-owner-type override** → **per-retention-class override** → **env default**. First match wins. | recorded in `file.driver_assignments` |
| BR--027 | A driver in state `draining` (admin-marked or auto on health flap) MUST NOT accept new uploads; existing reads, completions, and hard-deletes continue. | |
| BR--028 | A migration is a multi-step write (begin → copy → verify SHA-256 → atomic flip of canonical `driver_id` → cleanup of source). A migration that fails on verify MUST be reverted to the source driver with the original `driver_id` preserved; the file is never left in `available` against a partial copy. | |
| BR--029 | The `local_fs` driver is permitted only in dev / CI / edge; production deployments MUST have at least one cloud driver enabled. | enforced by the admission controller |

## 9. Assumptions

- A Storage Driver is one of: `s3` (AWS S3 / MinIO / Ceph
  RGW / Wasabi / Cloudflare R2 via S3 v4), `azure_blob`
  (Azure Blob Storage via SAS / RBAC),
  `oracle_object_storage` (OCI Object Storage via native
  SDK or the S3-compatible endpoint), `gcs` (Google Cloud
  Storage via native SDK or S3 interoperability mode),
  `local_fs` (mounted POSIX volume). The service is the
  abstraction.
- The virus scan provider is reliable (with retries); we
  scan synchronously for small files and asynchronously
  for large.
- The retention policy is configured centrally; per-class
  overrides are rare.
- The volume of uploads is bursty (e.g. during a KYC
  campaign) but bounded; we can scale horizontally.
- A single tenant may live on a different driver than the
  env default (e.g. an EU tenant pinned to `azure_blob`
  while the rest of the world uses `s3`).
- Migration between drivers is rare and operator-driven
  (regulatory data-residency change, vendor consolidation,
  cost rebalancing); the migration worker is sized for
  bulk rehydration, not steady-state.

## 10. Constraints

- **Latency**: signed URL P99 ≤ 200ms.
- **Security**: 100% scan, 100% encryption, signed URLs
  only.
- **Compliance**: GDPR / PDPL right-to-erasure; PCI (no
  PAN in files).
- **Cost**: object-storage cost is a significant cost
  line on every cloud driver; intelligent tiering and
  retention are non-negotiable.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Storage Driver(s) | provider | one or more of: AWS S3 / S3-compatible (MinIO, Ceph RGW, Wasabi, Cloudflare R2), Azure Blob Storage, Oracle Object Storage, GCS, local FS |
| Virus scan provider | provider | ClamAV, VirusTotal, or equivalent |
| `configuration-service` | service | retention policies, **driver catalog, default driver, per-tenant / per-class driver overrides** |
| Every consumer service | consumer | uploads, reads, signed URLs (driver-agnostic surface) |
| `audit-service` | consumer | reads `file.*.v1` events including `file.migrated.v1` |
| ``admin-service` (support module)` | consumer | right-to-erasure requests, ticket attachments |
| `identity-service` | system | KMS key per tenant (also for non-S3 drivers that delegate key management) |
| PostgreSQL 19 | infra | core storage |
| Redis 8 | infra | signed URL cache (keyed per driver + purpose), scan dedup |
| Kafka | infra | events |
| Vault | infra | **per-driver credentials** (S3 access keys, Azure service-principal secret, OCI user OCID + fingerprint + private key, GCS service-account JSON, local-FS path) + virus scan API keys |

## 12. Business Workflows

- **Upload a file (small, proxy)** — see `WORKFLOWS.md` 1.
- **Upload a file (large, direct-to-driver)** — see
  `WORKFLOWS.md` 2.
- **Download a file (signed URL)** — see `WORKFLOWS.md` 3.
- **Right-to-erasure** — see `WORKFLOWS.md` 4.
- **Retention purge** — see `WORKFLOWS.md` 5.
- **Infected file quarantine** — see `WORKFLOWS.md` 6.
- **Driver selection on upload** — see `WORKFLOWS.md` 7.
- **Driver migration (single file)** — see `WORKFLOWS.md` 8.
- **Driver migration (bulk, e.g. AZ → EU data-residency)** —
  see `WORKFLOWS.md` 9.
- **Driver drain & decommission** — see `WORKFLOWS.md` 10.

## 13. Exception Workflows

- **Virus scan provider down**: the file is marked
  `pending_scan`; downloads are blocked; an alert fires.
  The retention job retries the scan daily for 7 days; if
  still unscanned, the file is escalated to ops.
- **A specific driver down**: only files on that driver
  fail; the driver enters `circuit_open` and its
  per-driver readiness flips; traffic is routed to a
  healthy driver when a new driver is needed
  (`pending_scan` files on the failing driver are
  retried; reads return 503 with `DRIVER_UNAVAILABLE`
  after the per-driver circuit breaker opens).
- **Signed URL cache miss**: re-sign on the fly against
  the active driver (still fast for warm drivers).
- **Migration verify fails** (SHA-256 mismatch on
  destination): the canonical `driver_id` is **not**
  flipped; the source bytes remain canonical; an alert
  fires; the migration job retries with exponential
  backoff; after 5 failures it escalates.
- **Driver drained mid-flight**: in-flight uploads and
  reads complete; new uploads fail fast with 503
  `DRIVER_DRAINED`; ops migrate or roll back.

## 14. Success Criteria

- 100% of file storage flows through this service,
  regardless of which Storage Driver is selected.
- 0% of files served without a clean scan.
- 100% of KYC files encrypted with per-tenant KMS key
  (or per-tenant data key for the `local_fs` driver).
- 100% of right-to-erasure requests within 24h.
- P99 signed URL ≤ 200ms (cache hit ≤ 50ms), measured
  per driver and overall.
- 0% of files retained past their class retention.
- Object-storage cost trending down month over month.
- 0% of migrations with silent SHA-256 mismatch
  (every migration is verified before the canonical
  `driver_id` is flipped).
- 100% of migrations produce a `driver_history` row.
- Adding a new Storage Driver (e.g. a new
  self-hosted object store) requires zero changes outside
  the `internal/storage/drivers/<new-id>/` package.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Upload P95 | ≤ 1s (proxy), ≤ 500ms (direct-to-driver) | `http_request_duration_seconds` |
| Signed URL P99 | ≤ 200ms (cache hit ≤ 50ms), measured per driver | `file_signed_url_seconds{driver_id}` |
| Scan P95 | ≤ 500ms (sync), ≤ 5s (async) | `file_scan_seconds` |
| Files served without scan | 0% | `files_served_total{scan_status != clean}` (must be 0) |
| Right-to-erasure within 24h | 100% | `erasure_completed_total{on_time=true} / erasure_total` |
| Retention compliance | 100% | `files_retained_past_class / files_total` (must be 0) |
| Storage cost per GB | trending down | finance dashboard |
| Infected file rate | < 0.1% | `files_scanned_total{result=infected} / files_scanned_total` |
| Driver error rate (per driver) | < 0.5% | `storage_driver_errors_total / storage_driver_requests_total` (per `driver_id`) |
| Migration integrity | 100% | `file_migrations_failed_total{reason=verify}` must be 0 after auto-recovery |
| Public API driver-agnosticism | 100% | integration tests assert identical response shape regardless of driver |
| Local CI without cloud objects | 100% | `local_fs` driver end-to-end green; no S3 / Azure / OCI / GCS credentials needed |

## 16. Acceptance Criteria

- All 25 business requirements (BR--010..BR--023,
  BR--030..BR--038) implemented and verified by automated
  tests.
- A simulated infected file in staging is quarantined
  (no signed URL issued) on every driver.
- A right-to-erasure request in staging results in the
  file being purged from its driver within 1h.
- A retention purge in staging removes files past their
  class retention within 24h on every driver.
- A KYC file uploaded in staging against the `s3`,
  `azure_blob`, `oracle_object_storage`, and `gcs`
  drivers is encrypted with a per-tenant KMS key (verified
  by driver-side metadata).
- A signed URL request in staging returns within 200ms
  (cache hit ≤ 50ms) on every driver.
- An end-to-end migration from `s3` to `azure_blob` (and
  back) preserves `sha256` and emits `file.migrated.v1`
  with the correct `from_driver_id` and `to_driver_id`.
- The same public API contract returns identical JSON
  shapes when the underlying driver is `s3`,
  `azure_blob`, `oracle_object_storage`, `gcs`, or
  `local_fs` (verified by an integration matrix).
- `docker compose --profile file up` succeeds offline
  with only the `local_fs` driver; no cloud credentials
  required.

---

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

