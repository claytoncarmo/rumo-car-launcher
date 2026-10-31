package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private val sha = "a".repeat(64)

    private fun appJson(code: Long = 1060, url: String = "https://github.com/x/y/releases/download/nightly/rumo.apk") =
        """{"versionName":"0.5.0-nightly.60","versionCode":$code,"sha256":"$sha","size":18022389,"url":"$url",
            "builtAt":"2026-10-31T03:00:00Z","commit":"abc1234"}"""

    @Test
    fun leOManifestoDoApp() {
        val r = parseAppRelease(appJson())!!
        assertEquals("0.5.0-nightly.60", r.versionName)
        assertEquals(1060L, r.versionCode)
        assertEquals(18022389L, r.size)
        assertEquals("abc1234", r.commit)
    }

    @Test
    fun recusaManifestoDoAppInvalido() {
        assertNull(parseAppRelease("não é json"))
        assertNull(parseAppRelease("""{"versionName":"1.0.0"}"""))
        assertNull(parseAppRelease(appJson(url = "http://exemplo.org/rumo.apk")))
        assertNull(parseAppRelease(appJson().replace(sha, "curto")))
    }

    @Test
    fun comparaComAVersaoInstalada() {
        val r = parseAppRelease(appJson(code = 1060))!!
        assertEquals(UpdateKind.NEWER, compareWithInstalled(1050, r))
        assertEquals(UpdateKind.SAME, compareWithInstalled(1060, r))
        assertEquals(UpdateKind.OLDER, compareWithInstalled(1061, r))
    }

    @Test
    fun canalDaVersaoInstalada() {
        assertEquals("nightly", installedChannelLabel("0.5.0-nightly.57"))
        assertEquals("estável", installedChannelLabel("0.5.0"))
        assertEquals("build local", installedChannelLabel("0.0.0-dev"))
    }

    private fun dataJson(dest: String = "regioes/sc/mapa.pmtiles", id: String = "sc") =
        """{"versao":2,"geradoEm":"2026-10-31T03:00:00Z",
            "comum":[{"destino":"fontes/NotoSansMedium/0-255.pbf","tamanho":5000,"sha256":"${"c".repeat(64)}","url":"https://exemplo.org/f.pbf"}],
            "regioes":[{"id":"$id","nome":"Santa Catarina","grupo":"Sul","arquivos":[
            {"destino":"$dest","tamanho":310000000,"sha256":"$sha","url":"https://exemplo.org/mapa.pmtiles"},
            {"destino":"regioes/sc/vias.db","tamanho":100000000,"sha256":"${"b".repeat(64)}","url":"https://exemplo.org/vias.db"}]}]}"""

    @Test
    fun leOManifestoDosDados() {
        val m = parseDataManifest(dataJson())!!
        assertEquals("2026-10-31T03:00:00Z", m.builtAt)
        val sc = m.regions.single()
        assertEquals("Santa Catarina", sc.name)
        assertEquals(listOf("regioes/sc/mapa.pmtiles", "regioes/sc/vias.db"), sc.files.map { it.dest })
        assertEquals(410_000_000L, sc.totalSize)
        assertEquals("Sul", sc.group)
        assertEquals(listOf("fontes/NotoSansMedium/0-255.pbf"), m.common.map { it.dest })
    }

    @Test
    fun manifestoAntigoSemComumEGrupoAindaLe() {
        val antigo = """{"versao":1,"geradoEm":"2026-10-31T03:00:00Z","regioes":[{"id":"sc","nome":"Santa Catarina","arquivos":[
            {"destino":"mapa.pmtiles","tamanho":100,"sha256":"$sha","url":"https://exemplo.org/mapa.pmtiles"}]}]}"""
        val m = parseDataManifest(antigo)!!
        assertTrue(m.common.isEmpty())
        assertEquals("", m.regions.single().group)
    }

    @Test
    fun recusaIdDeRegiaoQueViraPastaPerigosa() {
        for (ruim in listOf("../x", "SC", "a/b", "", "a".repeat(17), "s c")) {
            assertNull("id '$ruim' devia ser recusado", parseDataManifest(dataJson(id = ruim)))
        }
        assertTrue(isSafeRegionId("sc"))
        assertTrue(isSafeRegionId("mg_2"))
    }

    @Test
    fun recusaRegioesComIdRepetido() {
        val um = dataJson()
        val regiao = um.substring(um.indexOf("{\"id\""), um.lastIndexOf("]}"))
        assertNull(parseDataManifest(um.replace(regiao, "$regiao,$regiao")))
    }

    @Test
    fun recusaDestinoQueSaiDaPastaRumo() {
        // "a\\\\b" vira a\\b no JSON, que o parser lê como a\b (com barra invertida)
        for (ruim in listOf("../mapa.pmtiles", "/sdcard/mapa.pmtiles", "a/../b", "a\\\\b", "a//b", "./x", "")) {
            assertNull("destino '$ruim' devia ser recusado", parseDataManifest(dataJson(ruim)))
        }
        assertNotNull(parseDataManifest(dataJson("regioes/sc/outro.pmtiles")))
        assertTrue(isSafeDestination("mapa.pmtiles"))
        assertFalse(isSafeDestination(".."))
    }

    @Test
    fun recusaManifestoDosDadosInvalido() {
        assertNull(parseDataManifest("{}"))
        assertNull(parseDataManifest("""{"regioes":[]}"""))
        assertNull(parseDataManifest(dataJson().replace("https://exemplo.org/vias.db", "http://exemplo.org/vias.db")))
        assertNull(parseDataManifest(dataJson().replace("https://exemplo.org/f.pbf", "http://exemplo.org/f.pbf")))
    }

    private val mapa = RemoteFile("regioes/sc/mapa.pmtiles", 100, sha, "https://exemplo.org/mapa.pmtiles")
    private val vias = RemoteFile("regioes/sc/vias.db", 50, "b".repeat(64), "https://exemplo.org/vias.db")
    private val regiao = Region("sc", "Santa Catarina", listOf(mapa, vias))

    @Test
    fun semNadaNoAparelhoBaixaTudo() {
        val plano = planData(regiao, { LocalFile(false, 0, null) }, { error("não devia calcular hash") })
        assertEquals(listOf(mapa, vias), plano.download)
        assertEquals(150L, plano.bytes)
        assertEquals(DataStatus.NOT_INSTALLED, plano.status)
    }

    @Test
    fun arquivoRegistradoENoTamanhoCertoEstaEmDia() {
        val plano = planData(
            regiao,
            { f -> LocalFile(true, f.size, f.sha256) },
            { error("com registro não precisa calcular hash") },
        )
        assertTrue(plano.download.isEmpty())
        assertEquals(DataStatus.UP_TO_DATE, plano.status)
    }

    @Test
    fun tamanhoOuHashDiferenteBaixaDeNovo() {
        val plano = planData(
            regiao,
            { f -> if (f == mapa) LocalFile(true, 99, mapa.sha256) else LocalFile(true, f.size, "c".repeat(64)) },
            { "d".repeat(64) },
        )
        assertEquals(listOf(mapa, vias), plano.download)
        assertEquals(DataStatus.UPDATE, plano.status)
    }

    @Test
    fun copiadoAMaoSemRegistroConfereOHash() {
        val plano = planData(
            regiao,
            { f -> LocalFile(true, f.size, null) },
            { f -> f.sha256 },
        )
        assertTrue(plano.download.isEmpty())
        assertEquals(listOf(mapa, vias), plano.verified)
    }

    private fun radarJson(dest: String = "maparadar.csv", url: String = "https://exemplo.org/maparadar.csv") =
        """{"versao":1,"geradoEm":"2026-10-31T03:00:00Z","linhas":39975,
            "arquivo":{"destino":"$dest","tamanho":1700000,"sha256":"$sha","url":"$url"}}"""

    @Test
    fun leOManifestoDoMapaRadar() {
        val f = parseMapaRadarManifest(radarJson())!!
        assertEquals("maparadar.csv", f.dest)
        assertEquals(1_700_000L, f.size)
    }

    @Test
    fun recusaManifestoDoMapaRadarInvalido() {
        assertNull(parseMapaRadarManifest("{}"))
        assertNull(parseMapaRadarManifest(radarJson(dest = "../x.csv")))
        assertNull(parseMapaRadarManifest(radarJson(dest = "outro.csv")))
        assertNull(parseMapaRadarManifest(radarJson(url = "http://exemplo.org/maparadar.csv")))
    }
}
