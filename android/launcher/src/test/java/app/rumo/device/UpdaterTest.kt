package app.rumo.device

import app.rumo.core.AppRelease
import app.rumo.core.DataManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UpdaterTest {
    private val release = AppRelease("0.1.0", 1049, "abc", 10, "https://example.com/rumo.apk", "2026-11-01", "bb41111")
    private val found = ChannelInfo(release, null)
    private val offline = ChannelInfo(null, "Sem conexão com o GitHub", offline = true)

    @Test
    fun offlineChannelKeepsTheLastKnownRelease() {
        assertSame(found, offline.keepingLast(found))
    }

    @Test
    fun offlineChannelWithNothingBeforeShowsTheProblem() {
        assertSame(offline, offline.keepingLast(null))
        assertSame(offline, offline.keepingLast(ChannelInfo(null, "Nenhuma versão publicada")))
    }

    @Test
    fun serverErrorReplacesTheLastKnownRelease() {
        val error = ChannelInfo(null, "Erro 500 no servidor")
        assertSame(error, error.keepingLast(found))
    }

    @Test
    fun freshResultReplacesTheLastKnownRelease() {
        val newer = ChannelInfo(release.copy(versionName = "0.1.1"), null)
        assertEquals("0.1.1", newer.keepingLast(found).release?.versionName)
    }

    @Test
    fun offlineDataKeepsTheLastManifest() {
        val before = DataInfo(DataManifest("2026-11-01", emptyList()), emptyList(), null, null)
        val none = DataInfo(null, emptyList(), null, "Sem conexão com o GitHub", offline = true)
        assertSame(before, none.keepingLast(before))
        assertSame(none, none.keepingLast(null))
    }
}
