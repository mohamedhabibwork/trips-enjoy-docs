package com.trips_enjoy.notification

import org.springframework.boot.fromApplication


fun main(args: Array<String>) {
	// Phase A (platform DRY): TestcontainersConfiguration deleted — the
	// platform auto-configuration now wires Testcontainers via the
	// spring-boot-starter umbrella. The `with(...)` slot is no longer needed.
	fromApplication<NotificationServiceApplication>().run(*args)
}