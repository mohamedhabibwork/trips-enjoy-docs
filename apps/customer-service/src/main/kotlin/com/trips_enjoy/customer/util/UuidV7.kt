package com.trips_enjoy.customer.util

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Platform-mandated UUIDv7 primary-key generator (RFC 9562 §5.7).
 *
 * Delegates to `kotlin.uuid.Uuid.Companion.generateV7()` — the canonical
 * stdlib implementation since Kotlin 2.4 (per ADR-0015 + memory
 * `uber-uuid-v7-stdlib-adoption-2026-08-14`).
 */
@OptIn(ExperimentalUuidApi::class)
fun uuidV7(): UUID = Uuid.generateV7().toJavaUuid()
