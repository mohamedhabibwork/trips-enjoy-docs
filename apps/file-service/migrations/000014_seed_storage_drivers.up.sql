-- 000014_seed_storage_drivers.up.sql
--
-- Seed the local_fs driver so the dev scaffold boots with a working
-- default driver. Cloud drivers (s3 / azure_blob / oracle_object_storage
-- / gcs) are added by configuration-service on first
-- configuration.updated.v1. Idempotent.

INSERT INTO file.storage_drivers (
    id, kind, display_name, state, priority, region, container, endpoint,
    path_style, signed_url_ttl_seconds, max_object_size_bytes,
    multipart_threshold_bytes, is_default, config_hash, health, created_by, updated_by
)
VALUES (
    'local_fs',
    'local_fs',
    'Local filesystem (dev / CI / edge)',
    'enabled',
    100,
    'local',
    '/tmp/trips-enjoy-file-dev',
    NULL,
    false,
    900,
    100 * 1024 * 1024,
    0,
    true,
    'local-fs-dev',
    'healthy',
    gen_random_uuid(),
    gen_random_uuid()
)
ON CONFLICT (id) DO NOTHING;