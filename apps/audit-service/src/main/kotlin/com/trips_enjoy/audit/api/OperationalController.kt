package com.trips_enjoy.audit.api

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.contributor.Status
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Liveness / readiness per SRS §22. The actuator `/health` endpoint is also
 * exposed for richer detail; these flat endpoints mirror the platform
 * convention.
 */
@RestController
class OperationalController(private val health: HealthEndpoint) {
    @GetMapping("/health")
    fun health() = health.health()

    @GetMapping("/ready")
    fun ready(): ResponseEntity<Any> = status(health.health().status)

    @GetMapping("/started")
    fun started(): ResponseEntity<Any> = status(health.health().status)

    private fun status(status: Status): ResponseEntity<Any> =
        if (status == Status.UP) {
            ResponseEntity.ok(mapOf("status" to "UP"))
        } else {
            ResponseEntity.status(503).body(mapOf("status" to status.code))
        }
}
