# file-service — Integration Contract

> All byte I/O passes through the **Storage Driver**
> layer (`internal/storage/drivers/<id>/`). The public
> API is **driver-agnostic**: the response shapes below
> are identical regardless of whether the underlying
> driver is `s3` (AWS S3 / MinIO / Ceph RGW / Wasabi /
> R2), `azure_blob`, `oracle_object_storage`, `gcs`, or
> `local_fs`. The `driver_id` is included in every
> response so callers can introspect, but the URL is
> opaque to them.

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md`.

### 1.1 `POST /v1/files`

- **Purpose**: Initiate a file upload. The service
  resolves which Storage Driver will hold the bytes using
  the documented precedence (per-file pin → per-tenant →
  per-owner-type → per-retention-class → env default) and
  calls that driver's `InitiateUpload` (or accepts the
  body for the proxy flow).
- **Auth**: Bearer JWT; the user owns the file; service
  can upload on behalf of users.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "name": "avatar.jpg",
    "mime_type": "image/jpeg",
    "size_bytes": 102400,
    "sha256": "...",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "owner_type": "customer",
    "retention_class": "avatar",
    "metadata": { "width": 1024, "height": 1024 }
  }
  ```
- **Response (201)** — identical shape regardless of
  driver; `upload_url` is **opaque** to the caller (it
  may be an S3 v4 presigned URL, an Azure SAS URL, an
  OCI pre-authenticated request, a GCS v4 signed URL,
  or a `local_fs` signed-redirect). `driver_id` tells the
  caller which driver handled it.
  - For small files (≤ `file.scan.sync_max_size_bytes`):
    ```json
    {
      "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
      "upload_method": "proxy",
      "upload_url": null,
      "driver_id": "s3",
      "status": "scanning",
      "retention_until": "2026-08-29T10:42:11.183Z"
    }
    ```
    The client then uploads the bytes to
    `POST /v1/files/{file_id}/upload` (multipart).
  - For large files (> `file.scan.sync_max_size_bytes`):
    ```json
    {
      "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
      "upload_method": "direct",
      "upload_url": "https://...opaque-signed-url...",
      "driver_id": "azure_blob",
      "status": "pending",
      "expires_at": "2026-07-29T11:00:00.000Z"
    }
    ```
    The client uploads directly to the resolved driver;
    then calls `POST /v1/files/{file_id}/complete` to
    trigger the scan.
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 / 403
  - 422 `MIME_TYPE_NOT_ALLOWED` / `FILE_TOO_LARGE`
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 503 `DRIVER_UNAVAILABLE` (the resolved driver is
    `disabled`, drained, or its circuit is open and no
    other driver is eligible).

### 1.2 `POST /v1/files/{id}/upload` (proxy upload)

- **Purpose**: Upload the file bytes (for the proxy flow).
  Internally streams the body to the resolved driver.
- **Auth**: Bearer JWT; the file must be owned by the
  caller.
- **Request**: `multipart/form-data` with the file body.
- **Response (200)**: file shape, `status=scanning` (the
  scan is triggered automatically).
- **Errors**: 403 / 404 / 409 `FILE_NOT_AVAILABLE` (file is
  in the wrong state) / 503 `DRIVER_UNAVAILABLE`.

### 1.3 `POST /v1/files/{id}/complete`

- **Purpose**: Notify that a direct-to-driver upload is
  done (triggers the virus scan). The service calls
  `storage.driver.HeadObject` / equivalent on the
  resolved driver to verify the object's presence +
  size + ETag, then triggers the scan.
- **Auth**: Bearer JWT; the file must be owned by the
  caller.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "sha256": "..."
  }
  ```
- **Response (200)**: file shape, `status=scanning`,
  carries `driver_id`.
- **Errors**: 400 / 403 / 404 / 409 / 422 / 503
  `DRIVER_UNAVAILABLE`.

### 1.4 `GET /v1/files/{id}`

- **Purpose**: Read file metadata (not the bytes).
- **Auth**: Bearer JWT; the file must be owned by the
  caller (or admin / service).
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "avatar.jpg",
    "mime_type": "image/jpeg",
    "size_bytes": 102400,
    "sha256": "...",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "owner_type": "customer",
    "retention_class": "avatar",
    "status": "available",
    "scan_result": "clean",
    "driver_id": "s3",
    "driver_kind": "s3",
    "retention_until": "2026-08-29T10:42:11.183Z",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 403 / 404.

### 1.5 `POST /v1/files/{id}/signed-url`

- **Purpose**: Issue a time-bound signed URL using the
  resolved driver's native signing primitive
  (`S3.PresignGetObject`, `azblob.GetSASURL`,
  `objectstorage.CreatePreauthenticatedRequest`,
  `gcs.SignedURL`, or a short-lived reverse-proxy URL
  for `local_fs`).
- **Auth**: Bearer JWT; the file must be owned by the
  caller (or admin / service).
- **Request**:
  ```json
  {
    "ttl_seconds": 900,
    "purpose": "download"
  }
  ```
- **Response (200)**:
  ```json
  {
    "url": "https://...opaque-signed-url...",
    "driver_id": "azure_blob",
    "expires_at": "2026-07-29T11:00:00.000Z"
  }
  ```
- **Errors**: 403 / 404 / 409 `FILE_NOT_AVAILABLE` (file
  in `pending` or `quarantined`) / 503 `DRIVER_UNAVAILABLE`.

### 1.6 `GET /v1/files/{id}/download`

- **Purpose**: Download the file (proxy for small files —
  bytes streamed through the service via the active
  driver's `GetObject`; redirect to a driver-signed URL
  for large).
- **Auth**: Bearer JWT; ownership check.
- **Response (200)**: file bytes (or 302 redirect to a
  driver-signed URL).
- **Errors**: 403 / 404 / 409 / 503 `DRIVER_UNAVAILABLE`.

### 1.7 `DELETE /v1/files/{id}`

- **Purpose**: Soft delete a file. Hard delete (later) is
  issued against the file's `driver_id`.
- **Auth**: Bearer JWT; ownership check.
- **Idempotency**: required.
- **Response (204)**: no content.
- **Errors**: 403 / 404 / 409 `LEGAL_HOLD_ACTIVE`.

### 1.8 `GET /v1/files/{id}/scan`

- **Purpose**: Read the scan result.
- **Auth**: Bearer JWT; ownership check.
- **Response (200)**:
  ```json
  {
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "status": "clean",
    "result": "clean",
    "threat_name": null,
    "started_at": "2026-07-29T10:42:11.500Z",
    "completed_at": "2026-07-29T10:42:12.100Z",
    "provider": "clamav"
  }
  ```
- **Errors**: 403 / 404.

### 1.9 `GET /v1/files/{id}/driver`

- **Purpose**: Inspect the file's driver assignment +
  the opaque driver locator (for ops, support, and
  migrations).
- **Auth**: Bearer JWT with `file.read` scope (admin /
  service allowed; end users only on files they own).
- **Response (200)**:
  ```json
  {
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "driver_id": "oracle_object_storage",
    "driver_kind": "oracle_object_storage",
    "assignment_source": "tenant_override",
    "driver_locator": {
      "namespace": "idcz5...", "bucket": "prod-eu1-kyc", "object": "..."
    },
    "driver_locale_version": 3,
    "kms_key_id": "ocid1.key.oc1.eu-frankfurt-1..."
  }
  ```
  The exact shape of `driver_locator` is **per driver**
  and the service does not interpret it. Clients MUST
  treat it as opaque.
- **Errors**: 403 / 404.

### 1.10 `GET /v1/admin/drivers`

- **Purpose**: List configured drivers, their state, and
  health (synthetic probe result).
- **Auth**: Bearer JWT + role `admin` or `ops`.
- **Response (200)**:
  ```json
  {
    "drivers": [
      {
        "id": "s3-prod-eu1-kyc",
        "kind": "s3",
        "state": "enabled",
        "priority": 10,
        "is_default": true,
        "health": "healthy",
        "health_last_checked_at": "2026-07-29T10:42:11Z",
        "region": "eu-west-1",
        "container": "prod-eu1-kyc",
        "signed_url_ttl_seconds": 900
      },
      {
        "id": "azure-prod-westeu-kyc",
        "kind": "azure_blob",
        "state": "draining",
        "priority": 20,
        "is_default": false,
        "health": "healthy",
        "container": "prodwesteukyc"
      }
    ]
  }
  ```

### 1.11 `POST /v1/admin/drivers/{id}/pin`

- **Purpose**: Pin a file (or all files of an owner) to a
  specific Storage Driver. Subsequent reads / deletes use
  that driver. Recorded as a `driver_history` row of
  `change_type = 'pin'`.
- **Auth**: Bearer JWT + role `admin` or `ops`; HMAC.
- **Request**:
  ```json
  {
    "scope": "file",
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "driver_id": "azure_blob",
    "reason": "EU data residency"
  }
  ```
  For owner-wide pinning:
  ```json
  {
    "scope": "owner",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "owner_type": "customer",
    "driver_id": "azure_blob",
    "reason": "EU data residency"
  }
  ```
- **Response (200)**:
  ```json
  {
    "assignment_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE",
    "files_affected": 1,
    "driver_id": "azure_blob",
    "scope": "file"
  }
  ```
- **Errors**: 403 / 404 / 409 `DRIVER_NOT_CONFIGURED` /
  409 `DRIVER_DRAINED` (cannot pin to a draining driver
  if scope is single file and bytes must move).

### 1.12 `POST /v1/admin/migrations`

- **Purpose**: Enqueue a single-file or bulk migration
  of files from one driver to another.
- **Auth**: Bearer JWT + role `admin` or `ops`; HMAC.
- **Request (single file)**:
  ```json
  {
    "mode": "single",
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "to_driver_id": "azure_blob",
    "reason": "EU data residency",
    "verify_sha256": true
  }
  ```
- **Request (bulk)**:
  ```json
  {
    "mode": "bulk",
    "filter": {
      "owner_type": "customer",
      "tenant_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "retention_class": "kyc"
    },
    "from_driver_id": "s3",
    "to_driver_id": "azure_blob",
    "max_objects": 100000,
    "reason": "EU data residency",
    "verify_sha256": true,
    "dual_write_window_days": 7
  }
  ```
- **Response (202)**:
  ```json
  {
    "migration_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PF",
    "files_estimated": 8742,
    "bytes_estimated": 1731248901,
    "queue_position": 2
  }
  ```
- **Errors**: 403 / 404 / 409 `DRIVER_NOT_CONFIGURED` /
  422 `DRIVER_DRAINED` (cannot migrate into a draining
  driver).

### 1.13 `GET /v1/admin/migrations/{id}`

- **Purpose**: Read a migration's status.
- **Auth**: Bearer JWT + role `admin` or `ops`.
- **Response (200)**:
  ```json
  {
    "migration_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PF",
    "from_driver_id": "s3",
    "to_driver_id": "azure_blob",
    "state": "running",
    "files_completed": 3120,
    "files_pending": 5622,
    "files_failed": 0,
    "files_verify_failed": 0,
    "bytes_transferred": 612847123,
    "started_at": "2026-07-29T10:42:11Z",
    "last_progress_at": "2026-07-29T11:48:21Z"
  }
  ```

### 1.14 `POST /v1/admin/retention/run`

- **Purpose**: Manually trigger the retention job (for
  testing or recovery). Hard-deletes are routed through
  each file's resolved driver.
- **Auth**: Bearer JWT + role `admin` or `ops`; HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "retention_class": "support_attachment",
    "dry_run": false
  }
  ```
- **Response (200)**:
  ```json
  {
    "job_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "files_soft_deleted": 1234,
    "files_hard_deleted": 1100,
    "files_skipped_legal_hold": 5,
    "per_driver": {
      "s3": { "hard_deleted": 700 },
      "azure_blob": { "hard_deleted": 400 }
    },
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

## 2. Outbound APIs

All byte I/O is brokered by the **Storage Driver**
interface; the matrix below lists the **logical**
operations the service needs and the per-driver
implementation that fulfils them.

### 2.1 Logical operations on the Storage Driver interface

| Operation | Direction | Caller | Used by |
|-----------|-----------|--------|---------|
| `InitiateUpload` | outbound | service | `POST /v1/files` (direct flow) |
| `CompleteUpload` | outbound | service | `POST /v1/files/{id}/complete` |
| `GetObject` | outbound | service | proxy download (`GET /v1/files/{id}/download`) |
| `PutObject` | outbound | service | proxy upload (`POST /v1/files/{id}/upload`); migration |
| `DeleteObject` | outbound | service | hard delete (retention, erasure, migration cleanup) |
| `HeadObject` | outbound | service | `POST /v1/files/{id}/complete`; reconciliation |
| `CreateSignedURL` | outbound | service | `POST /v1/files/{id}/signed-url` |
| `Probe` | outbound | background | synthetic health flip |
| `Shutdown` | outbound | service | graceful shutdown |

### 2.2 Per-driver implementation surface

| Operation | `s3` (AWS S3 / MinIO / Ceph RGW / Wasabi / R2) | `azure_blob` | `oracle_object_storage` | `gcs` | `local_fs` |
|-----------|-----------------------------------------------|--------------|--------------------------|-------|------------|
| `InitiateUpload` | `s3.PresignPutObject` | `azblob.GetSASURL` (write) | `CreatePreauthenticatedRequest` | `SignedURL` (PUT) | mint a short-lived ticket URL → `/local-fs-proxy/upload?ticket=…` |
| `CompleteUpload` | `s3.HeadObject` | `GetProperties` | `HeadObject` | `ObjectHandle.Attrs` | stat the local path |
| `GetObject` | `s3.GetObject` | `DownloadStream` | `GetObject` | `Reader` | `os.Open` |
| `PutObject` | `PutObject` (multipart if size > `multipart_threshold_bytes`) | `UploadStream` (chunked if size > `multipart_threshold_bytes`) | `PutObject` (multipart if size > `multipart_threshold_bytes`) | `Writer` (chunked via `CompositeWriter` if size > `multipart_threshold_bytes`) | stream-write to a `*.tmp` file then `rename` |
| `DeleteObject` | `DeleteObject` | `DeleteBlob` | `DeleteObject` | `ObjectHandle.Delete` | `os.Remove` (after grace period) |
| `HeadObject` | `HeadObject` | `GetProperties` | `HeadObject` | `Attrs` | `os.Stat` |
| `CreateSignedURL` | `PresignGetObject` (S3v4) | `GetSASURL` (read) | `CreatePreauthenticatedRequest` (read) | `SignedURL` (GET) | mint a short-lived ticket URL → `/local-fs-proxy/stream?ticket=…` |
| `Probe` | `HeadBucket` | `GetServiceProperties` | `GetNamespace` | `GetBucket` | `statvfs` on the mount |
| `Shutdown` | drain SDK clients | drain SDK clients | drain SDK clients | drain SDK clients | close fd pools, fsync pending |

### 2.3 Hard guarantees (all drivers)

| | |
|---|---|
| **Timeout** | 30 s for I/O; 5 s for `Probe` |
| **Retry** | 2 attempts with exponential backoff; never on 4xx; never on signature errors |
| **Circuit breaker** | per-driver breaker; 3 consecutive 5xx/timeout in 30 s opens; half-open after 60 s |
| **Idempotency** | every operation is idempotent on `(driver_id, driver_locator)`; retries are safe |
| **Pooling** | per-driver connection pool, sized via environment defaults; isolation between drivers |

### 2.4 Other outbound calls (driver-agnostic)

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Virus scan provider | POST | `/scan` | scan a file | 5s | 2 | yes |
| `configuration-service` | GET | `/v1/config/file` | read retention policies + driver catalog | 500ms | 3 | yes |

All outbound calls (driver + non-driver) carry
`X-Correlation-Id` and `traceparent`.

## 3. Produced Events

### 3.1 `file.uploaded.v1`

- **Producer**: `file-service`.
- **Topic**: `file.file.uploaded`.
- **Trigger**: every successful upload + clean scan.
- **Partition key**: `owner_id`.
- **Schema (data)**:
  ```json
  {
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "owner_type": "customer",
    "name": "avatar.jpg",
    "mime_type": "image/jpeg",
    "size_bytes": 102400,
    "retention_class": "avatar",
    "sha256": "...",
    "scan_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PD",
    "scan_result": "clean",
    "driver_id": "s3",
    "driver_kind": "s3",
    "uploaded_at": "2026-07-29T10:42:11.500Z",
    "retention_until": "2026-08-29T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `file.file.uploaded.dlq`.
- **Consumers**: owner service, `audit-service`.

### 3.2 `file.scanned.v1`

- **Producer**: `file-service`.
- **Topic**: `file.file.scanned`.
- **Trigger**: every scan result (clean or infected).
- **Partition key**: `owner_id`.
- **Schema (data)**:
  ```json
  {
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "scan_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PD",
    "result": "clean",
    "threat_name": null,
    "provider": "clamav",
    "occurred_at": "2026-07-29T10:42:12.100Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: owner service, `support-service` (if
  infected).

### 3.3 `file.deleted.v1`

- **Producer**: `file-service`.
- **Topic**: `file.file.deleted`.
- **Trigger**: every soft delete (user, retention,
  admin) on any driver.
- **Partition key**: `owner_id`.
- **Schema (data)**:
  ```json
  {
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "reason": "user",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "driver_id": "azure_blob",
    "driver_kind": "azure_blob",
    "hard_delete_at": "2026-08-05T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Consumers**: `audit-service`.

### 3.4 `file.migrated.v1`

- **Producer**: `file-service` (migration worker).
- **Topic**: `file.file.migrated`.
- **Trigger**: every successful driver-to-driver
  migration (after SHA-256 verified and the canonical
  `driver_id` flipped).
- **Partition key**: `file_id`.
- **Schema (data)**:
  ```json
  {
    "file_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "owner_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "migration_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PF",
    "from_driver_id": "s3",
    "from_driver_kind": "s3",
    "to_driver_id": "azure_blob",
    "to_driver_kind": "azure_blob",
    "from_sha256": "...",
    "to_sha256": "...",
    "verified_sha256": true,
    "reason": "EU data residency",
    "actor_sub": "01HZX9C5G3V1L7K0P2F8V4T6DBX",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: outbox, 3 attempts; DLQ
  `file.file.migrated.dlq`.
- **Consumers**: `audit-service`, owner service (so the
  owner service can refresh a stale `file_id →
  driver_id` cache it may hold for image transformation
  or pre-signed URL generation).

## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: retention policies, allowed mime types, max
  upload size, scan provider, **Storage Driver catalog
  (new driver added, default driver changed, per-tenant /
  per-retention-class / per-owner-type override added or
  removed), per-driver `state` / `priority` / `kms_key_id`
  / `signed_url_ttl_seconds` / `max_object_size_bytes`**.
- **Handler**: reload config (idempotent; `config_hash`
  compared before swap, then write to
  `storage_drivers` / `retention_policies` table).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `customer.document.uploaded.v1`

- **Producer**: `customer-service`.
- **Reason**: A customer uploaded a KYC document.
- **Handler**: Mark ready for virus scan.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `driver.document.uploaded.v1`

- **Producer**: `driver-service`.
- **Reason**: A driver uploaded a KYC document.
- **Handler**: Mark ready for virus scan.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `file.scanned.v1`

- **Producer**: `internal`.
- **Reason**: Virus scan completed.
- **Handler**: Update file status.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**:
  - Driver I/O (per driver): 30 s.
  - Synthetic probe (per driver): 5 s.
  - Virus scan: 5 s.
  - `configuration-service`: 500 ms.
- **Retries**: 2 attempts with exponential backoff. Never
  on 4xx. Never on signature errors.
- **Circuit breakers**: one per driver (and one for virus
  scan). Open on ≥ 3 consecutive 5xx/timeout in 30 s;
  half-open after 60 s. Driver breakers are isolated —
  one failing driver does not take down the others.
- **Outbox / Inbox**: standard pattern.
- **DLQ**: every topic has a paired `<topic>.dlq`.
- **Reconciliation**:
  - A daily job verifies that every `available` file's
    canonical `driver_locator` resolves to an existing
    object on the file's `driver_id`; reports drift.
  - A per-driver nightly job verifies that every
    `draining` driver still has zero files with
    `driver_locator` pointing at it (or reports a list
    to be migrated); supports `auto_migrate_to`
    parameter.

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event.
  - The `headers.correlation_id` of every outbox row.
  - The `correlation_id` column of every file and access
    log row.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments HTTP, **driver
  call (custom span `storage.driver` with attributes
  `driver.id`, `driver.kind`, `driver.operation`,
  `object.key` (driver-opaque))**, virus scan (custom
  span), DB, Redis.
- One root span per upload; **driver call as child
  span**; virus scan as child span.
- Sample 100% of errors, 10% of successes in production;
  100% in staging.
- The inbound `traceparent` is honored.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-safety-service`](../ride-safety-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-safety-service`](../ride-safety-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`support-service`](../support-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`user-profile-service`](../user-profile-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` §1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in §1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

