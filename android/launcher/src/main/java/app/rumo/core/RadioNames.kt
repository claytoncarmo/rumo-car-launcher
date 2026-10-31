package app.rumo.core

/**
 * Nomes que as emissoras mandam pelo RDS, por frequência, para todas as estações (não só as favoritas). Valem por
 * [ttlMs] (6 h): numa viagem para outra cidade, a mesma frequência pode ser outra rádio.
 */
class RadioNames(private val ttlMs: Long = 6 * 3_600_000L) {
    private val map = HashMap<Int, Pair<String, Long>>()

    @Synchronized
    fun get(khz: Int, now: Long): String? = map[khz]?.takeIf { now - it.second < ttlMs }?.first

    /** Devolve true se mudou (nome novo para a frequência). */
    @Synchronized
    fun put(khz: Int, name: String, now: Long): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val changed = map[khz]?.first != n
        map[khz] = n to now
        return changed
    }

    /** Nomes ainda válidos. */
    @Synchronized
    fun valid(now: Long): Map<Int, String> = map.filterValues { now - it.second < ttlMs }.mapValues { it.value.first }

    /** Uma por linha: "95900\tPenha\t1793391816000" (só as válidas). */
    @Synchronized
    fun encode(now: Long): String = map.filterValues { now - it.second < ttlMs }.entries
        .joinToString("\n") { (khz, v) -> "$khz\t${v.first.replace('\t', ' ').replace('\n', ' ')}\t${v.second}" }

    @Synchronized
    fun load(text: String?, now: Long) {
        text.orEmpty().lines().forEach { line ->
            val p = line.split('\t')
            val khz = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val name = p.getOrNull(1)?.trim().orEmpty()
            val at = p.getOrNull(2)?.toLongOrNull() ?: return@forEach
            if (name.isNotEmpty() && now - at < ttlMs) map[khz] = name to at
        }
    }
}
