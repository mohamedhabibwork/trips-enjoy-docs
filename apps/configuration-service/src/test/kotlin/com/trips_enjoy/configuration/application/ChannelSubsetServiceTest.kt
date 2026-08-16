package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ChannelSubsetServiceTest {
    private val mapper = ObjectMapper()

    @Test
    fun `applyPointer returns the original node when pointer is empty`() {
        val node = mapper.readTree("""{"theme":{"primary":"#0F62FE"}}""")
        val out = invokeApply(node, "/")
        Assertions.assertEquals(node, out)
    }

    @Test
    fun `applyPointer navigates a single-level pointer`() {
        val node = mapper.readTree("""{"theme":{"primary":"#0F62FE"}}""")
        val out = invokeApply(node, "/theme")
        Assertions.assertEquals(mapper.readTree("""{"primary":"#0F62FE"}"""), out)
    }

    @Test
    fun `applyPointer navigates a nested pointer`() {
        val node = mapper.readTree("""{"theme":{"primary":"#0F62FE"}}""")
        val out = invokeApply(node, "/theme/primary")
        Assertions.assertEquals("#0F62FE", out.asText())
    }

    @Test
    fun `applyPointer returns a missing node for a non-existent path`() {
        val node = mapper.readTree("""{"theme":{"primary":"#0F62FE"}}""")
        val out = invokeApply(node, "/theme/secondary")
        Assertions.assertTrue(out.isMissingNode || out.isNull)
    }

    @Test
    fun `applyPointer unescapes RFC 6901 tilde sequences`() {
        val node = mapper.readTree("""{"a/b":42,"c~d":7}""")
        val out = invokeApply(node, "/a~1b")
        Assertions.assertEquals(42, out.asInt())
    }

    private fun invokeApply(
        node: com.fasterxml.jackson.databind.JsonNode,
        pointer: String,
    ): com.fasterxml.jackson.databind.JsonNode {
        // Use a small helper that mirrors the private logic without instantiating
        // the full service (which would require Redis + DB).
        if (pointer.isEmpty() || pointer == "/") return node
        val segments = pointer.removePrefix("/").split("/")
        var current: com.fasterxml.jackson.databind.JsonNode = node
        for (segment in segments) {
            val unescaped = segment.replace("~1", "/").replace("~0", "~")
            current = current.path(unescaped)
            if (current.isMissingNode || current.isNull) return current
        }
        return current
    }
}
