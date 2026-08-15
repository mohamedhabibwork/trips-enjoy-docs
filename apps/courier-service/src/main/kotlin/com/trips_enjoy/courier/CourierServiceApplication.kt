package com.trips_enjoy.courier

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.trips_enjoy.courier.domain"])
@EnableScheduling
class CourierServiceApplication

fun main(args: Array<String>) {
    runApplication<CourierServiceApplication>(*args)
}