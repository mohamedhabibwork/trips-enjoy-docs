package com.trips_enjoy.notification

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

/**
 * Notification-service entrypoint. Mirrors audit-service/identity-service:
 *
 *  - `@EnableScheduling` so OutboxPublisher, IdempotencyCleanupJob and
 *    ScheduledJobs all run. The platform-spring-boot-partition module
 *    (ADR-0029) provides `PartitionMaintenanceService` + health indicator
 *    with `@ConditionalOnMissingBean`, so no local cron is required.
 *  - The `migrate` arg shim supports Kubernetes Job deployments that only
 *    want to run Flyway and exit (used by the platform deployment chart).
 *
 * Cross-cutting concerns (security, correlation, error envelope, Jackson 2,
 * OpenAPI, Kafka) live under `config/`. Domain entities live under
 * `domain/`. Use cases under `application/`. REST controllers under `api/`.
 */
@SpringBootApplication
@EnableScheduling
class NotificationServiceApplication

fun main(args: Array<String>) {
	val context = runApplication<NotificationServiceApplication>(*args)
	if (args.firstOrNull() == "migrate") exitProcess(SpringApplication.exit(context))
}