package app.rumo.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapStyleTest {
    private val fonts = File("/sdcard/Rumo/fonts")
    private val sc = File("/sdcard/Rumo/regions/sc/map.pmtiles")
    private val pr = File("/sdcard/Rumo/regions/pr/map.pmtiles")

    private fun ids(json: String): List<String> =
        JSONObject(json).getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    @Test
    fun oneSourcePerStateAndCorrectPaths() {
        val style = JSONObject(mapStyle(listOf(sc, pr), fonts, night = false))
        val sources = style.getJSONObject("sources")
        // um mapa por estado, mais a fonte dos radares e a dos pontos do usuário
        assertEquals(4, sources.length())
        assertEquals("pmtiles://file://" + sc.absolutePath, sources.getJSONObject("osm0").getString("url"))
        assertEquals("pmtiles://file://" + pr.absolutePath, sources.getJSONObject("osm1").getString("url"))
        assertTrue(style.getString("glyphs").startsWith("file://" + fonts.absolutePath))
    }

    @Test
    fun waterOfAllStatesComesBeforeRoads() {
        val layers = ids(mapStyle(listOf(sc, pr), fonts, night = false))
        val lastWater = layers.indexOfLast { it.startsWith("water") || it.startsWith("rivers") }
        val firstRoad = layers.indexOfFirst { it.startsWith("casing") || it.startsWith("minor") }
        assertTrue("water $lastWater, roads $firstRoad", lastWater < firstRoad)
        assertEquals("land", layers.first())
    }

    @Test
    fun darkHidesRoadCasing() {
        val style = JSONObject(mapStyle(listOf(sc), fonts, night = true))
        val layers = style.getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val casing = layers.filter { it.getString("id").startsWith("casing") }
        // As camadas existem nos dois temas (trocar o tema só muda cores e visibilidade), escondidas no escuro
        assertEquals(2, casing.size)
        assertTrue(casing.all { it.getJSONObject("layout").getString("visibility") == "none" })
        assertEquals(1, layers.count { it.getString("id").startsWith("names") })
    }

    @Test
    fun dayAndNightHaveTheSameLayers() {
        assertEquals(ids(mapStyle(listOf(sc, pr), fonts, night = false)), ids(mapStyle(listOf(sc, pr), fonts, night = true)))
    }

    @Test
    fun allIdsAreUnique() {
        val layers = ids(mapStyle(listOf(sc, pr), fonts, night = false))
        assertEquals(layers.size, layers.toSet().size)
    }

    @Test
    fun radarsStayAboveStreetsAndNames() {
        val layers = ids(mapStyle(listOf(sc, pr), fonts, night = false))
        val dot = layers.indexOf("radar-dot")
        val text = layers.indexOf("radar-text")
        assertTrue(dot > layers.indexOfLast { it.startsWith("names") })
        assertTrue(text > dot)
        // Os pontos do usuário por cima dos radares da base
        assertTrue(layers.indexOf("mine-dot") > text)
    }

    @Test
    fun radarSourceExistsEvenWithoutMap() {
        val style = JSONObject(mapStyle(emptyList(), fonts, night = true))
        assertEquals("geojson", style.getJSONObject("sources").getJSONObject(RADAR_SOURCE).getString("type"))
        assertEquals("geojson", style.getJSONObject("sources").getJSONObject(MINE_SOURCE).getString("type"))
    }
}
