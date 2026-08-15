package com.trips_enjoy.restaurant

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<RestaurantServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
