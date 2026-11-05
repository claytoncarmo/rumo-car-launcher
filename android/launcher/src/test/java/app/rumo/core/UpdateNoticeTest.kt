package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateNoticeTest {
    private val sha = "a".repeat(64)

    private fun release(code: Long = 1060, notes: String = "") =
        parseAppRelease(
            """{"versionName":"0.5.0","versionCode":$code,"sha256":"$sha","size":18022389,
                "url":"https://github.com/x/y/releases/download/v0.5.0/rumo.apk"$notes}""",
        )!!

    @Test
    fun noticeFollowsTheInstalledChannel() {
        assertEquals(Channel.STABLE, noticeChannel("0.1.1"))
        assertEquals(Channel.NIGHTLY, noticeChannel("0.1.1-nightly.57"))
        assertNull(noticeChannel("0.0.0-dev"))
    }

    @Test
    fun checksEveryFourHours() {
        val hour = 3_600_000L
        assertEquals(0L, msUntilUpdateCheck(0, 5 * hour))
        assertEquals(2 * hour, msUntilUpdateCheck(10 * hour, 12 * hour))
        assertEquals(0L, msUntilUpdateCheck(10 * hour, 14 * hour))
        assertEquals(0L, msUntilUpdateCheck(10 * hour, 30 * hour))
    }

    @Test
    fun clockBehindTheLastCheckChecksNow() {
        assertEquals(0L, msUntilUpdateCheck(lastCheckedAt = 100 * 3_600_000L, now = 5 * 3_600_000L))
    }

    @Test
    fun onlyNewerReleasesAreNoticed() {
        assertNotNull(newerThanInstalled(1050, release(1060)))
        assertNull(newerThanInstalled(1060, release(1060)))
        assertNull(newerThanInstalled(1061, release(1060)))
        assertNull(newerThanInstalled(1050, null))
    }

    @Test
    fun readsNotesAndKeepsThemShort() {
        val long = "x".repeat(300)
        val many = (1..12).joinToString(",") { "\"nota $it\"" }
        val r = release(notes = ""","notes":["  Mapa mais fluido ","","$long",$many]""")
        assertEquals("Mapa mais fluido", r.notes.first())
        assertEquals(MAX_NOTES, r.notes.size)
        assertEquals(MAX_NOTE_CHARS, r.notes[1].length)
    }

    @Test
    fun oldManifestHasNoNotes() {
        assertEquals(emptyList<String>(), release().notes)
        assertEquals(emptyList<String>(), release(notes = ""","notes":"texto solto"""").notes)
    }

    @Test
    fun releaseSurvivesTheRoundTripThroughJson() {
        val original = release(notes = ""","notes":["Correções de erros","Rádio mais estável"]""")
        assertEquals(original, parseAppRelease(original.toJson()))
    }
}
