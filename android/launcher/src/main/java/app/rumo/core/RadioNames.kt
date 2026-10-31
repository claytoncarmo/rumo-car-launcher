package app.rumo.core

/**
 * Nomes que as emissoras mandam pelo RDS, por frequência, para todas as estações (não só as favoritas). Não expiram
 * por tempo: valem enquanto o carro ficar a até [radiusM] (50 km) do ponto onde o primeiro nome foi guardado. Passou
 * disso (outra cidade, mesma frequência pode ser outra rádio), a lista zera e recomeça ali.
 */
class RadioNames(private val radiusM: Double = 50_000.0) {
    private val map = HashMap<Int, String>()
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var hasCenter = false
    private var hereLat = 0.0
    private var hereLon = 0.0
    private var hasHere = false

    @Synchronized
    fun get(khz: Int): String? = map[khz]

    /** Devolve true se mudou (nome novo para a frequência). */
    @Synchronized
    fun put(khz: Int, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (!hasCenter && hasHere) setCenter(hereLat, hereLon)
        val changed = map[khz] != n
        map[khz] = n
        return changed
    }

    /**
     * Posição atual do carro. Devolve true se a lista mudou e precisa ser gravada: zerou por sair do raio, ou ganhou
     * o centro (nomes guardados antes do GPS fixar).
     */
    @Synchronized
    fun onPosition(lat: Double, lon: Double): Boolean {
        hereLat = lat
        hereLon = lon
        hasHere = true
        if (!hasCenter) {
            if (map.isEmpty()) return false
            setCenter(lat, lon)
            return true
        }
        if (distanceM(centerLat, centerLon, lat, lon) <= radiusM) return false
        val had = map.isNotEmpty()
        map.clear()
        hasCenter = false
        return had
    }

    private fun setCenter(lat: Double, lon: Double) {
        centerLat = lat
        centerLon = lon
        hasCenter = true
    }

    @Synchronized
    fun valid(): Map<Int, String> = HashMap(map)

    /** Primeira linha "#lat\tlon" (se já há centro); depois uma por frequência: "95900\tPenha". */
    @Synchronized
    fun encode(): String = buildList {
        if (hasCenter) add("#$centerLat\t$centerLon")
        map.forEach { (khz, name) -> add("$khz\t${name.replace('\t', ' ').replace('\n', ' ')}") }
    }.joinToString("\n")

    /** Formato antigo (com hora no fim, sem centro) é descartado: a lista recomeça. */
    @Synchronized
    fun load(text: String?) {
        for (line in text.orEmpty().lines()) {
            val p = line.split('\t')
            if (line.startsWith("#")) {
                val lat = p.getOrNull(0)?.drop(1)?.toDoubleOrNull()
                val lon = p.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) setCenter(lat, lon)
                continue
            }
            if (p.size != 2) continue
            val khz = p[0].toIntOrNull() ?: continue
            val name = p[1].trim()
            if (name.isNotEmpty()) map[khz] = name
        }
    }
}
