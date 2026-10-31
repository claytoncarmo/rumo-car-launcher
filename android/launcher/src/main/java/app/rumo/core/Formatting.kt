package app.rumo.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

val PT_BR: Locale = Locale("pt", "BR")

private val COMPASS = arrayOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")
private val DIRECTION = arrayOf("norte", "nordeste", "leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste")

private fun octant(bearing: Float): Int = Math.floorMod((bearing / 45f).roundToInt(), 8)

/** Ponto cardeal abreviado (N, NE, L…) do rumo em graus. */
fun compassOf(bearing: Float): String = COMPASS[octant(bearing)]

/** Sentido por extenso ("norte", "sudoeste"…), como na faixa da rua no protótipo. */
fun directionOf(bearing: Float): String = DIRECTION[octant(bearing)]

/** Distância até um radar: "350 m" (de 10 em 10, mínimo 10) ou "1,2 km". */
fun formatDistance(meters: Int): String =
    if (meters >= 995) {
        String.format(PT_BR, "%.1f km", meters / 1000.0)
    } else {
        "${maxOf(10, (meters + 5) / 10 * 10)} m"
    }

/** Frequência do rádio em kHz: "102,7 FM" (FM a partir de 30 MHz) ou "1020 AM". */
fun formatRadioFreq(khz: Int): String =
    if (khz >= 30_000) String.format(PT_BR, "%.1f FM", khz / 1000.0) else "$khz AM"

/** Distância da viagem: "0,8 km", "12,3 km", "154 km". */
fun formatKm(meters: Double): String {
    val km = meters / 1000
    return if (km < 100) String.format(PT_BR, "%.1f km", km) else "${km.roundToInt()} km"
}

/** Tamanho de arquivo: "800 B", "18 MB", "1,2 GB". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(PT_BR, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> "${(bytes / 1e6).roundToInt()} MB"
    bytes >= 1_000 -> "${(bytes / 1e3).roundToInt()} kB"
    else -> "$bytes B"
}

/** Data e hora de uma publicação (ISO em UTC, como no manifesto) no fuso do aparelho: "31/10 03:00"; vazio se inválida. */
fun formatBuiltAt(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = try {
    DateTimeFormatter.ofPattern("dd/MM HH:mm", PT_BR).format(Instant.parse(iso).atZone(zone))
} catch (e: Exception) {
    ""
}

/** Duração em h:mm ("0:07", "1:42"). */
fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return "%d:%02d".format(min / 60, min % 60)
}
