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
 * pelo que passou do tranquilo, e a nota vai perdendo os pontos com o tempo ([MEMORY_S]):
 * - aceleração acima de [ACCEL_FREE] e freada acima de [BRAKE_FREE], pela velocidade do carro numa janela de pelo menos
 *   1 s (a velocidade vem em km/h inteiros: leitura a leitura, cada degrau pareceria um tranco);
 * - pedal do acelerador além de [PEDAL_FREE] do curso, e pisadas bruscas (o pedal anda [STAB_TRAVEL] do curso de uma
 *   leitura para a outra). O curso é aprendido: a leitura com o pé solto não é 0 % nem o pé no fundo é 100 %;
 * - carga do motor acima de [LOAD_FREE] (pé pesado; numa subida ou com o carro cheio também conta, e tudo bem);
 * - rotação acima de [RPM_FREE].
 * Uma arrancada forte aparece na hora e some em menos de um minuto de direção calma; velocidade constante não soma. O
 * rótulo só troca com folga, para não ficar piscando. É uma estimativa: o carro não informa a marcha nem o freio.
 */
class DrivingStyle {
    var score = 0.0
        private set
    var label = DrivingLabel.ECONOMIC
        private set
    /** Última leitura, para o registro: aceleração (m/s²), curso do pedal (%) e pontos por segundo. */
    var lastAccel = 0.0
        private set
    var lastTravel: Double? = null
        private set
    var lastRate = 0.0
        private set
    /** Pedal com o pé solto e o mais fundo visto (leitura crua, %). */
    var pedalMin: Double? = null
        private set
    var pedalMax: Double? = null
        private set

    private var clock = 0.0
    private val speeds = ArrayDeque<Pair<Double, Double>>()

    /** Uma leitura [dtS] segundos depois da anterior (pausas longas recomeçam a conta da aceleração). */
    fun update(dtS: Double, speedKmh: Double?, rpm: Double?, pedalPct: Double?, loadPct: Double?) {
        if (dtS <= 0.0 || dtS > MAX_STEP_S) {
            speeds.clear()
            lastTravel = null
            speedKmh?.let { speeds.addLast(clock to it / 3.6) }
            return
        }
        clock += dtS
        // Aceleração: contra a leitura mais nova que tenha pelo menos 1 s
        var accel = 0.0
        if (speedKmh != null) {
            val v = speedKmh / 3.6
            val ref = speeds.lastOrNull { clock - it.first >= ACCEL_WINDOW_S }
            if (ref != null) accel = (v - ref.second) / (clock - ref.first)
            speeds.addLast(clock to v)
            while (speeds.size > 2 && clock - speeds.first().first > ACCEL_WINDOW_S * 3) speeds.removeFirst()
        }
        lastAccel = accel
        // Pedal: aprende o pé solto e o fundo
        val travel = pedalPct?.let { raw ->
            val lo = minOf(pedalMin ?: raw, raw).also { pedalMin = it }
            val hi = maxOf(pedalMax ?: raw, raw).also { pedalMax = it }
            ((raw - lo) / maxOf(hi - lo, PEDAL_MIN_SPAN) * 100).coerceIn(0.0, 100.0)
        }
        val stab = travel != null && lastTravel != null && dtS <= STAB_MAX_DT_S && travel - lastTravel!! >= STAB_TRAVEL
        lastTravel = travel
        lastRate = rate(accel, rpm ?: 0.0, travel ?: 0.0, loadPct ?: 0.0)
        score = (score * exp(-dtS / MEMORY_S) + lastRate * dtS + if (stab) STAB_POINTS else 0.0).coerceIn(0.0, 100.0)
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
        /** A aceleração é medida contra uma leitura de pelo menos este tempo atrás. */
        const val ACCEL_WINDOW_S = 1.0
        /** Até aqui é direção tranquila: aceleração e freada (m/s²), pedal (% do curso), carga (%) e rotação. */
        const val ACCEL_FREE = 1.2
        const val BRAKE_FREE = 2.0
        const val PEDAL_FREE = 60.0
        const val LOAD_FREE = 85.0
        const val RPM_FREE = 4_000.0
        /** Pontos por segundo para cada m/s² acima do tranquilo (aceleração e freada). */
        const val ACCEL_POINTS = 12.0
        /** Curso mínimo do pedal para a conta (até o motorista pisar fundo pela primeira vez). */
        const val PEDAL_MIN_SPAN = 55.0
        /** Pisada brusca: o pedal anda isto do curso entre duas leituras próximas, e soma [STAB_POINTS] de uma vez. */
        const val STAB_TRAVEL = 35.0
        const val STAB_MAX_DT_S = 1.5
        const val STAB_POINTS = 15.0

        /**
         * Pontos por segundo de uma leitura. Exemplos: 0 a 50 km/h em 12 s não soma; em 8 s, cerca de 50 pontos no
         * total (moderada); em 5 s, cerca de 95 (agressiva). Frear de 50 a 0 em 6 s soma pouco; em 3 s, cerca de 90.
         * Pedal no fundo: 20 por segundo; carga do motor em 100 %: 15 por segundo.
         */
        fun rate(accel: Double, rpm: Double, travelPct: Double, loadPct: Double): Double {
            var r = 0.0
            if (accel > ACCEL_FREE) r += (accel - ACCEL_FREE) * ACCEL_POINTS
            if (accel < -BRAKE_FREE) r += (-accel - BRAKE_FREE) * ACCEL_POINTS
            if (travelPct > PEDAL_FREE) r += (travelPct - PEDAL_FREE) / 2
            if (loadPct > LOAD_FREE) r += loadPct - LOAD_FREE
            if (rpm > RPM_FREE) r += (rpm - RPM_FREE) / 100
            return r
        }
    }
}
