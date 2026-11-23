package app.rumo.core

import kotlin.math.exp

/** Rótulo do jeito de dirigir. */
enum class DrivingLabel(val text: String) {
    ECONOMIC("econômica"),
    MODERATE("moderada"),
    AGGRESSIVE("agressiva"),
}

/**
 * Jeito de dirigir numa nota de 0 (econômico) a 100 (agressivo), pelas leituras do scanner: acelerações e freadas
 * fortes (pela variação da velocidade do carro), rotação alta e acelerador no fundo. Cada leitura dá uma "batida" de 0
 * a 100 e a nota segue a média móvel com cerca de [MEMORY_S] de memória; o rótulo só troca com folga, para não ficar
 * piscando. É uma estimativa: o carro não informa a marcha.
 */
class DrivingStyle {
    var score = 0.0
        private set
    var label = DrivingLabel.ECONOMIC
        private set

    private var lastSpeedMs: Double? = null

    /** Uma leitura [dtS] segundos depois da anterior (pausas longas não contam como aceleração). */
    fun update(dtS: Double, speedKmh: Double?, rpm: Double?, throttlePct: Double?) {
        val v = speedKmh?.div(3.6)
        val last = lastSpeedMs
        lastSpeedMs = v
        if (dtS <= 0.0 || dtS > MAX_STEP_S) return
        val accel = if (v != null && last != null) (v - last) / dtS else 0.0
        var hit = 0.0
        hit += when {
            accel > STRONG_ACCEL -> 60.0
            accel > SOFT_ACCEL -> 25.0
            accel < STRONG_BRAKE -> 70.0
            accel < SOFT_BRAKE -> 30.0
            else -> 0.0
        }
        val r = rpm ?: 0.0
        hit += when {
            r > HIGH_RPM -> 50.0
            r > MID_RPM -> 20.0
            else -> 0.0
        }
        if ((throttlePct ?: 0.0) > HEAVY_THROTTLE) hit += 30.0
        val alpha = 1 - exp(-dtS / MEMORY_S)
        score += (minOf(100.0, hit) - score) * alpha
        label = when (label) {
            DrivingLabel.ECONOMIC -> if (score > 45) DrivingLabel.MODERATE else DrivingLabel.ECONOMIC
            DrivingLabel.MODERATE -> when {
                score < 35 -> DrivingLabel.ECONOMIC
                score > 75 -> DrivingLabel.AGGRESSIVE
                else -> DrivingLabel.MODERATE
            }
            DrivingLabel.AGGRESSIVE -> if (score < 65) DrivingLabel.MODERATE else DrivingLabel.AGGRESSIVE
        }
    }

    companion object {
        /** Memória da nota, em segundos. */
        const val MEMORY_S = 60.0
        const val MAX_STEP_S = 5.0
        /** Aceleração e freada em m/s² (2,5 m/s² ≈ ganhar 9 km/h em um segundo). */
        const val SOFT_ACCEL = 1.5
        const val STRONG_ACCEL = 2.5
        const val SOFT_BRAKE = -2.5
        const val STRONG_BRAKE = -3.5
        const val MID_RPM = 2_800.0
        const val HIGH_RPM = 3_500.0
        const val HEAVY_THROTTLE = 60.0
    }
}
