package com.trips_enjoy.audit.util

import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Platform-mandated UUIDv7 primary-key generator (RFC 9562 §5.7).
 *
 * Delegates to `kotlin.uuid.Uuid.Companion.generateV7()` — the canonical
 * stdlib implementation since Kotlin 2.4 (per ADR-0015 +
 * docs/architecture/DATABASE_ARCHITECTURE.md §1). The stdlib encodes a
 * millisecond unix timestamp in the high 48 bits, fills the remaining
 * 80 random bits via CSPRNG (`java.security.SecureRandom` on the JVM),
 * and achieves strict monotonicity within an application lifetime using
 * RFC 9562 §6.2 (fixed-bit-length dedicated counter).
 */
fun uuidV7(): UUID = Uuid.generateV7().toJavaUuid()
