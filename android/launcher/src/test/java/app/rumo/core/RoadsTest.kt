package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsTest {
    // Cruzamento em Florianópolis: avenida leste-oeste (mão dupla, 60/40) e rua norte-sul de mão única (30)
    private val lat0 = -27.5950
    private val lon0 = -48.5480
    private val avenue = Way(1, "Av. Mauro Ramos", null, 2, false, 60, 40, false, true)
    private val street = Way(2, "R. Esteves Júnior", null, 6, true, 30, 30, true, true)
    private val runs = listOf(
        run(avenue, lat0 to lon0 - 0.003, lat0 to lon0 + 0.003),
        // mão única desenhada de sul para norte
        run(street, lat0 - 0.003 to lon0 + 0.0002, lat0 + 0.003 to lon0 + 0.0002),
    )

    private fun run(way: Way, vararg pts: Pair<Double, Double>) =
        Run(way, IntArray(pts.size) { (pts[it].first * 1e6).toInt() }, IntArray(pts.size) { (pts[it].second * 1e6).toInt() })

    @Test
    fun headingEastOnAvenueGetsDirectionLimit() {
        val m = RoadMatcher().update(lat0 + 0.00005, lon0 - 0.001, 90f, 50, runs)
        assertNotNull(m)
        assertEquals(1L, m!!.way.id)
        assertTrue(m.forward)
        assertEquals(60, m.limit)
        assertFalse(m.estimated)
    }

    @Test
    fun headingWestUsesOtherDirection() {
        val m = RoadMatcher().update(lat0, lon0 - 0.001, 270f, 50, runs)!!
        assertFalse(m.forward)
        assertEquals(40, m.limit)
        assertTrue(m.estimated)
    }

    @Test
    fun atCrossingHeadingDecidesRoad() {
        // em cima dos dois, indo para o norte: a rua
        val m = RoadMatcher().update(lat0 + 0.00002, lon0 + 0.0002, 2f, 30, runs)!!
        assertEquals(2L, m.way.id)
    }

    @Test
    fun doesNotMatchWrongWayOnOneWay() {
        // na rua indo para o sul (contramão) e longe da avenida: nenhuma via
        val m = RoadMatcher().update(lat0 + 0.002, lon0 + 0.0002, 180f, 30, runs)
        assertNull(m)
    }

    @Test
    fun farFromEverythingDoesNotMatch() {
        assertNull(RoadMatcher().update(lat0 + 0.001, lon0 - 0.002, 90f, 40, runs))
    }

    @Test
    fun stoppedKeepsRoadAndDirection() {
        val matcher = RoadMatcher()
        matcher.update(lat0, lon0 - 0.001, 270f, 50, runs)
        val m = matcher.update(lat0, lon0 - 0.0012, 270f, 0, runs)!!
        assertEquals(1L, m.way.id)
        assertFalse(m.forward)
    }

    @Test
    fun roadName() {
        assertEquals("Rod. Admar Gonzaga · SC-404", Way(3, "Rod. Admar Gonzaga", "SC-404", 2, false, 60, 60, false, false).label)
        assertEquals("BR-101/BR-282", Way(4, null, "BR-101;BR-282", 0, true, 110, 110, false, false).label)
        assertEquals("Rua sem nome", Way(5, null, null, 6, false, 30, 30, true, true).label)
    }

    @Test
    fun speedAboveLimitWithHysteresis() {
        val o = OverSpeed()
        assertFalse(o.update(60, 60))
        assertTrue(o.update(61, 60))
        assertTrue(o.update(60, 60))   // histerese: só apaga abaixo do limite
        assertFalse(o.update(59, 60))
        assertFalse(o.update(70, null))
        o.toleranceKmh = 7
        assertFalse(o.update(67, 60))
        assertTrue(o.update(68, 60))
    }
}
