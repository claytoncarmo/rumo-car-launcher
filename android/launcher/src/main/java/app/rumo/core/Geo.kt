package app.rumo.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Contas de distância e rumo em metros, numa projeção plana local (boa para algumas centenas de metros). */
object Geo {
    /** Grade de 0,01° do vias.db (data/roads.py usa a mesma). */
    const val CELL = 0.01
    const val M_PER_DEG_LAT = 110_540.0
    const val M_PER_DEG_LON = 111_320.0

    fun cellOf(lat: Double, lon: Double): Long =
        floor((lat + 90) / CELL).toLong() * 36_000 + floor((lon + 180) / CELL).toLong()

    /** Células que cobrem o quadrado de lado 2·[radiusM] em volta do ponto. */
    fun cellsAround(lat: Double, lon: Double, radiusM: Double): List<Long> {
        val dLat = radiusM / M_PER_DEG_LAT
        val dLon = radiusM / (M_PER_DEG_LON * cos(Math.toRadians(lat)))
        val out = ArrayList<Long>(4)
        var y = floor((lat - dLat + 90) / CELL).toLong()
        val y1 = floor((lat + dLat + 90) / CELL).toLong()
        val x0 = floor((lon - dLon + 180) / CELL).toLong()
        val x1 = floor((lon + dLon + 180) / CELL).toLong()
        while (y <= y1) {
            for (x in x0..x1) out += y * 36_000 + x
            y++
        }
        return out
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2)) * M_PER_DEG_LON
        return hypot((lon2 - lon1) * k, (lat2 - lat1) * M_PER_DEG_LAT)
    }

    /** Rumo de 1 para 2 em graus (0 = norte, sentido horário). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2)) * M_PER_DEG_LON
        val deg = Math.toDegrees(atan2((lon2 - lon1) * k, (lat2 - lat1) * M_PER_DEG_LAT))
        return (deg + 360) % 360
    }

    /** Diferença entre dois rumos, de 0 a 180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs((a - b) % 360)
        return if (d > 180) 360 - d else d
    }
}
