package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStyleTest {
    /** Acelera de [from] a [to] km/h em [seconds] s, uma leitura por segundo, pedal leve. */
    private fun DrivingStyle.ramp(from: Double, to: Double, seconds: Int, rpm: Double = 2_000.0, pedal: Double = 30.0) {
        for (i in 1..seconds) update(1.0, from + (to - from) * i / seconds, rpm, pedal)
    }

    private fun DrivingStyle.cruise(seconds: Int, speed: Double = 50.0, rpm: Double = 2_000.0) {
        repeat(seconds) { update(1.0, speed, rpm, 22.0) }
    }

    /** Começa parado, com o pé solto (pedal 15 %). */
    private fun start() = DrivingStyle().apply { update(1.0, 0.0, 800.0, 15.0) }

    @Test
    fun calmDrivingStaysEconomic() {
        val d = start()
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
        d.cruise(300, speed = 110.0, rpm = 3_300.0)
        assertEquals(0.0, d.score, 0.0)
    }

    @Test
    fun fastReadingsWithWholeKmhDoNotLookLikeJolts() {
        // 5 leituras por segundo, ganhando 1 m/s² (3,6 km/h por segundo) com a velocidade em km/h inteiros: leitura a
        // leitura, ora 0, ora 1 km/h em 0,2 s (1,4 m/s²); na janela de 1 s, 1 m/s², abaixo do limite
        val d = start()
        var t = 0.0
        repeat(70) {
            t += 0.2
            d.update(0.2, Math.floor(t * 3.6), 1_800.0, 30.0)
        }
        assertEquals(0.0, d.score, 0.0)
    }

    @Test
    fun briskAccelerationIsModerateForAWhile() {
        val d = start()
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
        val d = start()
        d.ramp(0.0, 50.0, 5, rpm = 4_500.0)   // 0 a 50 em 5 s, giro alto
        assertEquals(DrivingLabel.AGGRESSIVE, d.label)
        d.cruise(120)
        assertEquals(DrivingLabel.ECONOMIC, d.label)
        d.ramp(50.0, 0.0, 3)                   // freada forte: 50 a 0 em 3 s
        assertTrue(d.score > 75)
    }

    @Test
    fun heavyFootCountsEvenWithoutMuchAcceleration() {
        // Subida com o carro cheio: pedal no fundo (80 % cru), quase sem ganhar velocidade
        val d = DrivingStyle()
        d.update(1.0, 40.0, 2_000.0, 15.0)
        repeat(4) { d.update(1.0, 40.0 + it, 3_000.0, 80.0) }
        assertTrue(d.label != DrivingLabel.ECONOMIC)
        // O curso aprendido: pé solto em 15 %, fundo em 80 %
        assertEquals(15.0, d.pedalMin!!, 0.0)
        assertEquals(80.0, d.pedalMax!!, 0.0)
    }

    @Test
    fun suddenPedalStabAddsPointsAtOnce() {
        // Já andando a 50, com o pé solto em 15 %
        val d = DrivingStyle()
        d.update(1.0, 50.0, 2_000.0, 15.0)
        d.cruise(5)
        val before = d.score
        // Pedal de 22 % a 60 % cru numa leitura (cerca de 70 % do curso), sem tempo de acelerar ainda
        d.update(0.5, 50.0, 2_200.0, 60.0)
        assertTrue(d.score - before >= DrivingStyle.STAB_POINTS)
    }

    @Test
    fun firmButOrdinaryStopsAddNothing() {
        // Parar de 38 a 0 em 5 s (2,1 m/s²): parada firme de trânsito, não é agressiva
        val d = DrivingStyle()
        d.update(1.0, 38.0, 1_800.0, 18.0)
        d.ramp(38.0, 0.0, 5, rpm = 1_000.0, pedal = 18.0)
        assertEquals(0.0, d.score, 0.0)
    }

    @Test
    fun longPausesAreNotAccelerations() {
        val d = start()
        // O Rumo saiu da tela e voltou com o carro a 80: não é uma arrancada
        d.update(30.0, 80.0, 2_000.0, 25.0)
        d.update(1.0, 80.0, 2_000.0, 25.0)
        d.update(1.0, 80.0, 2_000.0, 25.0)
        assertEquals(0.0, d.score, 0.0)
    }
}
