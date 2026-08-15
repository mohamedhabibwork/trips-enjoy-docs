package com.trips_enjoy.driver

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<DriverServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
