package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelNowTest {
    @Test
    fun averagesTheLastTwoSeconds() {
        val f = FuelNow()
        assertNull(f.kmPerLiter)
        assertNull(f.litersPerHour)
        // Leituras a cada 0,2 s alternando 3 e 9 L/h a 60 km/h: a média é 6 L/h, 10 km/L
        for (i in 0..10) f.add(i * 200L, if (i % 2 == 0) 3.0 else 9.0, 60.0)
        assertEquals(6.0, f.litersPerHour!!, 0.6)
        assertEquals(10.0, f.kmPerLiter!!, 1.0)
        // Leituras antigas saem da janela: depois de 2 s só com 4 L/h, fica 4
        for (i in 11..30) f.add(i * 200L, 4.0, 60.0)
        assertEquals(4.0, f.litersPerHour!!, 1e-9)
        assertEquals(15.0, f.kmPerLiter!!, 1e-9)
    }

    @Test
    fun stoppedShowsOnlyLitersPerHour() {
        val f = FuelNow()
        f.add(0, 0.8, 0.0)
        f.add(500, 0.8, 0.0)
        assertNull(f.kmPerLiter)
        assertEquals(0.8, f.litersPerHour!!, 1e-9)
        f.clear()
        assertNull(f.litersPerHour)
    }
}
