package app.rumo.core

import kotlin.math.abs

/** Tipos de ponto do roads.db (`radars.kind`, os mesmos de core/MapaRadar.kt). 0: radar do OSM sem tipo. */
object RadarKind {
    const val OSM = 0
    const val FIXED = 1
    const val TRAFFIC_LIGHT_RADAR = 2
    const val MOBILE = 3
    const val BUMP = 4
    const val ELECTRONIC_BUMP = 5
    const val RED_LIGHT_CAMERA = 6
    const val POLICE = 7
    const val TOLL = 8

    /** Os que fiscalizam velocidade (têm limite, "acima do limite" e bipes). */
    val ENFORCING = setOf(FIXED, TRAFFIC_LIGHT_RADAR, MOBILE, ELECTRONIC_BUMP)
}

/**
 * Radar (ou outro ponto de atenção) do roads.db: limite fiscalizado (0 = não fiscaliza velocidade: pedágio, lombada
 * comum, polícia, câmera de semáforo), sentido (graus, ou -1 para os dois) e tipo.
 */
class Radar(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val limit: Int,
    val bearing: Int,
    val kind: Int = RadarKind.OSM,
) {
    /** Só quem fiscaliza velocidade tem "acima do limite" e bipes. */
    val enforcesSpeed: Boolean get() = limit > 0

    /** Aviso sonoro de entrada na zona: os que fiscalizam e a polícia; lombada, pedágio e câmera de semáforo só aparecem. */
    val chimes: Boolean get() = enforcesSpeed || kind == RadarKind.POLICE
}

/** Nome do tipo para o aviso na tela. */
fun radarLabel(kind: Int): String = when (kind) {
    RadarKind.TRAFFIC_LIGHT_RADAR -> "Semáforo com radar"
    RadarKind.MOBILE -> "Radar móvel"
    RadarKind.BUMP -> "Lombada"
    RadarKind.ELECTRONIC_BUMP -> "Lombada eletrônica"
    RadarKind.RED_LIGHT_CAMERA -> "Semáforo com câmera"
    RadarKind.POLICE -> "Polícia rodoviária"
    RadarKind.TOLL -> "Pedágio"
    else -> "Radar"
}

/** Radar à frente, dentro da zona de aviso. [alarm]: acima do limite dele. */
data class RadarAlert(val radar: Radar, val distanceM: Int, val alarm: Boolean) {
    /** 0 na borda da zona, 1 em cima do radar: ritmo e tom dos bipes. */
    fun closeness(zoneM: Int): Float = (1f - distanceM.toFloat() / zoneM).coerceIn(0f, 1f)
}

/**
 * Acompanha o radar à frente. Entra na zona a até [zoneM] metros, com o radar no rumo do carro e fiscalizando
 * o sentido em que ele anda; sai ao passar do radar ou ao sair do rumo dele (outra rua).
 */
class RadarWatch(var zoneM: Int = 500) {
    var current: RadarAlert? = null
        private set

    /** Devolve true quando acabou de entrar na zona de um radar novo (hora do aviso curto). */
    fun update(lat: Double, lon: Double, bearing: Float?, speedKmh: Int, radars: List<Radar>): Boolean {
        val prev = current?.radar
        if (bearing == null) {
            // Sem rumo (parado): mantém o radar atual com a distância nova
            current = prev?.let { alertFor(it, Geo.distanceM(lat, lon, it.lat, it.lon), speedKmh) }
            return false
        }
        var best: Radar? = null
        var bestD = Double.MAX_VALUE
        for (r in radars) {
            // Com dezenas de milhares de pontos (vários estados), descarta os longe antes da conta de distância
            if (abs(r.lat - lat) > MAX_DEG || abs(r.lon - lon) > MAX_DEG) continue
            val d = Geo.distanceM(lat, lon, r.lat, r.lon)
            if (d > zoneOf(r) || d >= bestD) continue
            // Já em cima do radar o rumo até ele fica instável: aceita mais
            val maxOff = if (d < NEAR_M) 70.0 else 30.0
            if (Geo.angleDiff(bearing.toDouble(), Geo.bearing(lat, lon, r.lat, r.lon)) > maxOff) continue
            if (r.bearing >= 0 && Geo.angleDiff(bearing.toDouble(), r.bearing.toDouble()) > 45) continue
            // Um radar novo só entra com o carro andando
            if (r !== prev && speedKmh < 5) continue
            best = r
            bestD = d
        }
        current = best?.let { alertFor(it, bestD, speedKmh) }
        return best != null && best !== prev
    }

    fun reset() {
        current = null
    }

    private fun alertFor(r: Radar, d: Double, speedKmh: Int) = RadarAlert(r, d.toInt(), r.enforcesSpeed && speedKmh > r.limit)

    /** Lombada, pedágio e câmera de semáforo avisam mais perto: não há o que reduzir com tanta antecedência. */
    private fun zoneOf(r: Radar) = if (r.chimes) zoneM else minOf(zoneM, NEAR_ZONE_M)

    private companion object {
        const val NEAR_M = 40.0
        const val NEAR_ZONE_M = 300
        // ~1 grau de latitude = 111 km: 0,01° (~1,1 km) cobre qualquer zona de aviso
        const val MAX_DEG = 0.01
    }
}
