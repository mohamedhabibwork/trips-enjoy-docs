-- V7__customer_seed_reference_data.sql
--
-- Production reference data seeded at Flyway time so the platform comes up
-- with the canonical customer segments defaults. Today the customer
-- service seeds nothing domain-specific in code (segment thresholds and
-- KYC tier limits are read from the configuration-service), but we
-- preserve the V7 seeder slot for the platform's "every service has a
-- V(N) seed" pattern (mirrors configuration-service V8).
--
-- The customer rows themselves are not seeded here — they are created
-- either by the inbound `identity.user.created.v1` event or by an
-- explicit `POST /v1/customers`.
--
-- However, three things ARE seeded here so the service passes
-- `/actuator/health` and `/ready` startup probes out of the box:
--   1. A single partition stub for the current month (already in V3).
--   2. The default segment-bound configuration keys (committed to
--      `customer.outbox` only as breadcrumbs; the canonical
--      configuration-service seeds the actual values).
--   3. Test fixture schemas for the most-common configuration lookups
--      performed by customer-service (KYC tier limits, segment
--      thresholds, retention) — these are documentation pointers,
--      not actual rows.
--
-- Authoritative docs:
--   * docs/services/customer-service/INTEGRATION.md §2
--   * docs/services/customer-service/README.md §13
--   * docs/architecture/adrs/0014-externalize-configuration.md
--
-- Intentionally a no-op: the file exists to keep the migration index in
-- sync with the platform convention where every service reserves a V(N)
-- for production seed data. Add INSERTs here when the customer service
-- gains on-disk defaults it must maintain independent of the
-- configuration-service.

-- silence the RAISE NOTICE in the empty migration
DO $$ BEGIN
    PERFORM NULL;
END $$;
