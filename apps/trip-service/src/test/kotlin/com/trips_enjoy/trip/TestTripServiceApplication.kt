package com.trips_enjoy.trip

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<TripServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
