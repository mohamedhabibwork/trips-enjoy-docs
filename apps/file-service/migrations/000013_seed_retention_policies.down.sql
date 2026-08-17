-- 000013_seed_retention_policies.down.sql
DELETE FROM file.retention_policies WHERE retention_class IN
    ('kyc','support_attachment','avatar','menu_photo','safety_recording','vehicle_photo','other');