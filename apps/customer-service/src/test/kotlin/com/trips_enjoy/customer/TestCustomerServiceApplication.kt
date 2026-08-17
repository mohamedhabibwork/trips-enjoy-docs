package com.trips_enjoy.customer

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	// Phase A (platform DRY): TestcontainersConfiguration deleted — the
	// platform auto-configuration now wires Testcontainers via the
	// spring-boot-starter umbrella. The `with(...)` slot is no longer needed.
	fromApplication<CustomerServiceApplication>().run(*args)
}
