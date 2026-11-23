package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStyleTest {
    /** Acelera de [from] a [to] km/h em [seconds] s, uma leitura por segundo. */
    private fun DrivingStyle.ramp(from: Double, to: Double, seconds: Int, rpm: Double = 2_000.0) {
        for (i in 1..seconds) update(1.0, from + (to - from) * i / seconds, rpm, 30.0)
    }

    private fun DrivingStyle.cruise(seconds: Int, speed: Double = 50.0, rpm: Double = 2_000.0) {
        repeat(seconds) { update(1.0, speed, rpm, 20.0) }
    }

    @Test
    fun calmDrivingStaysEconomic() {
        val d = DrivingStyle()
        d.update(1.0, 0.0, 800.0, 10.0)
        repeat(5) {
            d.ramp(0.0, 50.0, 12)   // 0 a 50 em 12 s
            d.cruise(30)
            d.ramp(50.0, 0.0, 8)    // para em 8 s
        }
        assertEquals(DrivingLabel.ECONOMIC, d.label)
        assertEquals(0.0, d.score, 0.0)
    }

    @Test
    fun highwayCruiseWithHigherRpmAddsNothing() {
        val d = DrivingStyle()
        d.update(1.0, 110.0, 3_300.0, 25.0)
        d.cruise(300, speed = 110.0, rpm = 3_300.0)
        assertEquals(0.0, d.score, 0.0)
    }

    @Test
    fun briskAccelerationIsModerateForAWhile() {
        val d = DrivingStyle()
        d.update(1.0, 0.0, 800.0, 10.0)
        d.ramp(0.0, 50.0, 8)        // 0 a 50 em 8 s
        assertEquals(DrivingLabel.MODERATE, d.label)
        // 20 s depois ainda é moderada (folga do rótulo); depois de 1 min tranquilo, volta a econômica
        d.cruise(20)
        assertEquals(DrivingLabel.MODERATE, d.label)
        d.cruise(40)
        assertEquals(DrivingLabel.ECONOMIC, d.label)
    }

    @Test
    fun hardAccelerationAndBrakingAreAggressive() {
        val d = DrivingStyle()
        d.update(1.0, 0.0, 800.0, 10.0)
        d.ramp(0.0, 50.0, 5, rpm = 4_500.0)   // 0 a 50 em 5 s, giro alto
        assertEquals(DrivingLabel.AGGRESSIVE, d.label)
        d.cruise(120)
        assertEquals(DrivingLabel.ECONOMIC, d.label)
        d.ramp(50.0, 0.0, 3)                   // freada forte: 50 a 0 em 3 s
        assertTrue(d.score > 75)
    }

    @Test
    fun slowReadingsStillCount() {
        // Scanner lento: uma leitura a cada 2,5 s
        val d = DrivingStyle()
        d.update(1.0, 0.0, 800.0, 10.0)
        for (i in 1..2) d.update(2.5, i * 25.0, 3_000.0, 60.0)   // 0 a 50 em 5 s
        assertTrue(d.label != DrivingLabel.ECONOMIC)
    }

    @Test
    fun longPausesAreNotAccelerations() {
        val d = DrivingStyle()
        d.update(1.0, 0.0, 800.0, 0.0)
        // O Rumo saiu da tela e voltou com o carro a 80: não é uma arrancada
        d.update(30.0, 80.0, 2_000.0, 20.0)
        d.update(1.0, 80.0, 2_000.0, 20.0)
        assertEquals(0.0, d.score, 0.0)
    }
}
