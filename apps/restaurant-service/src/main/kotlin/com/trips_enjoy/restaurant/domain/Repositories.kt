package com.trips_enjoy.restaurant.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repositories for the restaurant-service aggregates.
 * Mirrors the customer-service + driver-service + courier-service
 * pattern.
 */

@Repository
interface RestaurantRepository : JpaRepository<Restaurant, UUID> {
    fun findByIdAndDeletedAtIsNull(id: UUID): Restaurant?
    fun findBySlugAndDeletedAtIsNull(slug: String): Restaurant?
    fun findByMerchantIdAndDeletedAtIsNull(merchantId: UUID): List<Restaurant>
    fun findByStateAndDeletedAtIsNull(state: String): List<Restaurant>
    fun findByOnlineTrueAndDeletedAtIsNull(): List<Restaurant>
}

@Repository
interface RestaurantCuisineRepository : JpaRepository<RestaurantCuisine, RestaurantCuisineKey> {
    fun findByRestaurantId(restaurantId: UUID): List<RestaurantCuisine>
    fun findByCuisine(cuisine: String): List<RestaurantCuisine>
}

@Repository
interface RestaurantTagRepository : JpaRepository<RestaurantTag, RestaurantTagKey> {
    fun findByRestaurantId(restaurantId: UUID): List<RestaurantTag>
    fun findByTag(tag: String): List<RestaurantTag>
}

@Repository
interface RestaurantAuditLogRepository : JpaRepository<RestaurantAuditLog, UUID> {
    fun findByRestaurantIdOrderByOccurredAtDesc(restaurantId: UUID): List<RestaurantAuditLog>
}

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, UUID> {
    fun findByScopeAndIdemKey(scope: String, idemKey: String): IdempotencyKey?
}

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL AND o.nextAttemptAt <= :now ORDER BY o.nextAttemptAt ASC")
    fun findPending(@Param("now") now: Instant, pageable: org.springframework.data.domain.Pageable): List<OutboxEvent>

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
    fun deletePublishedBefore(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface InboxEventRepository : JpaRepository<InboxEvent, UUID> {
    fun findBySourceTopicAndSourceEventId(sourceTopic: String, sourceEventId: UUID): InboxEvent?

    @Modifying
    @Query("DELETE FROM InboxEvent i WHERE i.consumedAt < :cutoff")
    fun deleteConsumedBefore(@Param("cutoff") cutoff: Instant): Int
}