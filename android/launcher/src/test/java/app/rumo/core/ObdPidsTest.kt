package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidsTest {
    // Valores lidos no carro (relatório do teste do Bluetooth)
    @Test
    fun decodesWhatTheCarReported() {
        assertEquals("956 rpm", ObdPids.display(0x0C, listOf(0x0E, 0xF0)))
        assertEquals("0 km/h", ObdPids.display(0x0D, listOf(0x00)))
        assertEquals("81 °C", ObdPids.display(0x05, listOf(0x79)))
        assertEquals("93 %", ObdPids.display(0x2F, listOf(0xEC)))
        assertEquals("22 %", ObdPids.display(0x04, listOf(0x37)))
    }

    @Test
    fun decodesTwoByteAndSignedValues() {
        assertEquals(12.5, ObdPids.value(0x10, listOf(0x04, 0xE2))!!, 1e-9)   // MAF 1250 / 100
        assertEquals(14.123, ObdPids.value(0x42, listOf(0x37, 0x2B))!!, 1e-9) // 14123 mV
        assertEquals(0.0, ObdPids.value(0x06, listOf(128))!!, 1e-9)           // correção de combustível: 128 = 0 %
        assertTrue(ObdPids.value(0x06, listOf(0))!! < -99.0)
        assertEquals(-5.5, ObdPids.value(0x0E, listOf(117))!!, 1e-9)          // avanço: 117/2 - 64
        assertEquals(1.0, ObdPids.value(0x44, listOf(0x80, 0x00))!!, 1e-9)    // lambda 1,000
        assertNull(ObdPids.value(0x0C, listOf(0x0E)))                         // falta byte
        assertNull(ObdPids.value(0x99, listOf(1, 2)))                         // PID que não conhecemos
    }

    @Test
    fun formatsInBrazilianStyle() {
        assertEquals("1.726 rpm", ObdPids.display(0x0C, listOf(0x1A, 0xF8)))
        assertEquals("1,000", ObdPids.text(ObdPids.def(0x44)!!, 1.0))
        assertEquals("12,5 g/s", ObdPids.display(0x10, listOf(0x04, 0xE2)))
    }

    @Test
    fun everyDefinitionDecodesAFullPayload() {
        for (d in ObdPids.DEFS) {
            assertNotNull("PID ${d.pid} sem valor", d.decode(listOf(100, 100, 100, 100)))
        }
        assertEquals(ObdPids.DEFS.size, ObdPids.DEFS.map { it.pid }.toSet().size)
    }

    @Test
    fun readsMonitorStatus() {
        val m = ObdPids.monitor(listOf(0x82, 0x07, 0xE5, 0x00))!!
        assertTrue(m.milOn)
        assertEquals(2, m.dtcCount)
        assertEquals(false, ObdPids.monitor(listOf(0x00))!!.milOn)
    }

    @Test
    fun readsTroubleCodes() {
        assertEquals(emptyList<String>(), ObdPids.dtcs("4300\r\r>"))
        assertEquals(listOf("P0133"), ObdPids.dtcs("43 01 01 33\r>"))      // CAN: com a quantidade
        assertEquals(listOf("P0133"), ObdPids.dtcs("43 01 33 00 00 00 00\r>")) // sem CAN: pares, com zeros no fim
        assertEquals(listOf("P0420", "C0035"), ObdPids.dtcs("43 02 04 20 40 35\r>"))
        assertEquals(listOf("B1234", "U0100"), ObdPids.dtcs("43 02 92 34 C1 00\r>"))
        assertEquals(emptyList<String>(), ObdPids.dtcs("NO DATA\r>"))
        assertEquals(emptyList<String>(), ObdPids.dtcs(""))
    }

    @Test
    fun estimatesFuelFromAirFlow() {
        // 4 g/s de ar na marcha lenta: ~1,45 L/h
        assertEquals(1.45, FuelEstimate.litersPerHour(4.0), 0.02)
        assertEquals(2.9, FuelEstimate.litersPerHour(4.0, 2.0), 0.04)
        assertNull(FuelEstimate.kmPerLiter(0.0, 1.5))
        assertNull(FuelEstimate.kmPerLiter(60.0, 0.0))
        assertEquals(12.0, FuelEstimate.kmPerLiter(60.0, 5.0)!!, 1e-9)
    }

    @Test
    fun calibratesWithAFillUp() {
        // O carro estimou 40 L e o abastecimento foi de 44 L: o fator sobe 10 %
        assertEquals(1.1, FuelEstimate.calibrate(1.0, 40.0, 44.0)!!, 1e-9)
        assertEquals(1.21, FuelEstimate.calibrate(1.1, 40.0, 44.0)!!, 1e-9)
        assertNull(FuelEstimate.calibrate(1.0, 0.0, 44.0))
        assertEquals(2.0, FuelEstimate.calibrate(1.0, 1.0, 100.0)!!, 1e-9) // limitado
    }

    @Test
    fun tripAverageSumsConsumptionAndDistance() {
        val t = FuelTrip()
        assertNull(t.kmPerLiter())
        // 1 hora a 60 km/h gastando 5 L/h = 60 km com 5 L = 12 km/L
        repeat(3600) { t.add(1.0, 5.0, 60.0) }
        assertEquals(12.0, t.kmPerLiter()!!, 0.01)
        assertEquals(60.0, t.km, 0.1)
        // Uma pausa de 10 minutos entre duas leituras não vira consumo: limitada a 5 s
        val before = t.liters
        t.add(600.0, 5.0, 60.0)
        assertEquals(before + 5.0 * 5.0 / 3600.0, t.liters, 1e-9)
        t.reset()
        assertEquals(0.0, t.liters, 0.0)
    }
}
