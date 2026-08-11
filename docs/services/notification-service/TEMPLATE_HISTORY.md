# notification-service — `template_history` (immutable audit chain)

> Companion to [`ERD.md`](./ERD.md) 3 (`TemplateHistory`
> entity, immutability trigger, CHECK constraints) and
> [`INTEGRATION.md`](./INTEGRATION.md) 1.7.b–1.7.d (admin
> endpoints). This document explains *why* the audit table
> exists, the bit-for-bit invariants it preserves, the
> diff-summary schema, the approver workflow, and how it
> chains forward to per-delivery snapshots described in
> [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md).

## 1. The problem

Before the `template_history` table, support agents had one
truncated way to answer *"what was this customer actually sent
at 14:32 last Tuesday?"*:

1. Find the `notifications.deliveries` row for the user at
   that moment.
2. Read `deliveries.rendered_body_encrypted` (subject to the
   90-day retention purge).
3. Trust that the `template_name` on the delivery row still
   had the same `body` as on the day it was rendered.

Step (3) is unreliable: `templates.body` is mutable, the
template may have been deleted and re-published, the schema
didn't preserve old versions consistently, and there was no
provenance of WHO published WHAT and WHEN.

The audit table fixes this. Every publication — whether a
plain text template update, a WhatsApp structured-template
creation, an approval, or a pause — writes a single
immutable snapshot row carrying the exact content + the
publisher/approver UUIDs + a structured diff summary.

The audit chain then chains into the delivery row:

```
templates (logical row, mutable)
   → templates.version (column)
      → template_history.id (immutable row per published version)
         → deliveries.template_version_snapshot_id (FK-style ref)
```

## 2. Entity recap

See [`ERD.md`](./ERD.md) 3 (`TemplateHistory`) for the column
list. Highlights:

- `id UUID PK` — referenced from `deliveries.template_version_snapshot_id`.
- `revision_no INT` — monotonically increasing per
  `template_id`; UNIQUE on `(template_id, revision_no)`.
- `template_id UUID` — the mutable `templates.id`.
- `version INT` — the `templates.version` at the moment of
  publication; UNIQUE on `(template_id, version)`.
- `name` / `channel` / `locale` — denormalised for query speed.
- `subject` / `body` / `body_structured` — the exact rendered
  shape at publication.
- `template_type` — `plain` | `whatsapp_structured`.
- `provider_template_id` / `provider_template_status` /
  `provider_template_approved_at` — WhatsApp provider mirror.
- `metadata`, `required_variables` — denormalised.
- `diff_summary JSONB NOT NULL` — required audit summary.
- `published_by UUID NOT NULL` — Keycloak `sub` of the user
  who triggered the publish.
- `approved_by UUID NULL` — Keycloak `sub` of the WhatsApp
  approver (only for `channel='whatsapp'`).
- `created_at TIMESTAMPTZ NOT NULL` — snapshot time.

## 3. `diff_summary` schema

```jsonc
{
  "added_variables":     ["currency_code"],            // required_variables added since last revision
  "removed_variables":   ["legacy_field"],             // required_variables removed since last revision
  "body_changed":        true,                          // body text/subject differs from previous version
  "structure_changed":   false,                         // body_structured header/footer/buttons count differs
  "subject_changed":     true,                          // subject differs from previous version
  "approver_sub":        "01HZX9C5S3B1L7K0P2F8V4T6YDD", // null until approved; populated on approval snapshot
  "approved_at":         "2026-07-29T10:50:11.183Z",   // mirrors templates.provider_template_approved_at
  "note":                "approved by Meta"            // free-form note; populated by admins
}
```

The minimum schema is the empty object `{}` for the very first
publication of a template. Every subsequent publication MUST
include all keys with their truthy/falsey values; the CHECK
constraint enforcing this is in [`ERD.md`](./ERD.md).

## 4. Immutability — why a trigger, not just RLS

PostgreSQL row-level security has a subtle interaction with
foreign-key cascading and bulk admin operations; a trigger
gives a clearer contract for operators and a sharper error
message for application code. The canonical DDL lives in
[`ERD.md`](./ERD.md) 5 (DDL sketch):

```sql
CREATE OR REPLACE FUNCTION notification.template_history_immutable()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'notification.template_history is append-only (op=%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER template_history_no_update
    BEFORE UPDATE OR DELETE ON notification.template_history
    FOR EACH ROW EXECUTE FUNCTION notification.template_history_immutable();
```

Operators who need to redact PII — there is none in this
table; only admin `sub` UUIDs — must work through an
`audit-service`-authored retention run rather than direct DML.

## 5. Atomicity with `templates`

Every publication goes through ONE transaction:

```sql
BEGIN;

-- 1) Optionally update the templates row to the new version.
UPDATE notification.templates
   SET version = version + 1,
       body = $new_body,
       body_structured = $new_body_structured,
       template_type = $new_template_type,
       subject = $new_subject,
       provider_template_id = $new_provider_template_id,
       provider_template_status = $new_provider_template_status,
       provider_template_approved_at = $new_provider_template_approved_at,
       required_variables = $new_required_variables,
       metadata = $new_metadata,
       status = 'active',
       updated_at = now(),
       updated_by = $actor
 WHERE id = $template_id
 RETURNING version;

-- 2) Insert one new snapshot per (channel, locale) in the publish
--    batch (for atomic-across-locales publish this is N rows).
INSERT INTO notification.template_history (...)
VALUES (..., $diff_summary, $published_by, $approved_by, now());

COMMIT;
```

If the transaction fails (network, DB error, validation
failure), the new version is rolled back together with the
`templates` update — there is never a partial state.

## 6. Backfill for pre-v1.1 templates

Existing pre-v1.1 templates have no snapshot. The first
publication under v1.1 captures the current state; for
*historical* deliveries (created before v1.1) the
`deliveries.template_version_snapshot_id` stays NULL.

For teams that need a fully reconstructed audit chain, a
one-time admin backfill job iterates the active
`templates` rows and writes one `template_history` row for
their current `version` (so the audit chain begins from
"today, version=N, content=current"). The job is idempotent
and may be run repeatedly on dev/staging without harm. On
production we run it ONCE during the v1.1 deployment.

The job specification is intentionally simple; it is run by
the `notification-ops` admin team, not as part of the
default deployment. See [`PLAN.md`](./PLAN.md) for
acceptance criteria.

## 7. Query patterns

### 7.1 "What was this recipient actually sent?"

```sql
SELECT th.subject, th.body, th.body_structured, th.metadata,
       th.provider_template_id, th.required_variables, th.approved_by,
       d.sent_at, d.channel, d.locale
  FROM notification.deliveries d
  JOIN notification.template_history th
    ON th.id = d.template_version_snapshot_id
 WHERE d.id = $delivery_id;
```

`deliveries.template_version_snapshot_id` is the immutable
pointer; it is set atomically with the gateway call (see
[`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md)).

### 7.2 "What did admin X publish last week?"

```sql
SELECT name, channel, locale, version, revision_no,
       provider_template_status, diff_summary, created_at
  FROM notification.template_history
 WHERE published_by = $admin_sub
   AND created_at BETWEEN $from AND $to
 ORDER BY created_at DESC;
```

Backed by index `template_history_publisher_idx`.

### 7.3 "Has this template ever been approved?"

```sql
SELECT version, revision_no, approved_by, created_at
  FROM notification.template_history
 WHERE template_id = $template_id
   AND provider_template_status = 'approved'
 ORDER BY created_at DESC
 LIMIT 1;
```

### 7.4 "Compare two revisions" (for support)

```sql
SELECT id, revision_no, version, body, body_structured,
       required_variables, diff_summary, published_by, approved_by
  FROM notification.template_history
 WHERE template_id = $template_id
   AND revision_no IN ($r1, $r2)
 ORDER BY revision_no;
```

## 8. Operational notes

| Concern | Resolution |
|---------|-----------|
| Storage | `template_history` is append-only and bounded by the velocity of template publications (a few hundred rows/day peak). Bloat is rarely an issue; periodic vacuum and partition not required. |
| PII | None. Only admin `sub` UUIDs. Right-to-erasure does NOT touch this table. |
| Retention | `notification.template_history.retention_days` config (default: indefinite). Lower values honored only via an `audit-service`-authored retention run. |
| Disaster recovery | Standard DR (PostgreSQL PITR + cross-region replica) per platform baseline. |
| Reconciliation | If a `template_history` insert fails after `templates` updates, both halves roll back; reconciliation jobs are not needed. |
| Cross-service ownership | None. This table is private to `notification-service` (per `DATA_OWNERSHIP.md`). |

## 9. Audit-event correlation

Every publication emits `notification.template.published.v1`
(see [`INTEGRATION.md`](./INTEGRATION.md) 3.6) with
`template_history_id` as the aggregation key. Downstream:

- ``reporting-service` (data lake)` — counts publications by locale / channel
  / approver; computes "how often does this template change?".
- `audit-service` — also ingests the same event, treating the
  `template_history_id` as the immutable primary key.

Both consumers are idempotent on `template_history_id`; the
event may be safely replayed.

## 10. Worked walkthrough

The full end-to-end flow (admin submits → provider approves →
notification-service writes snapshot → emit
`notification.template.published.v1` → a delivery uses it →
the recipient reads the message) is in
[`WORKFLOWS.md`](./WORKFLOWS.md) 9.

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements (BR--029, BR--030, BR--031, BR--034, BR--042, BR--043, BR--044)
- [`SRS.md`](./SRS.md) — functional + non-functional requirements (FR--045, FR--046, FR--047, FR--048, NFR--043)
- [`ERD.md`](./ERD.md) — data model (entity, columns, immutability trigger, migration snippet)
- [`INTEGRATION.md`](./INTEGRATION.md) — admin endpoints + events
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — structured-template model that produces the snapshots
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — delivery-side audit chain that consumes these snapshots
- [`PLAN.md`](./PLAN.md) — implementation tracker

### Platform-wide

- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../../README.md`](../../README.md) — services overview
- [`../../../main.md`](../../../main.md) — top-level platform specification
