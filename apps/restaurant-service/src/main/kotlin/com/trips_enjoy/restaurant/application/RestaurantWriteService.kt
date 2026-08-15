package com.trips_enjoy.restaurant.application

import com.trips_enjoy.restaurant.domain.IdempotencyKey
import com.trips_enjoy.restaurant.domain.OutboxEvent
import com.trips_enjoy.restaurant.domain.OutboxEventRepository
import com.trips_enjoy.restaurant.domain.Restaurant
import com.trips_enjoy.restaurant.domain.RestaurantAuditLog
import com.trips_enjoy.restaurant.domain.RestaurantAuditLogRepository
import com.trips_enjoy.restaurant.domain.RestaurantCuisine
import com.trips_enjoy.restaurant.domain.RestaurantCuisineRepository
import com.trips_enjoy.restaurant.domain.RestaurantRepository
import com.trips_enjoy.restaurant.domain.RestaurantTag
import com.trips_enjoy.restaurant.domain.RestaurantTagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The restaurant write-service — encapsulates every state-machine
 * mutation (submit / approve / reject / online / offline / suspend /
 * reinstate / close / resubmit / cascade from merchant-level events),
 * rating line items, and cuisine/tag management. Every mutation is
 * idempotent on the Idempotency-Key, emits a row to
 * `restaurant_audit_log`, and writes one or more rows to `outbox_events`
 * for kafka publication.
 *
 * Mirrors the driver-service `DriverWriteService` pattern.
 */
@Service
class RestaurantWriteService(
    private val restaurantRepository: RestaurantRepository,
    private val cuisineRepository: RestaurantCuisineRepository,
    private val tagRepository: RestaurantTagRepository,
    private val auditLogRepository: RestaurantAuditLogRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {

    @Transactional
    fun create(
        merchantId: UUID,
        name: String,
        slug: String,
        type: String,
        description: String?,
        correlationId: UUID,
        createdBy: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): Restaurant {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_RESTAURANT_CREATE, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            return restaurantRepository.findBySlugAndDeletedAtIsNull(slug)
                ?: error("idempotency recorded but no restaurant for slug $slug")
        }
        val now = Instant.now()
        val restaurant = Restaurant(
            id = UUID.randomUUID(),
            merchantId = merchantId,
            name = name,
            slug = slug,
            type = type,
            description = description,
            createdAt = now,
            updatedAt = now,
            createdBy = createdBy,
            updatedBy = createdBy,
        )
        restaurantRepository.save(restaurant)
        writeAudit(
            restaurantId = restaurant.id,
            action = "create",
            actorKcSub = createdBy,
            actorType = "owner",
            fromState = null,
            toState = restaurant.state,
            correlationId = correlationId,
            reasonCode = null,
            reasonText = null,
        )
        idemService.record(
            IdempotencyKey.SCOPE_RESTAURANT_CREATE,
            idempotencyKey,
            requestHash,
            201,
            mapOf("restaurant_id" to restaurant.id.toString()),
            createdBy,
            now,
        )
        emitEvent(restaurant.id, "restaurant.created.v1", correlationId, createdBy, mapOf(
            "restaurant_id" to restaurant.id.toString(),
            "merchant_id" to merchantId.toString(),
            "state" to restaurant.state,
        ))
        return restaurant
    }

    @Transactional
    fun update(
        restaurantId: UUID,
        name: String?,
        description: String?,
        logoFileId: UUID?,
        coverFileId: UUID?,
        autoOfflineEnabled: Boolean?,
        correlationId: UUID,
        actingUser: UUID,
    ): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        val before = mapOf("name" to restaurant.name, "description" to restaurant.description)
        name?.let { restaurant.name = it }
        description?.let { restaurant.description = it }
        logoFileId?.let { restaurant.logoFileId = it }
        coverFileId?.let { restaurant.coverFileId = it }
        autoOfflineEnabled?.let { restaurant.autoOfflineEnabled = it }
        restaurant.updatedAt = now
        restaurant.rowVersion += 1
        writeAudit(restaurant.id, "update", actingUser, "owner", null, restaurant.state, correlationId, null, null)
        emitEvent(restaurant.id, "restaurant.updated.v1", correlationId, actingUser, before)
        return restaurant
    }

    @Transactional
    fun submit(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.submit(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_SUBMIT, actingUser, "owner", null, restaurant.state, correlationId, "submitted_for_review", null)
        emitEvent(restaurant.id, "restaurant.submitted.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun approve(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.approve(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_APPROVE, actingUser, "admin", null, restaurant.state, correlationId, "approved_by_admin", null)
        emitEvent(restaurant.id, "restaurant.approved.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun reject(restaurantId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.reject(reason, actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_REJECT, actingUser, "admin", null, restaurant.state, correlationId, "rejected_by_admin", reason)
        emitEvent(restaurant.id, "restaurant.rejected.v1", correlationId, actingUser, mapOf("state" to restaurant.state, "reason" to reason))
        return restaurant
    }

    @Transactional
    fun goOnline(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.goOnline(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_ONLINE, actingUser, "owner", null, restaurant.state, correlationId, "merchant_toggled_online", null)
        emitEvent(restaurant.id, "restaurant.online.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun goOffline(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.goOffline(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_OFFLINE, actingUser, "owner", null, restaurant.state, correlationId, "merchant_toggled_offline", null)
        emitEvent(restaurant.id, "restaurant.offline.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun suspend(restaurantId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.suspend(reason, actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_SUSPEND, actingUser, "admin", null, restaurant.state, correlationId, "suspended_by_admin", reason)
        emitEvent(restaurant.id, "restaurant.suspended.v1", correlationId, actingUser, mapOf("state" to restaurant.state, "reason" to reason))
        return restaurant
    }

    @Transactional
    fun reinstate(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.reinstate(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_REINSTATE, actingUser, "admin", null, restaurant.state, correlationId, "reinstated_by_admin", null)
        emitEvent(restaurant.id, "restaurant.reinstated.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun close(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.close(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_CLOSE, actingUser, "owner", null, restaurant.state, correlationId, "closed_by_owner", null)
        emitEvent(restaurant.id, "restaurant.closed.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun resubmit(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.resubmit(actingUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_RESUBMIT, actingUser, "owner", null, restaurant.state, correlationId, "resubmitted_for_review", null)
        emitEvent(restaurant.id, "restaurant.resubmitted.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun cascadeSuspend(restaurantId: UUID, reason: String, correlationId: UUID, systemUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.cascadeSuspend(reason, systemUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_MERCHANT_SUSPEND_CASCADE, systemUser, "system", null, restaurant.state, correlationId, "merchant_suspend_cascade", reason)
        emitEvent(restaurant.id, "restaurant.suspended.v1", correlationId, systemUser, mapOf("state" to restaurant.state, "cascade" to true, "reason" to reason))
        return restaurant
    }

    @Transactional
    fun cascadeReinstate(restaurantId: UUID, correlationId: UUID, systemUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.cascadeReinstate(systemUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_MERCHANT_REINSTATE_CASCADE, systemUser, "system", null, restaurant.state, correlationId, "merchant_reinstate_cascade", null)
        emitEvent(restaurant.id, "restaurant.reinstated.v1", correlationId, systemUser, mapOf("state" to restaurant.state, "cascade" to true))
        return restaurant
    }

    @Transactional
    fun cascadeClose(restaurantId: UUID, correlationId: UUID, systemUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.cascadeClose(systemUser, now)
        writeAudit(restaurant.id, RestaurantAuditLog.ACTION_MERCHANT_CLOSE_CASCADE, systemUser, "system", null, restaurant.state, correlationId, "merchant_close_cascade", null)
        emitEvent(restaurant.id, "restaurant.closed.v1", correlationId, systemUser, mapOf("state" to restaurant.state, "cascade" to true))
        return restaurant
    }

    @Transactional
    fun applyRating(restaurantId: UUID, rating: BigDecimal, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.applyRating(rating, now)
        emitEvent(restaurant.id, "restaurant.rating.added.v1", correlationId, actingUser, mapOf(
            "rating" to rating.toDouble(),
            "new_avg_rating" to restaurant.avgRating.toDouble(),
            "new_review_count" to restaurant.reviewCount,
        ))
        return restaurant
    }

    @Transactional
    fun addCuisine(restaurantId: UUID, cuisine: String, correlationId: UUID, actingUser: UUID): RestaurantCuisine {
        val restaurant = requireNotErased(restaurantId)
        val c = RestaurantCuisine(
            restaurantId = restaurant.id,
            cuisine = cuisine,
        )
        cuisineRepository.save(c)
        emitEvent(restaurant.id, "restaurant.cuisine.added.v1", correlationId, actingUser, mapOf("cuisine" to cuisine))
        return c
    }

    @Transactional
    fun addTag(restaurantId: UUID, tag: String, correlationId: UUID, actingUser: UUID): RestaurantTag {
        val restaurant = requireNotErased(restaurantId)
        val t = RestaurantTag(
            restaurantId = restaurant.id,
            tag = tag,
        )
        tagRepository.save(t)
        emitEvent(restaurant.id, "restaurant.tag.added.v1", correlationId, actingUser, mapOf("tag" to tag))
        return t
    }

    private fun requireNotErased(restaurantId: UUID): Restaurant {
        val restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
            ?: error("restaurant $restaurantId not found")
        check(restaurant.state != Restaurant.STATE_CLOSED) {
            "restaurant $restaurantId is closed"
        }
        return restaurant
    }

    private fun writeAudit(
        restaurantId: UUID,
        action: String,
        actorKcSub: UUID?,
        actorType: String,
        fromState: String?,
        toState: String?,
        correlationId: UUID,
        reasonCode: String?,
        reasonText: String?,
    ) {
        auditLogRepository.save(
            RestaurantAuditLog(
                id = UUID.randomUUID(),
                restaurantId = restaurantId,
                action = action,
                actorKcSub = actorKcSub,
                actorType = actorType,
                fromState = fromState,
                toState = toState,
                correlationId = correlationId,
                reasonCode = reasonCode,
                reasonText = reasonText,
            ),
        )
    }

    private fun emitEvent(
        restaurantId: UUID,
        eventType: String,
        correlationId: UUID,
        createdBy: UUID,
        payload: Map<String, Any?>,
    ) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Restaurant",
                aggregateId = restaurantId,
                eventType = eventType,
                topic = eventType,
                payload = payload,
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
    }
}