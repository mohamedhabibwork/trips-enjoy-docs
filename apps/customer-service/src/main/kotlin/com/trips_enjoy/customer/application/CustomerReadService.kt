package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * Read path for customer-service (INTEGRATION.md §1.1 / §1.4).
 *
 * The dominant path is a single-row PK lookup by `customer_id`. The
 * platform-wide contract per NFR--002 is a P99 ≤ 30 ms read; the Redis
 * cache (TTL 5m) is a stampede-protection sentinel — presence of the
 * key implies a recent fetch, we still re-read the canonical row to
 * honour the soft-delete contract.
 *
 * Authorization: callers must hold either the X-User-Id == customerId
 * binding (self-service) or the `customer.read.any` service scope. The
 * controller layer is responsible for enforcing the rule; this service
 * simply returns the row.
 */
@Service
class CustomerReadService(
    private val customerRepository: CustomerRepository,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val cacheTtlSeconds: Long = 300L,
) {
    @Transactional(readOnly = true)
    fun get(customerId: UUID): Customer {
        val cacheKey = "customer:profile:$customerId"
        val cached = runCatching { redis.opsForValue().get(cacheKey) }.getOrNull()
        val customer =
            customerRepository.findById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.deletedAt != null) {
            throw ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId has been erased")
        }
        if (cached == null) {
            runCatching {
                redis.opsForValue().set(
                    cacheKey,
                    mapper.writeValueAsString(mapOf("id" to customer.id.toString())),
                    Duration.ofSeconds(cacheTtlSeconds),
                )
            }
        }
        return customer
    }

    @Transactional(readOnly = true)
    fun getByIdentityId(identityId: UUID): Customer? =
        customerRepository.findByIdentityIdAndDeletedAtIsNull(identityId).orElse(null)

    /**
     * Push-invalidate the cache entry after every write.
     */
    @Transactional
    fun invalidate(customerId: UUID) {
        redis.delete("customer:profile:$customerId")
    }
}
