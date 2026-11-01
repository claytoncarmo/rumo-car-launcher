package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapaRadarTest {
    private fun line(desc: String, lon: String = "-48.5", lat: String = "-27.6") = "$lon,$lat,$desc"

    @Test
    fun fixedRadarWithLimit() {
        val p = MapaRadar.parseLine(line("Radar Fixo - 60 kmh@60"), 7)!!
        assertEquals(-7L, p.id)
        assertEquals(RadarKind.FIXED, p.kind)
        assertEquals(60, p.limit)
        assertEquals(-27.6, p.lat, 1e-9)
        assertEquals(-48.5, p.lon, 1e-9)
    }

    @Test
    fun typesWithoutAccent() {
        val cases = mapOf(
            "Semaforo com Radar - 40 kmh@40" to (RadarKind.TRAFFIC_LIGHT_RADAR to 40),
            "Radar Movel - 60 kmh@60" to (RadarKind.MOBILE to 60),
            "Semaforo com Camera - 0 kmh@0" to (RadarKind.RED_LIGHT_CAMERA to 0),
            "Policia Rodoviaria - 0 kmh@0" to (RadarKind.POLICE to 0),
            "Pedagio - 0 kmh@0" to (RadarKind.TOLL to 0),
        )
        for ((desc, expected) in cases) {
            val p = MapaRadar.parseLine(line(desc), 1)!!
            assertEquals(desc, expected, p.kind to p.limit)
        }
    }

    @Test
    fun commonAndElectronicBump() {
        val common = MapaRadar.parseLine(line("Lombada - 0 kmh@0"), 1)!!
        assertEquals(RadarKind.BUMP to 0, common.kind to common.limit)
        val electronic = MapaRadar.parseLine(line("Lombada - 50 kmh@50"), 2)!!
        assertEquals(RadarKind.ELECTRONIC_BUMP to 50, electronic.kind to electronic.limit)
    }

    @Test
    fun onlyEnforcersKeepLimit() {
        assertEquals(0, MapaRadar.parseLine(line("Pedagio - 80 kmh@80"), 1)!!.limit)
    }

    @Test
    fun badLinesAreIgnored() {
        val bad = listOf(
            "a,b,Radar Fixo - 60 kmh@60",
            line("Tipo Novo - 60 kmh@60"),
            line("Radar Fixo"),
            line("Radar Fixo - 300 kmh@300"),
            line("Radar Fixo - 60 kmh@60", lon = "-10"),
            "-48,-27",
            "",
        )
        for (l in bad) assertNull(l, MapaRadar.parseLine(l, 1))
    }

    @Test
    fun readsFileWithoutDuplicatesAndIdFromLine() {
        val lines = sequenceOf(
            "-48.500000,-27.600000,Radar Fixo - 60 kmh@60",
            "-48.500000,-27.600000,Radar Fixo - 60 kmh@60",
            "lixo",
            "-48.510000,-27.610000,Pedagio - 0 kmh@0",
        )
        val points = MapaRadar.read(lines)
        assertEquals(listOf(RadarKind.FIXED, RadarKind.TOLL), points.map { it.kind })
        assertEquals(listOf(-1L, -4L), points.map { it.id })
    }

    // ---- contorno

    private val square = """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":
        [[[-49,-28],[-48,-28],[-48,-27],[-49,-27],[-49,-28]]]}}"""

    @Test
    fun contourSaysWhetherPointIsInside() {
        val c = Contour.parse(square)!!
        assertTrue(c.contains(-27.5, -48.5))
        assertFalse(c.contains(-25.0, -48.5))
        assertFalse(c.contains(-27.5, -50.0))
    }

    @Test
    fun contourWithHoleAndMultiPolygon() {
        val hole = """{"type":"Polygon","coordinates":
            [[[-49,-28],[-48,-28],[-48,-27],[-49,-27],[-49,-28]],[[-48.6,-27.6],[-48.4,-27.6],[-48.4,-27.4],[-48.6,-27.4],[-48.6,-27.6]]]}"""
        val c = Contour.parse(hole)!!
        assertTrue(c.contains(-27.9, -48.9))
        assertFalse(c.contains(-27.5, -48.5))
        val two = """{"type":"MultiPolygon","coordinates":
            [[[[-49,-28],[-48,-28],[-48,-27],[-49,-27],[-49,-28]]],[[[-40,-20],[-39,-20],[-39,-19],[-40,-19],[-40,-20]]]]}"""
        val m = Contour.parse(two)!!
        assertTrue(m.contains(-19.5, -39.5))
        assertTrue(m.contains(-27.5, -48.5))
        assertFalse(m.contains(-23.0, -44.0))
    }

    @Test
    fun invalidContourIsNull() {
        assertNull(Contour.parse("não é json"))
        assertNull(Contour.parse("""{"type":"Point","coordinates":[1,2]}"""))
    }

    // ---- junção com o OSM

    private val noWay: (Double, Double) -> WayHint? = { _, _ -> null }

    @Test
    fun bearingFromOsmRadarThatIsSamePoint() {
        val osm = listOf(Radar(100, -27.6000, -48.5000, 60, 90))
        val points = listOf(MapaRadarPoint(-1, -27.60002, -48.50002, RadarKind.FIXED, 60))
        val out = MapaRadar.merge(osm, points, noWay)
        assertEquals(1, out.size)
        assertEquals(90, out[0].bearing)
        assertEquals(-1L, out[0].id)
        assertEquals(RadarKind.FIXED, out[0].kind)
    }

    @Test
    fun osmRadarWithoutPairRemains() {
        val osm = listOf(Radar(100, -27.6, -48.5, 60, 90))
        val points = listOf(MapaRadarPoint(-1, -27.7, -48.5, RadarKind.FIXED, 60))
        val out = MapaRadar.merge(osm, points, noWay)
        assertEquals(setOf(-1L, 100L), out.map { it.id }.toSet())
        assertEquals(RadarKind.OSM, out.first { it.id == 100L }.kind)
    }

    @Test
    fun oneWayRoadGivesBearingTwoWayGivesBoth() {
        val p = listOf(MapaRadarPoint(-1, -27.6, -48.5, RadarKind.FIXED, 60))
        assertEquals(270, MapaRadar.merge(emptyList(), p) { _, _ -> WayHint(270.4, true) }[0].bearing)
        assertEquals(-1, MapaRadar.merge(emptyList(), p) { _, _ -> WayHint(270.0, false) }[0].bearing)
        assertEquals(0, MapaRadar.merge(emptyList(), p) { _, _ -> WayHint(359.6, true) }[0].bearing)
    }

    @Test
    fun tollDoesNotEatNearbyOsmRadar() {
        val osm = listOf(Radar(100, -27.6, -48.5, 60, 90))
        val points = listOf(MapaRadarPoint(-1, -27.6, -48.5, RadarKind.TOLL, 0))
        assertEquals(2, MapaRadar.merge(osm, points, noWay).size)
    }

    @Test
    fun oneOsmRadarMatchesOnlyOnePoint() {
        val osm = listOf(Radar(100, -27.6, -48.5, 60, 90))
        val points = listOf(
            MapaRadarPoint(-1, -27.6, -48.5, RadarKind.FIXED, 60),
            MapaRadarPoint(-2, -27.6, -48.50001, RadarKind.FIXED, 60),
        )
        assertEquals(listOf(-1, 90), MapaRadar.merge(osm, points, noWay).map { it.bearing }.sorted())
    }

    // ---- via mais perto

    private fun run(oneway: Boolean, vararg pts: Pair<Double, Double>) = Run(
        Way(1, "Rua", null, 6, oneway, 50, 50, false, false),
        IntArray(pts.size) { (pts[it].first * 1e6).toInt() },
        IntArray(pts.size) { (pts[it].second * 1e6).toInt() },
    )

    @Test
    fun findsNearestRoadAndSegmentBearing() {
        // Via para leste, 20 m ao norte do ponto, de mão única
        val dLat = 20 / Geo.M_PER_DEG_LAT
        val east = run(true, (-27.6 + dLat) to -48.51, (-27.6 + dLat) to -48.49)
        val hint = nearestWayHint(listOf(east), -27.6, -48.5)!!
        assertEquals(90.0, hint.bearing, 1.0)
        assertTrue(hint.oneway)
    }

    @Test
    fun hintCarriesTheClosestPointOnTheWay() {
        // Via para leste, 20 m ao norte do ponto: o ponto mais perto fica na via, na mesma longitude
        val dLat = 20 / Geo.M_PER_DEG_LAT
        val east = run(false, (-27.6 + dLat) to -48.51, (-27.6 + dLat) to -48.49)
        val hint = nearestWayHint(listOf(east), -27.6, -48.5)!!
        assertEquals(-27.6 + dLat, hint.lat, 1e-6)
        assertEquals(-48.5, hint.lon, 1e-6)
        assertTrue(hint.lat.isFinite())
    }

    @Test
    fun picksNearestAndNotAboveLimit() {
        val dNear = 10 / Geo.M_PER_DEG_LAT
        val dFar = 30 / Geo.M_PER_DEG_LAT
        val near = run(false, (-27.6 + dNear) to -48.51, (-27.6 + dNear) to -48.49)
        val far = run(true, (-27.6 - dFar) to -48.51, (-27.6 - dFar) to -48.49)
        assertFalse(nearestWayHint(listOf(far, near), -27.6, -48.5)!!.oneway)
        val veryFar = run(true, (-27.6 + 100 / Geo.M_PER_DEG_LAT) to -48.51, (-27.6 + 100 / Geo.M_PER_DEG_LAT) to -48.49)
        assertNull(nearestWayHint(listOf(veryFar), -27.6, -48.5))
    }

    // ---- cache

    @Test
    fun cacheComesBackEqualAndNotWithOtherSignature() {
        val radars = listOf(
            Radar(-1, -27.5, -48.5, 60, 90, RadarKind.FIXED),
            Radar(100, -27.6, -48.6, 0, -1, RadarKind.TOLL),
        )
        val bytes = RadarCache.encode("sig", radars)
        val back = RadarCache.decode(bytes, "sig")!!
        assertEquals(radars.map { it.id }, back.map { it.id })
        assertEquals(radars.map { it.kind }, back.map { it.kind })
        assertEquals(radars.map { it.bearing }, back.map { it.bearing })
        assertEquals(-27.5, back[0].lat, 1e-12)
        assertNull(RadarCache.decode(bytes, "outra"))
        assertNull(RadarCache.decode(bytes.copyOf(10), "sig"))
        assertNotNull(RadarCache.decode(RadarCache.encode("x", emptyList()), "x"))
    }
}
