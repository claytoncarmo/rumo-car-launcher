package app.rumo.core

/**
 * Nível do tanque a partir da leitura crua do carro (PID 2F), que é muito ruidosa: no registro de 25/11/2026 ela pulou
 * de 22 % a 92,5 % em segundos, com desvio de uns 13 pontos andando e 5 parado (a boia balança e o sensor oscila).
 *
 * - **Mediana** das leituras dos últimos [WINDOW_MS]: os picos não puxam o valor (uma média puxaria, e a versão
 *   anterior ainda tomava cada pico para cima como "abasteceu" e saltava para ele).
 * - **Tanque cheio:** a leitura mais alta que o carro dá é a boia no batente de cima (92,5 % no carro do Clayton), não
 *   100 %. A maior leitura já vista (de [MIN_FULL] para cima) vira o cheio, e o nível é a mediana dividida por ela: com
 *   mediana 65,1 % e cheio 92,5 %, 70 %, o que o ponteiro do painel mostrava (68–70 %).
 * - Sem leitura por [RESET_GAP_MS] (carro desligado, talvez abastecendo), recomeça só com as leituras novas.
 */
class FuelLevel(fullRaw: Double? = null) {
    /** Maior leitura crua já vista (o batente da boia), ou null enquanto nenhuma passou de [MIN_FULL]. */
    var fullRaw: Double? = fullRaw?.takeIf { it >= MIN_FULL }
        private set

    /** Nível do tanque (0 a 100 % do cheio); null sem leituras. */
    var percent: Double? = null
        private set

    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** Nova leitura crua [rawPct] (0 a 100) no instante [nowMs]; devolve se o cheio mudou (para guardar). */
    fun update(nowMs: Long, rawPct: Double): Boolean {
        if (samples.isNotEmpty() && nowMs - samples.last().first > RESET_GAP_MS) samples.clear()
        samples.addLast(nowMs to rawPct)
        while (nowMs - samples.first().first > WINDOW_MS) samples.removeFirst()
        val fullChanged = rawPct >= MIN_FULL && rawPct > (fullRaw ?: 0.0)
        if (fullChanged) fullRaw = rawPct
        val sorted = samples.map { it.second }.sorted()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        percent = (median / (fullRaw ?: 100.0) * 100).coerceIn(0.0, 100.0)
        return fullChanged
    }

    companion object {
        const val WINDOW_MS = 120_000L
        const val RESET_GAP_MS = 5 * 60_000L
        /** Abaixo disto uma leitura não é tomada como o batente de cima da boia. */
        const val MIN_FULL = 80.0
    }
}
