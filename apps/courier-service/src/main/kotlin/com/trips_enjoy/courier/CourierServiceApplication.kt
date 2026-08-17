package com.trips_enjoy.courier

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Phase C (platform DRY): `@EnableJpaAuditing` is added here so the
 * `PlatformAuditorAware` (JWT `sub`) populates the BaseEntity
 * `createdBy` / `updatedBy` audit columns on the simple-PK +
 * audit entities.
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.trips_enjoy.courier.domain"])
@EnableJpaAuditing
@EnableScheduling
class CourierServiceApplication

fun main(args: Array<String>) {
    runApplication<CourierServiceApplication>(*args)
}