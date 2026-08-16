-- 000007_outbox_inbox.down.sql
DROP INDEX IF EXISTS geolocation.inbox_consumer_received_idx;
DROP TABLE IF EXISTS geolocation.inbox;
DROP INDEX IF EXISTS geolocation.outbox_topic_pub_idx;
DROP INDEX IF EXISTS geolocation.outbox_poller_idx;
DROP TABLE IF EXISTS geolocation.outbox;