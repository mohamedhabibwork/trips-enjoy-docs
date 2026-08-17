-- V1: create the `driver` schema.
-- Idempotent. Per docs/architecture/DATABASE_ARCHITECTURE.md:102 the schema
-- name is the snake_case form of the service name. Domain tables (per
-- docs/services/driver-service/ERD.md) will land as V2/V3 in subsequent migrations.
--
-- Note: Spring Boot also pre-creates this via
-- spring.datasource.hikari.connection-init-sql — both are
-- CREATE SCHEMA IF NOT EXISTS so the operation is race-safe and idempotent.
CREATE SCHEMA IF NOT EXISTS driver;
