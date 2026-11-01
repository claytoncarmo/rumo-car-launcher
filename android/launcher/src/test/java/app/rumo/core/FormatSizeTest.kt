package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class FormatSizeTest {
    @Test
    fun fileSize() {
        assertEquals("800 B", formatSize(800))
        assertEquals("12 kB", formatSize(12_345))
        assertEquals("18 MB", formatSize(18_022_389))
        assertEquals("432 MB", formatSize(431_543_135))
        assertEquals("1,2 GB", formatSize(1_234_000_000))
    }

    @Test
    fun publishDateInDeviceTimeZone() {
        assertEquals("31/10 03:00", formatBuiltAt("2026-10-31T03:00:00Z", ZoneOffset.UTC))
        assertEquals("31/10 00:00", formatBuiltAt("2026-10-31T03:00:00Z", ZoneOffset.ofHours(-3)))
        assertEquals("", formatBuiltAt("ontem", ZoneOffset.UTC))
        assertEquals("", formatBuiltAt("", ZoneOffset.UTC))
    }
}
