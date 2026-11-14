package app.rumo.core

/**
 * Leitura do logcat (formato `threadtime`: "11-14 17:10:11.123  1234  1250 E AndroidRuntime: ..."), que um app vê só
 * com as próprias linhas, inclusive as de antes de ele fechar.
 */
object CrashLog {
    /** Sinais de que o processo morreu: exceção Java, erro nativo (sinal), serviço em primeiro plano sem notificação. */
    private val MARKERS = listOf("FATAL EXCEPTION", "Fatal signal", "RemoteServiceException", "ANR in", "DeadSystemException")

    fun pidOf(line: String): Int? = line.trim().split(Regex("\\s+")).getOrNull(2)?.toIntOrNull()

    /**
     * O último fechamento de um processo que não é o atual ([myPid]): umas linhas antes do sinal e as seguintes, só as
     * daquele processo. Null se não houver.
     */
    fun lastCrash(lines: List<String>, myPid: Int, before: Int = 40, after: Int = 120): List<String>? {
        val i = lines.indexOfLast { l -> MARKERS.any { it in l } && pidOf(l).let { it != null && it != myPid } }
        if (i < 0) return null
        val pid = pidOf(lines[i])
        return lines.subList(maxOf(0, i - before), minOf(lines.size, i + after + 1)).filter { pidOf(it) == pid }
    }
}
