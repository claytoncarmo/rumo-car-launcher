package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioNamesTest {
    // Jaraguá do Sul; Florianópolis fica a ~170 km
    private val lat = -26.486
    private val lon = -49.067

    @Test
    fun doesNotExpireByTime() {
        val n = RadioNames()
        n.onPosition(lat, lon)
        assertTrue(n.put(95900, " Penha "))
        assertEquals("Penha", n.get(95900))
        assertNull(n.get(88100))
    }

    @Test
    fun onlyReportsWhenChanged() {
        val n = RadioNames()
        assertTrue(n.put(95900, "Penha"))
        assertFalse(n.put(95900, "Penha"))
        assertTrue(n.put(95900, "Araquari"))
        assertFalse(n.put(95900, "  "))
    }

    @Test
    fun nearCenterKeepsAndFarResets() {
        val n = RadioNames()
        n.onPosition(lat, lon)
        n.put(95900, "Penha")
        assertFalse(n.onPosition(lat + 0.18, lon)) // ~20 km
        assertEquals(mapOf(95900 to "Penha"), n.valid())
        assertTrue(n.onPosition(-27.595, -48.548)) // Florianópolis
        assertTrue(n.valid().isEmpty())
    }

    @Test
    fun afterResetRestartUsesNewPlace() {
        val n = RadioNames()
        n.onPosition(lat, lon)
        n.put(95900, "Penha")
        n.onPosition(-27.595, -48.548)
        n.put(95900, "Floripa")
        assertFalse(n.onPosition(-27.6, -48.55))
        assertEquals("Floripa", n.get(95900))
    }

    @Test
    fun nameStoredWithoutGpsGetsCenterOnFirstFix() {
        val n = RadioNames()
        n.put(95900, "Penha")
        assertTrue(n.onPosition(lat, lon))
        assertFalse(n.onPosition(lat, lon))
        assertTrue(n.onPosition(-27.595, -48.548))
        assertTrue(n.valid().isEmpty())
    }

    @Test
    fun saveAndRestoreWithCenter() {
        val n = RadioNames()
        n.onPosition(lat, lon)
        n.put(95900, "Penha")
        n.put(102700, "Indaial")
        val back = RadioNames()
        back.load(n.encode())
        assertEquals(mapOf(95900 to "Penha", 102700 to "Indaial"), back.valid())
        assertTrue(back.onPosition(-27.595, -48.548))
        assertTrue(back.valid().isEmpty())
    }

    @Test
    fun legacyFormatWithTimeIsDiscarded() {
        val back = RadioNames()
        back.load("95900\tPenha\t1793391816000")
        assertTrue(back.valid().isEmpty())
    }
}
