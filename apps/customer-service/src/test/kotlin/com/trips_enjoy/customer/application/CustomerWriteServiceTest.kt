package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerAuditLogRepository
import com.trips_enjoy.customer.domain.CustomerRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

class CustomerWriteServiceTest {
    private val customerRepository: CustomerRepository = mock()
    private val auditLogRepository: CustomerAuditLogRepository = mock()
    private val readService: CustomerReadService = mock()
    private val eventPublisher: EventPublisher = mock()
    private val mapper = ObjectMapper()
    private val service = CustomerWriteService(
        customerRepository = customerRepository,
        auditLogRepository = auditLogRepository,
        readService = readService,
        eventPublisher = eventPublisher,
        mapper = mapper,
    )

    @Test
    fun `create rejects duplicate identity with a CONFLICT`() {
        val identityId = UUID.randomUUID()
        whenever(readService.getByIdentityId(identityId)).thenReturn(stubCustomer(identityId = identityId))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.create(
                identityId = identityId,
                name = "Jane",
                email = null,
                phone = null,
                primaryCityId = null,
                actorId = UUID.randomUUID(),
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("CUSTOMER_EXISTS", exception.code)
        Assertions.assertEquals(409, exception.status.value())
    }

    @Test
    fun `updateProfile rejects version mismatch with CONFLICT`() {
        val customer = stubCustomer(version = 5L)
        val customerId = requireNotNull(customer.id)
        whenever(customerRepository.lockById(customerId)).thenReturn(Optional.of(customer))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.updateProfile(
                customerId = customerId,
                name = "Jane",
                email = null,
                phone = null,
                primaryCityId = null,
                expectedRowVersion = 4L,
                actorId = UUID.randomUUID(),
                actorType = "user",
                reason = null,
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("VERSION_CONFLICT", exception.code)
    }

    @Test
    fun `suspend rejects an already-suspended customer`() {
        val customer = stubCustomer(status = "suspended")
        val customerId = requireNotNull(customer.id)
        whenever(customerRepository.lockById(customerId)).thenReturn(Optional.of(customer))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.suspend(
                customerId = customerId,
                reason = "fraud",
                note = null,
                actorId = UUID.randomUUID(),
                actorType = "admin",
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("CUSTOMER_ALREADY_SUSPENDED", exception.code)
    }

    @Test
    fun `suspend rejects unknown reason with 400 VALIDATION_FAILED`() {
        val customer = stubCustomer(status = "active")
        val customerId = requireNotNull(customer.id)
        whenever(customerRepository.lockById(customerId)).thenReturn(Optional.of(customer))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.suspend(
                customerId = customerId,
                reason = "made_up",
                note = null,
                actorId = UUID.randomUUID(),
                actorType = "admin",
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("VALIDATION_FAILED", exception.code)
    }

    @Test
    fun `erase rejects an already-erased customer with CONFLICT`() {
        val customer = stubCustomer(status = "erased")
        val customerId = requireNotNull(customer.id)
        whenever(customerRepository.lockById(customerId)).thenReturn(Optional.of(customer))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.erase(
                customerId = customerId,
                legalBasis = "user_request",
                note = null,
                actorId = UUID.randomUUID(),
                actorType = "admin",
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("CUSTOMER_ERASED", exception.code)
    }

    @Test
    fun `reinstate rejects a customer that is not suspended`() {
        val customer = stubCustomer(status = "active")
        val customerId = requireNotNull(customer.id)
        whenever(customerRepository.lockById(customerId)).thenReturn(Optional.of(customer))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.reinstate(
                customerId = customerId,
                note = null,
                actorId = UUID.randomUUID(),
                actorType = "admin",
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("CUSTOMER_NOT_SUSPENDED", exception.code)
    }

    @Test
    fun `setDefaultPaymentMethod rejects an erased customer`() {
        val customer = stubCustomer(status = "erased")
        val customerId = requireNotNull(customer.id)
        whenever(customerRepository.lockById(customerId)).thenReturn(Optional.of(customer))
        val exception = Assertions.assertThrows(ApiException::class.java) {
            service.setDefaultPaymentMethod(
                customerId = customerId,
                paymentMethodId = UUID.randomUUID(),
                actorId = UUID.randomUUID(),
                actorType = "user",
                correlationId = UUID.randomUUID(),
            )
        }
        Assertions.assertEquals("CUSTOMER_ERASED", exception.code)
    }

    @Test
    fun `upsertFromIdentity is a no-op when the customer exists and claims are unchanged`() {
        val identityId = UUID.randomUUID()
        val existing = stubCustomer(identityId = identityId, name = "Jane", email = "jane@example.com")
        whenever(readService.getByIdentityId(identityId)).thenReturn(existing)
        val result = service.upsertFromIdentity(
            identityId = identityId,
            name = "Jane",
            email = "jane@example.com",
            phone = null,
            primaryCityId = null,
            actorId = UUID.randomUUID(),
            correlationId = UUID.randomUUID(),
        )
        Assertions.assertEquals(existing, result)
    }

    @Test
    fun `upsertFromIdentity creates a new customer when the identity is unknown`() {
        val identityId = UUID.randomUUID()
        whenever(readService.getByIdentityId(identityId)).thenReturn(null)
        whenever(customerRepository.save(any<Customer>())).thenAnswer { invocation ->
            val customer = invocation.arguments[0] as Customer
            customer.id = UUID.randomUUID()
            customer
        }
        val result = service.upsertFromIdentity(
            identityId = identityId,
            name = "Jane",
            email = null,
            phone = null,
            primaryCityId = null,
            actorId = UUID.randomUUID(),
            correlationId = UUID.randomUUID(),
        )
        // The newly created customer must carry the identity id we passed in.
        Assertions.assertEquals(identityId, result.identityId)
        Assertions.assertEquals("Jane", result.name)
    }

    private fun stubCustomer(
        identityId: UUID = UUID.randomUUID(),
        status: String = "active",
        version: Long = 1L,
        name: String? = null,
        email: String? = null,
    ): Customer {
        return Customer(
            identityId = identityId,
            name = name,
            email = email,
            status = status,
        ).apply {
            id = UUID.randomUUID()
            this.version = version
        }
    }

    @Suppress("unused")
    private val _ignored = listOf(mapper)
}
