-- V5__loosen_status_check.sql
-- The CHECK constraint in V1 only accepts lowercase values ('active', 'suspended',
-- 'disabled', 'erased'), but the JPA entity uses @Enumerated(EnumType.STRING)
-- which writes the enum's name() in UPPERCASE. Loosen the constraint to accept
-- both casings.
ALTER TABLE identity.identities
    DROP CONSTRAINT IF EXISTS identities_status_check;
ALTER TABLE identity.identities
    ADD CONSTRAINT identities_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED', 'ERASED', 'active', 'suspended', 'disabled', 'erased'));
