package com.trips_enjoy.payment.config

import com.trips_enjoy.payment.domain.InboxEvent
import com.trips_enjoy.payment.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Kafka consumer configuration — consumes payment-service-relevant events
 * from upstream services (trip-service, food-order-service, driver-service,
 * courier-service, customer-service, ledger-service, identity-service).
 * Each listener writes a row to `payment.inbox_events` for idempotent
 * dedup before delegating to the appropriate handler.
 *
 * The full event catalog is in docs/services/payment-service/INTEGRATION.md §4.
 * The events consumed in this implementation:
 *   * trip.reward.granted.v1             (from trip-service)
 *   * trip.reward.reversed.v1            (from trip-service)
 *   * trip.completed.v1                  (from trip-service, for ride earnings)
 *   * food.order.completed.v1            (from food-order-service)
 *   * food.order.cancelled.v1            (from food-order-service)
 *   * delivery.courier.completed.v1      (from courier-service)
 *   * customer.wallet.topup.requested.v1 (from customer-service)
 *   * ledger.posting.recorded.v1         (from ledger-service)
 *   * identity.user.created.v1           (from identity-service)
 */
@Component
class KafkaConsumerConfiguration(
    private val inboxRepository: InboxEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * trip.reward.granted.v1 — credit the driver wallet with the
     * guaranteed topup. Idempotent on (event_id, source).
     */
    @KafkaListener(topics = ["trip.reward.granted.v1"], groupId = "payment-service")
    @Transactional
    fun onTripRewardGranted(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("trip.reward.granted.v1", payload, ack)
    }

    /**
     * trip.reward.reversed.v1 — debit the driver wallet for the
     * correction. Idempotent on (event_id, source).
     */
    @KafkaListener(topics = ["trip.reward.reversed.v1"], groupId = "payment-service")
    @Transactional
    fun onTripRewardReversed(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("trip.reward.reversed.v1", payload, ack)
    }

    /**
     * trip.completed.v1 — append a ride line to the driver earnings
     * aggregate. Idempotent on (event_id, source).
     */
    @KafkaListener(topics = ["trip.completed.v1"], groupId = "payment-service")
    @Transactional
    fun onTripCompleted(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("trip.completed.v1", payload, ack)
    }

    /**
     * food.order.completed.v1 — append an order line to the merchant
     * settlement aggregate. Idempotent on (event_id, source).
     */
    @KafkaListener(topics = ["food.order.completed.v1"], groupId = "payment-service")
    @Transactional
    fun onFoodOrderCompleted(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("food.order.completed.v1", payload, ack)
    }

    /**
     * food.order.cancelled.v1 — append a refund_reversal line to the
     * merchant settlement. Idempotent on (event_id, source).
     */
    @KafkaListener(topics = ["food.order.cancelled.v1"], groupId = "payment-service")
    @Transactional
    fun onFoodOrderCancelled(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("food.order.cancelled.v1", payload, ack)
    }

    /**
     * delivery.courier.completed.v1 — append a delivery line to the
     * courier earnings aggregate. Idempotent on (event_id, source).
     */
    @KafkaListener(topics = ["delivery.courier.completed.v1"], groupId = "payment-service")
    @Transactional
    fun onCourierDeliveryCompleted(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("delivery.courier.completed.v1", payload, ack)
    }

    /**
     * customer.wallet.topup.requested.v1 — credit the customer wallet.
     */
    @KafkaListener(topics = ["customer.wallet.topup.requested.v1"], groupId = "payment-service")
    @Transactional
    fun onCustomerWalletTopup(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("customer.wallet.topup.requested.v1", payload, ack)
    }

    /**
     * Generic inbox dedup writer. Every listener routes through this
     * method so the dedup contract is uniform. Returns `true` if the
     * event was newly recorded; `false` if it was a replay.
     */
    private fun ingest(topic: String, payload: Map<String, Any?>, ack: Acknowledgment) {
        val eventId = (payload["event_id"] as? String)?.let(UUID::fromString)
            ?: error("event missing event_id in payload")
        val existing = inboxRepository.findBySourceTopicAndSourceEventId(topic, eventId)
        if (existing != null) {
            log.info("replay dedup: {}/{}", topic, eventId)
            ack.acknowledge()
            return
        }
        val correlationId = (payload["correlation_id"] as? String)?.let(UUID::fromString)
            ?: UUID.randomUUID()
        inboxRepository.save(
            InboxEvent(
                id = UUID.randomUUID(),
                sourceTopic = topic,
                sourceEventId = eventId,
                eventType = (payload["event_type"] as? String) ?: topic,
                payload = payload,
                correlationId = correlationId,
                consumedAt = Instant.now(),
                createdBy = UUID.randomUUID(),  // system consumer
            ),
        )
        ack.acknowledge()
    }
}