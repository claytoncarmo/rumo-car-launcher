package app.rumo.device

import app.rumo.core.RadarKind
import app.rumo.core.UserPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserPointsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun point(lat: Double, kind: Int = RadarKind.FIXED) = UserPoint(lat, -48.5, kind, 60, 90, "2026-11-01")

    @Test
    fun semArquivoNaoHaPontos() {
        assertTrue(UserPointsStore(listOf(File(tmp.root, "a.csv"), File(tmp.root, "b.csv"))).load().isEmpty())
    }

    @Test
    fun gravaNosDoisELeDeVolta() {
        val a = File(tmp.root, "externo/meus-pontos.csv")
        val b = File(tmp.root, "interno/meus-pontos.csv")
        val store = UserPointsStore(listOf(a, b))
        val pontos = listOf(point(-27.6), point(-27.7, RadarKind.TOLL))
        assertEquals(2, store.save(pontos))
        assertTrue(a.isFile && b.isFile)
        assertEquals(pontos.map { it.lat }, store.load().map { it.lat })
        // um app novo (outro objeto, os mesmos arquivos) lê o que ficou: é o que acontece depois de atualizar
        assertEquals(pontos.map { it.kind }, UserPointsStore(listOf(a, b)).load().map { it.kind })
    }

    @Test
    fun valeOArquivoMaisRecente() {
        val a = File(tmp.root, "a.csv")
        val b = File(tmp.root, "b.csv")
        UserPointsStore(listOf(a)).save(listOf(point(-27.1)))
        UserPointsStore(listOf(b)).save(listOf(point(-27.2), point(-27.3)))
        a.setLastModified(1_000_000L)
        b.setLastModified(2_000_000L)
        assertEquals(2, UserPointsStore(listOf(a, b)).load().size)
        a.setLastModified(3_000_000L)
        assertEquals(1, UserPointsStore(listOf(a, b)).load().size)
    }

    @Test
    fun umLugarQueNaoGravaNaoImpedeOOutro() {
        // "pasta" é um arquivo comum: não dá para criar nada dentro dela
        val pasta = tmp.newFile("pasta")
        val ruim = File(pasta, "meus-pontos.csv")
        val bom = File(tmp.root, "interno/meus-pontos.csv")
        val store = UserPointsStore(listOf(ruim, bom))
        assertEquals(1, store.save(listOf(point(-27.6))))
        assertEquals(1, store.load().size)
    }

    @Test
    fun apagarTudoDeixaAListaVazia() {
        val f = File(tmp.root, "meus-pontos.csv")
        val store = UserPointsStore(listOf(f))
        store.save(listOf(point(-27.6)))
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
