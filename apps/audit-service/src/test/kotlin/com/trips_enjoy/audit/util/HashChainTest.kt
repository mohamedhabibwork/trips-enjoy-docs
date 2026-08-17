package com.trips_enjoy.audit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HashChainTest {

    @Test
    fun `genesis hash is 64 zero hex chars`() {
        assertEquals(64, HashChain.GENESIS_HASH.length)
        assertTrue(HashChain.GENESIS_HASH.all { it == '0' })
    }

    @Test
    fun `next hash uses sha256 and is 64 hex chars`() {
        val hash = HashChain.nextHash(HashChain.GENESIS_HASH, "payload", "SHA-256")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `same prev hash and payload always produce the same hash`() {
        val a = HashChain.nextHash(HashChain.GENESIS_HASH, "payload")
        val b = HashChain.nextHash(HashChain.GENESIS_HASH, "payload")
        assertEquals(a, b)
    }

    @Test
    fun `different payload produces different hash`() {
        val a = HashChain.nextHash(HashChain.GENESIS_HASH, "payload-a")
        val b = HashChain.nextHash(HashChain.GENESIS_HASH, "payload-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `different prev hash produces different next hash`() {
        val payload = "payload"
        val a = HashChain.nextHash(HashChain.GENESIS_HASH, payload)
        val b = HashChain.nextHash("a".repeat(64), payload)
        assertNotEquals(a, b)
    }

    @Test
    fun `null prev hash defaults to genesis`() {
        val a = HashChain.nextHash(null, "payload")
        val b = HashChain.nextHash(HashChain.GENESIS_HASH, "payload")
        assertEquals(a, b)
    }

    @Test
    fun `canonicalize is deterministic and order-stable`() {
        val canonical = HashChain.canonicalize(
            eventId = "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
            eventName = "trip.completed.v1",
            schemaVersion = 1,
            occurredAtIso = "2026-08-01T10:00:00Z",
            producer = "trip-service",
            tenantId = "global",
            correlationId = "01HZX9C7T0XK2P9F0V6E4B1MZA",
            aggregateType = "Trip",
            aggregateId = "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
            subjectType = "trip",
            subjectId = "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
            dataJson = "{\"foo\":\"bar\"}",
        )
        assertNotNull(canonical)
        // First key in serialization is event_id; verify by construction.
        assertTrue(canonical.startsWith("{\"event_id\":\"01HZX9C8W6K0G3V2Y5N1Q4R7PB\""))
    }

    @Test
    fun `canonicalize escapes embedded quotes in string fields`() {
        val canonical = HashChain.canonicalize(
            eventId = "01HZX",
            eventName = "name-with-\"quote\"",
            schemaVersion = 1,
            occurredAtIso = "2026-08-01T10:00:00Z",
            producer = "svc",
            tenantId = "global",
            correlationId = "01HZX",
            aggregateType = "X",
            aggregateId = null,
            subjectType = null,
            subjectId = null,
            dataJson = "{}",
        )
        assertTrue(canonical.contains("\\\""))
    }
}
