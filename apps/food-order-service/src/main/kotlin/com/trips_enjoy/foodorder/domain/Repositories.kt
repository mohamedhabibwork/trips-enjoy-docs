package com.trips_enjoy.foodorder.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface RequestRepository : JpaRepository<Request, UUID> {
    fun findByCustomerIdAndDeletedAtIsNullOrderByPlacedAtDesc(customerId: UUID): List<Request>
    fun findByRestaurantIdAndDeletedAtIsNullOrderByPlacedAtDesc(restaurantId: UUID): List<Request>
    fun findByIdempotencyKey(idempotencyKey: String): Request?
}

@Repository
interface OrderRepository : JpaRepository<Order, UUID> {
    fun findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(customerId: UUID): List<Order>
    fun findByRestaurantIdAndDeletedAtIsNullOrderByCreatedAtDesc(restaurantId: UUID): List<Order>
    fun findByCourierIdAndDeletedAtIsNullOrderByCreatedAtDesc(courierId: UUID): List<Order>
    fun findByRequestId(requestId: UUID): Order?
}

@Repository
interface OrderItemRepository : JpaRepository<OrderItem, UUID> {
    fun findByOrderId(orderId: UUID): List<OrderItem>
}

@Repository
interface OrderItemModifierRepository : JpaRepository<OrderItemModifier, UUID> {
    fun findByOrderItemId(orderItemId: UUID): List<OrderItemModifier>
}

@Repository
interface OrderItemAddonRepository : JpaRepository<OrderItemAddon, UUID> {
    fun findByOrderItemId(orderItemId: UUID): List<OrderItemAddon>
}

@Repository
interface OrderStateHistoryRepository : JpaRepository<OrderStateHistory, UUID> {
    fun findBySubjectIdOrderByOccurredAtDesc(subjectId: UUID): List<OrderStateHistory>
}

@Repository
interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, UUID> {
    fun findByScopeAndIdemKey(scope: String, idemKey: String): IdempotencyRecord?
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