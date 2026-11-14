package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRadioTest {
    private val json = """
        [
          {"stationuuid":"a1","name":"  Litoral   Notícias 91,3 FM ","url":"http://x/pls","url_resolved":"http://s1/live",
           "tags":"News, Talk","state":"Santa Catarina","codec":"AAC","bitrate":64,"hls":0,"lastcheckok":1,
           "geo_lat":-27.59,"geo_long":-48.55},
          {"stationuuid":"a2","name":"Litoral Notícias 91,3 FM","url_resolved":"http://s2/live","state":"Santa Catarina","lastcheckok":1},
          {"stationuuid":"a3","name":"Fora do ar","url_resolved":"http://s3/live","lastcheckok":0},
          {"stationuuid":"a4","name":"Sem endereço","url_resolved":"","url":""},
          {"stationuuid":"a5","name":"Rock Estrada","url_resolved":"https://s5/live.m3u8","tags":"rock,classic rock",
           "state":"PR","codec":"UNKNOWN","bitrate":0,"hls":1,"geo_lat":null,"geo_long":null},
          {"stationuuid":"a6","name":"Bola na Rede","url_resolved":"http://s6/live","tags":"futebol","state":"Porto Alegre"},
          {"stationuuid":"a7","name":"Repetida","url_resolved":"http://s1/live"}
        ]
    """.trimIndent()

    @Test
    fun parsesSkipsBrokenAndRepeated() {
        val list = WebRadio.parse(json)
        assertEquals(listOf("a1", "a5", "a6"), list.map { it.id })
        val a = list[0]
        assertEquals("Litoral Notícias 91,3 FM", a.name)
        assertEquals("http://s1/live", a.url)
        assertEquals("news,talk", a.tags)
        assertEquals("AAC", a.codec)
        assertEquals(64, a.kbps)
        assertEquals(-27.59, a.lat!!, 1e-9)
        val rock = list[1]
        assertTrue(rock.hls)
        assertEquals("", rock.codec)
        assertNull(rock.lat)
    }

    @Test
    fun filters() {
        val list = WebRadio.parse(json)
        assertEquals(listOf("a1"), WebRadio.filter(list, WebFilter.NEWS).map { it.id })
        assertEquals(listOf("a6"), WebRadio.filter(list, WebFilter.SPORTS).map { it.id })
        assertEquals(listOf("a5"), WebRadio.filter(list, WebFilter.MUSIC).map { it.id })
        assertEquals(3, WebRadio.filter(list, WebFilter.ALL).size)
    }

    @Test
    fun nearByStateOrDistance() {
        val list = WebRadio.parse(json)
        val sc = WebRadio.stateOf("sc")
        assertEquals(listOf("a1"), WebRadio.near(list, sc, null, null).map { it.id })
        assertEquals(listOf("a5"), WebRadio.near(list, WebRadio.stateOf("PR"), null, null).map { it.id })
        // Sem o estado, só pela posição da estação: Itajaí fica a ~80 km de Florianópolis
        assertEquals(listOf("a1"), WebRadio.near(list, null, -26.91, -48.66).map { it.id })
        assertTrue(WebRadio.near(list, null, -23.55, -46.63).isEmpty())
    }

    @Test
    fun texts() {
        val list = WebRadio.parse(json)
        assertEquals("Notícias · Santa Catarina", WebRadio.subtitle(list[0]))
        assertEquals("Rock · PR", WebRadio.subtitle(list[1]))
        assertEquals("AAC 64 kbps", WebRadio.quality(list[0]))
        assertEquals("", WebRadio.quality(list[1]))
    }

    @Test
    fun fmFromName() {
        assertEquals(91_300, WebRadio.fmKhz("Litoral Notícias 91,3 FM"))
        assertEquals(104_500, WebRadio.fmKhz("Rádio 104.5"))
        assertNull(WebRadio.fmKhz("Rádio 1.040 AM"))
        assertNull(WebRadio.fmKhz("Clássica 24h"))
        assertNull(WebRadio.fmKhz("Rádio 120.5"))
    }

    @Test
    fun favoritesRoundTripToggleAndStep() {
        val list = WebRadio.parse(json)
        val favs = WebRadio.toggle(WebRadio.toggle(emptyList(), list[0]), list[1])
        val back = WebRadio.decode(WebRadio.encode(favs))
        assertEquals(favs.map { it.copy(lat = null, lon = null) }, back)
        assertEquals(listOf("a5"), WebRadio.toggle(back, list[0]).map { it.id })
        assertEquals("a5", WebRadio.step(back, "a1", true)?.id)
        assertEquals("a5", WebRadio.step(back, "a1", false)?.id)
        assertEquals("a1", WebRadio.step(back, "zz", true)?.id)
        assertEquals("a5", WebRadio.step(back, "zz", false)?.id)
        assertNull(WebRadio.step(emptyList(), "a1", true))
        assertTrue(WebRadio.decode("lixo\n\t\t").isEmpty())
    }

    @Test
    fun statesAndQueries() {
        assertEquals("SC", WebRadio.nearestState(-27.0, -48.7).code)
        assertEquals("Santa Catarina", WebRadio.stateOf("SC")?.name)
        assertNull(WebRadio.stateOf("XX"))
        assertTrue(WebRadio.stateQuery(WebRadio.stateOf("SP")!!).contains("state=S%C3%A3o%20Paulo"))
        assertFalse(WebRadio.countryQuery().contains(" "))
        assertEquals("sao paulo", WebRadio.key(" São Paulo"))
    }
}
