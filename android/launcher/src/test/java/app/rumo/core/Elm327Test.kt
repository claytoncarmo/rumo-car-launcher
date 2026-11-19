package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {
    @Test
    fun readsPayloadWithSpacesAndEcho() {
        assertEquals(listOf(0x1A, 0xF8), Elm327.payload("010C\r41 0C 1A F8 \r\r>", 1, 0x0C))
        assertEquals(listOf(0x7B), Elm327.payload("41 05 7B\r\r>", 1, 0x05))
        // Sem espaços (ATS0)
        assertEquals(listOf(0x1A, 0xF8), Elm327.payload("410C1AF8\r>", 1, 0x0C))
    }

    @Test
    fun noPayloadWhenCarIsSilentOrWrongPid() {
        assertNull(Elm327.payload("NO DATA\r\r>", 1, 0x0C))
        assertNull(Elm327.payload("41 0D 20\r>", 1, 0x0C))
        assertNull(Elm327.payload("41 0C 1A F\r>", 1, 0x0C)) // meio byte
        assertNull(Elm327.payload("", 1, 0x0C))
        assertTrue(Elm327.carSilent("SEARCHING...\rUNABLE TO CONNECT\r>"))
        assertTrue(Elm327.carSilent("NO DATA"))
        assertFalse(Elm327.carSilent("41 0C 1A F8"))
    }

    @Test
    fun decodesValues() {
        assertEquals(1726, Elm327.rpm(listOf(0x1A, 0xF8)))
        assertEquals(83, Elm327.coolantC(listOf(0x7B)))
        assertEquals(-40, Elm327.coolantC(listOf(0x00)))
        assertEquals(60, Elm327.speedKmh(listOf(60)))
        assertEquals(50, Elm327.fuelPercent(listOf(128)))
        assertNull(Elm327.rpm(listOf(0x1A)))
        assertNull(Elm327.coolantC(emptyList()))
    }

    @Test
    fun listsSupportedPids() {
        // BE 1F A8 13: PIDs 01, 03, 04, 05, 06, 07, 0C, 0D, 0F, 10, 11, 13, 15, 1C, 1F, 20
        val pids = Elm327.supportedPids(listOf(0xBE, 0x1F, 0xA8, 0x13), 0x00)
        assertTrue(0x0C in pids && 0x0D in pids && 0x05 in pids && 0x20 in pids)
        assertFalse(0x02 in pids)
        assertEquals(emptyList<Int>(), Elm327.supportedPids(listOf(1, 2), 0))
        // Bloco seguinte: o primeiro PID é 0x21
        assertEquals(listOf(0x21), Elm327.supportedPids(listOf(0x80, 0, 0, 0), 0x20))
    }

    @Test
    fun readsBatteryVoltage() {
        assertEquals(12.6, Elm327.voltage("ATRV\r12.6V\r\r>")!!, 1e-9)
        assertEquals(14.0, Elm327.voltage("14V")!!, 1e-9)
        assertNull(Elm327.voltage("?"))
    }
}
