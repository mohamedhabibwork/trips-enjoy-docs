package com.trips_enjoy.audit.application

import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.util.HashChain
import com.trips_enjoy.audit.util.uuidV7
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Dev-only seeder. Inserts a deterministic fixture set into the
 * immutable `audit.events` table on first boot, with a real SHA-256 hash
 * chain computed by [HashChain.canonicalize]. The rows are idempotent:
 * the seeder checks whether the marker row
 * (event_id = `11111111-aaaa-7aaa-8aaa-000000000001`) already exists and
 * short-circuits on subsequent boots.
 *
 * Activated only when:
 *   - `audit-service.seed.enabled=true` (dev profile sets true)
 *   - the active Spring profile is NOT one of `prod`, `stg`, `live`
 *     (defense in depth — never mutate the audit log in production).
 *
 * Runs at [Ordered.LOWEST_PRECEDENCE] so it fires AFTER Flyway (which
 * ran V4 to insert the inbox / litigation_hold / read_log fixtures)
 * and AFTER the JPA EntityManagerFactory is fully initialized.
 */
@Component
@ConditionalOnProperty(name = ["audit-service.seed.enabled"], havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)
class AuditDevDataSeeder(
    private val events: AuditEventRepository,
    @Value("\${spring.profiles.active:dev}") private val activeProfile: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (activeProfile in setOf("prod", "stg", "live")) {
            log.warn(
                "AuditDevDataSeeder is enabled but profile is '{}'; refusing to seed.",
                activeProfile,
            )
            return
        }

        val markerId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000001")
        if (events.findByEventId(markerId).isPresent) {
            log.info("AuditDevDataSeeder: seed events already present; skipping.")
            return
        }

        val now = Instant.now()
        val fixtures = buildFixtures(now)
        var prevHash: String? = null
        fixtures.forEach { fixture ->
            val canonical = HashChain.canonicalize(
                eventId = fixture.eventId.toString(),
                eventName = fixture.eventName,
                schemaVersion = fixture.schemaVersion,
                occurredAtIso = fixture.occurredAt.toString(),
                producer = fixture.producer,
                tenantId = fixture.tenantId,
                correlationId = fixture.correlationId.toString(),
                aggregateType = fixture.aggregateType,
                aggregateId = fixture.aggregateId?.toString(),
                subjectType = fixture.subjectType,
                subjectId = fixture.subjectId?.toString(),
                dataJson = fixture.dataJson,
            )
            val hash = HashChain.nextHash(prevHash, canonical, "sha256")
            events.save(
                AuditEvent(
                    id = fixture.id,
                    eventId = fixture.eventId,
                    eventName = fixture.eventName,
                    schemaVersion = fixture.schemaVersion,
                    occurredAt = fixture.occurredAt,
                    receivedAt = fixture.receivedAt,
                    producer = fixture.producer,
                    tenantId = fixture.tenantId,
                    correlationId = fixture.correlationId,
                    causationId = fixture.causationId,
                    aggregateType = fixture.aggregateType,
                    aggregateId = fixture.aggregateId,
                    subjectType = fixture.subjectType,
                    subjectId = fixture.subjectId,
                    data = fixture.dataJson,
                    headers = null,
                    topic = fixture.topic,
                    partition = fixture.partition,
                    offset = fixture.offset,
                    prevHash = prevHash,
                    hash = hash,
                    retentionClass = fixture.retentionClass,
                    litigationHold = false,
                    retentionUntil = fixture.retentionUntil,
                    createdAt = fixture.createdAt,
                ),
            )
            prevHash = hash
        }
        log.info("AuditDevDataSeeder: inserted {} events with valid hash chain (profile={})", fixtures.size, activeProfile)
    }

    private data class Fixture(
        val id: UUID,
        val eventId: UUID,
        val eventName: String,
        val schemaVersion: Int,
        val occurredAt: Instant,
        val receivedAt: Instant,
        val producer: String,
        val tenantId: String,
        val correlationId: UUID,
        val causationId: UUID? = null,
        val aggregateType: String,
        val aggregateId: UUID? = null,
        val subjectType: String,
        val subjectId: UUID? = null,
        val dataJson: String,
        val topic: String,
        val partition: Int,
        val offset: Long,
        val retentionClass: String,
        val retentionUntil: Instant? = null,
        val createdAt: Instant,
    )

    private fun buildFixtures(now: Instant): List<Fixture> {
        // Deterministic UUIDs so the seed is reproducible across runs.
        val baseTime = Instant.parse("2026-08-01T10:00:00Z")
        val baseTimePlus = Instant.parse("2026-08-01T10:00:01Z")
        val oldTime = Instant.parse("2025-01-15T10:00:00Z")
        val oldTimePlus = Instant.parse("2025-01-15T10:00:01Z")
        val customerId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000006")

        return listOf(
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000001"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000001"),
                eventName = "trip.completed.v1",
                schemaVersion = 1,
                occurredAt = baseTime,
                receivedAt = baseTimePlus,
                producer = "trip-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000001"),
                aggregateType = "Trip",
                aggregateId = customerId,
                subjectType = "trip",
                subjectId = customerId,
                dataJson = """{"amount_minor":1704,"currency":"EUR"}""",
                topic = "trip.completed",
                partition = 0,
                offset = 1L,
                retentionClass = "default",
                retentionUntil = baseTime.plusSeconds(31_536_000L),
                createdAt = baseTimePlus,
            ),
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000002"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000002"),
                eventName = "trip.started.v1",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-01T09:55:00Z"),
                receivedAt = Instant.parse("2026-08-01T09:55:01Z"),
                producer = "trip-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000002"),
                aggregateType = "Trip",
                aggregateId = customerId,
                subjectType = "trip",
                subjectId = customerId,
                dataJson = """{"lat":48.8566,"lng":2.3522}""",
                topic = "trip.started",
                partition = 0,
                offset = 2L,
                retentionClass = "default",
                retentionUntil = Instant.parse("2026-08-01T09:55:00Z").plusSeconds(31_536_000L),
                createdAt = Instant.parse("2026-08-01T09:55:01Z"),
            ),
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000003"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000003"),
                eventName = "payment.captured.v1",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-01T10:01:00Z"),
                receivedAt = Instant.parse("2026-08-01T10:01:01Z"),
                producer = "payment-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000003"),
                aggregateType = "PaymentIntent",
                aggregateId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000003"),
                subjectType = "payment_intent",
                subjectId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000003"),
                dataJson = """{"amount_minor":1704,"currency":"EUR","payment_method":"card"}""",
                topic = "payment.captured",
                partition = 0,
                offset = 1L,
                retentionClass = "financial",
                retentionUntil = Instant.parse("2033-08-01T10:01:00Z"),
                createdAt = Instant.parse("2026-08-01T10:01:01Z"),
            ),
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000004"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000004"),
                eventName = "ledger.posted.v1",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-01T10:01:30Z"),
                receivedAt = Instant.parse("2026-08-01T10:01:31Z"),
                producer = "ledger-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000003"),
                aggregateType = "LedgerEntry",
                aggregateId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000004"),
                subjectType = "ledger_entry",
                subjectId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000003"),
                dataJson = """{"debit_account":"1000_cash","credit_account":"4000_revenue","amount_minor":1704}""",
                topic = "ledger.posted",
                partition = 0,
                offset = 2L,
                retentionClass = "financial",
                retentionUntil = Instant.parse("2033-08-01T10:01:30Z"),
                createdAt = Instant.parse("2026-08-01T10:01:31Z"),
            ),
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000005"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000005"),
                eventName = "admin.action.performed.v1",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-01T10:30:00Z"),
                receivedAt = Instant.parse("2026-08-01T10:30:01Z"),
                producer = "admin-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000005"),
                aggregateType = "AdminAction",
                aggregateId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000005"),
                subjectType = "admin",
                subjectId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000005"),
                dataJson = """{"action":"grant_role","target":"platform.admin","actor":"super-admin"}""",
                topic = "admin.action.performed",
                partition = 0,
                offset = 1L,
                retentionClass = "default",
                retentionUntil = Instant.parse("2026-08-01T10:30:00Z").plusSeconds(31_536_000L),
                createdAt = Instant.parse("2026-08-01T10:30:01Z"),
            ),
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000006"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000006"),
                eventName = "customer.suspended.v1",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-01T11:00:00Z"),
                receivedAt = Instant.parse("2026-08-01T11:00:01Z"),
                producer = "customer-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000006"),
                aggregateType = "Customer",
                aggregateId = customerId,
                subjectType = "customer",
                subjectId = customerId,
                dataJson = """{"reason":"fraud","suspended_by":"security-oncall"}""",
                topic = "customer.suspended",
                partition = 0,
                offset = 1L,
                retentionClass = "default",
                retentionUntil = Instant.parse("2026-08-01T11:00:00Z").plusSeconds(31_536_000L),
                createdAt = Instant.parse("2026-08-01T11:00:01Z"),
            ),
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000007"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000007"),
                eventName = "pricing.quote.created.v1",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-01T09:50:00Z"),
                receivedAt = Instant.parse("2026-08-01T09:50:01Z"),
                producer = "pricing-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000007"),
                aggregateType = "PricingQuote",
                aggregateId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000007"),
                subjectType = "pricing_quote",
                subjectId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000007"),
                dataJson = """{"amount_minor":1704,"currency":"EUR","surge_multiplier":1.2}""",
                topic = "pricing.quote.created",
                partition = 0,
                offset = 1L,
                retentionClass = "default",
                retentionUntil = Instant.parse("2026-08-01T09:50:00Z").plusSeconds(31_536_000L),
                createdAt = Instant.parse("2026-08-01T09:50:01Z"),
            ),
            // Row 8 is a financial-class event past its 1-year default
            // window — used by the RetentionServiceTest scenarios.
            Fixture(
                id = UUID.fromString("11111111-1111-7111-8111-000000000008"),
                eventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000008"),
                eventName = "fraud.risk.scored.v1",
                schemaVersion = 1,
                occurredAt = oldTime,
                receivedAt = oldTimePlus,
                producer = "fraud-risk-service",
                tenantId = "global",
                correlationId = UUID.fromString("11111111-bbbb-7bbb-8bbb-000000000008"),
                aggregateType = "RiskScore",
                aggregateId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000008"),
                subjectType = "risk_score",
                subjectId = UUID.fromString("11111111-cccc-7ccc-8ccc-000000000008"),
                dataJson = """{"score":0.92,"tier":"high"}""",
                topic = "fraud.risk.scored",
                partition = 0,
                offset = 1L,
                retentionClass = "default",
                retentionUntil = Instant.parse("2025-02-15T10:00:00Z"),
                createdAt = oldTimePlus,
            ),
        )
    }
}
