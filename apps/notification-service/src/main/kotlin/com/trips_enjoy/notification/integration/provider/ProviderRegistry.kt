package com.trips_enjoy.notification.integration.provider

import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.domain.enums.Channel
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * Provider-registry per docs/services/notification-service/TECH.md §3.
 *
 *  - In-process, single-replica aware. Each channel has at least one driver;
 *    extension points allow fan-out / failover by registering more drivers.
 *  - Circuit state per channel lives here in-memory for the slice (placeholder
 *    for the upstream resilience4j integration); when all drivers for a
 *    channel are unhealthy the orchestrator falls back to the next channel.
 */
@Component
class ProviderRegistry(
	private val drivers: List<ProviderDriver>,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val byChannel: Map<Channel, List<ProviderDriver>> = drivers.groupBy { it.channel }

	fun firstHealthy(channel: Channel): ProviderDriver {
		val candidates = byChannel[channel].orEmpty().filter { it.healthy() }
		if (candidates.isEmpty()) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"CIRCUIT_OPEN",
				"No healthy provider driver for channel=${channel.value}",
			)
		}
		return candidates.first()
	}

	fun driverNames(): Map<Channel, List<String>> =
		byChannel.mapValues { (_, v) -> v.map { it.name } }

	fun healthy(): Boolean = drivers.any { it.healthy() }.also {
		log.debug("notification-service provider-registry healthy={}", it)
	}
}