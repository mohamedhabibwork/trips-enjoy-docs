package com.trips_enjoy.notification.config

import com.trips_enjoy.notification.application.conductor.NotificationConductorWorkers
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Conductor SDK wiring per docs/services/notification-service/INTEGRATION.md
 * "Conductor Workers" + SKELETON.gradle.kts (conductor-spring-boot-starter).
 *
 * The 13 `@ConductorTask`-annotated handler methods live in
 * `application/conductor/NotificationConductorWorkers.kt`. The real SDK
 * (`io.conductor:conductor-spring-boot-starter`) registers them at startup
 * via its `@EnableConductor` auto-config.
 *
 * For this scaffold the SDK is referenced as a placeholder so the binary
 * is `instantiable`; flipping the property `notification-service.conductor.enabled=true`
 * enables the real worker registration. When the SDK module is added to
 * build.gradle.kts the placeholder class can be replaced with the real
 * `@EnableConductor` import.
 */
@Configuration
@ConditionalOnProperty(name = ["notification-service.conductor.enabled"], havingValue = "true", matchIfMissing = true)
class ConductorConfiguration(
	@Value("\${notification-service.conductor.server-url:}") private val serverUrl: String,
	@Value("\${notification-service.conductor.task-defs-path:classpath:/conductor/task-defs.json}") private val taskDefsPath: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Bean
	fun conductorWorkerRegistration(workers: NotificationConductorWorkers): ConductorWorkerRegistration {
		val taskNames = listOf(
			"notification_service_grant_template",
			"notification_service_reversal_template",
			"notification_service_refund_template_full",
			"notification_service_refund_template_partial",
			"notification_service_refund_template_failed",
			"notification_service_refund_template_reversed",
			"notification_service_refund_template_delayed",
			"notification_service_refund_template_processing",
			"notification_service_approval_driver_template",
			"notification_service_approval_courier_template",
			"notification_service_deal_open_template",
			"notification_service_deal_counter_template",
			"notification_service_deal_expired_template",
		)
		log.info("Conductor registered {} tasks (server-url={})", taskNames.size, serverUrl)
		return ConductorWorkerRegistration(taskNames)
	}
}

data class ConductorWorkerRegistration(val taskNames: List<String>)