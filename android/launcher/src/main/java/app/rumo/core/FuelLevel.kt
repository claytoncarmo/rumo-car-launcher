package app.rumo.core

/**
 * Nível do tanque a partir da leitura crua do carro (PID 2F), que é muito ruidosa: no registro de 25/11/2026 ela pulou
 * de 22 % a 92,5 % em segundos, com desvio de uns 13 pontos andando e 5 parado (a boia balança e o sensor oscila).
 *
 * - **Mediana** das últimas [WINDOW_SAMPLES] leituras (uma a cada ~2 s: uns 5 min com o carro ligado). Os picos não
 *   puxam o valor; contar leituras em vez de tempo faz a janela atravessar o carro desligado: as leituras ficam
 *   guardadas ([saved]) e o nível volta certo ao ligar o carro de novo.
 * - **Abastecimento:** quando a mediana das últimas [RECENT_SAMPLES] leituras passa a das anteriores em mais de
 *   [REFUEL_JUMP] pontos, as anteriores saem (o nível sobe logo, sem esperar a janela inteira).
 * - **Tanque cheio:** a leitura mais alta que o carro dá é a boia no batente de cima (92,5 % no carro do Clayton), não
 *   100 %. A maior leitura já vista (de [MIN_FULL] para cima) vira o cheio, e o nível é a mediana dividida por ela: com
 *   mediana 65,1 % e cheio 92,5 %, 70 %, o que o ponteiro do painel mostrava (68–70 %).
 */
class FuelLevel(fullRaw: Double? = null, saved: List<Double> = emptyList()) {
    /** Maior leitura crua já vista (o batente da boia), ou null enquanto nenhuma passou de [MIN_FULL]. */
    var fullRaw: Double? = fullRaw?.takeIf { it >= MIN_FULL }
        private set

    /** Nível do tanque (0 a 100 % do cheio); null sem leituras. */
    var percent: Double? = null
        private set

    /** Quantas vezes um abastecimento limpou as leituras antigas (para o registro). */
    var refuels = 0
        private set

    private val samples = ArrayDeque(saved.takeLast(WINDOW_SAMPLES))

    init {
        recompute()
    }

    /** As leituras da janela, para guardar e voltar ao ligar o carro. */
    fun saved(): List<Double> = samples.toList()

    /** Nova leitura crua [rawPct] (0 a 100); devolve se o cheio mudou (para guardar). */
    fun update(rawPct: Double): Boolean {
        samples.addLast(rawPct)
        while (samples.size > WINDOW_SAMPLES) samples.removeFirst()
        if (samples.size >= RECENT_SAMPLES + MIN_OLDER_SAMPLES) {
            val recent = samples.takeLast(RECENT_SAMPLES)
            val older = samples.take(samples.size - RECENT_SAMPLES)
            if (median(recent) - median(older) > REFUEL_JUMP) {
                samples.clear()
                samples.addAll(recent)
                refuels++
            }
        }
        val fullChanged = rawPct >= MIN_FULL && rawPct > (fullRaw ?: 0.0)
        if (fullChanged) fullRaw = rawPct
        recompute()
        return fullChanged
    }

    private fun recompute() {
        percent = if (samples.isEmpty()) null else (median(samples) / (fullRaw ?: 100.0) * 100).coerceIn(0.0, 100.0)
    }

    companion object {
        /** Leituras na janela: uma a cada ~2 s, uns 5 min. */
        const val WINDOW_SAMPLES = 150
        /** As últimas leituras (~30 s) comparadas com as anteriores para ver se abasteceu. */
        const val RECENT_SAMPLES = 15
        const val MIN_OLDER_SAMPLES = 30
        /** Subida (em pontos da leitura crua) que é abastecimento, e não balanço da boia: ~15 % do tanque. */
        const val REFUEL_JUMP = 15.0
        /** Abaixo disto uma leitura não é tomada como o batente de cima da boia. */
        const val MIN_FULL = 80.0

        fun median(values: Collection<Double>): Double {
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        }

        /** "65.1,62.4,…" (para guardar) e de volta; ignora o que não for número. */
        fun encode(values: List<Double>): String = values.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) }

        fun decode(text: String?): List<Double> = text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
    }
}
