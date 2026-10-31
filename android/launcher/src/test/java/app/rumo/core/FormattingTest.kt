package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun compass() {
        assertEquals("N", compassOf(0f))
        assertEquals("N", compassOf(359f))
        assertEquals("L", compassOf(90f))
        assertEquals("SO", compassOf(225f))
        assertEquals("NO", compassOf(316f))
    }

    @Test
    fun direction() {
        assertEquals("sudeste", directionOf(135f))
        assertEquals("oeste", directionOf(268f))
    }

    @Test
    fun km() {
        assertEquals("0,8 km", formatKm(812.0))
        assertEquals("12,3 km", formatKm(12_340.0))
        assertEquals("154 km", formatKm(154_200.0))
    }

    @Test
    fun duration() {
        assertEquals("0:07", formatDuration(7 * 60_000L + 59_000))
        assertEquals("1:42", formatDuration(102 * 60_000L))
    }
}
