-- 000013_seed_retention_policies.up.sql
--
-- Seed the canonical seven retention policies per BR-022..BR-024 +
-- TECH.md §13.3. Idempotent — re-running the migration is a no-op
-- (UNIQUE on retention_class + ON CONFLICT DO NOTHING).

INSERT INTO file.retention_policies (id, retention_class, display_name, duration, grace_period, enabled, created_by, updated_by)
VALUES
    (gen_random_uuid(), 'kyc',               'KYC documents',                interval '5 years',    interval '7 days', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'support_attachment','Support attachments',          interval '1 year',     interval '7 days', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'avatar',           'User avatars',                 interval '30 days',    interval '7 days', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'menu_photo',       'Restaurant menu photos',       interval '2 years',    interval '7 days', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'safety_recording', 'Ride safety recordings',       interval '1 year',     interval '7 days', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'vehicle_photo',    'Driver vehicle photos',        interval '3 years',    interval '7 days', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'other',            'Unclassified files',           interval '1 year',     interval '7 days', true, gen_random_uuid(), gen_random_uuid())
ON CONFLICT (retention_class) DO NOTHING;