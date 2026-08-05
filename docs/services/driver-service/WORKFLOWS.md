# driver-service — Workflows

## 1. Driver Onboarding (KYC)

### 1.1 Objective

Onboard a new driver: create a `drivers` row in
`pending_review`, accept document uploads, and route
to an admin for review. The driver can be `approved`
or `rejected`.

### 1.2 Initiating Actor

A driver (Keycloak user) registers and starts
onboarding. `identity-service` emits
`identity.user.created.v1`.

### 1.3 Participating Services

- `identity-service` (producer).
- `driver-service` (this service).
- `file-service` (KYC document storage).
- KYC provider (document verification).
- Background-check provider.
- `admin-service` (admin review).
- `notification-service` (driver notifications).
- `audit-service`.

### 1.4 Prerequisites

- `identity.user.created.v1` has been emitted.
- The driver has a Keycloak account.

### 1.5 Happy Path

```mermaid
sequenceDiagram
    participant D as Driver
    participant ID as identity-service
    participant DSV as driver-service
    participant FS as file-service
    participant KYC as KYC provider
    participant BC as Background-check
    participant ADM as admin-service
    participant NOT as notification-service
    participant T as Kafka

    ID->>T: produce identity.user.created.v1
    T->>DSV: deliver
    DSV->>DSV: upsert drivers row, status=pending_review
    DSV-->>T: produce driver.created.v1
    D->>FS: upload license
    FS-->>D: { file_id }
    D->>DSV: POST /v1/drivers/{id}/documents { type: license, file_id, expiry_date }
    DSV->>KYC: submit document
    KYC-->>DSV: { verification_id, status: processing }
    DSV-->>D: 201 Created
    Note over KYC: async verification
    KYC-->>DSV: webhook: verification.completed { verification_id, status: verified }
    DSV->>DSV: update document status=verified
    Note over DSV: similar for vehicle_reg, insurance, selfie, background_check
    D->>DSV: POST /v1/drivers/{id}/documents (background_check)
    DSV->>BC: submit
    BC-->>DSV: { verification_id, status: clear }
    DSV->>DSV: update document status=verified
    ADM->>DSV: POST /v1/drivers/{id}/approve { note: all verified }
    DSV->>DSV: status=approved
    DSV-->>T: produce driver.approved.v1
    T->>NOT: consume -> notify driver
```

### 1.6 Alternate Paths

- **Driver rejection**: admin reviews and finds an
  issue; the admin calls
  `POST /v1/drivers/{id}/reject` with a reason.
  `driver.rejected.v1` is emitted.
- **Re-submission**: a rejected driver can
  re-submit; `status` returns to `pending_review`.
- **Pending review expiry**: after 30 days in
  `pending_review`, the driver is auto-`expired`
  (no event; the row is marked `expired` and the
  driver must start over).

### 1.7 Failure Paths

- **KYC provider unreachable**: the service
  degrades to admin-override; a ticket is opened.
- **Document upload fails**: 502
  `DEPENDENCY_UPSTREAM_FAILURE`; the driver
  retries.
- **Background-check fails**: the document is
  marked `rejected`; the driver is told to
  contact support.

### 1.8 Business Rules

- A driver cannot be `approved` until all
  required documents are uploaded AND verified.
- A driver cannot be `approved` without a
  primary vehicle (the ``driver-service` (vehicles)` flow).
- The `pending_review` state has a 30-day TTL;
  after that, the driver is auto-`expired`.

### 1.9 State Transitions

```mermaid
stateDiagram-v2
    [*] --> PendingReview: driver.created.v1
    PendingReview --> Approved: admin approves
    PendingReview --> Rejected: admin rejects
    PendingReview --> Expired: 30 days no decision
    Rejected --> PendingReview: re-submit
    Approved --> Suspended: admin suspends
    Approved --> Inactive: long offline
    Suspended --> Approved: admin reinstates
    Inactive --> Approved: re-onboard
    Approved --> Disabled: admin disables
    Approved --> Erased: GDPR erasure
    Erased --> [*]
```

### 1.10 Events

| Event | Direction | When |
|-------|-----------|------|
| `driver.created.v1` | produced | on creation |
| `driver.approved.v1` | produced | on approval |
| `driver.rejected.v1` | produced | on rejection |
| `identity.user.created.v1` | consumed | to create the row |

### 1.11 APIs Involved

| API | Direction | When |
|-----|-----------|------|
| `POST /v1/drivers` | inbound | on creation |
| `POST /v1/drivers/{id}/documents` | inbound | per document |
| KYC provider | outbound | per document |
| `POST /v1/drivers/{id}/approve` | inbound | on approval |
| `POST /v1/drivers/{id}/reject` | inbound | on rejection |
| Kafka publish | outbound (outbox) | per state change |

### 1.12 Compensation / Rollback

A rejection can be followed by re-submission (status
back to `pending_review`). An approval can be
followed by a suspension (status to `suspended`).

### 1.13 Final State

- The `drivers` row is `approved` (or `rejected`).
- All required documents are `verified`.
- `driver.approved.v1` (or `driver.rejected.v1`) is
  on the topic.

## 2. Driver Approval

### 2.1 Objective

An admin reviews the driver's documents and approves
the driver. The driver can now go online and accept
rides.

### 2.2 Initiating Actor

`admin-service` calls
`POST /v1/drivers/{driver_id}/approve` on behalf of
an admin.

### 2.3 Participating Services

- `admin-service` (caller).
- `driver-service` (this service).
- ``driver-service` (availability)`, ``driver-service` (dispatch)`,
  `notification-service`, `audit-service`
  (consumers of `driver.approved.v1`).

### 2.4 Prerequisites

- The `drivers` row exists with `status='pending_review'`.
- All required documents are `verified`.
- The driver has a `primary_vehicle_id`.

### 2.5 Happy Path

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant DSV as driver-service
    participant DB as PostgreSQL (driver)
    participant OB as Outbox
    participant T as Kafka (driver.approved)
    participant DAS as `driver-service` (availability)
    participant DSP as `driver-service` (dispatch)
    participant NOT as notification-service

    ADM->>DSV: POST /v1/drivers/{id}/approve { note }
    DSV->>DB: BEGIN; UPDATE drivers SET status='approved', row_version=row_version+1; INSERT INTO driver_audit_log; INSERT INTO outbox; COMMIT
    DSV-->>ADM: 200 OK
    OB->>T: produce driver.approved.v1
    T->>DAS: consume -> driver is now allowed to go online
    T->>DSP: consume -> driver is in the dispatch pool
    T->>NOT: consume -> notify driver
```

### 2.6 Alternate Paths

- **Re-approval after suspension**: a suspended
  driver is re-instated via
  `POST /v1/drivers/{id}/reinstate`; status
  returns to `approved`.

### 2.7 Failure Paths

- **Missing documents**: 422
  `KYC_DOCUMENTS_REQUIRED`.
- **No primary vehicle**: 422
  `PRIMARY_VEHICLE_REQUIRED`.
- **Already approved**: 409 `CONFLICT`.

### 2.8 Business Rules

- All required documents MUST be `verified`.
- A `primary_vehicle_id` MUST be set.
- The state change MUST propagate to
  ``driver-service` (availability)` and
  ``driver-service` (dispatch)` within 10 s (P99).

### 2.9 State Transitions

```mermaid
stateDiagram-v2
    PendingReview --> Approved: POST /approve
    Suspended --> Approved: POST /reinstate
```

### 2.10 Events

| Event | Direction | When |
|-------|-----------|------|
| `driver.approved.v1` | produced | on approval |

### 2.11 APIs Involved

| API | Direction | When |
|-----|-----------|------|
| `POST /v1/drivers/{id}/approve` | inbound | per approval |
| Kafka publish | outbound (outbox) | per approval |

### 2.12 Compensation / Rollback

A suspension reverts the driver's state to
`suspended`. There is no compensation at the service
level.

### 2.13 Final State

- The `drivers.status` is `approved`.
- `driver.approved.v1` is on the topic.
- The driver can go online and accept rides.

## 3. Document Expiry

### 3.1 Objective

A driver's document (license, insurance, etc.) is
nearing expiry. The service emits warnings 30, 7, 1
day before; if the document expires and is not
replaced within the grace period, the driver is
auto-suspended.

### 3.2 Initiating Actor

A nightly job in `driver-service` scans
`driver_documents` for upcoming expiries and
past-expiry entries past the grace period.

### 3.3 Participating Services

- `driver-service` (this service; nightly job).
- `notification-service` (warnings).
- ``driver-service` (availability)`, ``driver-service` (dispatch)`
  (auto-suspend).

### 3.4 Prerequisites

- The `driver_documents` rows have `expiry_date`
  populated.

### 3.5 Happy Path (Warning)

```mermaid
sequenceDiagram
    participant JOB as Nightly job
    participant DB as PostgreSQL (driver)
    participant OB as Outbox
    participant T as Kafka (driver.document.expiring)
    participant NOT as notification-service
    participant D as Driver

    JOB->>DB: SELECT * FROM driver_documents WHERE status='verified' AND expiry_date BETWEEN now() AND now() + interval '30 days' AND deleted_at IS NULL
    loop for each document
        alt days_remaining in [30, 7, 1]
            JOB->>DB: BEGIN; INSERT INTO driver_audit_log; INSERT INTO outbox (driver.document.expiring.v1, days_remaining=...); COMMIT
            OB->>T: produce driver.document.expiring.v1
            T->>NOT: consume -> notify driver
            NOT-->>D: SMS/push: "Your license expires in 7 days"
        end
    end
```

### 3.6 Auto-Suspend

```mermaid
sequenceDiagram
    participant JOB as Nightly job
    participant DB as PostgreSQL (driver)
    participant OB as Outbox
    participant T1 as Kafka (driver.document.expired)
    participant T2 as Kafka (driver.suspended)
    participant DAS as `driver-service` (availability)
    participant DSP as `driver-service` (dispatch)
    participant NOT as notification-service

    JOB->>DB: SELECT * FROM driver_documents WHERE status='verified' AND expiry_date < now() - interval '7 days' AND critical=true AND deleted_at IS NULL
    loop for each document
        JOB->>DB: BEGIN; UPDATE driver_documents SET status='expired'; UPDATE drivers SET status='suspended', suspended_reason='document_expired', suspended_at=now(), suspended_by=system; INSERT INTO driver_audit_log; INSERT INTO outbox (driver.document.expired.v1); INSERT INTO outbox (driver.suspended.v1); COMMIT
        OB->>T1: produce driver.document.expired.v1
        OB->>T2: produce driver.suspended.v1
        T1->>DAS: consume -> take offline
        T2->>DAS: consume -> take offline
        T1->>NOT: consume -> notify driver
    end
```

### 3.7 Alternate Paths

- **Driver replaces document before expiry**: the
  new document is `verified`; the old one is
  soft-deleted; the warnings are no longer
  emitted.
- **Document expired but in grace period**: the
  driver is `approved` with `documents_warn=true`;
  the platform shows a banner; the grace period
  counts down.

### 3.8 Business Rules

- Warning windows are 30, 7, 1 day.
- The grace period is `expiry_grace_days` (default
  7).
- Only `critical` documents trigger auto-suspend.
- The auto-suspend MUST happen within the grace
  period (100% target).

### 3.9 State Transitions

```mermaid
stateDiagram-v2
    Approved --> Approved: warning (days_remaining in [30,7,1])
    Approved --> Suspended: auto-suspend (expired + grace)
    Suspended --> Approved: reinstate after document replace
```

### 3.10 Events

| Event | Direction | When |
|-------|-----------|------|
| `driver.document.expiring.v1` | produced | 30/7/1 day before expiry |
| `driver.document.expired.v1` | produced | past expiry + grace |
| `driver.suspended.v1` | produced | on auto-suspend |

### 3.11 APIs Involved

None (internal job).

### 3.12 Compensation / Rollback

A driver can replace the expired document and the
admin (or the system, on verification) reinstates
the driver.

### 3.13 Final State

- The expired document is `status='expired'`.
- The driver is `suspended` (auto-suspend path).
- The events are on the topic.

## 4. Driver Suspension

### 4.1 Objective

Suspend a driver (admin action); block them from
accepting rides; propagate `driver.suspended.v1` to
every dependent service within 10 seconds (P99).

### 4.2 Initiating Actor

`admin-service` calls
`POST /v1/drivers/{driver_id}/suspend` on behalf of
an admin, fraud-reviewer, or the auto-suspend job
(actor_type=system).

### 4.3 Participating Services

- `admin-service` (caller) or auto-suspend job.
- `driver-service` (this service).
- Kafka (`driver.suspended.v1`).
- ``driver-service` (availability)`, ``driver-service` (dispatch)`,
  ``trip-service` (ride-request)`, `notification-service`,
  `fraud-risk-service`, `audit-service`
  (consumers).

### 4.4 Prerequisites

- The `drivers` row exists.
- The admin has the `driver.admin` realm role.

### 4.5 Happy Path

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant DSV as driver-service
    participant DB as PostgreSQL (driver)
    participant OB as Outbox
    participant T as Kafka (driver.suspended)
    participant DAS as `driver-service` (availability)
    participant DSP as `driver-service` (dispatch)
    participant RRS as `trip-service` (ride-request)
    participant NOT as notification-service

    ADM->>DSV: POST /v1/drivers/{id}/suspend { reason: "fraud" }
    DSV->>DB: BEGIN; UPDATE drivers SET status='suspended', suspended_reason=..., suspended_at=now(), suspended_by=actor; INSERT INTO driver_audit_log; INSERT INTO outbox; COMMIT
    DSV-->>ADM: 200 OK
    OB->>T: produce driver.suspended.v1
    T->>DAS: consume -> take offline
    T->>DSP: consume -> remove from dispatch pool
    T->>RRS: consume -> block ride requests to this driver
    T->>NOT: consume -> notify driver
```

### 4.6 Alternate Paths

- **Auto-suspend on document expiry**: the nightly
  job calls the same path with
  `actor_type=system`, `reason='document_expired'`.
- **Auto-suspend on insurance expiry**: the
  `vehicle.insurance.expired.v1` consumer triggers
  the same path.

### 4.7 Failure Paths

- **DB write fails**: the action is not performed;
  the admin retries.
- **Outbox publish fails**: the poller retries; the
  event is eventually emitted. The propagation lag
  may exceed 10 s during the failure window.

### 4.8 Business Rules

- A suspension reason MUST be in the allowed set
  (e.g. `fraud`, `quality`, `document_expired`,
  `insurance_expired`, `manual_review`).
- The suspension MUST be propagated to dependent
  services within 10 s (P99).

### 4.9 State Transitions

As in §1.9.

### 4.10 Events

| Event | Direction | When |
|-------|-----------|------|
| `driver.suspended.v1` | produced | on suspension |
| `driver.reinstated.v1` | produced | on re-instatement |
| `driver.disabled.v1` | produced | on disablement |

### 4.11 APIs Involved

| API | Direction | When |
|-----|-----------|------|
| `POST /v1/drivers/{id}/suspend` | inbound | per suspension |
| Kafka publish | outbound (outbox) | per suspension |

### 4.12 Compensation / Rollback

A re-instatement (`POST /v1/drivers/{id}/reinstate`)
reverts the action; `driver.reinstated.v1` is
emitted; the dependent services clear the
suspension flag.

### 4.13 Final State

- The `drivers.status` is `suspended`.
- The `driver_audit_log` has the suspension entry.
- `driver.suspended.v1` is on the topic.
- The dependent services have marked the driver
  as suspended.

## 5. City-Level Eligibility

### 5.1 Objective

A driver requests eligibility in a city; the admin
(or auto-approval in trusted cities) grants it; the
driver can be matched to rides in that city.

### 5.2 Initiating Actor

A driver (or admin) calls
`POST /v1/drivers/{driver_id}/eligibility/cities/{city_id}`.

### 5.3 Participating Services

- `driver-service` (this service).
- ``geolocation-service` (zones)` (city validation).
- ``driver-service` (dispatch)` (consumer; uses eligibility
  for matching).

### 5.4 Prerequisites

- The driver is `approved`.
- The city is in ``geolocation-service` (zones)` and serves rides.

### 5.5 Happy Path

```mermaid
sequenceDiagram
    participant D as Driver
    participant DSV as driver-service
    participant ZN as `geolocation-service` (zones)
    participant DB as PostgreSQL (driver)
    participant OB as Outbox
    participant T as Kafka (driver.eligibility.changed)
    participant DSP as `driver-service` (dispatch)

    D->>DSV: POST /v1/drivers/{id}/eligibility/cities/{city_id}
    DSV->>ZN: GET /v1/cities/{id}
    ZN-->>DSV: { name, country, status: active }
    DSV->>DB: BEGIN; INSERT INTO driver_city_eligibility (driver_id, city_id, status='pending_review'); INSERT INTO driver_audit_log; INSERT INTO outbox; COMMIT
    Note over DSV: admin reviews the city
    ADM->>DSV: POST /v1/drivers/{id}/eligibility/cities/{city_id} (grant, status=eligible)
    DSV->>DB: UPDATE driver_city_eligibility SET status='eligible', granted_at=now()
    OB->>T: produce driver.eligibility.changed.v1
    T->>DSP: consume -> add to dispatch pool for the city
```

### 5.6 Alternate Paths

- **Auto-approval in trusted cities**: the service
  marks the eligibility `eligible` immediately
  (configurable per city).
- **Admin revocation**: an admin revokes
  eligibility (e.g. due to local incident); the
  row is updated to `ineligible` with a reason.
- **Auto-revocation on rating drop**: if the
  driver's rating in a city drops below the
  per-city `min_rating`, eligibility is
  auto-revoked.

### 5.7 Failure Paths

- **City not in ``geolocation-service` (zones)`**: 404
  `CITY_NOT_FOUND`.
- **Driver not approved**: 422
  `DRIVER_NOT_APPROVED`.
- **DB write fails**: the action is not performed;
  the driver retries.

### 5.8 Business Rules

- A driver can be eligible in multiple cities.
- The `min_rating` is per-city (or global default).
- The eligibility change MUST propagate to
  ``driver-service` (dispatch)` within 10 s (P99).

### 5.9 State Transitions

```mermaid
stateDiagram-v2
    [*] --> PendingReview: POST request
    PendingReview --> Eligible: grant
    PendingReview --> Ineligible: reject
    Eligible --> Ineligible: revoke
    Ineligible --> PendingReview: re-request
```

### 5.10 Events

| Event | Direction | When |
|-------|-----------|------|
| `driver.eligibility.changed.v1` | produced | on eligibility change |

### 5.11 APIs Involved

| API | Direction | When |
|-----|-----------|------|
| `POST /v1/drivers/{id}/eligibility/cities/{city_id}` | inbound | per change |
| `GET /v1/cities/{id}` (`geolocation-service` (zones)) | outbound | on validation |
| Kafka publish | outbound (outbox) | per change |

### 5.12 Compensation / Rollback

A revocation reverts the eligibility; the driver
can re-request.

### 5.13 Final State

- The `driver_city_eligibility` row is updated.
- `driver.eligibility.changed.v1` is on the topic.
- ``driver-service` (dispatch)` has the new eligibility.

## 6. GDPR Right-to-Erasure

### 6.1 Objective

Anonymize the `drivers` row and the cached claims;
emit `driver.erased.v1`; preserve the `driver_id`
and `identity_id` for referential integrity
(financial records in ``payment-service` (driver earnings)`,
`ledger-service`, `payment-service` retain the
`driver_id` reference but their PII fields are
redacted by the owning service).

### 6.2 Initiating Actor

`admin-service` calls
`POST /v1/drivers/{driver_id}/erase` on behalf of
a compliance officer or a user self-service flow.

### 6.3 Participating Services

- `admin-service` (caller).
- `driver-service` (this service).
- Kafka (`driver.erased.v1`).
- `audit-service`, ``reporting-service` (data lake)`, every
  service that owns a profile (consumers).

### 6.4 Prerequisites

- The `drivers` row exists.
- The compliance officer has `driver.admin` or
  `super_admin` realm role.

### 6.5 Happy Path

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant DSV as driver-service
    participant DB as PostgreSQL (driver)
    participant OB as Outbox
    participant T as Kafka (driver.erased)
    participant AUD as audit-service
    participant DE as `payment-service` (driver earnings)
    participant LD as ledger-service

    ADM->>DSV: POST /v1/drivers/{id}/erase { legal_basis: "user_request" }
    DSV->>DB: BEGIN; UPDATE drivers SET name='REDACTED', email='REDACTED', phone='REDACTED', primary_vehicle_id=NULL, status='erased', erased_at=now(), deleted_at=now(), rating=0, rating_count=0; UPDATE driver_documents SET file_id=NULL, status='erased', deleted_at=now(); UPDATE driver_city_eligibility SET revoked_at=now(), revoked_by=actor, revoked_reason='erasure'; INSERT INTO driver_audit_log; INSERT INTO outbox; COMMIT
    DSV-->>ADM: 200 OK { status: "erased", warnings: [] }
    OB->>T: produce driver.erased.v1
    T->>AUD: consume
    T->>DE: consume -> earnings retains driver_id, redacts PII
    T->>LD: consume -> ledger retains driver_id, redacts PII
```

### 6.6 Alternate Paths

- **Erasure with active financial records**: the
  service performs the erasure but populates
  `warnings[]` in the response (e.g.
  "active_ledger_entries: 12"). The owning
  services retain the `driver_id` reference but
  redact PII.

### 6.7 Failure Paths

- **DB write fails**: the action is not performed;
  the admin retries.
- **Outbox publish fails**: the poller retries; the
  event is eventually emitted. The dependent
  services eventually anonymize their PII; the
  reconciliation job in `reporting-service`
  detects any drift and re-emits the erasure
  (idempotent).

### 6.8 Business Rules

- The `driver_id` and `identity_id` are preserved.
- All PII columns are set to `REDACTED` / NULL.
- The `status` is set to `erased`.
- The `deleted_at` is set; the row is a tombstone.
- Documents and city eligibility are cleared.
- `driver.erased.v1` is emitted exactly once
  (idempotency on `Idempotency-Key`).
- The audit log retains the erasure entry
  indefinitely (legal hold).

### 6.9 State Transitions

```mermaid
stateDiagram-v2
    [*] --> PendingReview
    PendingReview --> Erased: POST /erase
    Approved --> Erased: POST /erase
    Suspended --> Erased: POST /erase
    Inactive --> Erased: POST /erase
    Disabled --> Erased: POST /erase
    Rejected --> Erased: POST /erase
    Erased --> [*]
    Erased -.->|re-activation NOT allowed| Erased
```

### 6.10 Events

| Event | Direction | When |
|-------|-----------|------|
| `driver.erased.v1` | produced | on erasure |

### 6.11 APIs Involved

| API | Direction | When |
|-----|-----------|------|
| `POST /v1/drivers/{id}/erase` | inbound | per erasure |
| Kafka publish | outbound (outbox) | per erasure |

### 6.12 Compensation / Rollback

None. Erasure is irreversible.

### 6.13 Final State

- The `drivers` row is a tombstone with PII
  redacted.
- `driver_documents` rows are erased.
- `driver_city_eligibility` rows are revoked.
- `driver.erased.v1` is on the topic.
- The dependent services have anonymized their PII
  but retain the `driver_id` reference.
- The audit log has the erasure entry.

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

