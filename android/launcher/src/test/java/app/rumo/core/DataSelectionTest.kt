package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSelectionTest {
    private val sha = "a".repeat(64)
    private fun file(dest: String, size: Long = 100) = RemoteFile(dest, size, sha, "https://exemplo.org/" + dest.replace('/', '_'))
    private fun region(id: String) = Region(id, id.uppercase(), listOf(file("regioes/$id/mapa.pmtiles"), file("regioes/$id/vias.db", 50)))

    private val fonte = file("fontes/a.pbf", 5)
    private val commonMissing = DataPlan(listOf(fonte), emptyList(), 0)
    private val commonOk = DataPlan(emptyList(), emptyList(), 1)

    private fun missing(id: String) = RegionInfo(region(id), DataPlan(region(id).files, emptyList(), 0))
    private fun upToDate(id: String) = RegionInfo(region(id), DataPlan(emptyList(), emptyList(), 2))
    private fun outdated(id: String) = RegionInfo(region(id), DataPlan(listOf(region(id).files[0]), emptyList(), 2))

    @Test
    fun baixaOsMarcadosQueFaltamComAsFontes() {
        val c = planChange(listOf(missing("sc"), missing("pr")), commonMissing, setOf("sc"))
        assertEquals(listOf("fontes/a.pbf", "regioes/sc/mapa.pmtiles", "regioes/sc/vias.db"), c.download.map { it.dest })
        assertEquals(155L, c.bytes)
        assertTrue(c.remove.isEmpty())
    }

    @Test
    fun atualizaSoOQueMudou() {
        val c = planChange(listOf(outdated("sc"), upToDate("pr")), commonOk, setOf("sc", "pr"))
        assertEquals(listOf("regioes/sc/mapa.pmtiles"), c.download.map { it.dest })
    }

    @Test
    fun removeOsInstaladosDesmarcados() {
        val c = planChange(listOf(upToDate("sc"), upToDate("pr"), missing("rs")), commonOk, setOf("sc"))
        assertTrue(c.download.isEmpty())
        assertEquals(listOf("pr"), c.remove.map { it.id })
        assertTrue(!c.isEmpty)
    }

    @Test
    fun naoRemoveOQueNaoEstavaInstalado() {
        val c = planChange(listOf(missing("sc")), commonMissing, emptySet())
        assertTrue(c.isEmpty)
    }

    @Test
    fun semMudancaNaoFazNada() {
        val regions = listOf(upToDate("sc"), missing("pr"))
        val c = planChange(regions, commonOk, installedIds(regions))
        assertTrue(c.isEmpty)
        assertEquals(setOf("sc"), installedIds(regions))
    }

    @Test
    fun arquivoIgualEmDoisEstadosBaixaUmaVez() {
        val dupla = RegionInfo(region("sc"), DataPlan(listOf(fonte, region("sc").files[1]), emptyList(), 0))
        val c = planChange(listOf(dupla), commonMissing, setOf("sc"))
        assertEquals(1, c.download.count { it.dest == "fontes/a.pbf" })
    }
}
