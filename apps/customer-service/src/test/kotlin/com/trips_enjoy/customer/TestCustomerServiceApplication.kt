package com.trips_enjoy.customer

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<CustomerServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
