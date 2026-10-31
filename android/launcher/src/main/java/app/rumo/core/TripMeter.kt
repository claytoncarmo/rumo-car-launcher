package app.rumo.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distância e tempo em movimento a partir das posições do GPS, ignorando a deriva com o carro parado. */
class TripMeter {
    var meters = 0.0
        private set
    var movingMs = 0L
        private set
    private var last: Point? = null

    private class Point(val lat: Double, val lon: Double, val time: Long)

    fun add(lat: Double, lon: Double, timeMs: Long, speedMs: Float, accuracyM: Float) {
        if (accuracyM > MAX_ACCURACY_M) return
        val prev = last
        last = Point(lat, lon, timeMs)
        if (prev == null) return
        val dt = timeMs - prev.time
        if (dt <= 0 || dt > MAX_GAP_MS || speedMs < MIN_SPEED_MS) return
        meters += distanceM(prev.lat, prev.lon, lat, lon)
        movingMs += dt
    }

    /** Média em km/h, só depois de um minuto em movimento (antes disso oscila demais). */
    val averageKmh: Int?
        get() = if (movingMs < 60_000) null else (meters / movingMs * 3600).roundToInt()

    private companion object {
        const val MIN_SPEED_MS = 1.4f   // ~5 km/h: abaixo disso é deriva do GPS com o carro parado
        const val MAX_GAP_MS = 10_000L  // sem sinal por mais que isso, não liga os pontos
        const val MAX_ACCURACY_M = 30f
    }
}

private const val EARTH_RADIUS_M = 6_371_000.0

/** Distância em metros entre dois pontos (fórmula de haversine). */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}
