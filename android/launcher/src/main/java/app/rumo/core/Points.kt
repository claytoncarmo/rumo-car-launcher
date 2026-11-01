package app.rumo.core

/** Latitudes e longitudes (micrograus) de um trecho de via, em ordem. */
class Points(val lat: IntArray, val lon: IntArray)

/**
 * Pontos do `vias.db` versão 2 (`data/roads.py`, `encode_points`): cada coordenada é a diferença para o ponto anterior
 * (o primeiro, para 0,0), em zigue-zague e varint (7 bits por byte, bit alto = tem mais).
 */
fun decodePoints(blob: ByteArray): Points {
    val nums = LongArray(blob.size)
    var count = 0
    var z = 0L
    var shift = 0
    for (b in blob) {
        val v = b.toInt() and 0xFF
        z = z or ((v and 0x7F).toLong() shl shift)
        if (v and 0x80 != 0) {
            shift += 7
        } else {
            nums[count++] = (z ushr 1) xor -(z and 1)
            z = 0
            shift = 0
        }
    }
    val n = count / 2
    val lat = IntArray(n)
    val lon = IntArray(n)
    var la = 0L
    var lo = 0L
    for (i in 0 until n) {
        la += nums[2 * i]
        lo += nums[2 * i + 1]
        lat[i] = la.toInt()
        lon[i] = lo.toInt()
    }
    return Points(lat, lon)
}

/** Formato antigo (`vias.db` versão 1): pares int32 little-endian (lat, lon). */
fun decodePointsV1(blob: ByteArray): Points {
    val n = blob.size / 8
    val lat = IntArray(n)
    val lon = IntArray(n)
    fun int(at: Int) =
        (blob[at].toInt() and 0xFF) or ((blob[at + 1].toInt() and 0xFF) shl 8) or
            ((blob[at + 2].toInt() and 0xFF) shl 16) or ((blob[at + 3].toInt() and 0xFF) shl 24)
    for (i in 0 until n) {
        lat[i] = int(8 * i)
        lon[i] = int(8 * i + 4)
    }
    return Points(lat, lon)
}
