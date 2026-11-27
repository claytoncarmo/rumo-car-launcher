package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FuelLevelTest {
    /** Leituras como as de 25/11: mediana crua ~[center] %, com a boia batendo em cima (92,5 %) e embaixo. */
    private fun noisy(center: Double, n: Int, seed: Int = 7): List<Double> {
        val rnd = Random(seed)
        return List(n) {
            when (it % 10) {
                0 -> 92.5
                1 -> center - 40 + rnd.nextDouble() * 20
                else -> center + (rnd.nextDouble() - 0.5) * 16
            }
        }
    }

    @Test
    fun noisySensorReadsLikeThePanel() {
        val f = FuelLevel()
        assertNull(f.percent)
        noisy(65.0, 300).forEach { f.update(it) }
        assertEquals(92.5, f.fullRaw!!, 0.0)
        // 65 / 92,5 = 70 %: o que o ponteiro do painel mostrava; e o balanço não é tomado como abastecimento
        assertEquals(70.3, f.percent!!, 3.0)
        assertEquals(0, f.refuels)
        assertEquals(FuelLevel.WINDOW_SAMPLES, f.saved().size)
    }

    @Test
    fun spikesDoNotPullTheLevel() {
        val f = FuelLevel(fullRaw = 92.5)
        repeat(100) { f.update(46.0) }
        // Picos para cima e para baixo, um em cada três leituras: a mediana não se mexe
        repeat(50) { i -> f.update(if (i % 3 == 0) 92.5 else if (i % 3 == 1) 20.0 else 46.0) }
        assertEquals(46.0 / 92.5 * 100, f.percent!!, 0.5)
    }

    @Test
    fun learnsTheFullOnlyFromHighReadings() {
        val f = FuelLevel(fullRaw = 50.0)  // valor guardado estranho: ignorado
        assertNull(f.fullRaw)
        assertFalse(f.update(60.0))
        assertNull(f.fullRaw)
        assertEquals(60.0, f.percent!!, 0.0)  // sem o cheio, a leitura vale como está
        assertTrue(f.update(92.5))
        assertFalse(f.update(90.0))
        assertEquals(92.5, f.fullRaw!!, 0.0)
    }

    @Test
    fun readingsComeBackWhenTheCarStartsAgain() {
        val before = FuelLevel(fullRaw = 92.5)
        repeat(150) { before.update(46.0) }
        val text = FuelLevel.encode(before.saved())
        // Carro desligado e ligado de novo: o nível já volta certo, antes da primeira leitura nova
        val after = FuelLevel(fullRaw = 92.5, saved = FuelLevel.decode(text))
        assertEquals(46.0 / 92.5 * 100, after.percent!!, 0.1)
        repeat(10) { after.update(45.0) }
        assertEquals(46.0 / 92.5 * 100, after.percent!!, 0.1)
        assertEquals(emptyList<Double>(), FuelLevel.decode(null))
        assertEquals(listOf(1.5, 2.0), FuelLevel.decode("1.5, x ,2"))
    }

    @Test
    fun refuelClearsTheOldReadings() {
        val f = FuelLevel(fullRaw = 92.5)
        repeat(150) { f.update(18.5) }
        assertEquals(20.0, f.percent!!, 0.1)
        // Abasteceu (com o carro desligado): as leituras novas, bem mais altas, tiram as antigas
        repeat(15) { f.update(88.0) }
        assertEquals(1, f.refuels)
        assertEquals(88.0 / 92.5 * 100, f.percent!!, 0.1)
    }
}
