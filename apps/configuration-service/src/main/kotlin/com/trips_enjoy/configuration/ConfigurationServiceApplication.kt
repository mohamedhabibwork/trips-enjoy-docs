package com.trips_enjoy.configuration

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ConfigurationServiceApplication

fun main(args: Array<String>) {
    runApplication<ConfigurationServiceApplication>(*args)
}
