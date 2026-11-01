package app.rumo.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ponto de atenção marcado pelo próprio usuário no aparelho (radar, lombada, polícia…) que falta no OSM e no MapaRadar.
 * [bearing]: sentido em graus, ou -1 para os dois. [date]: dia da marcação (AAAA-MM-DD), só para consulta.
 */
data class UserPoint(
    val lat: Double,
    val lon: Double,
    val kind: Int,
    val limit: Int,
    val bearing: Int,
    val date: String,
)

/** Onde o usuário tocou no "+": posição e rumo daquele instante, e o limite sugerido (o da via atual). */
class MarkSpot(val lat: Double, val lon: Double, val bearing: Int?, val limit: Int) {
    companion object {
        /** Parado o GPS não dá o rumo: sem ele só vale "os dois sentidos". */
        fun of(lat: Double, lon: Double, bearing: Float?, speedKmh: Int?, roadLimit: Int?): MarkSpot {
            val b = bearing?.takeIf { (speedKmh ?: 0) >= UserPoints.MIN_BEARING_KMH }?.roundToInt()?.mod(360)
            val l = roadLimit?.takeIf { it > 0 }?.let { ((it / 10.0).roundToInt() * 10) } ?: UserPoints.DEFAULT_LIMIT
            return MarkSpot(lat, lon, b, l.coerceIn(UserPoints.MIN_LIMIT, UserPoints.MAX_LIMIT))
        }
    }
}

/**
 * Banco local de pontos do usuário: um arquivo de texto, um ponto por linha, `longitude,latitude,Tipo - N kmh@N,sentido,dia`.
 * As três primeiras colunas são as do CSV do MapaRadar (dá para copiar as linhas e mandar ao fórum deles); o sentido
 * (graus) e o dia podem faltar. Linhas em branco, `#` e as que não prestam são ignoradas ao ler.
 */
object UserPoints {
    const val FILE = "my-points.csv"
    const val MIN_LIMIT = 20
    const val MAX_LIMIT = 120
    const val DEFAULT_LIMIT = 60
    const val MIN_BEARING_KMH = 5

    /** Marcar duas vezes o mesmo tipo a menos disto troca o ponto em vez de repeti-lo (toque duplo). */
    const val SAME_M = 15.0

    /** Os tipos que dá para marcar, na ordem da tela. */
    val KINDS = listOf(
        RadarKind.FIXED,
        RadarKind.TRAFFIC_LIGHT_RADAR,
        RadarKind.MOBILE,
        RadarKind.ELECTRONIC_BUMP,
        RadarKind.BUMP,
        RadarKind.RED_LIGHT_CAMERA,
        RadarKind.POLICE,
        RadarKind.TOLL,
    )

    // Ids próprios (fora dos do OSM, positivos, e dos do MapaRadar, que são o número da linha negativo)
    private const val ID_BASE = -(1L shl 40)

    fun encode(points: List<UserPoint>): String = buildString {
        append("# Rumo: pontos marcados no aparelho. longitude,latitude,Tipo - limite kmh@limite,sentido (graus; vazio = os dois),dia\n")
        for (p in points) {
            val desc = MapaRadar.describe(p.kind, p.limit) ?: continue
            append(String.format(Locale.ROOT, "%.6f,%.6f,%s,", p.lon, p.lat, desc))
            if (p.bearing >= 0) append(p.bearing)
            append(',').append(p.date).append('\n')
        }
    }

    fun decode(text: String): List<UserPoint> = text.lineSequence().mapNotNull { parse(it) }.toList()

    private fun parse(line: String): UserPoint? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val parts = l.split(',')
        if (parts.size < 3) return null
        val lon = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val (kind, limit) = MapaRadar.parseDescription(parts[2]) ?: return null
        val bearing = parts.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it in 0..359 } ?: -1
        val date = parts.getOrNull(4)?.trim().orEmpty()
        return UserPoint(lat, lon, kind, limit, bearing, date)
    }

    /** Os pontos como radares do mapa e dos avisos. */
    fun toRadars(points: List<UserPoint>): List<Radar> =
        points.mapIndexed { i, p -> Radar(ID_BASE - i, p.lat, p.lon, p.limit, p.bearing, p.kind) }

    /** Acrescenta [point]; um do mesmo tipo a menos de [SAME_M] é trocado por ele. */
    fun add(points: List<UserPoint>, point: UserPoint): List<UserPoint> =
        points.filterNot { it.kind == point.kind && Geo.distanceM(it.lat, it.lon, point.lat, point.lon) < SAME_M } + point

    /** Troca o ponto [index] por [point], no mesmo lugar da lista (a ordem de inclusão não muda). */
    fun replace(points: List<UserPoint>, index: Int, point: UserPoint): List<UserPoint> =
        if (index in points.indices) points.mapIndexed { i, p -> if (i == index) point else p } else points

    /** O ponto movido [meters] ao longo da rua, no rumo [axisBearing] (negativo anda para trás). */
    fun move(point: UserPoint, meters: Int, axisBearing: Int): UserPoint {
        val (lat, lon) = Geo.offset(point.lat, point.lon, axisBearing.toDouble(), meters.toDouble())
        return point.copy(lat = lat, lon = lon)
    }

    /** O ponto como outro tipo: quem fiscaliza velocidade ganha um limite (o padrão, se não tinha), os demais ficam sem. */
    fun withKind(point: UserPoint, kind: Int): UserPoint =
        point.copy(kind = kind, limit = if (kind in RadarKind.ENFORCING) point.limit.takeIf { it > 0 } ?: DEFAULT_LIMIT else 0)

    /** O ponto com outro limite, dentro da faixa. */
    fun withLimit(point: UserPoint, limit: Int): UserPoint = point.copy(limit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT))

    fun remove(points: List<UserPoint>, index: Int): List<UserPoint> =
        if (index in points.indices) points.filterIndexed { i, _ -> i != index } else points

    /** "Radar fixo · 60 km/h" ou "Lombada". */
    fun label(p: UserPoint): String = radarLabel(p.kind) + if (p.limit > 0) " · ${p.limit} km/h" else ""
}
