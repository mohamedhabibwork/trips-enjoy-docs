package com.trips_enjoy.payment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The payment-service Spring Boot application. Bootstraps:
 *   * Spring Boot 4 (web + webmvc + actuator + security)
 *   * Spring Data JPA (PostgreSQL, per-service schema `payment`)
 *   * Spring Kafka (consumers + OutboxPublisher)
 *   * Spring Scheduling (OutboxPublisher 200ms + PartitionMaintenanceJob daily)
 *   * platform-spring-boot-starter (Keycloak JWT + RFC 7807 envelope +
 *     error envelope + observability + Kafka + caching + audit + api-docs
 *     + money + lookup + test + autoconfigure + umbrella)
 *
 * Per docs/services/payment-service/INTEGRATION.md §1 the public REST
 * surface is mounted under `/v1/payment-intents`, `/v1/wallets`,
 * `/v1/drivers`, `/v1/courier-earnings`, `/v1/merchant-settlements`,
 * plus `/admin/v1/payments`. Health endpoints are exposed by Spring
 * Boot Actuator at `/actuator/health` (K8s liveness + readiness).
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.trips_enjoy.payment.domain"])
@EnableScheduling
class PaymentServiceApplication

fun main(args: Array<String>) {
    runApplication<PaymentServiceApplication>(*args)
}