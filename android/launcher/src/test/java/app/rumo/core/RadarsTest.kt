package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarsTest {
    private val lat0 = -27.0
    private val lon0 = -49.0
    // ~1 m de longitude a -27°
    private val dLon = 1.0 / (Geo.M_PER_DEG_LON * kotlin.math.cos(Math.toRadians(lat0)))

    // Radar a 400 m para leste, fiscalizando quem vai para leste, limite 60
    private val radar = Radar(1, lat0, lon0 + 400 * dLon, 60, 90)

    @Test
    fun entraNaZonaUmaVezSo() {
        val w = RadarWatch(500)
        assertTrue(w.update(lat0, lon0, 90f, 50, listOf(radar)))
        assertEquals(400.0, w.current!!.distanceM.toDouble(), 2.0)
        assertFalse(w.current!!.alarm)
        assertFalse(w.update(lat0, lon0 + 100 * dLon, 90f, 50, listOf(radar)))
        assertNotNull(w.current)
    }

    @Test
    fun acimaDoLimiteDoRadarDisparaOAlarme() {
        val w = RadarWatch(500)
        w.update(lat0, lon0 + 300 * dLon, 90f, 72, listOf(radar))
        val a = w.current!!
        assertTrue(a.alarm)
        assertEquals(0.8f, a.closeness(500), 0.01f)
    }

    @Test
    fun foraDaZonaOuNoSentidoContrarioNaoAvisa() {
        val w = RadarWatch(500)
        assertFalse(w.update(lat0, lon0 - 200 * dLon, 90f, 50, listOf(radar)))  // 600 m
        assertNull(w.current)
        // indo para oeste, do outro lado do radar
        assertFalse(w.update(lat0, lon0 + 700 * dLon, 270f, 50, listOf(radar)))
        assertNull(w.current)
    }

    @Test
    fun radarDeOutroSentidoNaoAvisa() {
        val w = RadarWatch(500)
        val paraOeste = Radar(2, lat0, lon0 + 300 * dLon, 60, 270)
        assertFalse(w.update(lat0, lon0, 90f, 50, listOf(paraOeste)))
        assertNull(w.current)
        // sem sentido definido (-1): avisa
        assertTrue(w.update(lat0, lon0, 90f, 50, listOf(Radar(3, lat0, lon0 + 300 * dLon, 60, -1))))
    }

    @Test
    fun saiDaZonaDepoisDePassar() {
        val w = RadarWatch(500)
        w.update(lat0, lon0 + 390 * dLon, 90f, 50, listOf(radar))
        assertNotNull(w.current)
        w.update(lat0, lon0 + 460 * dLon, 90f, 50, listOf(radar))
        assertNull(w.current)
    }

    @Test
    fun distanciaFormatada() {
        assertEquals("350 m", formatDistance(347))
        assertEquals("10 m", formatDistance(3))
        assertEquals("1,2 km", formatDistance(1234))
    }

    @Test
    fun frequenciaDoRadio() {
        assertEquals("102,7 FM", formatRadioFreq(102700))
        assertEquals("88,1 FM", formatRadioFreq(88100))
        assertEquals("1020 AM", formatRadioFreq(1020))
    }

    @Test
    fun celulasIguaisAoGerador() {
        // mesmos números de dados/vias.py (cell_of)
        assertEquals(Geo.cellOf(-27.5951, -48.5480), Geo.cellOf(-27.5901, -48.5401))
        assertEquals(226777100L, Geo.cellOf(-27.0005, -48.9995))
        assertTrue(Geo.cellsAround(-27.0, -49.0, 40.0).size in 1..4)
    }

    @Test
    fun semLimiteNuncaDisparaOAlarme() {
        val w = RadarWatch(500)
        val pedagio = Radar(4, lat0, lon0 + 200 * dLon, 0, -1, RadarKind.TOLL)
        assertTrue(w.update(lat0, lon0, 90f, 110, listOf(pedagio)))
        assertFalse(w.current!!.alarm)
        assertFalse(pedagio.enforcesSpeed)
        assertFalse(pedagio.chimes)
    }

    @Test
    fun quemFiscalizaEPoliciaAvisamComSom() {
        assertTrue(Radar(5, lat0, lon0, 60, -1, RadarKind.FIXED).chimes)
        assertTrue(Radar(6, lat0, lon0, 0, -1, RadarKind.POLICE).chimes)
        assertFalse(Radar(7, lat0, lon0, 0, -1, RadarKind.BUMP).chimes)
    }

    @Test
    fun lombadaEPedagioAvisamMaisPerto() {
        val w = RadarWatch(500)
        val lombada = Radar(8, lat0, lon0 + 400 * dLon, 0, -1, RadarKind.BUMP)
        assertFalse(w.update(lat0, lon0, 90f, 50, listOf(lombada)))  // 400 m: fora da zona de 300 m
        assertNull(w.current)
        assertTrue(w.update(lat0, lon0 + 150 * dLon, 90f, 50, listOf(lombada)))  // 250 m
    }

    @Test
    fun pontosLongeNemEntramNaConta() {
        val w = RadarWatch(500)
        val longe = Radar(9, lat0 + 0.5, lon0, 60, -1, RadarKind.FIXED)
        assertFalse(w.update(lat0, lon0, 90f, 50, listOf(longe)))
        assertNull(w.current)
    }

    @Test
    fun nomesDosTipos() {
        assertEquals("Radar", radarLabel(RadarKind.OSM))
        assertEquals("Radar", radarLabel(RadarKind.FIXED))
        assertEquals("Pedágio", radarLabel(RadarKind.TOLL))
        assertEquals("Lombada", radarLabel(RadarKind.BUMP))
    }
}
