package com.trips_enjoy.configuration.util

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.UUID

class UuidV7Test {
    @Test
    fun `ids are unique across many calls`() {
        val seen = HashSet<UUID>()
        repeat(5_000) {
            Assertions.assertTrue(seen.add(uuidV7()), "duplicate UUID detected at iteration $it")
        }
    }

    @Test
    fun `ids are time-ordered (later calls produce lex-greater ids)`() {
        val first = uuidV7()
        Thread.sleep(5)
        val second = uuidV7()
        // The high 48 bits of UUIDv7 are the millisecond clock; later calls
        // MUST be lex-greater than earlier calls.
        Assertions.assertTrue(second > first, "expected $second > $first")
    }

    @Test
    fun `version and variant bits are set per RFC 9562`() {
        val id = uuidV7()
        // Version 7: the high nibble of the long at bit 12 (the 13th bit)
        // holds the version. In Java's two-long UUID, the 4 bits of the
        // version are at bits 12-15 of the `mostSignificantBits`.
        val version = (id.mostSignificantBits shr 12) and 0xF
        Assertions.assertEquals(7L, version, "version nibble must be 7")
        // Variant 10: the top two bits of `leastSignificantBits` are 10.
        val variant = (id.leastSignificantBits shr 62) and 0x3
        Assertions.assertEquals(2L, variant, "variant bits must be 10 (binary)")
    }
}
