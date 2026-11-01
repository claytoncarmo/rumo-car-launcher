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
    fun noFileNoPoints() {
        assertTrue(UserPointsStore(listOf(File(tmp.root, "a.csv"), File(tmp.root, "b.csv"))).load().isEmpty())
    }

    @Test
    fun writesToBothAndReadsBack() {
        val a = File(tmp.root, "external/my-points.csv")
        val b = File(tmp.root, "internal/my-points.csv")
        val store = UserPointsStore(listOf(a, b))
        val points = listOf(point(-27.6), point(-27.7, RadarKind.TOLL))
        assertEquals(2, store.save(points))
        assertTrue(a.isFile && b.isFile)
        assertEquals(points.map { it.lat }, store.load().map { it.lat })
        // um app novo (outro objeto, os mesmos arquivos) lê o que ficou: é o que acontece depois de atualizar
        assertEquals(points.map { it.kind }, UserPointsStore(listOf(a, b)).load().map { it.kind })
    }

    @Test
    fun newestFileWins() {
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
    fun aPlaceThatCannotWriteDoesNotBlockTheOther() {
        // "folder" é um arquivo comum: não dá para criar nada dentro dela
        val folder = tmp.newFile("folder")
        val bad = File(folder, "my-points.csv")
        val good = File(tmp.root, "internal/my-points.csv")
        val store = UserPointsStore(listOf(bad, good))
        assertEquals(1, store.save(listOf(point(-27.6))))
        assertEquals(1, store.load().size)
    }

    @Test
    fun deletingAllLeavesEmptyList() {
        val f = File(tmp.root, "my-points.csv")
        val store = UserPointsStore(listOf(f))
        store.save(listOf(point(-27.6)))
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
