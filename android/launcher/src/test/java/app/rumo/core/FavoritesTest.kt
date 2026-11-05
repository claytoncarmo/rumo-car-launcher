package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoritesTest {
    private val list = listOf(Favorite(88700, "Atlântida"), Favorite(95900, "CBN"), Favorite(102700))

    @Test
    fun saveAndLoad() {
        assertEquals(list, Favorites.decode(Favorites.encode(list)))
        assertEquals(emptyList<Favorite>(), Favorites.decode(null))
        assertEquals(listOf(Favorite(90500)), Favorites.decode("90500\n\nlixo\t?"))
    }

    @Test
    fun favoritesInFrequencyOrderAndRemoves() {
        assertEquals(listOf(88700, 90500, 95900, 102700), Favorites.toggle(list, 90500).map { it.khz })
        assertEquals(listOf(Favorite(88700, "Atlântida"), Favorite(102700)), Favorites.toggle(list, 95900))
        // Lista antiga fora de ordem volta ordenada
        assertEquals(listOf(88700, 102700), Favorites.decode("102700\tX\n88700").map { it.khz })
    }

    @Test
    fun stationLine() {
        // O texto da emissora vale primeiro; sem ele, o nome dado à favorita; sem nome, a posição na lista
        assertEquals("Gaspar", Favorites.line(list, 95900, " Gaspar "))
        assertEquals("CBN", Favorites.line(list, 95900, null))
        assertEquals("Favorito 3", Favorites.line(list, 102700, ""))
        assertEquals("", Favorites.line(list, 100100, null))
        assertEquals("", Favorites.line(list, null, null))
    }

    @Test
    fun renames() {
        assertEquals("Itapema", Favorites.rename(list, 102700, " Itapema ").last().name)
    }

    @Test
    fun fineStepByOddNumbers() {
        assertEquals(88300, fineStep(88100, true))
        assertEquals(87900, fineStep(88100, false))
        assertEquals(90700, fineStep(90600, true))
        assertEquals(90500, fineStep(90600, false))
        assertEquals(87500, fineStep(107900, true))
        assertEquals(107900, fineStep(87500, false))
    }

    @Test
    fun previousAndNextWrapAround() {
        assertEquals(102700, Favorites.step(list, 95900, true)?.khz)
        assertEquals(88700, Favorites.step(list, 102700, true)?.khz)
        assertEquals(102700, Favorites.step(list, 88700, false)?.khz)
        assertEquals(88700, Favorites.step(list, 91100, true)?.khz)
        assertEquals(102700, Favorites.step(list, null, false)?.khz)
        assertNull(Favorites.step(emptyList(), 95900, true))
    }
}
