package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStyleTest {
    @Test
    fun calmDrivingStaysEconomic() {
        val d = DrivingStyle()
        var v = 0.0
        // Ganha 2 km/h por segundo até 60, com rotação baixa
        repeat(120) {
            v = minOf(60.0, v + 2.0)
            d.update(1.0, v, 1_800.0, 20.0)
        }
        assertEquals(DrivingLabel.ECONOMIC, d.label)
        assertTrue(d.score < 10)
    }

    @Test
    fun hardAccelerationsAndHighRpmTurnAggressiveThenCalmDown() {
        val d = DrivingStyle()
        // Arrancadas fortes (12 km/h por segundo) com giro alto, repetidas por 3 minutos
        var v = 0.0
        repeat(180) {
            v = if (v >= 60.0) 0.0 else v + 12.0
            d.update(1.0, v, 4_000.0, 80.0)
        }
        assertEquals(DrivingLabel.AGGRESSIVE, d.label)
        // Depois, 3 minutos tranquilos: volta a econômica, passando pela moderada
        repeat(180) { d.update(1.0, 50.0, 1_900.0, 15.0) }
        assertEquals(DrivingLabel.ECONOMIC, d.label)
    }

    @Test
    fun labelDoesNotFlickerAroundTheThreshold() {
        val d = DrivingStyle()
        // Leva a nota até um pouco acima de 45 (moderada)
        while (d.label != DrivingLabel.MODERATE) d.update(1.0, 50.0, 3_000.0, 70.0)
        // Batidas de meio-termo (rotação média só): a nota cai, mas fica acima de 35 e não volta a econômica
        repeat(20) { d.update(1.0, 50.0, 3_000.0, 10.0) }
        assertEquals(DrivingLabel.MODERATE, d.label)
    }

    @Test
    fun longPausesAreNotAccelerations() {
        val d = DrivingStyle()
        d.update(1.0, 0.0, 800.0, 0.0)
        // O Rumo saiu da tela e voltou com o carro a 80: não é uma arrancada
        d.update(30.0, 80.0, 2_000.0, 20.0)
        d.update(1.0, 80.0, 2_000.0, 20.0)
        assertTrue(d.score < 1)
    }
}
