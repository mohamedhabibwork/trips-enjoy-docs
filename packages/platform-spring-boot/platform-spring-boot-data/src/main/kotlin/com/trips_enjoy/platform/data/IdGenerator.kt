package com.trips_enjoy.platform.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Canonical UUIDv7 id generator used by [BaseEntity] subclasses.
 *
 * UUIDv7 (RFC 9562) embeds a millisecond-precision timestamp in the
 * most significant bits, giving us time-ordered ids that are still
 * 128-bit opaque tokens from the database's perspective. This is the
 * platform-blessed shape for primary keys (DATA--002).
 *
 * Uses the Kotlin stdlib's `Uuid.generateV7()` and adapts to the JDK
 * `java.util.UUID` shape that JPA expects.
 */
object IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    fun newV7(): java.util.UUID = Uuid.generateV7().toJavaUuid()
}
