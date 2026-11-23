package app.rumo.core

import kotlin.math.exp

/** Rótulo do jeito de dirigir. */
enum class DrivingLabel(val text: String) {
    ECONOMIC("econômica"),
    MODERATE("moderada"),
    AGGRESSIVE("agressiva"),
}

/**
 * Jeito de dirigir numa nota de 0 (econômico) a 100 (agressivo), pelas leituras do scanner. Cada leitura soma pontos
 * pelo que passou do tranquilo: aceleração acima de [ACCEL_FREE], freada acima de [BRAKE_FREE] (pela variação da
 * velocidade do carro), rotação acima de [RPM_FREE] e acelerador acima de [THROTTLE_FREE]; a nota vai perdendo os
 * pontos com o tempo ([MEMORY_S]). Assim uma arrancada forte aparece na hora e some em menos de um minuto de direção
 * calma, e andar em velocidade constante (mesmo na estrada, com o giro mais alto) não soma nada. O rótulo só troca com
 * folga, para não ficar piscando. É uma estimativa: o carro não informa a marcha.
 */
class DrivingStyle {
    var score = 0.0
        private set
    var label = DrivingLabel.ECONOMIC
        private set
    /** Aceleração (m/s²) e pontos por segundo da última leitura, para o registro. */
    var lastAccel = 0.0
        private set
    var lastRate = 0.0
        private set

    private var lastSpeedMs: Double? = null

    /** Uma leitura [dtS] segundos depois da anterior (pausas longas não contam como aceleração). */
    fun update(dtS: Double, speedKmh: Double?, rpm: Double?, throttlePct: Double?) {
        val v = speedKmh?.div(3.6)
        val last = lastSpeedMs
        lastSpeedMs = v
        if (dtS <= 0.0 || dtS > MAX_STEP_S) return
        lastAccel = if (v != null && last != null) (v - last) / dtS else 0.0
        lastRate = rate(lastAccel, rpm ?: 0.0, throttlePct ?: 0.0)
        score = (score * exp(-dtS / MEMORY_S) + lastRate * dtS).coerceIn(0.0, 100.0)
        label = when (label) {
            DrivingLabel.ECONOMIC -> if (score > 40) DrivingLabel.MODERATE else DrivingLabel.ECONOMIC
            DrivingLabel.MODERATE -> when {
                score < 25 -> DrivingLabel.ECONOMIC
                score > 75 -> DrivingLabel.AGGRESSIVE
                else -> DrivingLabel.MODERATE
            }
            DrivingLabel.AGGRESSIVE -> if (score < 55) DrivingLabel.MODERATE else DrivingLabel.AGGRESSIVE
        }
    }

    companion object {
        /** Quanto tempo os pontos levam para cair a cerca de um terço (s). */
        const val MEMORY_S = 40.0
        /** Leituras mais espaçadas que isto (o Rumo saiu da tela, o scanner travou) não contam. */
        const val MAX_STEP_S = 8.0
        /** Até aqui é direção tranquila: aceleração e freada em m/s², rotação e acelerador (%). */
        const val ACCEL_FREE = 1.2
        const val BRAKE_FREE = 2.0
        const val RPM_FREE = 4_000.0
        const val THROTTLE_FREE = 70.0
        /** Pontos por segundo para cada m/s² acima do tranquilo (aceleração e freada). */
        const val ACCEL_POINTS = 12.0

        /**
         * Pontos por segundo de uma leitura. Exemplos: 0 a 50 km/h em 12 s não soma; em 8 s, cerca de 50 pontos no
         * total (moderada); em 5 s, cerca de 95 (agressiva). Frear de 50 a 0 em 6 s soma pouco; em 3 s, cerca de 90.
         */
        fun rate(accel: Double, rpm: Double, throttlePct: Double): Double {
            var r = 0.0
            if (accel > ACCEL_FREE) r += (accel - ACCEL_FREE) * ACCEL_POINTS
            if (accel < -BRAKE_FREE) r += (-accel - BRAKE_FREE) * ACCEL_POINTS
            if (rpm > RPM_FREE) r += (rpm - RPM_FREE) / 100
            if (throttlePct > THROTTLE_FREE) r += (throttlePct - THROTTLE_FREE) / 2
            return r
        }
    }
}
