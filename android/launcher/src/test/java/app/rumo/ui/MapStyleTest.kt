package app.rumo.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapStyleTest {
    private val fonts = File("/sdcard/Rumo/fontes")
    private val sc = File("/sdcard/Rumo/regioes/sc/mapa.pmtiles")
    private val pr = File("/sdcard/Rumo/regioes/pr/mapa.pmtiles")

    private fun ids(json: String): List<String> =
        JSONObject(json).getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    @Test
    fun umaFontePorEstadoEOsCaminhosCertos() {
        val style = JSONObject(mapStyle(listOf(sc, pr), fonts, night = false))
        val sources = style.getJSONObject("sources")
        assertEquals(2, sources.length())
        assertEquals("pmtiles://file://" + sc.absolutePath, sources.getJSONObject("osm0").getString("url"))
        assertEquals("pmtiles://file://" + pr.absolutePath, sources.getJSONObject("osm1").getString("url"))
        assertTrue(style.getString("glyphs").startsWith("file://" + fonts.absolutePath))
    }

    @Test
    fun aAguaDeTodosOsEstadosVemAntesDasVias() {
        val layers = ids(mapStyle(listOf(sc, pr), fonts, night = false))
        val lastWater = layers.indexOfLast { it.startsWith("water") || it.startsWith("rivers") }
        val firstRoad = layers.indexOfFirst { it.startsWith("casing") || it.startsWith("minor") }
        assertTrue("água $lastWater, vias $firstRoad", lastWater < firstRoad)
        assertEquals("land", layers.first())
    }

    @Test
    fun noEscuroNaoTemContornoDeVia() {
        val layers = ids(mapStyle(listOf(sc), fonts, night = true))
        assertTrue(layers.none { it.startsWith("casing") })
        assertEquals(1, layers.count { it.startsWith("names") })
    }

    @Test
    fun todosOsIdsSaoUnicos() {
        val layers = ids(mapStyle(listOf(sc, pr), fonts, night = false))
        assertEquals(layers.size, layers.toSet().size)
    }
}
