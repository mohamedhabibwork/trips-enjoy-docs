# Time-Bounded SUPER_ADMIN Aliases

> **Added:** 2026-08-06
> **Memory anchor:** `trips-enjoy-super-admin-preset-management (2026-08-05)` —
> "20 active service-admin scopes plus time-bounded aliases; break-glass
> remains mandatory".
>
> **Canonical reference** for the time-bounded SUPER_ADMIN alias contract.
> Adopted per [ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) 3.5.4
> (`wf.service_request.time_bounded_alias.v1`).

## 1. Concept

A **time-bounded alias** is a `platform.super_admin` grant with an
**expiry timestamp**. It is functionally identical to a permanent
`SUPER_ADMIN` grant for the duration of its TTL, but is **auto-revoked**
when `expires_at` elapses (via the alias-revoke scheduled job).

The alias enables incident response, cross-team coverage, and
time-bounded operational tasks without granting permanent SUPER_ADMIN
membership. The break-glass flow (per
[`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.14)
remains mandatory: every alias grant requires a different
`platform.super_admin` co-signer, MFA, and the SUPER_ADMIN IP allowlist.

## 2. Schema

### 2.1 `super_admin_grant` table

A new column is appended to the existing `admin.super_admin_grant`
table:

```sql
ALTER TABLE admin.super_admin_grant
  ADD COLUMN expires_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN admin.super_admin_grant.expires_at IS
  'When the time-bounded alias expires (NULL = permanent grant). See docs/shared/TIME_BOUNDED_ALIASES.md for the alias lifecycle.';
```

- `expires_at IS NULL` → permanent grant (the historical behavior).
- `expires_at IS NOT NULL` → time-bounded alias; auto-revoke at `expires_at`.

### 2.2 Audit row extensions

The `admin.super_admin_grant` row gains two new fields:

- `alias_ttl_seconds INTEGER` — the operator-specified TTL.
- `incident_id UUID NULL` — optional incident tracking reference.

### 2.3 Identity-service mirror

The `identity.role_assignment_history` row gains:

- `expires_at TIMESTAMPTZ NULL` — mirrors the alias expiry.

The identity-service cron job (`identity.alias_revoke_job`) runs
hourly and revokes any `platform.super_admin` role where
`expires_at < now()`.

## 3. API

### 3.1 Issue an alias

**Endpoint**: `POST /v1/admin/identity/grant-time-bounded-super-admin`
(spec in [`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.19.1).

**Required headers** (all five — same gates as the permanent grant per
[`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) 14):

- `X-Audit-Reason: string ≥ 8 chars`
- `X-Signature: t=<unix>,v1=<hex>` — HMAC-SHA256 over body + timestamp
- `X-Break-Glass-Cosigner: <uuid>` — a different admin with `platform.super_admin`
- `X-Mfa-Claim: <signed MFA token>` — step-up MFA proof
- `Idempotency-Key: <uuid>` — required

**Body**:

```json
{
  "user_id": "01HZX...",
  "ttl_seconds": 86400,
  "incident_id": "01HZX...optional..."
}
```

**TTL bounds**: `3600` ≤ `ttl_seconds` ≤ `1209600` (1 hour to 14 days).

**Response**: 201 with `{ "grant_id", "expires_at", "status": "active" }`.

**Errors**: 409 `ALIAS_ALREADY_ACTIVE` if the user already has an
active alias; 422 `TTL_OUT_OF_BOUNDS` for out-of-range TTLs; 403
`CO_SIGNER_REQUIRED` if `X-Break-Glass-Cosigner` is missing or is the
same user as the actor.

### 3.2 Revoke an alias (manual, before expiry)

**Endpoint**: `DELETE /v1/admin/identity/revoke-time-bounded-super-admin`
(spec in [`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.19.2).

Revokes an active alias immediately. Same gates as 3.1 minus the
`X-Break-Glass-Cosigner` (the cosigner has already approved the grant).

### 3.3 List aliases for a user

**Endpoint**: `GET /v1/admin/identity/aliases/{user_id}`
(spec in [`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.19.3).

Returns both active and historical aliases. Caller must be the user
OR `platform.admin`.

### 3.4 Request via Conductor workflow

Operator-initiated requests go through the Conductor workflow
`wf.service_request.time_bounded_alias.v1`
([`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.5.4):

- Endpoint: `POST /v1/admin/access-requests/{id}/alias`
  ([`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.17.9).
- The workflow handles validation, fraud-risk review, co-signer
  approval (HUMAN TASK), and identity-service grant.
- Alias auto-revoke is handled by the same identity-service cron job.

## 4. Lifecycle

```
┌──────────────┐  POST /v1/admin/identity/grant-time-bounded-super-admin
│  Requested   │
└──────┬───────┘
       │ (gates: MFA, signature, co-signer, IP allowlist, Idempotency-Key)
       ▼
┌──────────────┐
│   Active     │──── expires_at = now() + ttl_seconds
└──────┬───────┘
       │ (two transitions)
       ├───────────────────────┐
       ▼                       ▼
┌──────────────┐         ┌──────────────┐
│ Auto-Revoked │         │ Manual-Revoked│
│ (cron job)    │         │ (DELETE)      │
└──────────────┘         └──────────────┘
       │                       │
       └───────────┬───────────┘
                   ▼
            ┌──────────────┐
            │  audit row   │
            │  + identity  │
            │  revoke      │
            └──────────────┘
```

States:

| State | Set by | Visible via |
|-------|--------|--------------|
| `requested` | workflow start | `wf.service_request.time_bounded_alias.v1` state |
| `active` | grant issued | `GET /v1/admin/identity/aliases/{user_id}` |
| `auto_revoked` | identity-service cron at `expires_at` | audit row + identity.revoke |
| `manual_revoked` | `DELETE /v1/admin/identity/revoke-time-bounded-super-admin` | audit row + identity.revoke |

### 4.1 Cron job

The identity-service `identity.alias_revoke_job` runs hourly
(00:30, 01:30, …, 23:30 platform time). It executes:

```sql
UPDATE identity.role_assignment_history
SET revoked_at = now(),
    revocation_reason = 'alias_expired'
WHERE role = 'platform.super_admin'
  AND expires_at < now()
  AND revoked_at IS NULL;
```

Then for each row updated, identity-service fans out the
`identity.role.revoked.v1` event with `revocation_reason = 'alias_expired'`.

The platform also publishes `admin.alias_request.expired.v1` so that
the operator who requested the alias is notified of the auto-revoke.

### 4.2 Clock skew

Each cron invocation includes a 60-second safety margin
(`expires_at < now() - INTERVAL '60 seconds'`) to absorb minor clock
skew between identity-service replicas.

## 5. Use cases

| Use case | Recommended TTL | Justification format |
|----------|----------------|---------------------|
| **Incident response** | 1h - 24h | "Incident <incident_id>: <one-line description>. <rollback plan>" |
| **Cross-team coverage** | 7d | "Covering for <colleague> on PTO from <date> to <date>. Operational tasks: <list>." |
| **Quarterly business review** | 14d | "QBR access for <date range>. Read-only on <services>." |
| **Maintenance window** | 4h - 12h | "DB migration window <start>-<end>. Operational tasks: <list>." |
| **On-call escalation** | 24h | "On-call escalation for <team> from <date> to <date>." |

Hard caps per the API:

- **Minimum**: 1 hour (`3600` seconds)
- **Maximum**: 14 days (`1209600` seconds)
- **Default**: 24 hours (`86400` seconds)

Beyond 14 days, the operator must request a permanent `SUPER_ADMIN`
grant (per [`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.14)
with full break-glass gates and a stronger justification.

## 6. Operational references

- **API spec**: [`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.19
- **Conductor workflow**: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.5.4
- **Audit events**: `admin.alias_request.*.v1`, `identity.role.granted.v1` (with `expires_at` header),
  `identity.role.revoked.v1` (with `revocation_reason = 'alias_expired'`)
- **SRS**: [`admin-service/SRS.md`](../services/admin-service/SRS.md) FR—046/047,
  SEC—016, DATA—017 (new IDs per the 2026-08-06 append)
- **ERD**: [`admin-service/ERD.md`](../services/admin-service/ERD.md) "SuperAdminGrant"
  (`expires_at` column added 2026-08-06)
- **Master task registry**: [`MASTER_TASK.md`](../MASTER_TASK.md) 11 (Role Mapping
  for the alias-grant / extend / revoke / scheduled-job tasks)
- **Memory anchor**: `trips-enjoy-super-admin-preset-management (2026-08-05)`

## 8. Audit and compliance

Every alias issuance and revocation emits an audit row in
`admin.super_admin_grant` (append-only, 7-year retention). The
`incident_id` field (optional) lets incident post-mortems trace
alias usage to specific incidents. Compliance reviewers query
`admin.super_admin_grant WHERE expires_at IS NOT NULL` to enumerate
all historical aliases for SOX/PCI audits.

The alias co-signer (`X-Break-Glass-Cosigner`) is logged in
`super_admin_grant.cosigner_id` so that the 2-of-2 approval pattern
(actor + co-signer, both `platform.super_admin`) is auditable.

## 9. Append-not-renumber note

This document is the canonical reference for time-bounded aliases. Per
the platform's append-not-renumber policy, future changes append new
N sections (e.g. 10, 11, …) rather than renumbering existing ones.

The alias API endpoints live in
[`admin-service/INTEGRATION.md`](../services/admin-service/INTEGRATION.md) 1.19
as the canonical spec; this shared doc explains the contract and
lifecycle.