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
 *
 * Phase C (platform DRY): the audit fields (`id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, `deletedAt`) are
 * inherited from `BaseEntity`. `version` is the optimistic-lock counter
 * (formerly `rowVersion`); `createdBy` / `updatedBy` are populated by
 * `PlatformAuditorAware` from the JWT `sub` and stored as `String?`.
 * The `Restaurant.id` accessor is now `UUID?` (auto-generated via
 * `@UuidGenerator`); the helper `restaurantId(restaurant)` enforces
 * non-null after save.
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
        val restaurant = Restaurant(
            merchantId = merchantId,
            name = name,
            slug = slug,
            type = type,
            description = description,
        )
        restaurantRepository.save(restaurant)
        val restaurantId = restaurantId(restaurant)
        writeAudit(
            restaurantId = restaurantId,
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
            mapOf("restaurant_id" to restaurantId.toString()),
            createdBy,
            Instant.now(),
        )
        emitEvent(restaurantId, "restaurant.created.v1", correlationId, createdBy, mapOf(
            "restaurant_id" to restaurantId.toString(),
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
        val restaurant = requireNotErased(restaurantId)
        val before = mapOf("name" to restaurant.name, "description" to restaurant.description)
        name?.let { restaurant.name = it }
        description?.let { restaurant.description = it }
        logoFileId?.let { restaurant.logoFileId = it }
        coverFileId?.let { restaurant.coverFileId = it }
        autoOfflineEnabled?.let { restaurant.autoOfflineEnabled = it }
        val rid = restaurantId(restaurant)
        writeAudit(rid, "update", actingUser, "owner", null, restaurant.state, correlationId, null, null)
        emitEvent(rid, "restaurant.updated.v1", correlationId, actingUser, before)
        return restaurant
    }

    @Transactional
    fun submit(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.submit(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_SUBMIT, actingUser, "owner", null, restaurant.state, correlationId, "submitted_for_review", null)
        emitEvent(rid, "restaurant.submitted.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun approve(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.approve(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_APPROVE, actingUser, "admin", null, restaurant.state, correlationId, "approved_by_admin", null)
        emitEvent(rid, "restaurant.approved.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun reject(restaurantId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.reject(reason, actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_REJECT, actingUser, "admin", null, restaurant.state, correlationId, "rejected_by_admin", reason)
        emitEvent(rid, "restaurant.rejected.v1", correlationId, actingUser, mapOf("state" to restaurant.state, "reason" to reason))
        return restaurant
    }

    @Transactional
    fun goOnline(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.goOnline(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_ONLINE, actingUser, "owner", null, restaurant.state, correlationId, "merchant_toggled_online", null)
        emitEvent(rid, "restaurant.online.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun goOffline(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.goOffline(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_OFFLINE, actingUser, "owner", null, restaurant.state, correlationId, "merchant_toggled_offline", null)
        emitEvent(rid, "restaurant.offline.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun suspend(restaurantId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.suspend(reason, actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_SUSPEND, actingUser, "admin", null, restaurant.state, correlationId, "suspended_by_admin", reason)
        emitEvent(rid, "restaurant.suspended.v1", correlationId, actingUser, mapOf("state" to restaurant.state, "reason" to reason))
        return restaurant
    }

    @Transactional
    fun reinstate(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.reinstate(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_REINSTATE, actingUser, "admin", null, restaurant.state, correlationId, "reinstated_by_admin", null)
        emitEvent(rid, "restaurant.reinstated.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun close(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.close(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_CLOSE, actingUser, "owner", null, restaurant.state, correlationId, "closed_by_owner", null)
        emitEvent(rid, "restaurant.closed.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun resubmit(restaurantId: UUID, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.resubmit(actingUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_RESUBMIT, actingUser, "owner", null, restaurant.state, correlationId, "resubmitted_for_review", null)
        emitEvent(rid, "restaurant.resubmitted.v1", correlationId, actingUser, mapOf("state" to restaurant.state))
        return restaurant
    }

    @Transactional
    fun cascadeSuspend(restaurantId: UUID, reason: String, correlationId: UUID, systemUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.cascadeSuspend(reason, systemUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_MERCHANT_SUSPEND_CASCADE, systemUser, "system", null, restaurant.state, correlationId, "merchant_suspend_cascade", reason)
        emitEvent(rid, "restaurant.suspended.v1", correlationId, systemUser, mapOf("state" to restaurant.state, "cascade" to true, "reason" to reason))
        return restaurant
    }

    @Transactional
    fun cascadeReinstate(restaurantId: UUID, correlationId: UUID, systemUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.cascadeReinstate(systemUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_MERCHANT_REINSTATE_CASCADE, systemUser, "system", null, restaurant.state, correlationId, "merchant_reinstate_cascade", null)
        emitEvent(rid, "restaurant.reinstated.v1", correlationId, systemUser, mapOf("state" to restaurant.state, "cascade" to true))
        return restaurant
    }

    @Transactional
    fun cascadeClose(restaurantId: UUID, correlationId: UUID, systemUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.cascadeClose(systemUser, now)
        val rid = restaurantId(restaurant)
        writeAudit(rid, RestaurantAuditLog.ACTION_MERCHANT_CLOSE_CASCADE, systemUser, "system", null, restaurant.state, correlationId, "merchant_close_cascade", null)
        emitEvent(rid, "restaurant.closed.v1", correlationId, systemUser, mapOf("state" to restaurant.state, "cascade" to true))
        return restaurant
    }

    @Transactional
    fun applyRating(restaurantId: UUID, rating: BigDecimal, correlationId: UUID, actingUser: UUID): Restaurant {
        val now = Instant.now()
        val restaurant = requireNotErased(restaurantId)
        restaurant.applyRating(rating, now)
        val rid = restaurantId(restaurant)
        emitEvent(rid, "restaurant.rating.added.v1", correlationId, actingUser, mapOf(
            "rating" to rating.toDouble(),
            "new_avg_rating" to restaurant.avgRating.toDouble(),
            "new_review_count" to restaurant.reviewCount,
        ))
        return restaurant
    }

    @Transactional
    fun addCuisine(restaurantId: UUID, cuisine: String, correlationId: UUID, actingUser: UUID): RestaurantCuisine {
        val restaurant = requireNotErased(restaurantId)
        val rid = restaurantId(restaurant)
        val c = RestaurantCuisine(
            restaurantId = rid,
            cuisine = cuisine,
        )
        cuisineRepository.save(c)
        emitEvent(rid, "restaurant.cuisine.added.v1", correlationId, actingUser, mapOf("cuisine" to cuisine))
        return c
    }

    @Transactional
    fun addTag(restaurantId: UUID, tag: String, correlationId: UUID, actingUser: UUID): RestaurantTag {
        val restaurant = requireNotErased(restaurantId)
        val rid = restaurantId(restaurant)
        val t = RestaurantTag(
            restaurantId = rid,
            tag = tag,
        )
        tagRepository.save(t)
        emitEvent(rid, "restaurant.tag.added.v1", correlationId, actingUser, mapOf("tag" to tag))
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

    /**
     * Returns the [Restaurant.id] UUID, asserting non-null. The id is
     * auto-populated by the `@UuidGenerator` on [BaseEntity] after the
     * entity is persisted, so this helper is the single place that
     * enforces the post-save invariant.
     */
    private fun restaurantId(restaurant: Restaurant): UUID =
        requireNotNull(restaurant.id) { "Restaurant.id must be assigned after persist" }
}
