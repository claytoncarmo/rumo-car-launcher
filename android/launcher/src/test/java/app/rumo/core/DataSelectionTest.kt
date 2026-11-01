package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSelectionTest {
    private val sha = "a".repeat(64)
    private fun file(dest: String, size: Long = 100) = RemoteFile(dest, size, sha, "https://example.org/" + dest.replace('/', '_'))
    private fun region(id: String) = Region(id, id.uppercase(), listOf(file("regions/$id/map.pmtiles"), file("regions/$id/roads.db", 50)))

    private val font = file("fonts/a.pbf", 5)
    private val commonMissing = DataPlan(listOf(font), emptyList(), 0)
    private val commonOk = DataPlan(emptyList(), emptyList(), 1)

    private fun missing(id: String) = RegionInfo(region(id), DataPlan(region(id).files, emptyList(), 0))
    private fun upToDate(id: String) = RegionInfo(region(id), DataPlan(emptyList(), emptyList(), 2))
    private fun outdated(id: String) = RegionInfo(region(id), DataPlan(listOf(region(id).files[0]), emptyList(), 2))

    @Test
    fun downloadsSelectedMissingWithCommonFiles() {
        val c = planChange(listOf(missing("sc"), missing("pr")), commonMissing, setOf("sc"))
        assertEquals(listOf("fonts/a.pbf", "regions/sc/map.pmtiles", "regions/sc/roads.db"), c.download.map { it.dest })
        assertEquals(155L, c.bytes)
        assertTrue(c.remove.isEmpty())
    }

    @Test
    fun updatesOnlyWhatChanged() {
        val c = planChange(listOf(outdated("sc"), upToDate("pr")), commonOk, setOf("sc", "pr"))
        assertEquals(listOf("regions/sc/map.pmtiles"), c.download.map { it.dest })
    }

    @Test
    fun removesInstalledUnselected() {
        val c = planChange(listOf(upToDate("sc"), upToDate("pr"), missing("rs")), commonOk, setOf("sc"))
        assertTrue(c.download.isEmpty())
        assertEquals(listOf("pr"), c.remove.map { it.id })
        assertTrue(!c.isEmpty)
    }

    @Test
    fun doesNotRemoveWhatWasNotInstalled() {
        val c = planChange(listOf(missing("sc")), commonMissing, emptySet())
        assertTrue(c.isEmpty)
    }

    @Test
    fun noChangeDoesNothing() {
        val regions = listOf(upToDate("sc"), missing("pr"))
        val c = planChange(regions, commonOk, installedIds(regions))
        assertTrue(c.isEmpty)
        assertEquals(setOf("sc"), installedIds(regions))
    }

    @Test
    fun sameFileInTwoStatesDownloadsOnce() {
        val twin = RegionInfo(region("sc"), DataPlan(listOf(font, region("sc").files[1]), emptyList(), 0))
        val c = planChange(listOf(twin), commonMissing, setOf("sc"))
        assertEquals(1, c.download.count { it.dest == "fonts/a.pbf" })
    }
}
