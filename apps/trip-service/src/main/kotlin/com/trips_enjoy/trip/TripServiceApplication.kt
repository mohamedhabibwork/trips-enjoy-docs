package com.trips_enjoy.trip

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.trips_enjoy.trip.domain"])
@EnableScheduling
class TripServiceApplication

fun main(args: Array<String>) {
    runApplication<TripServiceApplication>(*args)
}