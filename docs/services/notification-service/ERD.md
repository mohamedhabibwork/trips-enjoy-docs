# notification-service — Entity-Relationship Diagram

## 1. Database

- **Engine**: PostgreSQL 18.
- **Schema**: `notification` — owned exclusively by this service.
- **Migrations**: `services/notification-service/migrations/`
  (versioned, forward-only, golang-migrate; reviewed in PR; no
  destructive migrations without a multi-step plan).

The schema is the canonical source of truth for notification
templates, user preferences, and delivery state. Other
services consume `notification.*.v1` events or call our REST
API; they do not write here.

> **v1.1 schema extension (WhatsApp + template history).**
> Added `whatsapp` as a 5th channel; introduced
> `notification.template_history` (immutable audit table); added
> a structured WhatsApp template body (`body_structured`,
> `template_type`, provider-template fields); bound every
> `deliveries` row to a `template_history` snapshot via
> `template_version_snapshot_id`. See §11 and §12 for the
> canonical migration scripts and the audit-chain rationale.
>
> See [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md),
> [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md), and
> [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) for the deep
> rationale.

## 2. Cross-Service References

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `user_id` | UUID | `Customer` / `Driver` / `Courier` / `Merchant` in respective service | each owner service |
| `template_id` | UUID | `Template` in this service | `notification-service` |
| `template_version_snapshot_id` | UUID (nullable) | `TemplateHistory.id` (this service) | `notification-service` (immutable audit) |
| `trip_id` | UUID (nullable) | `Trip` in `trip-service` | `trip-service` |
| `order_id` | UUID (nullable) | `FoodOrder` in `food-order-service` | `food-order-service` |
| `payment_id` | UUID (nullable) | `PaymentIntent` in `payment-service` | `payment-service` |
| `delivery_id` | UUID (nullable) | `Delivery` in `delivery-service` | `delivery-service` |
| `provider_template_id` | TEXT (nullable, on `templates` / `template_history`) | the WhatsApp provider's pre-approved template id | provider (Meta Cloud, 360dialog, Twilio-WhatsApp, etc.) — we mirror it for routing only |
| `actor_sub` (audit) | UUID | Keycloak `sub` of admin | `identity-service` (Keycloak) |
| `correlation_id` (audit) | UUID | per request | gateway / caller |

## 3. Entities

### `Template`

A notification template. Per-channel body with locale variants.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `name` | TEXT | NOT NULL | `trip.completed`, `food.order.placed` |
| `category` | TEXT | NOT NULL | `trip`, `food`, `payment`, `safety`, `marketing` |
| `channel` | TEXT | NOT NULL | `push` \| `sms` \| `email` \| `in_app` \| `whatsapp` |
| `locale` | TEXT | NOT NULL | `en`, `ar`, … |
| `subject` | TEXT | NULL | for email; nullable for push/SMS/in_app; null for WhatsApp (WhatsApp carries header text inside `body_structured`) |
| `body` | TEXT | NULL | Handlebars template (NULL only allowed when `template_type='whatsapp_structured'` and `body_structured IS NOT NULL`) |
| `template_type` | TEXT | NOT NULL DEFAULT `'plain'` | `plain` \| `whatsapp_structured`. Existing rows stay `plain`; the new discriminator is required for structured WhatsApp templates. |
| `body_structured` | JSONB | NULL | For `template_type='whatsapp_structured'` only. Shape: `{ header: {type, text?|media_id?}, body: {type, text}, footer: {type, text}, buttons: [{type, text, url?|phone?}], variables: [{key, index}] }`. Mirrors the WhatsApp Business API "components" payload verbatim. For all other template types this column is null. |
| `provider_template_id` | TEXT | NULL | the provider's pre-approved template id (`twilio_wa_template_xyz`). Nullable until the gateway reports `approved`. |
| `provider_template_language` | TEXT | NULL | the language code the provider registered the template against (`en`, `ar`, `en_US`, …). May differ from `locale` (logical UI language) — a logical `ar` user can be served a registered `ar_SA` template. |
| `provider_template_status` | TEXT | NULL | `draft` \| `submitted` \| `approved` \| `rejected` \| `paused`. NULL while the template is still in `plain` mode (no provider approval needed). |
| `provider_template_approved_at` | TIMESTAMPTZ | NULL | when the provider approved this version. Required for the 24h customer-service-window anchor if `whatsapp` freeform is later added. |
| `provider_template_reject_reason` | TEXT | NULL | populated when `provider_template_status='rejected'` |
| `required_variables` | TEXT[] | NOT NULL | for validation (every `{{var}}` in `body` and every `{key, index}` in `body_structured.variables` must appear here) |
| `metadata` | JSONB | NULL | for deeplinks, tags, RTL flag (`rtl: true`) |
| `status` | TEXT | NOT NULL | `active` \| `disabled` |
| `version` | INT | NOT NULL DEFAULT 1 | for template versioning |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |

#### Indexes

- PK on `id`
- UNIQUE on `(name, channel, locale, version)` (the version
  allows old variants to remain for audit)
- BTree on `(name, channel, locale) WHERE status = 'active' AND deleted_at IS NULL`
- BTree on `category` WHERE `deleted_at IS NULL`
- BTree on `(provider_template_id, provider_template_language)` WHERE `provider_template_id IS NOT NULL` (for inbound `comms.whatsapp.template_status_update.v1` webhook reconciliation)
- BTree on `(template_type, status) WHERE deleted_at IS NULL` (operational dashboards)

#### Constraints

- CHECK: `channel IN ('push', 'sms', 'email', 'in_app', 'whatsapp')`
- CHECK: `template_type IN ('plain', 'whatsapp_structured')`
- CHECK: `provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','approved','rejected','paused')`
- CHECK: `(template_type = 'plain' AND body IS NOT NULL AND body_structured IS NULL)
         OR (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)`
  (a template is either a plain text/Handlebars body, or a WhatsApp structured body — never both)
- CHECK: `provider_template_approved_at IS NOT NULL OR provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','rejected')`
  (approved_at only meaningful once approved)
- CHECK: `locale IN ('en', 'ar', …)` (configured list)
- CHECK: `status IN ('active', 'disabled')`
- CHECK: `(body IS NULL) OR (length(body) > 0)`

### `Preference`

A user's notification preferences.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `user_id` | UUID | NOT NULL | cross-ref (Customer / Driver / …) |
| `category` | TEXT | NOT NULL | `trip`, `food`, `payment`, `safety`, `marketing` |
| `channel` | TEXT | NOT NULL | `push` \| `sms` \| `email` \| `in_app` |
| `opt_in` | BOOLEAN | NOT NULL | default true; false = opt out |
| `quiet_hours_start` | TIME | NULL | in user's timezone |
| `quiet_hours_end` | TIME | NULL | |
| `timezone` | TEXT | NULL | IANA tz, used for quiet hours |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | soft delete |

#### Indexes

- PK on `id`
- UNIQUE on `(user_id, category, channel) WHERE deleted_at IS NULL`
- BTree on `user_id` WHERE `deleted_at IS NULL`

#### Constraints

- CHECK: `channel IN ('push', 'sms', 'email', 'in_app', 'whatsapp')`
- CHECK: `category IN ('trip', 'food', 'payment', 'safety', 'marketing')`
- CHECK: `(quiet_hours_start IS NULL AND quiet_hours_end IS NULL) OR (quiet_hours_start IS NOT NULL AND quiet_hours_end IS NOT NULL)`

### `Delivery`

A delivery attempt and its state.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `user_id` | UUID | NOT NULL | |
| `template_id` | UUID | NOT NULL | |
| `template_version_snapshot_id` | UUID | NULL | cross-ref to `template_history.id` — the immutable bytes that were rendered. Set just before the gateway call so support / audit can reconstruct the exact template content even after the template is later updated. NULL only for legacy deliveries created before v1.1. |
| `rendered_template_version` | INT | NULL | denormalised from `template_history.version` for analytics dashboards without joining |
| `template_name` | TEXT | NOT NULL | denormalized for analytics |
| `category` | TEXT | NOT NULL | denormalized |
| `channel` | TEXT | NOT NULL | `push` \| `sms` \| `email` \| `in_app` \| `whatsapp` |
| `locale` | TEXT | NOT NULL | |
| `status` | TEXT | NOT NULL | `queued` \| `rendering` \| `sending` \| `sent` \| `delivered` \| `read` \| `failed` \| `suppressed` |
| `attempt` | INT | NOT NULL DEFAULT 1 | retry count |
| `rendered_subject_encrypted` | BYTEA | NULL | for email |
| `rendered_body_encrypted` | BYTEA | NULL | `pgcrypto` ciphertext. For WhatsApp structured templates this holds the JSON-serialised rendered components (with variable substitution applied). For plain channels this is the rendered Handlebars body. |
| `rendered_template_type` | TEXT | NULL | denormalised from `templates.template_type` at render time |
| `rendered_provider_template_id` | TEXT | NULL | denormalised from `templates.provider_template_id` at render time; required for `channel='whatsapp'` |
| `rendered_provider_template_language` | TEXT | NULL | denormalised for WhatsApp routing |
| `dedup_key` | TEXT | NULL | for dedup |
| `request_idempotency_key` | TEXT | NULL | for client idempotency |
| `correlation_id` | UUID | NOT NULL | |
| `gateway_request_id` | UUID | NULL | the comms-gateway call id |
| `gateway_response_status` | INT | NULL | HTTP status from gateway |
| `gateway_response_body` | JSONB | NULL | for debug |
| `failure_reason` | TEXT | NULL | `NO_CONTACT`, `TEMPLATE_MISSING`, `CIRCUIT_OPEN`, `TEMPLATE_NOT_APPROVED`, `WINDOW_EXPIRED`, etc. |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `sent_at` | TIMESTAMPTZ | NULL | |
| `delivered_at` | TIMESTAMPTZ | NULL | |
| `read_at` | TIMESTAMPTZ | NULL | WhatsApp only — when the recipient opened/reads the message. NULL for other channels. |
| `failed_at` | TIMESTAMPTZ | NULL | |
| `trip_id` | UUID | NULL | cross-ref |
| `order_id` | UUID | NULL | cross-ref |
| `payment_id` | UUID | NULL | cross-ref |
| `delivery_id` | UUID | NULL | cross-ref |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Indexes

- PK on `(id, created_at)` (because partitioned)
- BTree on `(user_id, created_at DESC)`
- BTree on `(template_name, created_at DESC)`
- BTree on `(status, created_at)` WHERE `status IN ('failed', 'suppressed')`
- BTree on `correlation_id`
- BTree on `dedup_key` WHERE `dedup_key IS NOT NULL`
- BTree on `request_idempotency_key` WHERE `request_idempotency_key IS NOT NULL`
- BTree on `template_version_snapshot_id` WHERE `template_version_snapshot_id IS NOT NULL`
- BTree on `(channel, provider_template_id)` WHERE `channel='whatsapp' AND rendered_provider_template_id IS NOT NULL`

#### Constraints

- CHECK: `status IN ('queued','rendering','sending','sent','delivered','read','failed','suppressed')`
- CHECK: `attempt >= 1`
- CHECK: `channel IN ('push','sms','email','in_app','whatsapp')`
- CHECK: `category IN ('trip','food','payment','safety','marketing')`
- CHECK: `(channel = 'whatsapp' AND rendered_provider_template_id IS NOT NULL)
         OR (channel <> 'whatsapp')`
- CHECK: `(status <> 'read') OR (channel = 'whatsapp')`
  (the `read` state is only reachable for WhatsApp)

#### Partitioning

- Range-partitioned by `created_at`, monthly.
- Retention: 90 days for body; 1y for delivery state. After
  90 days, a job purges `rendered_body_encrypted` and
  `rendered_subject_encrypted`.

### `Suppression`

A global suppression rule (admin-managed).

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `category` | TEXT | NOT NULL | |
| `reason` | TEXT | NOT NULL | |
| `expires_at` | TIMESTAMPTZ | NULL | null = permanent |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | NULL | |

#### Indexes

- PK on `id`
- BTree on `category` WHERE `deleted_at IS NULL`

#### Constraints

- CHECK: `category IN ('trip','food','payment','safety','marketing')`
- CHECK: `length(reason) > 0`

### `TemplateHistory`

An immutable, append-only snapshot of every published template
version. Acts as the audit counterpart of `templates`: every
time a template is published (created, updated, approved,
paused) a row is written here that captures the exact content
plus who published it and (for WhatsApp) who approved it.

The audit chain is:

```
template (logical row, mutable)
   → version INT (column on template)
      → template_history.id (immutable row per published version)
         → deliveries.template_version_snapshot_id (FK-style ref)
```

This means support can always answer *"what was this recipient
actually sent?"* by joining `deliveries.template_version_snapshot_id`
to `template_history.id` and reading the `subject`, `body`,
`body_structured`, `metadata`, and `provider_*` columns that
existed at that moment.

See [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) for the
diff-summary schema, approver workflow, and immutability
guarantees. See [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md)
for how the snapshot link chains through to the delivery row.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 — referenced by `deliveries.template_version_snapshot_id` |
| `revision_no` | INT | NOT NULL | monotonically increasing per `template_id`; ordered write |
| `template_id` | UUID | NOT NULL | the mutable `templates.id` this snapshot belongs to |
| `version` | INT | NOT NULL | the `templates.version` at the moment of publication |
| `name` | TEXT | NOT NULL | denormalised |
| `category` | TEXT | NOT NULL | denormalised |
| `channel` | TEXT | NOT NULL | denormalised |
| `locale` | TEXT | NOT NULL | denormalised |
| `subject` | TEXT | NULL | denormalised |
| `body` | TEXT | NULL | denormalised; null when `template_type='whatsapp_structured'` |
| `template_type` | TEXT | NOT NULL | denormalised |
| `body_structured` | JSONB | NULL | denormalised; shape mirrors `templates.body_structured` exactly |
| `provider_template_id` | TEXT | NULL | denormalised |
| `provider_template_language` | TEXT | NULL | denormalised |
| `provider_template_status` | TEXT | NULL | denormalised |
| `provider_template_approved_at` | TIMESTAMPTZ | NULL | denormalised |
| `metadata` | JSONB | NULL | denormalised |
| `required_variables` | TEXT[] | NOT NULL | denormalised |
| `diff_summary` | JSONB | NOT NULL | `{ added_variables: text[], removed_variables: text[], body_changed: bool, structure_changed: bool, subject_changed: bool, approver_sub: uuid\|null, approved_at: timestamptz\|null, note: text\|null }`. Required to be non-null but may be an empty object for the very first version. |
| `published_by` | UUID | NOT NULL | Keycloak `sub` of the user who triggered the publish |
| `approved_by` | UUID | NULL | Keycloak `sub` of the WhatsApp-template approver (only populated for `channel='whatsapp'`) |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |

#### Indexes

- PK on `id`
- UNIQUE on `(template_id, revision_no)` — guarantees monotonic order per logical template
- UNIQUE on `(template_id, version)` — guarantees one snapshot per logical version
- BTree on `(template_id, created_at DESC)` — for "latest snapshot of this template" lookups
- BTree on `(channel, name, created_at DESC)` — for "who approved `trip.completed` on WhatsApp last" queries
- BTree on `published_by` WHERE `created_at > now() - interval '90 days'` — for admin "my recent publishes" view
- BTree on `provider_template_id` WHERE `provider_template_id IS NOT NULL` — for inbound `comms.whatsapp.template_status_update.v1` reconciliation

#### Constraints

- CHECK: `channel IN ('push','sms','email','in_app','whatsapp')`
- CHECK: `template_type IN ('plain','whatsapp_structured')`
- CHECK: `provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','approved','rejected','paused')`
- CHECK: `(body IS NULL) OR (length(body) > 0)`
- CHECK: `(template_type = 'plain' AND body IS NOT NULL AND body_structured IS NULL)
         OR (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)`
- CHECK: `(channel = 'whatsapp' AND approved_by IS NOT NULL)
         OR (channel <> 'whatsapp')`
  (every WhatsApp publication must be approved; non-WhatsApp channels skip approval)
- CHECK: `revision_no >= 1`
- CHECK: `version >= 1`

#### Immutability

The table is **append-only**. UPDATE and DELETE are blocked
by a row-level security policy and a `BEFORE UPDATE OR DELETE`
trigger that raises an exception. See [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md)
§4 for the trigger DDL and the rationale.

### `Outbox` and `Inbox`

Standard outbox and inbox tables per `EVENT_ARCHITECTURE.md`.
The schema is identical to the geolocation-service pattern;
see `geolocation-service/ERD.md` for the canonical DDL.

## 4. Mermaid ER Diagram

```mermaid
erDiagram
    Template ||--o{ Delivery : "produces"
    Template ||--o{ TemplateHistory : "snapshots"
    TemplateHistory ||--o{ Delivery : "audit-binds"
    Preference ||--o{ Delivery : "honored by"
    Suppression ||--o{ Delivery : "may suppress"
    Template {
        uuid id PK
        text name
        text category
        text channel
        text locale
        text subject
        text body
        text template_type
        jsonb body_structured
        text provider_template_id
        text provider_template_language
        text provider_template_status
        timestamptz provider_template_approved_at
        text_array required_variables
        int version
    }
    TemplateHistory {
        uuid id PK
        uuid template_id FK
        int revision_no
        int version
        text name
        text channel
        text locale
        jsonb diff_summary
        uuid published_by
        uuid approved_by
        timestamptz created_at
    }
    Preference {
        uuid id PK
        uuid user_id FK_ref
        text category
        text channel
        bool opt_in
        time quiet_hours_start
        time quiet_hours_end
    }
    Delivery {
        uuid id PK
        uuid user_id FK_ref
        uuid template_id FK_ref
        uuid template_version_snapshot_id FK_ref
        text channel
        text rendered_template_type
        text rendered_provider_template_id
        text status
        int attempt
        bytea rendered_body_encrypted
        text dedup_key
        uuid correlation_id
        uuid trip_id FK_ref
        uuid order_id FK_ref
        uuid payment_id FK_ref
    }
    Suppression {
        uuid id PK
        text category
        text reason
        timestamptz expires_at
    }
```

## 5. DDL Sketch

```sql
CREATE SCHEMA IF NOT EXISTS notification;
SET search_path = notification, public;

CREATE TABLE notification.templates (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('trip','food','payment','safety','marketing')),
    channel TEXT NOT NULL CHECK (channel IN ('push','sms','email','in_app','whatsapp')),
    locale TEXT NOT NULL,
    subject TEXT,
    body TEXT,
    template_type TEXT NOT NULL DEFAULT 'plain' CHECK (template_type IN ('plain','whatsapp_structured')),
    body_structured JSONB,
    provider_template_id TEXT,
    provider_template_language TEXT,
    provider_template_status TEXT
        CHECK (provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','approved','rejected','paused')),
    provider_template_approved_at TIMESTAMPTZ,
    provider_template_reject_reason TEXT,
    required_variables TEXT[] NOT NULL,
    metadata JSONB,
    status TEXT NOT NULL CHECK (status IN ('active','disabled')),
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CHECK ((template_type = 'plain' AND body IS NOT NULL AND body_structured IS NULL)
        OR (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)),
    CHECK ((body IS NULL) OR (length(body) > 0)),
    CHECK (provider_template_approved_at IS NULL OR provider_template_status IN ('approved')),
    CHECK (locale IN ('en','ar', /* …configured list… */))
);
CREATE UNIQUE INDEX templates_name_channel_locale_version_uk
    ON notification.templates (name, channel, locale, version);
CREATE INDEX templates_active_idx
    ON notification.templates (name, channel, locale)
    WHERE status = 'active' AND deleted_at IS NULL;
CREATE INDEX templates_category_idx
    ON notification.templates (category)
    WHERE deleted_at IS NULL;
CREATE INDEX templates_provider_template_idx
    ON notification.templates (provider_template_id, provider_template_language)
    WHERE provider_template_id IS NOT NULL;
CREATE INDEX templates_template_type_idx
    ON notification.templates (template_type, status)
    WHERE deleted_at IS NULL;

CREATE TABLE notification.template_history (
    id UUID PRIMARY KEY,
    revision_no INT NOT NULL CHECK (revision_no >= 1),
    template_id UUID NOT NULL,
    version INT NOT NULL CHECK (version >= 1),
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('trip','food','payment','safety','marketing')),
    channel TEXT NOT NULL CHECK (channel IN ('push','sms','email','in_app','whatsapp')),
    locale TEXT NOT NULL,
    subject TEXT,
    body TEXT,
    template_type TEXT NOT NULL CHECK (template_type IN ('plain','whatsapp_structured')),
    body_structured JSONB,
    provider_template_id TEXT,
    provider_template_language TEXT,
    provider_template_status TEXT
        CHECK (provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','approved','rejected','paused')),
    provider_template_approved_at TIMESTAMPTZ,
    metadata JSONB,
    required_variables TEXT[] NOT NULL,
    diff_summary JSONB NOT NULL,
    published_by UUID NOT NULL,
    approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((template_type = 'plain' AND body IS NOT NULL AND body_structured IS NULL)
        OR (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)),
    CHECK ((body IS NULL) OR (length(body) > 0)),
    CHECK ((channel = 'whatsapp' AND approved_by IS NOT NULL)
        OR (channel <> 'whatsapp'))
);
CREATE UNIQUE INDEX template_history_revision_uk
    ON notification.template_history (template_id, revision_no);
CREATE UNIQUE INDEX template_history_version_uk
    ON notification.template_history (template_id, version);
CREATE INDEX template_history_template_created_idx
    ON notification.template_history (template_id, created_at DESC);
CREATE INDEX template_history_channel_name_idx
    ON notification.template_history (channel, name, created_at DESC);
CREATE INDEX template_history_publisher_idx
    ON notification.template_history (published_by)
    WHERE created_at > now() - interval '90 days';
CREATE INDEX template_history_provider_template_idx
    ON notification.template_history (provider_template_id)
    WHERE provider_template_id IS NOT NULL;

-- Append-only enforcement: UPDATE / DELETE raise an exception
CREATE OR REPLACE FUNCTION notification.template_history_immutable()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'notification.template_history is append-only (op=%)', TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER template_history_no_update
    BEFORE UPDATE OR DELETE ON notification.template_history
    FOR EACH ROW EXECUTE FUNCTION notification.template_history_immutable();

CREATE TABLE notification.preferences (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('trip','food','payment','safety','marketing')),
    channel TEXT NOT NULL CHECK (channel IN ('push','sms','email','in_app','whatsapp')),
    opt_in BOOLEAN NOT NULL DEFAULT true,
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    timezone TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CHECK ((quiet_hours_start IS NULL AND quiet_hours_end IS NULL)
        OR (quiet_hours_start IS NOT NULL AND quiet_hours_end IS NOT NULL))
);
CREATE UNIQUE INDEX preferences_user_cat_chan_uk
    ON notification.preferences (user_id, category, channel)
    WHERE deleted_at IS NULL;
CREATE INDEX preferences_user_idx
    ON notification.preferences (user_id)
    WHERE deleted_at IS NULL;

CREATE TABLE notification.deliveries (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    template_id UUID NOT NULL,
    template_version_snapshot_id UUID,
    rendered_template_version INT,
    template_name TEXT NOT NULL,
    category TEXT NOT NULL,
    channel TEXT NOT NULL CHECK (channel IN ('push','sms','email','in_app','whatsapp')),
    locale TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('queued','rendering','sending','sent','delivered','read','failed','suppressed')),
    attempt INT NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    rendered_subject_encrypted BYTEA,
    rendered_body_encrypted BYTEA,
    rendered_template_type TEXT,
    rendered_provider_template_id TEXT,
    rendered_provider_template_language TEXT,
    dedup_key TEXT,
    request_idempotency_key TEXT,
    correlation_id UUID NOT NULL,
    gateway_request_id UUID,
    gateway_response_status INT,
    gateway_response_body JSONB,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    trip_id UUID,
    order_id UUID,
    payment_id UUID,
    delivery_id UUID,
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id, created_at),
    CHECK ((channel = 'whatsapp' AND rendered_provider_template_id IS NOT NULL)
        OR (channel <> 'whatsapp')),
    CHECK ((status <> 'read') OR (channel = 'whatsapp'))
) PARTITION BY RANGE (created_at);
CREATE INDEX deliveries_user_created_idx ON notification.deliveries (user_id, created_at DESC);
CREATE INDEX deliveries_template_created_idx ON notification.deliveries (template_name, created_at DESC);
CREATE INDEX deliveries_status_open_idx ON notification.deliveries (status, created_at) WHERE status IN ('failed','suppressed');
CREATE INDEX deliveries_correlation_idx ON notification.deliveries (correlation_id);
CREATE INDEX deliveries_dedup_idx ON notification.deliveries (dedup_key) WHERE dedup_key IS NOT NULL;
CREATE INDEX deliveries_idem_idx ON notification.deliveries (request_idempotency_key) WHERE request_idempotency_key IS NOT NULL;
CREATE INDEX deliveries_template_history_idx
    ON notification.deliveries (template_version_snapshot_id)
    WHERE template_version_snapshot_id IS NOT NULL;
CREATE INDEX deliveries_channel_provider_template_idx
    ON notification.deliveries (channel, rendered_provider_template_id)
    WHERE channel = 'whatsapp' AND rendered_provider_template_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS notification.deliveries_2026_07
    PARTITION OF notification.deliveries
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

-- Verify the child is actually attached to the correct parent with
-- the expected bounds. IF NOT EXISTS only guards the name; it does
-- not verify bounds.
DO $$
DECLARE
    v_parent   REGCLASS := 'notification.deliveries'::REGCLASS;
    v_child    REGCLASS := 'notification.deliveries_2026_07'::REGCLASS;
    v_expected TSTZRANGE := tstzrange('2026-07-01 00:00:00+00',
                                      '2026-08-01 00:00:00+00',
                                      '[)');
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
    IF NOT (SELECT relpartbound FROM pg_class WHERE oid = v_child)
              = v_expected THEN
        RAISE EXCEPTION 'partition % has unexpected bounds', v_child::text;
    END IF;
END $$;

CREATE TABLE notification.suppressions (
    id UUID PRIMARY KEY,
    category TEXT NOT NULL CHECK (category IN ('trip','food','payment','safety','marketing')),
    reason TEXT NOT NULL CHECK (length(reason) > 0),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX suppressions_category_idx ON notification.suppressions (category) WHERE deleted_at IS NULL;
```

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`, `created_by`,
`updated_by`, `deleted_at`. `deliveries` has `version` for
optimistic concurrency.

## 7. Soft Delete

`templates`, `preferences`, `suppressions` use `deleted_at`.
Reads filter `WHERE deleted_at IS NULL`. `deliveries` is
append-mostly (state transitions are updates, not soft delete).

## 8. JSONB Usage

| Table | Column | Justification |
|-------|--------|---------------|
| `templates` | `metadata` | deeplinks, tags, RTL flag (`rtl: true`); never queried |
| `templates` | `body_structured` | WhatsApp structured components (`header/body/footer/buttons/variables`); queried by key only (`template_type`, `provider_template_id`) |
| `template_history` | `diff_summary` | required audit summary (`added_variables`, `removed_variables`, `body_changed`, `structure_changed`, `approver_sub`); never queried in hot path |
| `template_history` | `body_structured` | snapshot of `templates.body_structured` at publication; queried only by `id` |
| `deliveries` | `gateway_response_body` | raw gateway response for debug |
| `deliveries` | (n/a) | — |

## 9. Partitioning

| Table | Partition strategy | Retention |
|-------|--------------------|-----------|
| `deliveries` | RANGE by `created_at`, monthly | body 90d, state 1y; partition dropped at 1y |
| `template_history` | none — the table is append-only and bounded by the velocity of template publication (≈ hundreds of rows/day); retention is policy-driven, not partition-driven (see §10) |

See [`DATABASE_ARCHITECTURE.md` §"Table Partitioning — Canonical Template"](../../architecture/DATABASE_ARCHITECTURE.md) for the idempotent `CREATE TABLE IF NOT EXISTS … PARTITION OF …` pattern, naming convention, and the service-owned maintenance-job contract (advisory lock, verification, retention/mixed-retention handling).

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| `deliveries` (body) | 90d | job: `UPDATE SET rendered_body_encrypted = NULL WHERE created_at < now() - interval '90 days'` |
| `deliveries` (state) | 1y | partition drop |
| `templates` | indefinite (soft delete) | hard delete after 1y if not used |
| `template_history` | indefinite (audit-grade) | only via an explicit `audit-service`-authored retention run; product policy keeps the full chain |
| `preferences` | while user is active | anonymize on right-to-erasure |
| `suppressions` | 30d after `expires_at` | hard delete |

> The right-to-erasure endpoint `POST /v1/admin/erasure/{user_id}`
> does NOT delete template_history rows (those contain no PII
> — `published_by` is an admin UUID, not a recipient UUID). It
> does redact PII-bearing recipient fields on `deliveries`
> (see [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) §5).

## 11. Migration Considerations

- **Adding a new template** is a config change, not a schema
  change. The admin POSTs a new template; the new row appears.
- **Adding a new channel** requires a migration to update the
  CHECK constraint on `templates`, `preferences`,
  `deliveries`, `suppressions` (notification layer) and on
  `providers`, `sends`, `webhook_events`, `optouts`,
  `provider_health` (gateway layer). Use the canonical
  migration snippet in §12.
- **Adding a new locale** is a config change.
- **Body encryption key rotation** is a background job, not a
  migration.
- **Partition pre-creation**: monthly partitions for `deliveries`
  are pre-created for the next 12 months.
- **template_history is append-only** — no online backfill is
  ever needed because every publish writes both a `templates`
  row update AND a `template_history` insert in the same
  transaction. Existing pre-v1.1 templates have no snapshot;
  delivery rows created before v1.1 leave
  `template_version_snapshot_id` NULL (allowed by the schema).
- **WhatsApp template retroactive backfill** (operational, not
  required): a one-time admin job can iterate every active
  `templates` row with `channel='whatsapp'` and write a
  `template_history` snapshot for its current `version` so
  deliveries created after that point have a chain to follow.
  See [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) §6 for
  the job specification.

## 12. Migration Snippet — `notification` schema v1.1

This migration extends the schema in place. Forward-only,
reviewable, idempotent (`IF NOT EXISTS` guards).

```sql
BEGIN;

-- 1. Drop the existing CHECK on `templates.channel` (any name)
--    and replace it with the v1.1 value.
ALTER TABLE notification.templates
    DROP CONSTRAINT IF EXISTS templates_channel_check,
    ADD CONSTRAINT templates_channel_check
        CHECK (channel IN ('push','sms','email','in_app','whatsapp'));

-- 2. Same for `preferences.channel`.
ALTER TABLE notification.preferences
    DROP CONSTRAINT IF EXISTS preferences_channel_check,
    ADD CONSTRAINT preferences_channel_check
        CHECK (channel IN ('push','sms','email','in_app','whatsapp'));

-- 3. Same for `deliveries.channel`.
ALTER TABLE notification.deliveries
    DROP CONSTRAINT IF EXISTS deliveries_channel_check,
    ADD CONSTRAINT deliveries_channel_check
        CHECK (channel IN ('push','sms','email','in_app','whatsapp'));

-- 4. Extend `deliveries.status` to include `read` (WhatsApp only).
ALTER TABLE notification.deliveries
    DROP CONSTRAINT IF EXISTS deliveries_status_check,
    ADD CONSTRAINT deliveries_status_check
        CHECK (status IN ('queued','rendering','sending','sent','delivered','read','failed','suppressed'));

-- 5. Add the new template columns.
ALTER TABLE notification.templates
    ADD COLUMN IF NOT EXISTS template_type TEXT NOT NULL DEFAULT 'plain'
        CHECK (template_type IN ('plain','whatsapp_structured')),
    ADD COLUMN IF NOT EXISTS body_structured JSONB,
    ADD COLUMN IF NOT EXISTS provider_template_id TEXT,
    ADD COLUMN IF NOT EXISTS provider_template_language TEXT,
    ADD COLUMN IF NOT EXISTS provider_template_status TEXT
        CHECK (provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','approved','rejected','paused')),
    ADD COLUMN IF NOT EXISTS provider_template_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS provider_template_reject_reason TEXT;

-- 6. Relax `body` NOT NULL to allow WhatsApp-only templates
--    (the new CHECK enforces "plain has body, structured has body_structured").
ALTER TABLE notification.templates
    ALTER COLUMN body DROP NOT NULL;

-- 7. The discriminator CHECK.
ALTER TABLE notification.templates
    ADD CONSTRAINT templates_body_discriminator_chk
        CHECK ((template_type = 'plain' AND body IS NOT NULL AND body_structured IS NULL)
            OR (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL));

-- 8. Extend the deliveries row with snapshot linkage + WhatsApp fields.
ALTER TABLE notification.deliveries
    ADD COLUMN IF NOT EXISTS template_version_snapshot_id UUID,
    ADD COLUMN IF NOT EXISTS rendered_template_version INT,
    ADD COLUMN IF NOT EXISTS rendered_template_type TEXT,
    ADD COLUMN IF NOT EXISTS rendered_provider_template_id TEXT,
    ADD COLUMN IF NOT EXISTS rendered_provider_template_language TEXT,
    ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;

-- 9. New delivery constraints.
ALTER TABLE notification.deliveries
    ADD CONSTRAINT deliveries_whatsapp_provider_template_required_chk
        CHECK ((channel = 'whatsapp' AND rendered_provider_template_id IS NOT NULL)
            OR (channel <> 'whatsapp')),
    ADD CONSTRAINT deliveries_read_only_whatsapp_chk
        CHECK ((status <> 'read') OR (channel = 'whatsapp'));

-- 10. New append-only history table.
CREATE TABLE IF NOT EXISTS notification.template_history (
    id UUID PRIMARY KEY,
    revision_no INT NOT NULL CHECK (revision_no >= 1),
    template_id UUID NOT NULL,
    version INT NOT NULL CHECK (version >= 1),
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('trip','food','payment','safety','marketing')),
    channel TEXT NOT NULL CHECK (channel IN ('push','sms','email','in_app','whatsapp')),
    locale TEXT NOT NULL,
    subject TEXT,
    body TEXT,
    template_type TEXT NOT NULL CHECK (template_type IN ('plain','whatsapp_structured')),
    body_structured JSONB,
    provider_template_id TEXT,
    provider_template_language TEXT,
    provider_template_status TEXT
        CHECK (provider_template_status IS NULL OR provider_template_status IN ('draft','submitted','approved','rejected','paused')),
    provider_template_approved_at TIMESTAMPTZ,
    metadata JSONB,
    required_variables TEXT[] NOT NULL,
    diff_summary JSONB NOT NULL,
    published_by UUID NOT NULL,
    approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((template_type = 'plain' AND body IS NOT NULL AND body_structured IS NULL)
        OR (template_type = 'whatsapp_structured' AND body_structured IS NOT NULL AND body IS NULL)),
    CHECK ((body IS NULL) OR (length(body) > 0)),
    CHECK ((channel = 'whatsapp' AND approved_by IS NOT NULL)
        OR (channel <> 'whatsapp'))
);
CREATE UNIQUE INDEX IF NOT EXISTS template_history_revision_uk
    ON notification.template_history (template_id, revision_no);
CREATE UNIQUE INDEX IF NOT EXISTS template_history_version_uk
    ON notification.template_history (template_id, version);
CREATE INDEX IF NOT EXISTS template_history_template_created_idx
    ON notification.template_history (template_id, created_at DESC);
CREATE INDEX IF NOT EXISTS template_history_channel_name_idx
    ON notification.template_history (channel, name, created_at DESC);
CREATE INDEX IF NOT EXISTS template_history_provider_template_idx
    ON notification.template_history (provider_template_id)
    WHERE provider_template_id IS NOT NULL;

CREATE OR REPLACE FUNCTION notification.template_history_immutable()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'notification.template_history is append-only (op=%)', TG_OP;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS template_history_no_update ON notification.template_history;
CREATE TRIGGER template_history_no_update
    BEFORE UPDATE OR DELETE ON notification.template_history
    FOR EACH ROW EXECUTE FUNCTION notification.template_history_immutable();

-- 11. New deliveries indexes.
CREATE INDEX IF NOT EXISTS deliveries_template_history_idx
    ON notification.deliveries (template_version_snapshot_id)
    WHERE template_version_snapshot_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS deliveries_channel_provider_template_idx
    ON notification.deliveries (channel, rendered_provider_template_id)
    WHERE channel = 'whatsapp' AND rendered_provider_template_id IS NOT NULL;

COMMIT;
```

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
- [`WHATSAPP_TEMPLATES.md`](./WHATSAPP_TEMPLATES.md) — WhatsApp structured template model, approval workflow, 24h window
- [`TEMPLATE_HISTORY.md`](./TEMPLATE_HISTORY.md) — `notification.template_history` audit table, diff summary, immutable contract
- [`MESSAGE_HISTORY.md`](./MESSAGE_HISTORY.md) — `notification.deliveries.template_version_snapshot_id` audit chain, right-to-erasure interplay
- [`PLAN.md`](./PLAN.md) — implementation tracker for the WhatsApp + history extension
- [`seeds/templates.v1.json`](./seeds/templates.v1.json) — JSON seed of 24 templates × 5 channels × 2 locales
- [`seeds/RENDERING_DEMO.md`](./seeds/RENDERING_DEMO.md) — Mermaid template-rendering demo (en + ar + WhatsApp)

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`identity-service`](../identity-service/README.md), [`merchant-service`](../merchant-service/README.md), [`payment-service`](../payment-service/README.md), [`promotion-service`](../promotion-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`support-service`](../support-service/README.md), [`trip-service`](../trip-service/README.md), [`user-profile-service`](../user-profile-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`api-gateway`](../api-gateway/README.md), [`branch-service`](../branch-service/README.md), [`checkout-service`](../checkout-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-incentive-service`](../driver-incentive-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`identity-service`](../identity-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

