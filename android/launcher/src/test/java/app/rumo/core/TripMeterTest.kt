package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripMeterTest {

    @Test
    fun umMilesimoDeGrauDeLatitude() {
        assertEquals(111.19, distanceM(0.0, 0.0, 0.001, 0.0), 0.05)
    }

    @Test
    fun somaSoEmMovimentoEComBoaPrecisao() {
        val t = TripMeter()
        t.add(-23.5, -46.6, 0, 10f, 5f)
        t.add(-23.501, -46.6, 1000, 10f, 5f)   // +111 m
        t.add(-23.502, -46.6, 2000, 0.5f, 5f)  // parado (deriva): não conta
        t.add(-23.503, -46.6, 3000, 10f, 50f)  // impreciso: ignorado
        assertEquals(111.2, t.meters, 0.5)
        assertEquals(1000L, t.movingMs)
    }

    @Test
    fun mediaSoDepoisDeUmMinuto() {
        val t = TripMeter()
        var lat = 0.0
        for (i in 0..60) {
            t.add(lat, 0.0, i * 1000L, 20f, 5f)
            if (i == 30) assertNull(t.averageKmh)
            lat += 0.0002  // ≈ 22,24 m por segundo ≈ 80 km/h
        }
        assertEquals(80, t.averageKmh)
    }
}
