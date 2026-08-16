package com.trips_enjoy.identity

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<IdentityServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
