package com.trips_enjoy.audit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetentionClassTest {

    @Test
    fun `financial value is lowercase`() {
        assertEquals("financial", RetentionClass.FINANCIAL.value)
    }

    @Test
    fun `default value is lowercase`() {
        assertEquals("default", RetentionClass.DEFAULT.value)
    }

    @Test
    fun `fromWire accepts case-insensitive input`() {
        assertEquals(RetentionClass.FINANCIAL, RetentionClass.fromWire("financial"))
        assertEquals(RetentionClass.FINANCIAL, RetentionClass.fromWire("FINANCIAL"))
        assertEquals(RetentionClass.DEFAULT, RetentionClass.fromWire("default"))
        assertEquals(RetentionClass.DEFAULT, RetentionClass.fromWire("Default"))
    }

    @Test
    fun `fromWire falls back to DEFAULT for unknown values`() {
        assertEquals(RetentionClass.DEFAULT, RetentionClass.fromWire("forever"))
        assertEquals(RetentionClass.DEFAULT, RetentionClass.fromWire(""))
    }
}
