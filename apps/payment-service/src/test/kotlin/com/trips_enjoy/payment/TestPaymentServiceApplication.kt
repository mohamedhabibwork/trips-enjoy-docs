package com.trips_enjoy.payment

import org.springframework.boot.fromApplication


fun main(args: Array<String>) {
	// Phase A (platform DRY): TestcontainersConfiguration deleted — the
	// canonical BaseIntegrationTest now supplies the Spring Boot context.
	fromApplication<PaymentServiceApplication>().run(*args)
}
