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

    @Test
    fun buildsGroupedRequests() {
        assertEquals("010C0D", Elm327.request(listOf(0x0C, 0x0D), oneReply = false))
        assertEquals("010C0D1", Elm327.request(listOf(0x0C, 0x0D), oneReply = true))
        assertEquals("010D1", Elm327.request(listOf(0x0D), oneReply = true))
    }

    @Test
    fun groupsFitInOneCanFrame() {
        // Rotação (2) + velocidade (1) = 41 0C xx xx 0D xx (6 bytes); ar (2) + pedal (1) = 6; carga, temperatura e tanque = 7
        assertEquals(
            listOf(listOf(0x0C, 0x0D), listOf(0x10, 0x49), listOf(0x04, 0x05, 0x2F)),
            Elm327.singleFrameGroups(listOf(0x0C, 0x0D, 0x10, 0x49, 0x04, 0x05, 0x2F)),
        )
        // PID de tamanho desconhecido fica de fora (vai sozinho)
        assertEquals(listOf(listOf(0x0D)), Elm327.singleFrameGroups(listOf(0x0D, 0xA6)))
        assertEquals(emptyList<List<Int>>(), Elm327.singleFrameGroups(emptyList()))
    }

    @Test
    fun readsSeveralPidsInOneAnswer() {
        // Quadro único (até 7 bytes): rotação 0B1A (710 rpm) e velocidade 32 (50 km/h)
        val one = Elm327.multiPayload("410C0B1A0D32\r\r>")
        assertEquals(listOf(0x0B, 0x1A), one[0x0C])
        assertEquals(listOf(0x32), one[0x0D])
        // Em quadros (CAN, mais de 7 bytes), com espaços e o enchimento depois do tamanho
        val multi = Elm327.multiPayload("00E\r0: 41 0C 0B 1A 0D 32\r1: 10 01 F4 49 25 04\r2: 33 AA AA AA AA AA\r\r>")
        assertEquals(listOf(0x01, 0xF4), multi[0x10])
        assertEquals(listOf(0x25), multi[0x49])
        assertEquals(listOf(0x33), multi[0x04])
        assertEquals(5, multi.size)
        // Duas centrais respondem: vale a primeira de cada PID, e o que só a segunda tem também entra
        val two = Elm327.multiPayload("410D32\r410C0B1A0D33\r>")
        assertEquals(listOf(0x32), two[0x0D])
        assertEquals(listOf(0x0B, 0x1A), two[0x0C])
        // Sem dados, erro ou PID de tamanho desconhecido
        assertTrue(Elm327.multiPayload("NO DATA\r>").isEmpty())
        assertTrue(Elm327.multiPayload("?\r>").isEmpty())
        assertEquals(setOf(0x0D), Elm327.multiPayload("410D32A1FFFF\r>").keys)
        // Resposta cortada no meio de um PID: fica o que veio inteiro
        assertEquals(setOf(0x0D), Elm327.multiPayload("410D320C0B\r>").keys)
    }
}
