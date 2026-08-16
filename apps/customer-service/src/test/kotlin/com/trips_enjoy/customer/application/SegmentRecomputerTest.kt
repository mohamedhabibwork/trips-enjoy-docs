package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerAuditLogRepository
import com.trips_enjoy.customer.domain.CustomerRepository
import com.trips_enjoy.customer.domain.CustomerSegmentHistoryRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Instant
import java.util.UUID

class SegmentRecomputerTest {
    private val customerRepository: CustomerRepository = mock()
    private val segmentHistoryRepository: CustomerSegmentHistoryRepository = mock()
    private val auditLogRepository: CustomerAuditLogRepository = mock()
    private val readService: CustomerReadService = mock()
    private val eventPublisher: EventPublisher = mock()
    private val mapper = ObjectMapper()
    private val recomputer =
        SegmentRecomputer(
            customerRepository = customerRepository,
            segmentHistoryRepository = segmentHistoryRepository,
            auditLogRepository = auditLogRepository,
            readService = readService,
            eventPublisher = eventPublisher,
            mapper = mapper,
            frequentRides = 20,
            vipLtvMinor = 1_000_000L,
            churnedIdleDays = 90,
        )

    @Test
    fun `computeSegment returns vip when ltv crosses the vip threshold`() {
        val customer = newCustomer(ltvMinor = 1_000_000L, ridesThisMonth = 5)
        Assertions.assertEquals("vip", recomputer.computeSegment(customer))
    }

    @Test
    fun `computeSegment returns frequent when rides_this_month meets the threshold`() {
        val customer = newCustomer(ltvMinor = 500L, ridesThisMonth = 20)
        Assertions.assertEquals("frequent", recomputer.computeSegment(customer))
    }

    @Test
    fun `computeSegment returns churned when last_active_at is older than the idle threshold`() {
        val customer = newCustomer(
            ltvMinor = 100L,
            ridesThisMonth = 0,
            lastActiveAt = Instant.now().minusSeconds(91L * 86_400),
        )
        Assertions.assertEquals("churned", recomputer.computeSegment(customer))
    }

    @Test
    fun `computeSegment returns standard by default`() {
        val customer = newCustomer(ltvMinor = 0L, ridesThisMonth = 0)
        Assertions.assertEquals("standard", recomputer.computeSegment(customer))
    }

    private fun newCustomer(
        ltvMinor: Long,
        ridesThisMonth: Int,
        lastActiveAt: Instant? = null,
    ): Customer {
        val now = Instant.now()
        return Customer(
            id = UUID.randomUUID(),
            identityId = UUID.randomUUID(),
            ltvMinor = ltvMinor,
            ridesThisMonth = ridesThisMonth,
            lastActiveAt = lastActiveAt,
            createdAt = now,
            updatedAt = now,
            createdBy = UUID.randomUUID(),
            updatedBy = UUID.randomUUID(),
        )
    }
}
