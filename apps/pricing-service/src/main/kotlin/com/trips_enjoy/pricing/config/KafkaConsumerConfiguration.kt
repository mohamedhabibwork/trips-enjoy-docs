package com.trips_enjoy.pricing.config

import com.trips_enjoy.pricing.application.PricingQuoteService
import com.trips_enjoy.pricing.domain.InboxEvent
import com.trips_enjoy.pricing.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Component
class KafkaConsumerConfiguration(
    private val inboxRepository: InboxEventRepository,
    private val pricingQuoteService: PricingQuoteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["zone.surge.updated.v1"], groupId = "pricing-service")
    @Transactional
    fun onSurgeUpdated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("zone.surge.updated.v1", payload, ack)
        @Suppress("UNCHECKED_CAST")
        val zoneId = (payload["zone_id"] as? String)?.let(UUID::fromString) ?: return
        val multiplier = (payload["multiplier"] as? Number)?.toDouble()?.toBigDecimal() ?: return
        pricingQuoteService.applySurge(zoneId, multiplier, Instant.now(), UUID.randomUUID())
    }

    @KafkaListener(topics = ["review.zone_aggregated.v1"], groupId = "pricing-service")
    @Transactional
    fun onReviewZoneAggregated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("review.zone_aggregated.v1", payload, ack)
        @Suppress("UNCHECKED_CAST")
        val zoneId = (payload["zone_id"] as? String)?.let(UUID::fromString) ?: return
        val windowMinutes = (payload["window_minutes"] as? Number)?.toInt() ?: 15
        val avgRating = (payload["avg_rating"] as? Number)?.toDouble()?.toBigDecimal() ?: return
        val sampleSize = (payload["sample_size"] as? Number)?.toInt() ?: 0
        pricingQuoteService.applyRatingDensity(zoneId, windowMinutes, avgRating, sampleSize, Instant.now(), UUID.randomUUID())
    }

    @KafkaListener(topics = ["loyalty.frequent_zone.aggregated.v1"], groupId = "pricing-service")
    @Transactional
    fun onLoyaltyFrequentAggregated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("loyalty.frequent_zone.aggregated.v1", payload, ack)
        @Suppress("UNCHECKED_CAST")
        val customerId = (payload["customer_id"] as? String)?.let(UUID::fromString) ?: return
        val zoneId = (payload["zone_id"] as? String)?.let(UUID::fromString) ?: return
        val tripCount = (payload["trip_count_30d"] as? Number)?.toInt() ?: 0
        val tier = (payload["tier"] as? String) ?: "silver"
        val qualifyingAt = Instant.now()
        val ttlUntil = qualifyingAt.plusSeconds(86400L * 30)
        pricingQuoteService.applyLoyaltyFrequent(customerId, zoneId, tripCount, tier, qualifyingAt, ttlUntil, UUID.randomUUID())
    }

    @KafkaListener(topics = ["pricing.geo_config.updated.v1"], groupId = "pricing-service")
    @Transactional
    fun onGeoConfigUpdated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("pricing.geo_config.updated.v1", payload, ack)
        @Suppress("UNCHECKED_CAST")
        val tenantId = (payload["tenant_id"] as? String) ?: "global"
        val cityId = payload["city_id"] as? String
        val originZoneId = (payload["origin_zone_id"] as? String)?.let(UUID::fromString)
        val destinationZoneId = (payload["destination_zone_id"] as? String)?.let(UUID::fromString)
        val rideType = payload["ride_type"] as? String
        val ruleKind = (payload["rule_kind"] as? String) ?: return
        @Suppress("UNCHECKED_CAST")
        val value = (payload["value"] as? Map<String, Any?>) ?: emptyMap()
        val priority = (payload["priority"] as? Number)?.toInt() ?: 100
        pricingQuoteService.upsertRuleBinding(
            tenantId = tenantId,
            cityId = cityId,
            originZoneId = originZoneId,
            destinationZoneId = destinationZoneId,
            rideType = rideType,
            ruleKind = ruleKind,
            value = value,
            priority = priority,
            effectiveFrom = null,
            effectiveTo = null,
            createdBy = UUID.randomUUID(),
        )
    }

    @KafkaListener(topics = ["configuration.updated.v1"], groupId = "pricing-service")
    @Transactional
    fun onConfigurationUpdated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("configuration.updated.v1", payload, ack)
    }

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
                createdBy = UUID.randomUUID(),
            ),
        )
        ack.acknowledge()
    }
}