package app.rumo.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PointsTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun decodificaOVetorDoGerador() {
        // O mesmo vetor está em dados/vias_test.py (test_pontos_formato_fixo)
        val p = decodePoints(bytes("b7c5a81abfa2a62ec801c801"))
        assertArrayEquals(intArrayOf(-27595100, -27595000), p.lat)
        assertArrayEquals(intArrayOf(-48548000, -48547900), p.lon)
    }

    @Test
    fun semBytesNaoHaPontos() {
        assertEquals(0, decodePoints(ByteArray(0)).lat.size)
    }

    @Test
    fun formatoAntigoEmParesLittleEndian() {
        val lat = -27595100
        val lon = 258
        val blob = ByteArray(8) { i ->
            val v = if (i < 4) lat else lon
            (v shr (8 * (i % 4))).toByte()
        }
        val p = decodePointsV1(blob)
        assertArrayEquals(intArrayOf(lat), p.lat)
        assertArrayEquals(intArrayOf(lon), p.lon)
    }
}
