package app.rumo.core

/** Radar do vias.db: limite fiscalizado e sentido (graus, ou -1 para os dois). */
class Radar(val id: Long, val lat: Double, val lon: Double, val limit: Int, val bearing: Int)

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
            val d = Geo.distanceM(lat, lon, r.lat, r.lon)
            if (d > zoneM || d >= bestD) continue
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

    private fun alertFor(r: Radar, d: Double, speedKmh: Int) = RadarAlert(r, d.toInt(), speedKmh > r.limit)

    private companion object {
        const val NEAR_M = 40.0
    }
}
