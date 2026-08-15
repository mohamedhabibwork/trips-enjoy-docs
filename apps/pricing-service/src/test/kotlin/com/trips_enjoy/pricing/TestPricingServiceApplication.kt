package com.trips_enjoy.pricing

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<PricingServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
