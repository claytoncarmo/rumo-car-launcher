package app.rumo.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Base do MapaRadar (dados/maparadar.csv, CC BY-NC-ND 4.0): o app baixa o arquivo ORIGINAL e o processa aqui, no
 * aparelho, sem publicar nada derivado dele. Sem cabeçalho: `longitude,latitude,Tipo - N kmh@N` (sem acentos), um ponto
 * por linha e sem o sentido. Tipos: Radar Fixo, Semaforo com Radar, Radar Movel, Lombada (0 km/h é lombada comum; com
 * limite, eletrônica), Semaforo com Camera, Policia Rodoviaria, Pedagio.
 */
class MapaRadarPoint(val id: Long, val lat: Double, val lon: Double, val kind: Int, val limit: Int)

object MapaRadar {
    private val DESC = Regex("^(.+?)\\s*-\\s*(\\d+)\\s*kmh\\s*(?:@\\s*\\d+)?\\s*$")
    private val ACCENTS = Regex("\\p{Mn}+")
    private val TYPES = mapOf(
        "radar fixo" to RadarKind.FIXED,
        "semaforo com radar" to RadarKind.TRAFFIC_LIGHT_RADAR,
        "radar movel" to RadarKind.MOBILE,
        "lombada" to RadarKind.BUMP,
        "semaforo com camera" to RadarKind.RED_LIGHT_CAMERA,
        "policia rodoviaria" to RadarKind.POLICE,
        "pedagio" to RadarKind.TOLL,
    )

    // Os que fiscalizam velocidade: o radar do OSM a até MERGE_M deles é o mesmo ponto
    private val ENFORCING = setOf(RadarKind.FIXED, RadarKind.TRAFFIC_LIGHT_RADAR, RadarKind.MOBILE, RadarKind.ELECTRONIC_BUMP)
    const val MERGE_M = 50.0

    // Caixa do Brasil, com folga: fora dela é erro de digitação
    private val LAT = -34.5..6.0
    private val LON = -74.5..-33.5
    private const val MAX_LIMIT = 130

    private fun plain(s: String) = ACCENTS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().trim()

    /**
     * Uma linha do CSV, ou null se não presta. O id é o número da linha, negativo (nunca bate com um id do OSM; o mesmo
     * ponto tem o mesmo id em todos os estados). Só quem fiscaliza velocidade guarda o limite.
     */
    fun parseLine(line: String, number: Int): MapaRadarPoint? {
        val parts = line.split(',', limit = 3)
        if (parts.size != 3) return null
        val lon = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in LAT || lon !in LON) return null
        val m = DESC.find(parts[2].trim()) ?: return null
        val type = TYPES[plain(m.groupValues[1])] ?: return null
        var limit = m.groupValues[2].toIntOrNull() ?: return null
        if (limit > MAX_LIMIT) return null
        val kind = if (type == RadarKind.BUMP && limit > 0) RadarKind.ELECTRONIC_BUMP else type
        if (kind !in ENFORCING) limit = 0
        return MapaRadarPoint(-number.toLong(), lat, lon, kind, limit)
    }

    /** Todos os pontos do arquivo, sem os repetidos (mesma posição e tipo). */
    fun read(lines: Sequence<String>): List<MapaRadarPoint> {
        val seen = HashSet<Triple<Long, Long, Int>>()
        val out = ArrayList<MapaRadarPoint>()
        var number = 0
        for (line in lines) {
            number++
            val p = parseLine(line, number) ?: continue
            if (seen.add(Triple((p.lat * 1e5).roundToLong(), (p.lon * 1e5).roundToLong(), p.kind))) out += p
        }
        return out
    }

    /**
     * Junta os radares do OSM com os pontos do MapaRadar. O sentido do CSV não existe: vale o do radar do OSM a até
     * [MERGE_M] (que é o mesmo ponto e sai da lista), senão o da via se ela for de mão única, senão os dois (-1). O que
     * só o OSM tem continua. [wayAt] devolve a via mais perto do ponto (ou null).
     */
    fun merge(osm: List<Radar>, points: List<MapaRadarPoint>, wayAt: (Double, Double) -> WayHint?): List<Radar> {
        val grid = HashMap<Long, MutableList<Int>>()
        osm.forEachIndexed { i, r -> grid.getOrPut(cellKey(r.lat, r.lon)) { ArrayList() } += i }
        val used = HashSet<Int>()
        val out = ArrayList<Radar>(points.size + osm.size)
        for (p in points) {
            var bearing = -1
            if (p.kind in ENFORCING) {
                var best = -1
                var bestD = Double.MAX_VALUE
                for (dy in -1..1) for (dx in -1..1) {
                    val key = cellKey(p.lat, p.lon) + dy * KEY_ROW + dx
                    for (i in grid[key].orEmpty()) {
                        if (i in used) continue
                        val d = Geo.distanceM(p.lat, p.lon, osm[i].lat, osm[i].lon)
                        if (d <= MERGE_M && d < bestD) {
                            best = i
                            bestD = d
                        }
                    }
                }
                if (best >= 0) {
                    used += best
                    bearing = osm[best].bearing
                }
            }
            if (bearing < 0) {
                val way = wayAt(p.lat, p.lon)
                if (way != null && way.oneway) bearing = way.bearing.roundToLong().toInt().mod(360)
            }
            out += Radar(p.id, p.lat, p.lon, p.limit, bearing, p.kind)
        }
        osm.forEachIndexed { i, r -> if (i !in used) out += r }
        return out
    }

    private const val KEY_ROW = 100_000L

    private fun cellKey(lat: Double, lon: Double): Long =
        (lat / Geo.CELL).roundToLong() * KEY_ROW + (lon / Geo.CELL).roundToLong()
}

/** Via mais perto de um ponto: rumo do trecho (graus) e se é de mão única. */
class WayHint(val bearing: Double, val oneway: Boolean)

/** Via mais perto do ponto entre os [runs], a até [maxM] metros; null se nenhuma. */
fun nearestWayHint(runs: List<Run>, lat: Double, lon: Double, maxM: Double = 40.0): WayHint? {
    val kx = cos(Math.toRadians(lat)) * Geo.M_PER_DEG_LON / 1e6
    val ky = Geo.M_PER_DEG_LAT / 1e6
    val latE6 = lat * 1e6
    val lonE6 = lon * 1e6
    var best: WayHint? = null
    var bestD = maxM
    for (run in runs) {
        for (i in 0 until run.lat.size - 1) {
            val ax = (run.lon[i] - lonE6) * kx
            val ay = (run.lat[i] - latE6) * ky
            val bx = (run.lon[i + 1] - lonE6) * kx
            val by = (run.lat[i + 1] - latE6) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestD) {
                bestD = d
                best = WayHint(
                    Geo.bearing(run.lat[i] / 1e6, run.lon[i] / 1e6, run.lat[i + 1] / 1e6, run.lon[i + 1] / 1e6),
                    run.way.oneway,
                )
            }
        }
    }
    return best
}

/** Contorno de um estado (GeoJSON Feature, Polygon ou MultiPolygon): quais pontos caem dentro dele. */
class Contour private constructor(private val polygons: List<List<Ring>>) {
    private class Ring(val lon: DoubleArray, val lat: DoubleArray) {
        val minLon = lon.minOrNull() ?: 0.0
        val maxLon = lon.maxOrNull() ?: 0.0
        val minLat = lat.minOrNull() ?: 0.0
        val maxLat = lat.maxOrNull() ?: 0.0

        fun contains(la: Double, lo: Double): Boolean {
            if (la < minLat || la > maxLat || lo < minLon || lo > maxLon) return false
            var inside = false
            var j = lon.size - 1
            for (i in lon.indices) {
                if ((lat[i] > la) != (lat[j] > la) &&
                    lo < (lon[j] - lon[i]) * (la - lat[i]) / (lat[j] - lat[i]) + lon[i]
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }
    }

    fun contains(lat: Double, lon: Double): Boolean =
        polygons.any { rings -> rings.first().contains(lat, lon) && rings.drop(1).none { it.contains(lat, lon) } }

    companion object {
        fun parse(text: String): Contour? = try {
            val root = JSONObject(text)
            val geometry = if (root.optString("type") == "Feature") root.getJSONObject("geometry") else root
            val coordinates = geometry.getJSONArray("coordinates")
            val polygons = when (geometry.getString("type")) {
                "Polygon" -> listOf(rings(coordinates))
                "MultiPolygon" -> (0 until coordinates.length()).map { rings(coordinates.getJSONArray(it)) }
                else -> emptyList()
            }
            if (polygons.isEmpty() || polygons.any { it.isEmpty() }) null else Contour(polygons)
        } catch (e: Exception) {
            null
        }

        private fun rings(polygon: JSONArray): List<Ring> = (0 until polygon.length()).map { r ->
            val ring = polygon.getJSONArray(r)
            Ring(
                DoubleArray(ring.length()) { ring.getJSONArray(it).getDouble(0) },
                DoubleArray(ring.length()) { ring.getJSONArray(it).getDouble(1) },
            )
        }
    }
}

/**
 * Resultado do processamento guardado no aparelho (a junção custa alguns segundos): vale enquanto a assinatura (o
 * arquivo do MapaRadar e os dados dos estados instalados) for a mesma.
 */
object RadarCache {
    private const val MAGIC = 0x52414432 // "RAD2"

    fun encode(signature: String, radars: List<Radar>): ByteArray {
        val bytes = ByteArrayOutputStream(radars.size * 33 + 64)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(signature)
            out.writeInt(radars.size)
            for (r in radars) {
                out.writeLong(r.id)
                out.writeDouble(r.lat)
                out.writeDouble(r.lon)
                out.writeInt(r.limit)
                out.writeInt(r.bearing)
                out.writeByte(r.kind)
            }
        }
        return bytes.toByteArray()
    }

    /** Os radares, ou null se o arquivo é de outra assinatura ou está estragado. */
    fun decode(bytes: ByteArray, signature: String): List<Radar>? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readUTF() != signature) return null
            val n = input.readInt()
            if (n < 0 || n > bytes.size) return null
            List(n) { Radar(input.readLong(), input.readDouble(), input.readDouble(), input.readInt(), input.readInt(), input.readByte().toInt()) }
        }
    } catch (e: IOException) {
        null
    }
}
