package app.rumo.core

/**
 * "Consumo agora" suavizado: a média do fluxo de combustível e da velocidade nas leituras dos últimos [windowMs]. Leitura
 * a leitura, o fluxo de ar pula com cada toque no pedal e o número dançava. O km/L sai da razão das médias (distância
 * pelo combustível no período), não da média das razões.
 */
class FuelNow(private val windowMs: Long = WINDOW_MS) {
    private class Sample(val atMs: Long, val litersPerHour: Double, val speedKmh: Double)

    private val samples = ArrayDeque<Sample>()

    fun add(atMs: Long, litersPerHour: Double, speedKmh: Double) {
        samples.addLast(Sample(atMs, litersPerHour, speedKmh))
        while (samples.isNotEmpty() && atMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    fun clear() = samples.clear()

    /** Litros por hora no período; null sem leituras. */
    val litersPerHour: Double? get() = if (samples.isEmpty()) null else samples.sumOf { it.litersPerHour } / samples.size

    /** km/L no período; null parado (abaixo de 3 km/h, o número não quer dizer nada) ou sem leituras. */
    val kmPerLiter: Double?
        get() {
            val lph = litersPerHour ?: return null
            return FuelEstimate.kmPerLiter(samples.sumOf { it.speedKmh } / samples.size, lph)
        }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}
