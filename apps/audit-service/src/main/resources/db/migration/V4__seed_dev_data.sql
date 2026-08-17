-- V4__seed_dev_data.sql
-- Deterministic dev fixtures for audit-service.
--
-- Loaded by Flyway on every fresh schema (migrate subcommand + dev boot).
-- Skipped in production via the `audit-service.seed.enabled=false` profile
-- flag honored by AuditDevDataSeeder (see application/AuditDevDataSeeder.kt).
--
-- This migration is intentionally minimal — the immutable
-- `audit.events` rows are inserted by AuditDevDataSeeder at app boot so
-- the SHA-256 hash chain can be computed in code with the canonical
-- `HashChain.canonicalize()` function. Inserting placeholder rows here
-- and UPDATE-ing them after the fact is forbidden by the
-- `prevent_events_mutation` trigger from V2.
--
-- The fixtures here are the non-events side (which the trigger does
-- not guard):
--   - 5 inbox rows that mirror the 5 distinct event_ids the seeder
--     will insert, so a re-run of the Kafka consumer would see them
--     as duplicates (idempotency check)
--   - 3 litigation holds covering each selector type
--   - 3 read_log entries
--
-- All UUIDs are deterministic v7-shaped for reproducible tests.

-- =========================================================================
-- 1. audit.inbox — 5 rows so the consumer's dedup check fires
-- =========================================================================
INSERT INTO audit.inbox (event_id, topic, received_at) VALUES
('11111111-aaaa-7aaa-8aaa-000000000001', 'trip.completed',     '2026-08-01T10:00:01Z'),
('11111111-aaaa-7aaa-8aaa-000000000002', 'trip.started',       '2026-08-01T09:55:01Z'),
('11111111-aaaa-7aaa-8aaa-000000000003', 'payment.captured',   '2026-08-01T10:01:01Z'),
('11111111-aaaa-7aaa-8aaa-000000000004', 'ledger.posted',      '2026-08-01T10:01:31Z'),
('11111111-aaaa-7aaa-8aaa-000000000005', 'admin.action.performed', '2026-08-01T10:30:01Z');

-- =========================================================================
-- 2. audit.litigation_hold — 3 active holds covering each selector type
-- =========================================================================
INSERT INTO audit.litigation_hold (
    id, tenant_id, subject_type, subject_id, topic,
    reason, effective_from, effective_to, created_at, created_by
) VALUES
('22222222-2222-7222-8222-000000000001',
 'global', NULL, NULL, NULL,
 'Pending litigation: case #12345 — tenant-scoped hold for compliance review',
 '2026-08-01T00:00:00Z', NULL,
 '2026-08-01T10:00:00Z', '99999999-9999-7999-8999-000000000001'),

('22222222-2222-7222-8222-000000000002',
 NULL, 'customer', '11111111-cccc-7ccc-8ccc-000000000006', NULL,
 'Pending litigation: case #99999 — subject-scoped hold for fraud investigation',
 '2026-08-01T11:30:00Z', NULL,
 '2026-08-01T11:30:00Z', '99999999-9999-7999-8999-000000000001'),

('22222222-2222-7222-8222-000000000003',
 NULL, NULL, NULL, 'customer.suspended',
 'Pending litigation: case #77777 — topic-scoped hold for customer suspension review',
 '2026-08-01T12:00:00Z', NULL,
 '2026-08-01T12:00:00Z', '99999999-9999-7999-8999-000000000001');

-- =========================================================================
-- 3. audit.read_log — 3 historical reads
-- =========================================================================
INSERT INTO audit.read_log (
    id, actor_id, actor_ip, query, result_count, reason, correlation_id, created_at
) VALUES
('33333333-3333-7333-8333-000000000001',
 '99999999-9999-7999-8999-000000000001', '10.0.0.1',
 '{"topic":"trip.completed"}'::jsonb, 2,
 'Compliance review: case #12345',
 '44444444-4444-7444-8444-000000000001',
 '2026-08-01T11:00:00Z'),

('33333333-3333-7333-8333-000000000002',
 '99999999-9999-7999-8999-000000000002', '10.0.0.2',
 '{"subject_type":"customer","subject_id":"11111111-cccc-7ccc-8ccc-000000000006"}'::jsonb, 1,
 'Fraud investigation: case #99999',
 '44444444-4444-7444-8444-000000000002',
 '2026-08-01T12:00:00Z'),

('33333333-3333-7333-8333-000000000003',
 '99999999-9999-7999-8999-000000000001', '10.0.0.1',
 '{"tenant_id":"global","from":"2026-08-01T00:00:00Z"}'::jsonb, 7,
 'Monthly compliance attestation',
 '44444444-4444-7444-8444-000000000003',
 '2026-08-01T13:00:00Z');
