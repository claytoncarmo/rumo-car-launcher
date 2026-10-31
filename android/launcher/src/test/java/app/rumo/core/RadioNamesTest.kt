package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioNamesTest {
    private val hour = 3_600_000L

    @Test
    fun valePorSeisHoras() {
        val n = RadioNames()
        assertTrue(n.put(95900, " Penha ", 0))
        assertEquals("Penha", n.get(95900, 5 * hour))
        assertNull(n.get(95900, 6 * hour))
        assertNull(n.get(88100, 0))
    }

    @Test
    fun soAvisaQuandoMuda() {
        val n = RadioNames()
        assertTrue(n.put(95900, "Penha", 0))
        assertFalse(n.put(95900, "Penha", 10))
        assertTrue(n.put(95900, "Araquari", 20))
        assertFalse(n.put(95900, "  ", 30))
    }

    @Test
    fun guardaELeSoOsValidos() {
        val n = RadioNames()
        n.put(95900, "Penha", 0)
        n.put(102700, "Indaial", 5 * hour)
        val back = RadioNames()
        back.load(n.encode(5 * hour), 7 * hour)
        assertEquals(mapOf(102700 to "Indaial"), back.valid(7 * hour))
    }
}
