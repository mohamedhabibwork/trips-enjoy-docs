package com.trips_enjoy.admin

import org.springframework.boot.fromApplication


fun main(args: Array<String>) {
	// Phase A (platform DRY): TestcontainersConfiguration deleted — the
	// platform auto-configuration wires Testcontainers via the spring-boot-starter
	// umbrella, so the `with(...)` slot is no longer needed.
	fromApplication<AdminServiceApplication>().run(*args)
}