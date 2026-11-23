package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelLevelTest {
    @Test
    fun sloshingIsSmoothedOut() {
        val f = FuelLevel()
        assertNull(f.percent)
        f.update(50.0, 1.0)
        assertEquals(50.0, f.percent!!, 0.0)
        // A boia balança ±4 pontos a cada segundo por 2 minutos: o nível quase não sai do lugar
        repeat(120) { i -> f.update(if (i % 2 == 0) 54.0 else 46.0, 1.0) }
        assertEquals(50.0, f.percent!!, 1.0)
    }

    @Test
    fun followsTheRealDropAndJumpsOnRefuel() {
        val f = FuelLevel()
        f.update(40.0, 1.0)
        // O tanque cai de verdade para 35: o nível acompanha em alguns minutos
        repeat(600) { f.update(35.0, 1.0) }
        assertEquals(35.0, f.percent!!, 0.2)
        // Abasteceu: sobe na hora
        f.update(90.0, 1.0)
        assertEquals(90.0, f.percent!!, 0.0)
        // Uma subida pequena (balanço) não conta como abastecimento
        f.update(95.0, 1.0)
        assertTrue(f.percent!! < 91.0)
    }
}
