package com.trips_enjoy.notification.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Operational endpoints per docs/shared/OBSERVABILITY.md §Health.
 *  - /health  liveness, no downstream checks
 *  - /ready   readiness including DB + Redis + Kafka
 *  - /started warm-up (post-migrations / warm-caches)
 *
 * Actuator's own `/actuator/health` and `/actuator/info` carry the same
 * information; this controller simply maps the canonical paths required by
 * docs/architecture/OBSERVABILITY.md for the gateway's `/health` checks.
 *
 * For this scaffold the controller returns a stub `200` body — the real
 * readiness wiring is delegated to Spring Boot Actuator's
 * `/actuator/health/readiness` and `/actuator/health/liveness` endpoints
 * which the gateway is configured to read.
 */
@RestController
@RequestMapping
class OperationalController {

	@GetMapping("/health")
	fun health(): ResponseEntity<Map<String, Any?>> =
		ResponseEntity.ok(mapOf("status" to "UP"))

	@GetMapping("/ready")
	fun ready(): ResponseEntity<Map<String, Any?>> =
		ResponseEntity.ok(mapOf("status" to "UP", "ready" to true))

	@GetMapping("/started")
	fun started(): ResponseEntity<Map<String, Any?>> =
		ResponseEntity.ok(mapOf("status" to "started"))
}