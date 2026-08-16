package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.enums.Channel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Notification-service runtime seeder per
 * docs/services/notification-service/TECH.md §3 ("Configuration Hot Reload")
 * and docs/architecture/CONFIGURATION_ARCHITECTURE.md.
 *
 * - Mirrors identity-service's `KeycloakSeeder` pattern: idempotent
 *   ApplicationRunner that runs once after the context is up.
 * - Catalogs the *runtime* config surface for notification-service that is
 *   hot-loaded from `configuration-service` after deployment (default_locale,
 *   channel priority, dedup window, whatsapp approval gate). It does NOT
 *   touch the database — Flyway V6 handles the template catalog seed at
 *   migration time. This loader primes in-process state.
 * - Disabled by default. Enable in non-production environments with
 *   `notification-service.seed.enabled=true`. The flag also gates
 *   auto-publish of all seed-catalog template versions (admin service
 *   expects templates to be published via POST /v1/admin/templates/{id}/publish).
 */
@Component
@ConditionalOnProperty(
	name = ["notification-service.seed.enabled"],
	havingValue = "true",
	matchIfMissing = false,
)
class NotificationSeeder(
	@Value("\${notification-service.seed.default-locale:en}") private val defaultLocale: String,
	@Value("\${notification-service.seed.fallback-locale:en}") private val fallbackLocale: String,
	@Value("\${notification-service.seed.dedup-window-seconds:60}") private val dedupWindowSeconds: Long,
	@Value("\${notification-service.seed.whatsapp.approval-required:true}") private val whatsappApprovalRequired: Boolean,
	@Value("\${notification-service.seed.whatsapp.window-24h-enforced:true}") private val whatsapp24hEnforced: Boolean,
	@Value("\${notification-service.seed.channel-priority:push,sms,email,in_app,whatsapp}") private val channelPriorityCsv: String,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun run(args: ApplicationArguments) {
		val priority = parseChannelPriority(channelPriorityCsv)
		log.info(
			"notification-service seed: default_locale={} fallback_locale={} dedup_window_seconds={} whatsapp_approval_required={} whatsapp_24h_enforced={} channel_priority={}",
			defaultLocale, fallbackLocale, dedupWindowSeconds, whatsappApprovalRequired, whatsapp24hEnforced, priority.joinToString(",") { it.value },
		)
		// Future hook: write these values into Redis so the configuration.updated
		// consumer can pick them up and reconcile; for the slice we only log them
		// because the Spring `@Value` bindings already expose them via the
		// `notification-service.*` property tree.
	}

	private fun parseChannelPriority(csv: String): List<Channel> =
		csv.split(',').asSequence().map(String::trim).filter(String::isNotBlank)
			.map(Channel::fromValue).distinct().toList()
}