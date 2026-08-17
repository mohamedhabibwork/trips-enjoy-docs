package com.trips_enjoy.audit.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.application.AuditIngestService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Master consumer for every audit-relevant topic in INTEGRATION §4.1. A single
 * listener (group `audit-service`) reads the entire surface and delegates to
 * [AuditIngestService.ingest] which is idempotent on `event_id` (SRS §15).
 *
 * DLQ behaviour: poison events (deserialize failure) are routed to the
 * platform DLQ topic by Spring Kafka's `DeadLetterPublishingRecoverer`
 * (configured in `KafkaErrorHandlingConfiguration` below). Deserialization
 * failures don't propagate to the application code, so we simply log them
 * here. Application-level failures (e.g. DB unavailable) bubble up and trigger
 * Spring Kafka's container-level error handler, which retries with backoff and
 * then routes to the DLQ.
 */
@Component
class AuditEventConsumer(
    private val objectMapper: ObjectMapper,
    private val ingestService: AuditIngestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            // admin / auth
            "admin.action.performed",
            // payments
            "payment.attempted", "payment.authorized", "payment.captured", "payment.failed",
            "payment.refund.initiated", "payment.refund.completed",
            // wallet
            "wallet.credited", "wallet.debited", "wallet.held", "wallet.released",
            // ledger
            "ledger.posted",
            // trips
            "trip.started", "trip.arrived", "trip.completed", "trip.cancelled",
            "trip.reward.granted", "trip.reward.reversed",
            // ride requests / dispatch
            "ride.request.created", "ride.request.matched", "ride.request.cancelled", "ride.request.expired",
            "dispatch.matched", "dispatch.no_driver",
            // food / delivery
            "food.order.placed", "food.order.accepted", "food.order.rejected",
            "food.order.preparing", "food.order.ready", "food.order.cancelled",
            "delivery.pickup", "delivery.in_transit", "delivery.completed", "delivery.failed",
            // identity / personas
            "identity.user.created", "identity.user.suspended", "identity.user.disabled",
            "customer.created", "customer.updated", "customer.suspended",
            "driver.created", "driver.approved", "driver.suspended",
            "courier.created", "courier.approved", "courier.suspended",
            "merchant.created", "merchant.approved", "merchant.suspended",
            "restaurant.created", "restaurant.approved", "restaurant.online", "restaurant.offline", "restaurant.suspended",
            // configuration
            "configuration.updated", "feature_flag.updated",
            // promotion / loyalty / review
            "promotion.created", "promotion.disabled", "promotion.redeemed",
            "loyalty.points.earned", "loyalty.points.burned", "loyalty.tier.changed",
            "review.submitted", "review.aggregated",
            // tax / pricing
            "tax.calculated", "tax.rule.updated",
            "pricing.quote.created",
            "pricing.rating_density.applied", "pricing.loyalty_discount.applied",
            "pricing.geo_config.updated",
            // notifications / comms / support
            "notification.sent", "notification.failed",
            "comms.sms.sent", "comms.email.sent", "comms.push.sent",
            "support.ticket.opened", "support.ticket.resolved",
            // fraud / file
            "fraud.risk.scored", "fraud.account.blocked",
            "file.uploaded", "file.scanned", "file.deleted",
            // zone events
            "zone.created", "zone.updated", "zone.archived",
        ],
        groupId = "\${audit-service.consumer.group-id:audit-service}",
        containerFactory = "auditKafkaListenerContainerFactory",
    )
    fun consume(
        @Payload payload: String,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        ack: Acknowledgment,
    ) {
        try {
            val envelope = objectMapper.readValue(payload, MAP_TYPE)
            ingestService.ingest(envelope, topic, partition, offset, null)
            ack.acknowledge()
        } catch (exception: Exception) {
            log.warn(
                "Audit ingest failed for topic={} partition={} offset={} event_id={}: {}",
                topic, partition, offset, safeEventId(payload), exception.message,
            )
            // Rethrow so the container's error handler routes to DLQ + retries.
            throw exception
        }
    }

    private fun safeEventId(payload: String): UUID? = try {
        objectMapper.readTree(payload).path("event_id").asText().takeIf { it.isNotBlank() }?.let(UUID::fromString)
    } catch (_: Exception) {
        null
    }

    companion object {
        private val MAP_TYPE = objectMapperType()
        private fun objectMapperType(): com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>> =
            object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
