package app.rumo.core

import org.json.JSONArray
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Estação de rádio pela internet, do catálogo aberto Radio Browser (radio-browser.info). [place] é o campo "state" do
 * catálogo, preenchido à mão por quem cadastrou: às vezes o estado, às vezes a cidade.
 */
data class WebStation(
    val id: String,
    val name: String,
    val url: String,
    val tags: String = "",
    val place: String = "",
    val codec: String = "",
    val kbps: Int = 0,
    val hls: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** Filtros da aba Explorar. */
enum class WebFilter(val label: String) {
    NEAR("Perto de você"),
    NEWS("Notícias"),
    SPORTS("Esportes"),
    MUSIC("Música"),
    ALL("Todas"),
}

/** Estado brasileiro: sigla, nome (como no catálogo) e a capital, para estimar o estado sem os dados do mapa. */
data class BrState(val code: String, val name: String, val lat: Double, val lon: Double)

/** O que a tela mostra do rádio online. */
enum class WebStatus { OFF, CONNECTING, LIVE, NO_WIFI, LOST, ERROR }

object WebRadio {

    /** Raio em que uma estação com posição no catálogo conta como "perto". */
    const val NEAR_M = 150_000.0

    /**
     * Resposta de `/json/stations/search` → estações. Fica sem as que a última checagem do catálogo deu como fora do
     * ar, sem endereço e repetidas (mesmo endereço, ou mesmo nome e mesmo lugar: muitas aparecem cadastradas duas vezes).
     */
    fun parse(json: String): List<WebStation> {
        val array = JSONArray(json)
        val out = ArrayList<WebStation>(array.length())
        val seenUrl = HashSet<String>()
        val seenName = HashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.has("lastcheckok") && o.optInt("lastcheckok", 1) == 0) continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val id = o.optString("stationuuid").trim()
            val name = clean(o.optString("name"))
            if (id.isEmpty() || name.isEmpty()) continue
            val place = clean(o.optString("state"))
            if (!seenUrl.add(url) || !seenName.add(key(name) + "|" + key(place))) continue
            out += WebStation(
                id = id,
                name = name,
                url = url,
                tags = o.optString("tags").lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
                place = place,
                codec = o.optString("codec").trim().uppercase().takeIf { it != "UNKNOWN" }.orEmpty(),
                kbps = o.optInt("bitrate", 0).coerceAtLeast(0),
                hls = o.optInt("hls", 0) == 1,
                lat = o.optDoubleOrNull("geo_lat"),
                lon = o.optDoubleOrNull("geo_long"),
            )
        }
        return out
    }

    private fun org.json.JSONObject.optDoubleOrNull(name: String): Double? =
        if (isNull(name) || !has(name)) null else optDouble(name).takeIf { !it.isNaN() && it != 0.0 }

    private fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /** Texto sem acentos e em minúsculas, para comparar ("São Paulo" = "sao paulo"). */
    fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    private val NEWS_WORDS = listOf("news", "noticia", "jornal", "talk", "informa", "cbn", "band news", "bandnews")
    private val SPORTS_WORDS = listOf("sport", "esporte", "futebol", "football", "soccer")

    fun isNews(s: WebStation) = (key(s.tags) + "," + key(s.name)).let { t -> NEWS_WORDS.any { it in t } }
    fun isSports(s: WebStation) = (key(s.tags) + "," + key(s.name)).let { t -> SPORTS_WORDS.any { it in t } }

    /** Filtro da aba Explorar sobre a lista do país; "Perto" e "Todas" deixam a lista como está. */
    fun filter(list: List<WebStation>, f: WebFilter): List<WebStation> = when (f) {
        WebFilter.NEWS -> list.filter(::isNews)
        WebFilter.SPORTS -> list.filter { isSports(it) && !isNews(it) }
        WebFilter.MUSIC -> list.filter { !isNews(it) && !isSports(it) }
        WebFilter.NEAR, WebFilter.ALL -> list
    }

    /**
     * Estações do estado [state] (pelo campo "state" do catálogo: o nome ou a sigla) ou a até [NEAR_M] da posição,
     * quando o catálogo tem a posição da estação.
     */
    fun near(list: List<WebStation>, state: BrState?, lat: Double?, lon: Double?): List<WebStation> = list.filter { s ->
        val p = key(s.place)
        val inState = state != null && p.isNotEmpty() && (key(state.name) in p || p == state.code.lowercase())
        val close = lat != null && lon != null && s.lat != null && s.lon != null &&
            Geo.distanceM(lat, lon, s.lat, s.lon) <= NEAR_M
        inState || close
    }

    /** Linha de baixo: o gênero (ou "Notícias"/"Esportes") e o lugar. */
    fun subtitle(s: WebStation): String = listOf(genre(s), s.place).filter { it.isNotBlank() }.joinToString(" · ")

    fun genre(s: WebStation): String = when {
        isNews(s) -> "Notícias"
        isSports(s) -> "Esportes"
        else -> s.tags.split(',').firstOrNull { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }.orEmpty()
    }

    /** Qualidade: "AAC 64 kbps", só o que o catálogo informa. */
    fun quality(s: WebStation): String =
        listOf(s.codec, if (s.kbps > 0) "${s.kbps} kbps" else "").filter { it.isNotBlank() }.joinToString(" ")

    /**
     * A mesma emissora no FM, pela frequência no nome ("Rádio X 91,3 FM", "Litoral 104.5"): só de 87,5 a 108 MHz.
     * Serve para oferecer o FM quando o Wi-Fi cai (só se a frequência tem sinal aqui: ver quem chama).
     */
    fun fmKhz(name: String): Int? {
        for (m in Regex("(?<![\\d.,])(\\d{2,3})[.,](\\d)(?![\\d])").findAll(name)) {
            val khz = m.groupValues[1].toInt() * 1000 + m.groupValues[2].toInt() * 100
            if (khz in 87_500..108_000) return khz
        }
        return null
    }

    // ---- favoritas: uma por linha, campos separados por tab ----

    fun encode(list: List<WebStation>): String = list.joinToString("\n") { s ->
        listOf(s.id, s.name, s.url, s.tags, s.place, s.codec, s.kbps.toString(), if (s.hls) "1" else "0")
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    fun decode(text: String?): List<WebStation> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 3 || f[0].isBlank() || f[2].isBlank()) return@mapNotNull null
        WebStation(
            id = f[0], name = f[1], url = f[2],
            tags = f.getOrElse(3) { "" }, place = f.getOrElse(4) { "" }, codec = f.getOrElse(5) { "" },
            kbps = f.getOrElse(6) { "0" }.toIntOrNull() ?: 0, hls = f.getOrElse(7) { "0" } == "1",
        )
    }.distinctBy { it.id }

    /** Favorita (no fim da lista) ou tira, se já estiver. */
    fun toggle(list: List<WebStation>, s: WebStation): List<WebStation> =
        if (list.any { it.id == s.id }) list.filterNot { it.id == s.id } else list + s

    /** Próxima (ou anterior) da lista a partir da atual, dando a volta; fora da lista, a primeira (ou a última). */
    fun step(list: List<WebStation>, currentId: String?, forward: Boolean): WebStation? {
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.id == currentId }
        if (i < 0) return if (forward) list.first() else list.last()
        return list[(i + if (forward) 1 else list.size - 1) % list.size]
    }

    // ---- consultas ao catálogo ----

    /** Mais ouvidas do país (o catálogo ordena por cliques). */
    fun countryQuery(limit: Int = 200) =
        "/json/stations/search?countrycode=BR&hidebroken=true&order=clickcount&reverse=true&limit=$limit"

    /** Mais ouvidas do estado (o filtro "state" do catálogo acha o nome em qualquer parte do campo). */
    fun stateQuery(state: BrState, limit: Int = 150) =
        "/json/stations/search?countrycode=BR&state=${URLEncoder.encode(state.name, "UTF-8").replace("+", "%20")}" +
            "&hidebroken=true&order=clickcount&reverse=true&limit=$limit"

    /** Servidores conhecidos do catálogo (o endereço "all" do DNS lista os de agora; estes ficam de reserva). */
    val SERVERS = listOf("de1", "de2", "nl1", "at1", "fi1").map { "https://$it.api.radio-browser.info" }

    val STATES = listOf(
        BrState("AC", "Acre", -9.97, -67.81), BrState("AL", "Alagoas", -9.67, -35.74),
        BrState("AP", "Amapá", 0.03, -51.07), BrState("AM", "Amazonas", -3.12, -60.02),
        BrState("BA", "Bahia", -12.97, -38.50), BrState("CE", "Ceará", -3.73, -38.53),
        BrState("DF", "Distrito Federal", -15.79, -47.88), BrState("ES", "Espírito Santo", -20.32, -40.34),
        BrState("GO", "Goiás", -16.68, -49.25), BrState("MA", "Maranhão", -2.53, -44.30),
        BrState("MT", "Mato Grosso", -15.60, -56.10), BrState("MS", "Mato Grosso do Sul", -20.47, -54.62),
        BrState("MG", "Minas Gerais", -19.92, -43.94), BrState("PA", "Pará", -1.46, -48.50),
        BrState("PB", "Paraíba", -7.12, -34.86), BrState("PR", "Paraná", -25.43, -49.27),
        BrState("PE", "Pernambuco", -8.05, -34.88), BrState("PI", "Piauí", -5.09, -42.80),
        BrState("RJ", "Rio de Janeiro", -22.91, -43.17), BrState("RN", "Rio Grande do Norte", -5.79, -35.21),
        BrState("RS", "Rio Grande do Sul", -30.03, -51.23), BrState("RO", "Rondônia", -8.76, -63.90),
        BrState("RR", "Roraima", 2.82, -60.67), BrState("SC", "Santa Catarina", -27.60, -48.55),
        BrState("SP", "São Paulo", -23.55, -46.63), BrState("SE", "Sergipe", -10.91, -37.07),
        BrState("TO", "Tocantins", -10.18, -48.33),
    )

    fun stateOf(code: String?): BrState? = code?.let { c -> STATES.firstOrNull { it.code.equals(c, ignoreCase = true) } }

    /** Estimativa grosseira (capital mais perto), para quando o estado não sai dos contornos do mapa instalado. */
    fun nearestState(lat: Double, lon: Double): BrState = STATES.minBy { Geo.distanceM(lat, lon, it.lat, it.lon) }
}
