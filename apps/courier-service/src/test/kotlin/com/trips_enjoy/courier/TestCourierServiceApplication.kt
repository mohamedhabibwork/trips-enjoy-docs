package com.trips_enjoy.courier

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<CourierServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
