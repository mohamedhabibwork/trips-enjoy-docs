-- 000012_outbox_inbox.down.sql
DROP INDEX IF EXISTS file.inbox_received_idx;
DROP TABLE IF EXISTS file.inbox;
DROP INDEX IF EXISTS file.outbox_event_name_idx;
DROP INDEX IF EXISTS file.outbox_pending_idx;
DROP TABLE IF EXISTS file.outbox;