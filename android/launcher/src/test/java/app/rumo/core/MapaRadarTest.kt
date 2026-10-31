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
    fun radarFixoComLimite() {
        val p = MapaRadar.parseLine(line("Radar Fixo - 60 kmh@60"), 7)!!
        assertEquals(-7L, p.id)
        assertEquals(RadarKind.FIXED, p.kind)
        assertEquals(60, p.limit)
        assertEquals(-27.6, p.lat, 1e-9)
        assertEquals(-48.5, p.lon, 1e-9)
    }

    @Test
    fun tiposSemAcento() {
        val casos = mapOf(
            "Semaforo com Radar - 40 kmh@40" to (RadarKind.TRAFFIC_LIGHT_RADAR to 40),
            "Radar Movel - 60 kmh@60" to (RadarKind.MOBILE to 60),
            "Semaforo com Camera - 0 kmh@0" to (RadarKind.RED_LIGHT_CAMERA to 0),
            "Policia Rodoviaria - 0 kmh@0" to (RadarKind.POLICE to 0),
            "Pedagio - 0 kmh@0" to (RadarKind.TOLL to 0),
        )
        for ((desc, esperado) in casos) {
            val p = MapaRadar.parseLine(line(desc), 1)!!
            assertEquals(desc, esperado, p.kind to p.limit)
        }
    }

    @Test
    fun lombadaComumEEletronica() {
        val comum = MapaRadar.parseLine(line("Lombada - 0 kmh@0"), 1)!!
        assertEquals(RadarKind.BUMP to 0, comum.kind to comum.limit)
        val eletronica = MapaRadar.parseLine(line("Lombada - 50 kmh@50"), 2)!!
        assertEquals(RadarKind.ELECTRONIC_BUMP to 50, eletronica.kind to eletronica.limit)
    }

    @Test
    fun soQuemFiscalizaGuardaOLimite() {
        assertEquals(0, MapaRadar.parseLine(line("Pedagio - 80 kmh@80"), 1)!!.limit)
    }

    @Test
    fun linhasRuinsSaoIgnoradas() {
        val ruins = listOf(
            "a,b,Radar Fixo - 60 kmh@60",
            line("Tipo Novo - 60 kmh@60"),
            line("Radar Fixo"),
            line("Radar Fixo - 300 kmh@300"),
            line("Radar Fixo - 60 kmh@60", lon = "-10"),
            "-48,-27",
            "",
        )
        for (l in ruins) assertNull(l, MapaRadar.parseLine(l, 1))
    }

    @Test
    fun leOArquivoSemRepetidosEComIdPelaLinha() {
        val lines = sequenceOf(
            "-48.500000,-27.600000,Radar Fixo - 60 kmh@60",
            "-48.500000,-27.600000,Radar Fixo - 60 kmh@60",
            "lixo",
            "-48.510000,-27.610000,Pedagio - 0 kmh@0",
        )
        val pontos = MapaRadar.read(lines)
        assertEquals(listOf(RadarKind.FIXED, RadarKind.TOLL), pontos.map { it.kind })
        assertEquals(listOf(-1L, -4L), pontos.map { it.id })
    }

    // ---- contorno

    private val quadrado = """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":
        [[[-49,-28],[-48,-28],[-48,-27],[-49,-27],[-49,-28]]]}}"""

    @Test
    fun contornoDizSeOPontoEstaDentro() {
        val c = Contour.parse(quadrado)!!
        assertTrue(c.contains(-27.5, -48.5))
        assertFalse(c.contains(-25.0, -48.5))
        assertFalse(c.contains(-27.5, -50.0))
    }

    @Test
    fun contornoComBuracoEMultiPoligono() {
        val buraco = """{"type":"Polygon","coordinates":
            [[[-49,-28],[-48,-28],[-48,-27],[-49,-27],[-49,-28]],[[-48.6,-27.6],[-48.4,-27.6],[-48.4,-27.4],[-48.6,-27.4],[-48.6,-27.6]]]}"""
        val c = Contour.parse(buraco)!!
        assertTrue(c.contains(-27.9, -48.9))
        assertFalse(c.contains(-27.5, -48.5))
        val dois = """{"type":"MultiPolygon","coordinates":
            [[[[-49,-28],[-48,-28],[-48,-27],[-49,-27],[-49,-28]]],[[[-40,-20],[-39,-20],[-39,-19],[-40,-19],[-40,-20]]]]}"""
        val m = Contour.parse(dois)!!
        assertTrue(m.contains(-19.5, -39.5))
        assertTrue(m.contains(-27.5, -48.5))
        assertFalse(m.contains(-23.0, -44.0))
    }

    @Test
    fun contornoInvalidoENulo() {
        assertNull(Contour.parse("não é json"))
        assertNull(Contour.parse("""{"type":"Point","coordinates":[1,2]}"""))
    }

    // ---- junção com o OSM

    private val semVia: (Double, Double) -> WayHint? = { _, _ -> null }

    @Test
    fun sentidoDoRadarDoOsmQueEOMesmoPonto() {
        val osm = listOf(Radar(100, -27.6000, -48.5000, 60, 90))
        val pontos = listOf(MapaRadarPoint(-1, -27.60002, -48.50002, RadarKind.FIXED, 60))
        val out = MapaRadar.merge(osm, pontos, semVia)
        assertEquals(1, out.size)
        assertEquals(90, out[0].bearing)
        assertEquals(-1L, out[0].id)
        assertEquals(RadarKind.FIXED, out[0].kind)
    }

    @Test
    fun radarDoOsmSemParContinua() {
        val osm = listOf(Radar(100, -27.6, -48.5, 60, 90))
        val pontos = listOf(MapaRadarPoint(-1, -27.7, -48.5, RadarKind.FIXED, 60))
        val out = MapaRadar.merge(osm, pontos, semVia)
        assertEquals(setOf(-1L, 100L), out.map { it.id }.toSet())
        assertEquals(RadarKind.OSM, out.first { it.id == 100L }.kind)
    }

    @Test
    fun viaDeMaoUnicaDaOSentidoEDeMaoDuplaVale() {
        val p = listOf(MapaRadarPoint(-1, -27.6, -48.5, RadarKind.FIXED, 60))
        assertEquals(270, MapaRadar.merge(emptyList(), p) { _, _ -> WayHint(270.4, true) }[0].bearing)
        assertEquals(-1, MapaRadar.merge(emptyList(), p) { _, _ -> WayHint(270.0, false) }[0].bearing)
        assertEquals(0, MapaRadar.merge(emptyList(), p) { _, _ -> WayHint(359.6, true) }[0].bearing)
    }

    @Test
    fun pedagioNaoComeORadarDoOsmAoLado() {
        val osm = listOf(Radar(100, -27.6, -48.5, 60, 90))
        val pontos = listOf(MapaRadarPoint(-1, -27.6, -48.5, RadarKind.TOLL, 0))
        assertEquals(2, MapaRadar.merge(osm, pontos, semVia).size)
    }

    @Test
    fun umRadarDoOsmSoCasaComUmPonto() {
        val osm = listOf(Radar(100, -27.6, -48.5, 60, 90))
        val pontos = listOf(
            MapaRadarPoint(-1, -27.6, -48.5, RadarKind.FIXED, 60),
            MapaRadarPoint(-2, -27.6, -48.50001, RadarKind.FIXED, 60),
        )
        assertEquals(listOf(-1, 90), MapaRadar.merge(osm, pontos, semVia).map { it.bearing }.sorted())
    }

    // ---- via mais perto

    private fun run(oneway: Boolean, vararg pts: Pair<Double, Double>) = Run(
        Way(1, "Rua", null, 6, oneway, 50, 50, false, false),
        IntArray(pts.size) { (pts[it].first * 1e6).toInt() },
        IntArray(pts.size) { (pts[it].second * 1e6).toInt() },
    )

    @Test
    fun achaAViaMaisPertoEORumoDoTrecho() {
        // Via para leste, 20 m ao norte do ponto, de mão única
        val dLat = 20 / Geo.M_PER_DEG_LAT
        val leste = run(true, (-27.6 + dLat) to -48.51, (-27.6 + dLat) to -48.49)
        val hint = nearestWayHint(listOf(leste), -27.6, -48.5)!!
        assertEquals(90.0, hint.bearing, 1.0)
        assertTrue(hint.oneway)
    }

    @Test
    fun escolheAMaisPertoENaoAcimaDoLimite() {
        val dPerto = 10 / Geo.M_PER_DEG_LAT
        val dLonge = 30 / Geo.M_PER_DEG_LAT
        val perto = run(false, (-27.6 + dPerto) to -48.51, (-27.6 + dPerto) to -48.49)
        val longe = run(true, (-27.6 - dLonge) to -48.51, (-27.6 - dLonge) to -48.49)
        assertFalse(nearestWayHint(listOf(longe, perto), -27.6, -48.5)!!.oneway)
        val muitoLonge = run(true, (-27.6 + 100 / Geo.M_PER_DEG_LAT) to -48.51, (-27.6 + 100 / Geo.M_PER_DEG_LAT) to -48.49)
        assertNull(nearestWayHint(listOf(muitoLonge), -27.6, -48.5))
    }

    // ---- cache

    @Test
    fun cacheVoltaIgualEComOutraAssinaturaNao() {
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
