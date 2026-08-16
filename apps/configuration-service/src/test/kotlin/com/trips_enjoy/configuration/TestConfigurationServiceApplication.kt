package com.trips_enjoy.configuration

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<ConfigurationServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
