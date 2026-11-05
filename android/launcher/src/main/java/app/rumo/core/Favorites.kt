package app.rumo.core

/**
 * Ajuste fino do rádio FM: no Brasil as estações ficam nas frequências de final ímpar (88,1, 88,3 … 107,9), então
 * o passo é de 0,2 MHz por elas; de uma frequência par vai para a ímpar vizinha. Nas pontas (87,5 e 107,9) dá a volta.
 */
fun fineStep(khz: Int, up: Boolean): Int {
    val n = (khz + 50) / 100          // em centenas de kHz: 881 = 88,1 MHz
    var next = if (n % 2 == 1) n + (if (up) 2 else -2) else n + (if (up) 1 else -1)
    if (next > FM_TOP) next = FM_BOTTOM
    if (next < FM_BOTTOM) next = FM_TOP
    return next * 100
}

private const val FM_BOTTOM = 875
private const val FM_TOP = 1079

/** Estação favorita do Rumo: frequência em kHz e o nome dado pelo motorista (o rádio não informa nomes). */
data class Favorite(val khz: Int, val name: String = "")

/**
 * Lista própria de favoritas (sem o limite de 6 memórias do aparelho), sempre em ordem de frequência, guardada como
 * texto nas preferências.
 */
object Favorites {

    /** Uma por linha: "95900\tCBN". */
    fun encode(list: List<Favorite>): String =
        list.joinToString("\n") { "${it.khz}\t${it.name.replace('\n', ' ').replace('\t', ' ').trim()}" }

    fun decode(text: String?): List<Favorite> =
        text.orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            val khz = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            Favorite(khz, parts.getOrElse(1) { "" }.trim())
        }.distinctBy { it.khz }.sortedBy { it.khz }

    /** Favorita a estação (no lugar dela pela frequência) ou tira, se já estiver. */
    fun toggle(list: List<Favorite>, khz: Int): List<Favorite> =
        if (list.any { it.khz == khz }) list.filterNot { it.khz == khz } else add(list, khz)

    fun add(list: List<Favorite>, khz: Int): List<Favorite> =
        if (list.any { it.khz == khz }) list else (list + Favorite(khz)).sortedBy { it.khz }

    fun rename(list: List<Favorite>, khz: Int, name: String): List<Favorite> =
        list.map { if (it.khz == khz) it.copy(name = name.trim()) else it }

    fun remove(list: List<Favorite>, khz: Int): List<Favorite> = list.filterNot { it.khz == khz }

    /**
     * Linha de baixo da estação que toca: o texto que a emissora envia (RDS); sem ele, o nome dado à favorita ou
     * "Favorito N" (N = posição na lista, em ordem de frequência); fora das favoritas e sem texto, vazia.
     */
    fun line(list: List<Favorite>, khz: Int?, rdsText: String?): String {
        rdsText?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val i = list.indexOfFirst { it.khz == khz }
        if (i < 0) return ""
        return list[i].name.ifBlank { "Favorito ${i + 1}" }
    }

    /**
     * Próxima (ou anterior) favorita a partir da estação atual, dando a volta na lista. Fora da lista, a próxima é
     * a primeira e a anterior, a última.
     */
    fun step(list: List<Favorite>, currentKhz: Int?, forward: Boolean): Favorite? {
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.khz == currentKhz }
        if (i < 0) return if (forward) list.first() else list.last()
        val n = list.size
        return list[(i + if (forward) 1 else n - 1) % n]
    }
}
