-- 000009_driver_history.up.sql
--
-- file.driver_history — immutable append-only audit of every upload /
-- pin / migrate / rollback. The signed HMAC is verified before INSERT
-- (the application layer enforces it). Migration rows set
-- verified_sha256=true only after the destination HeadObject matches.

CREATE TABLE IF NOT EXISTS file.driver_history (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES file.files(id) ON DELETE CASCADE,
    change_type TEXT NOT NULL CHECK (change_type IN ('upload','pin','migrate','rollback')),
    from_driver_id TEXT,
    to_driver_id TEXT NOT NULL,
    from_locator JSONB,
    to_locator JSONB NOT NULL,
    from_sha256 TEXT,
    to_sha256 TEXT,
    verified_sha256 BOOLEAN NOT NULL DEFAULT false,
    migration_id UUID,
    actor_sub UUID NOT NULL,
    reason TEXT,
    signature TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB,
    CHECK (from_driver_id IS NULL OR change_type IN ('migrate','rollback'))
);

CREATE INDEX IF NOT EXISTS driver_history_file_idx ON file.driver_history (file_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS driver_history_migration_idx ON file.driver_history (migration_id);
CREATE INDEX IF NOT EXISTS driver_history_to_driver_idx ON file.driver_history (to_driver_id);
CREATE INDEX IF NOT EXISTS driver_history_change_type_idx ON file.driver_history (change_type, verified_sha256);