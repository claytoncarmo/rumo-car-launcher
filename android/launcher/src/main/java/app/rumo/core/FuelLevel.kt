package app.rumo.core

import kotlin.math.exp

/**
 * Nível do tanque suavizado. A boia do tanque balança com curvas, freadas e subidas: a leitura crua (PID 2F) pula
 * alguns pontos, e na autonomia cada ponto vale vários km. Aqui a leitura segue a média móvel com cerca de [TAU_S] de
 * memória; uma subida grande ([REFUEL_JUMP] pontos) é abastecimento e vale na hora.
 */
class FuelLevel {
    var percent: Double? = null
        private set

    /** Nova leitura [pct] (0 a 100), [dtS] segundos depois da anterior. */
    fun update(pct: Double, dtS: Double) {
        val cur = percent
        percent = when {
            cur == null || pct - cur > REFUEL_JUMP -> pct
            else -> cur + (pct - cur) * (1 - exp(-dtS.coerceAtLeast(0.0) / TAU_S))
        }
    }

    companion object {
        const val TAU_S = 120.0
        const val REFUEL_JUMP = 8.0
    }
}
