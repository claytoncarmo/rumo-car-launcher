package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FuelLevelTest {
    @Test
    fun noisySensorReadsLikeThePanel() {
        // Como no registro de 25/11: mediana crua ~65 %, leituras de 22 % a 92,5 % (o batente da boia)
        val f = FuelLevel()
        assertNull(f.percent)
        val rnd = Random(7)
        var t = 0L
        repeat(300) {
            t += 2_000
            val raw = when (it % 10) {
                0 -> 92.5                          // boia no batente de cima
                1 -> 25.0 + rnd.nextDouble() * 20  // boia lá embaixo
                else -> 65.0 + (rnd.nextDouble() - 0.5) * 16
            }
            f.update(t, raw)
        }
        assertEquals(92.5, f.fullRaw!!, 0.0)
        // 65 / 92,5 = 70 %: o que o ponteiro do painel mostrava
        assertEquals(70.3, f.percent!!, 3.0)
    }

    @Test
    fun spikesDoNotPullTheLevel() {
        val f = FuelLevel(fullRaw = 92.5)
        var t = 0L
        repeat(30) { t += 2_000; f.update(t, 46.0) }
        // Picos para cima e para baixo, um em cada três leituras: a mediana não se mexe
        repeat(30) { i ->
            t += 2_000
            f.update(t, if (i % 3 == 0) 92.5 else if (i % 3 == 1) 20.0 else 46.0)
        }
        assertEquals(46.0 / 92.5 * 100, f.percent!!, 0.5)
    }

    @Test
    fun learnsTheFullOnlyFromHighReadings() {
        val f = FuelLevel(fullRaw = 50.0)  // valor guardado estranho: ignorado
        assertNull(f.fullRaw)
        assertFalse(f.update(0, 60.0))
        assertNull(f.fullRaw)
        assertEquals(60.0, f.percent!!, 0.0)  // sem o cheio, a leitura vale como está
        assertTrue(f.update(1_000, 92.5))
        assertFalse(f.update(2_000, 90.0))
        assertEquals(92.5, f.fullRaw!!, 0.0)
    }

    @Test
    fun startsOverAfterALongStop() {
        val f = FuelLevel(fullRaw = 92.5)
        var t = 0L
        repeat(60) { t += 2_000; f.update(t, 18.5) }
        assertEquals(20.0, f.percent!!, 0.1)
        // Carro desligado 20 min (abasteceu): só as leituras novas
        t += 20 * 60_000
        f.update(t, 88.0)
        f.update(t + 2_000, 88.0)
        assertEquals(88.0 / 92.5 * 100, f.percent!!, 0.1)
    }
}
