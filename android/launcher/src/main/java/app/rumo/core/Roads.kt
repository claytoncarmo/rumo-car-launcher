package app.rumo.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Uma via do vias.db. Limites em km/h por sentido (fwd = sentido do desenho); "estimado" = padrão do CTB. */
class Way(
    val id: Long,
    val name: String?,
    val ref: String?,
    val kind: Int,
    val oneway: Boolean,
    val fwd: Int,
    val bwd: Int,
    val fwdEstimated: Boolean,
    val bwdEstimated: Boolean,
) {
    /** Nome para a tela: "Av. Beira-Mar · SC-401", só a sigla, ou o tipo da via. */
    val label: String
        get() = when {
            !name.isNullOrBlank() && !ref.isNullOrBlank() -> "$name · ${ref.replace(';', '/')}"
            !name.isNullOrBlank() -> name
            !ref.isNullOrBlank() -> ref.replace(';', '/')
            else -> KIND_LABELS.getOrElse(kind) { "Via sem nome" }
        }

    companion object {
        // Mesma ordem de KINDS em dados/vias.py
        val KIND_LABELS = listOf(
            "Rodovia", "Rodovia", "Avenida sem nome", "Avenida sem nome", "Rua sem nome",
            "Estrada sem nome", "Rua sem nome", "Rua sem nome", "Acesso", "Via sem nome",
        )
    }
}

/** Trecho contínuo de uma via dentro de uma célula; pontos em microgaus. */
class Run(val way: Way, val lat: IntArray, val lon: IntArray)

/** Via em que o carro está e o sentido em relação ao desenho dela. */
data class RoadMatch(val way: Way, val forward: Boolean, val distanceM: Double) {
    val limit: Int get() = if (forward) way.fwd else way.bwd
    val estimated: Boolean get() = if (forward) way.fwdEstimated else way.bwdEstimated
}

/**
 * Liga a posição do GPS à via mais provável: perto, no mesmo rumo do carro (sem contramão em via de mão única)
 * e, em caso de dúvida, a mesma via de antes (evita pular para a rua paralela ou para a transversal no cruzamento).
 */
class RoadMatcher {
    var last: RoadMatch? = null
        private set

    fun update(lat: Double, lon: Double, bearing: Float?, speedKmh: Int, runs: List<Run>): RoadMatch? {
        val moving = bearing != null && speedKmh >= MOVING_KMH
        val kx = cos(Math.toRadians(lat)) * Geo.M_PER_DEG_LON / 1e6
        val ky = Geo.M_PER_DEG_LAT / 1e6
        val latE6 = lat * 1e6
        val lonE6 = lon * 1e6
        val prev = last
        var best: RoadMatch? = null
        var bestScore = Double.MAX_VALUE

        for (run in runs) {
            val way = run.way
            val same = prev != null && prev.way.id == way.id
            for (i in 0 until run.lat.size - 1) {
                val ax = (run.lon[i] - lonE6) * kx
                val ay = (run.lat[i] - latE6) * ky
                val dx = (run.lon[i + 1] - lonE6) * kx - ax
                val dy = (run.lat[i + 1] - latE6) * ky - ay
                val l2 = dx * dx + dy * dy
                if (l2 == 0.0) continue
                val t = (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d > MAX_DISTANCE_M) continue

                var forward: Boolean
                var penalty = 0.0
                if (moving) {
                    val segBearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                    val diff = Geo.angleDiff(bearing!!.toDouble(), segBearing)
                    forward = diff <= 90
                    if (!forward && way.oneway) continue
                    val heading = if (forward) diff else 180 - diff
                    if (heading > MAX_HEADING_DEG) continue
                    penalty = heading * HEADING_WEIGHT
                } else {
                    // Parado não há rumo: mantém o sentido de antes na mesma via
                    forward = if (same) prev!!.forward else true
                }
                val score = d + penalty - (if (same) SAME_WAY_BONUS_M else 0.0)
                if (score < bestScore) {
                    bestScore = score
                    best = RoadMatch(way, forward, d)
                }
            }
        }
        last = best
        return best
    }

    fun reset() {
        last = null
    }

    companion object {
        const val MAX_DISTANCE_M = 30.0
        const val MAX_HEADING_DEG = 50.0
        const val HEADING_WEIGHT = 0.3   // 10° de diferença valem 3 m
        const val SAME_WAY_BONUS_M = 6.0
        const val MOVING_KMH = 8
    }
}

/** Velocidade em vermelho acima do limite + tolerância, com 1 km/h de histerese para não piscar. */
class OverSpeed(var toleranceKmh: Int = 0) {
    var on = false
        private set

    fun update(speedKmh: Int?, limitKmh: Int?): Boolean {
        on = if (speedKmh == null || limitKmh == null || speedKmh < 3) {
            false
        } else if (on) {
            speedKmh > limitKmh + toleranceKmh - 1
        } else {
            speedKmh > limitKmh + toleranceKmh
        }
        return on
    }
}
