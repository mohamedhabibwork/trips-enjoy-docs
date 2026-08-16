-- V8__configuration_seed_reference_data.sql
--
-- Production reference data seeded at Flyway time so the platform comes up
-- with canonical defaults. The seeder is gated by
-- `configuration-service.seed.enabled` (default false); an `ApplicationRunner`
-- reads the `configuration.outbox` rows this migration writes and publishes
-- the matching `configuration.updated.v1` events for downstream cache
-- invalidation. See ConfigurationReferenceDataSeeder.kt for the publish path.
--
-- Idempotent via `ON CONFLICT DO NOTHING` — re-running the migration on an
-- existing schema is a no-op (the `documents.key` UNIQUE constraint would
-- otherwise fail). The seeder only publishes events for the rows it just
-- inserted (rows whose `outbox.published_at IS NULL` after the migration).
--
-- Authoritative docs:
--   * INTEGRATION.md §10 (per-service key index)
--   * docs/shared/TYPE_CATALOG.md §8.7 (platform margin doctrine)
--   * docs/architecture/adrs/0014-externalize-configuration.md
--   * docs/services/configuration-service/ERD.md §3
--
-- The migration is self-contained: each seeded key ships with its JSON
-- Schema (v1) inline so it works on a fresh schema without any prior
-- `POST /v1/configurations` calls.
--
-- IMPORTANT: the `documents.key` regex is `^[a-z][a-z0-9_.\-]{1,127}$`
-- (lowercase only), so currency codes must be lowercased here. The
-- `pricing.commission.flat_minor.{currency}` keys use lowercase ISO 4217
-- (`eur`, `usd`, `sar`, `aed`) — the canonical lookup is
-- `pricing.commission.flat_minor.<lowercase-currency>` and a separate key
-- (not seeded by this migration) maps upper-case display codes to the
-- lowercase storage form via the channel subset filter.

-- ----------------------------------------------------------------------------
-- 1. Seed schemas (v1)
-- ----------------------------------------------------------------------------
INSERT INTO configuration.schemas (id, key, version, json_schema, created_at, created_by)
SELECT gen_random_uuid(), seed.key, 1, seed.json_schema::jsonb, now(), gen_random_uuid()
FROM (VALUES
    -- Locked commission keys (TYPE_CATALOG §8.7)
    ('pricing.commission.pct',                  '{"type":"number","minimum":0,"maximum":1}',                                          'number'),
    ('pricing.commission.flat_minor.eur',       '{"type":"integer","minimum":0}',                                                    'number'),
    ('pricing.commission.flat_minor.usd',       '{"type":"integer","minimum":0}',                                                    'number'),
    ('pricing.commission.flat_minor.sar',       '{"type":"integer","minimum":0}',                                                    'number'),
    ('pricing.commission.flat_minor.aed',       '{"type":"integer","minimum":0}',                                                    'number'),
    ('pricing.commission.base',                 '{"type":"string","enum":["gross","net_fare"]}',                                    'string'),
    ('pricing.discount_bearer',                 '{"type":"string","enum":["platform","driver","customer","merchant"]}',           'string'),
    -- Audit retention
    ('audit.retention.financial_years',         '{"type":"integer","minimum":1,"maximum":30}',                                     'number'),
    ('audit.retention.default_years',          '{"type":"integer","minimum":1,"maximum":30}',                                     'number'),
    ('audit.export.s3.path_template',           '{"type":"string","minLength":1}',                                                  'string'),
    ('audit.export.cron',                       '{"type":"string","pattern":"^[0-9*\\-\\s/]+$"}',                                   'string'),
    ('audit.hash.algo',                         '{"type":"string","enum":["sha256","sha512"]}',                                     'string'),
    -- Identity session
    ('identity.session.access_token_ttl_seconds',  '{"type":"integer","minimum":60,"maximum":86400}',                               'number'),
    ('identity.session.refresh_token_ttl_seconds', '{"type":"integer","minimum":300,"maximum":2592000}',                             'number'),
    ('identity.mfa.required_for_roles',         '{"type":"array","items":{"type":"string"},"minItems":0}',                       'array'),
    -- Notification retry
    ('notification.delivery.retry.max_attempts', '{"type":"integer","minimum":0,"maximum":10}',                                     'number'),
    ('notification.delivery.retry.backoff_seconds', '{"type":"array","items":{"type":"integer","minimum":1}}',                     'array'),
    ('notification.dnd.provider_rate_limits.email', '{"type":"object"}',                                                            'object'),
    ('notification.dnd.provider_rate_limits.sms',   '{"type":"object"}',                                                            'object'),
    -- Pricing per-city base fare
    ('pricing.base_fare.amsterdam',             '{"type":"object","required":["amount_minor","currency"]}',                       'object'),
    ('pricing.base_fare.london',                '{"type":"object","required":["amount_minor","currency"]}',                       'object'),
    ('pricing.base_fare.cairo',                 '{"type":"object","required":["amount_minor","currency"]}',                       'object'),
    ('pricing.min_fare.amsterdam',              '{"type":"integer","minimum":0}',                                                  'number'),
    ('pricing.min_fare.london',                 '{"type":"integer","minimum":0}',                                                  'number'),
    ('pricing.min_fare.cairo',                  '{"type":"integer","minimum":0}',                                                  'number'),
    ('pricing.surge.max_multiplier.amsterdam',  '{"type":"number","minimum":1,"maximum":5}',                                       'number'),
    ('pricing.surge.max_multiplier.london',     '{"type":"number","minimum":1,"maximum":5}',                                       'number'),
    ('pricing.surge.max_multiplier.cairo',      '{"type":"number","minimum":1,"maximum":5}',                                       'number')
) AS seed(key, json_schema, value_type)
ON CONFLICT (key, version) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. Seed documents (head rows)
-- ----------------------------------------------------------------------------
INSERT INTO configuration.documents (
    id, key, tenant_id, current_version, schema_id, value, value_type,
    created_at, updated_at, created_by, updated_by
)
SELECT
    gen_random_uuid(),
    seed.key,
    'global',
    1,
    s.id,
    seed.value::jsonb,
    seed.value_type,
    now(),
    now(),
    gen_random_uuid(),
    gen_random_uuid()
FROM (VALUES
    -- Locked commission keys (TYPE_CATALOG §8.7 — immutable until ADR flips)
    ('pricing.commission.pct',                  '0.20',                                                                            'number'),
    ('pricing.commission.flat_minor.eur',       '100',                                                                              'number'),
    ('pricing.commission.flat_minor.usd',       '100',                                                                              'number'),
    ('pricing.commission.flat_minor.sar',       '100',                                                                              'number'),
    ('pricing.commission.flat_minor.aed',       '100',                                                                              'number'),
    ('pricing.commission.base',                 '"gross"',                                                                          'string'),
    ('pricing.discount_bearer',                 '"platform"',                                                                       'string'),
    -- Audit retention
    ('audit.retention.financial_years',         '7',                                                                                'number'),
    ('audit.retention.default_years',          '1',                                                                                'number'),
    ('audit.export.s3.path_template',           '"s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/"',              'string'),
    ('audit.export.cron',                       '"0 0 4 * * *"',                                                                  'string'),
    ('audit.hash.algo',                         '"sha256"',                                                                        'string'),
    -- Identity session
    ('identity.session.access_token_ttl_seconds',  '600',                                                                           'number'),
    ('identity.session.refresh_token_ttl_seconds', '1800',                                                                          'number'),
    ('identity.mfa.required_for_roles',         '["platform.super_admin"]',                                                       'array'),
    -- Notification retry
    ('notification.delivery.retry.max_attempts', '5',                                                                                'number'),
    ('notification.delivery.retry.backoff_seconds', '[60,300,1800,7200,21600]',                                                       'array'),
    ('notification.dnd.provider_rate_limits.email', '{"per_minute":50,"per_hour":500}',                                                'object'),
    ('notification.dnd.provider_rate_limits.sms',   '{"per_minute":20,"per_hour":200}',                                                'object'),
    -- Pricing per-city base fare
    ('pricing.base_fare.amsterdam',             '{"amount_minor":250,"currency":"EUR"}',                                          'object'),
    ('pricing.base_fare.london',                '{"amount_minor":275,"currency":"GBP"}',                                          'object'),
    ('pricing.base_fare.cairo',                 '{"amount_minor":1500,"currency":"EGP"}',                                         'object'),
    ('pricing.min_fare.amsterdam',              '500',                                                                             'number'),
    ('pricing.min_fare.london',                 '550',                                                                             'number'),
    ('pricing.min_fare.cairo',                  '3000',                                                                            'number'),
    ('pricing.surge.max_multiplier.amsterdam',  '1.8',                                                                             'number'),
    ('pricing.surge.max_multiplier.london',     '1.8',                                                                             'number'),
    ('pricing.surge.max_multiplier.cairo',      '2.0',                                                                             'number')
) AS seed(key, value, value_type)
JOIN configuration.schemas s ON s.key = seed.key AND s.version = 1
ON CONFLICT (key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 3. Channel subsets — customer_app_en default view (FR-014)
-- ----------------------------------------------------------------------------
INSERT INTO configuration.channel_subsets (id, channel, key, json_pointer, created_at, updated_at)
SELECT
    gen_random_uuid(),
    'customer_app_en',
    seed.key,
    seed.pointer,
    now(), now()
FROM (VALUES
    ('ui.theme.primary',    '/theme/primary'),
    ('ui.copy.welcome',     NULL),
    ('ui.currency.list',    NULL),
    ('ui.locale.supported', NULL)
) AS seed(key, pointer)
ON CONFLICT (channel, key, json_pointer) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 4. Version-1 history rows for every seeded document (FR-003)
-- ----------------------------------------------------------------------------
INSERT INTO configuration.versions (
    id, document_id, version, value, scope_type, scope_id, cohort,
    effective_from, effective_to, reason, correlation_id, actor_id, client_ip, created_at
)
SELECT
    gen_random_uuid(),
    d.id,
    1,
    d.value,
    'global',
    NULL,
    NULL,
    NULL,
    NULL,
    'Initial seed (V8 reference data migration)',
    gen_random_uuid(),
    gen_random_uuid(),
    NULL,
    d.created_at
FROM configuration.documents d
WHERE d.current_version = 1
  AND NOT EXISTS (
      SELECT 1 FROM configuration.versions v
      WHERE v.document_id = d.id AND v.version = 1
  );

-- ----------------------------------------------------------------------------
-- 5. Audit log rows for every seeded document (FR-021, SEC-007)
-- ----------------------------------------------------------------------------
INSERT INTO configuration.audit_log (
    id, document_id, version, action, old_value, new_value,
    actor_id, reason, correlation_id, client_ip, request_signature, created_at
)
SELECT
    gen_random_uuid(),
    d.id,
    1,
    'create',
    NULL,
    d.value,
    d.created_by,
    'Initial seed (V8 reference data migration)',
    gen_random_uuid(),
    NULL,
    NULL,
    d.created_at
FROM configuration.documents d
WHERE d.current_version = 1
  AND NOT EXISTS (
      SELECT 1 FROM configuration.audit_log a
      WHERE a.document_id = d.id AND a.version = 1
  );

-- ----------------------------------------------------------------------------
-- 6. Outbox event stubs — ConfigurationReferenceDataSeeder picks these up
--    on boot (when seed.enabled=true) and publishes configuration.updated.v1
--    so downstream caches start warm.
-- ----------------------------------------------------------------------------
INSERT INTO configuration.outbox (
    id, topic, event_id, payload, headers, created_at
)
SELECT
    gen_random_uuid(),
    'configuration.updated',
    gen_random_uuid(),
    jsonb_build_object(
        'event_id',     gen_random_uuid()::text,
        'event_name',   'configuration.updated.v1',
        'occurred_at',  to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'schema_version', 1,
        'producer',     'configuration-service',
        'tenant_id',     d.tenant_id,
        'correlation_id', gen_random_uuid()::text,
        'causation_id',   null,
        'aggregate_type', 'ConfigurationDocument',
        'aggregate_id',   d.id::text,
        'data', jsonb_build_object(
            'key',         d.key,
            'version',     d.current_version,
            'old_version', null,
            'value',       d.value,
            'scope_type',  'global',
            'scope_id',    null,
            'actor_id',    d.created_by::text,
            'reason',      'Initial seed (V8 reference data migration)'
        )
    ),
    jsonb_build_object(
        'X-Correlation-Id', gen_random_uuid()::text,
        'X-Producer', 'configuration-service'
    ),
    now()
FROM configuration.documents d
WHERE d.current_version = 1
  AND NOT EXISTS (
      SELECT 1 FROM configuration.outbox o
      WHERE o.topic = 'configuration.updated'
        AND o.payload->>'aggregate_id' = d.id::text
        AND o.payload->'data'->>'version' = '1'
  );