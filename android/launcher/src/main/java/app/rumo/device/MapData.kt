package app.rumo.device

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import app.rumo.core.Contour
import app.rumo.core.Geo
import app.rumo.core.MapaRadar
import app.rumo.core.RadarCache
import app.rumo.core.nearestWayHint
import app.rumo.core.Radar
import app.rumo.core.RadarKind
import app.rumo.core.Run
import app.rumo.core.Way
import app.rumo.core.decodePoints
import app.rumo.core.decodePointsV1
import java.io.File
import java.io.IOException

/**
 * Pasta "Rumo" copiada para o aparelho (gerada pelo GitHub Actions, ver data/): `fonts/` e, em `regions/<estado>/`,
 * o map.pmtiles e o roads.db de cada estado instalado. Procura na memória interna e, se não achar, nos cartões e
 * pendrives. Vários estados valem ao mesmo tempo: o mapa desenha todos e as vias de todos entram na busca da rua atual,
 * então atravessar a divisa não muda nada.
 */
class MapData private constructor(val dir: File, private val regions: List<RegionData>) {

    /** Um map.pmtiles por estado instalado. */
    val mapFiles: List<File> = regions.mapNotNull { it.mapFile }

    /** Fontes dos nomes de rua (comuns a todos os estados). */
    val fontsDir = File(dir, "fonts")

    // Na divisa, os dois estados têm o mesmo radar (a folga de ~8 km se sobrepõe)
    private val osmRadars: List<Radar> = regions.flatMap { it.radars }.distinctBy { it.id }
    private var baseRadars: List<Radar> = osmRadars
    private var mineRadars: List<Radar> = emptyList()

    /**
     * Radares do OSM e, depois de [setMapaRadar], também os do MapaRadar, mais os pontos do usuário ([setMine]).
     * Lida na thread do GPS: a lista é trocada inteira, nunca alterada.
     */
    @Volatile var radars: List<Radar> = osmRadars
        private set

    /** Troca os radares de base (OSM junto com o MapaRadar); os pontos do usuário continuam. */
    @Synchronized
    fun setMapaRadar(merged: List<Radar>) {
        baseRadars = merged
        radars = baseRadars + mineRadars
    }

    /** Troca os pontos marcados pelo usuário (já como radares). */
    @Synchronized
    fun setMine(mine: List<Radar>) {
        mineRadars = mine
        radars = baseRadars + mineRadars
    }

    // Poucas células por vez: o carro anda ~1 célula por minuto a 60 km/h
    private val cells = object : LinkedHashMap<Long, List<Run>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Run>>) = size > 48
    }

    /** Trechos de via a até [radiusM] do ponto. Chamar fora da thread principal. */
    fun runsNear(lat: Double, lon: Double, radiusM: Double): List<Run> {
        val out = ArrayList<Run>()
        for (c in Geo.cellsAround(lat, lon, radiusM)) out += cells.getOrPut(c) { loadCell(c) }
        return out
    }

    fun close() = regions.forEach { it.close() }

    /**
     * Junta o `maparadar.csv` original (pasta Rumo) com os radares do OSM: recorta pelos contornos dos estados
     * instalados e acha, em cada ponto, o sentido da via. É o processamento no aparelho (a licença não deixa publicar
     * nada derivado do arquivo). Demora alguns segundos, então o resultado fica em [cacheFile] e só se refaz quando o
     * CSV ou os dados dos estados mudam. Null se não há CSV ou nenhum contorno. Chamar fora da thread principal.
     */
    fun loadMapaRadar(cacheFile: File): List<Radar>? {
        val csv = File(dir, MAPARADAR).takeIf { it.isFile } ?: return null
        val signature = "${csv.length()}:${csv.lastModified()}" + regions.joinToString("") { "|" + it.signature() }
        try {
            if (cacheFile.isFile) RadarCache.decode(cacheFile.readBytes(), signature)?.let { return it }
        } catch (e: IOException) {
            // cache ilegível: refaz
        }
        val contours = regions.mapNotNull { it.contourFile()?.let { f -> Contour.parse(f.readText()) } }
        if (contours.isEmpty()) return null
        val points = csv.bufferedReader().use { MapaRadar.read(it.lineSequence()) }
            .filter { p -> contours.any { it.contains(p.lat, p.lon) } }
            // Pontos vizinhos pedem as mesmas células de vias: ordenar poupa leituras do banco
            .sortedBy { Geo.cellOf(it.lat, it.lon) }
        val cells = object : LinkedHashMap<Long, List<Run>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Run>>) = size > 128
        }
        val merged = MapaRadar.merge(osmRadars, points) { lat, lon ->
            val runs = Geo.cellsAround(lat, lon, WAY_SEARCH_M).flatMap { c ->
                cells.getOrPut(c) { regions.flatMap { it.loadCell(c) } }
            }
            nearestWayHint(runs, lat, lon)
        }
        try {
            cacheFile.writeBytes(RadarCache.encode(signature, merged))
        } catch (e: IOException) {
            // sem cache, só refaz na próxima vez
        }
        return merged
    }

    private fun loadCell(cell: Long): List<Run> {
        if (regions.size == 1) return regions[0].loadCell(cell)
        // A mesma via pode estar nos dois estados vizinhos, na faixa de folga da divisa
        val seen = HashSet<Triple<Long, Int, Int>>()
        return regions.flatMap { it.loadCell(cell) }.filter { seen.add(Triple(it.way.id, it.lat.firstOrNull() ?: 0, it.lon.firstOrNull() ?: 0)) }
    }

    /** roads.db e map.pmtiles de um estado. */
    private class RegionData(dir: File, private val db: SQLiteDatabase?) {
        private val dir = dir
        val mapFile: File? = File(dir, MAP).takeIf { it.isFile }

        fun contourFile(): File? = File(dir, CONTOUR).takeIf { it.isFile }

        /** Muda quando os dados do estado mudam (entra na assinatura do cache dos radares). */
        fun signature(): String {
            val roadsFile = File(dir, ROADS)
            return "${dir.name}:${roadsFile.length()}:${roadsFile.lastModified()}:${contourFile()?.length() ?: 0}"
        }
        val radars: List<Radar> = db?.let(::loadRadars) ?: emptyList()
        private val info: Map<String, String> = db?.let(::loadInfo) ?: emptyMap()

        // roads.db versão 1 guardava os pontos em pares int32; a 2 (data/roads.py) em diferenças com varint
        private val legacyPoints = (info["version"] ?: "1") == "1"
        private val ways = HashMap<Long, Way>()

        fun close() = db?.close()

        @Synchronized
        fun loadCell(cell: Long): List<Run> {
            val db = db ?: return emptyList()
            val out = ArrayList<Run>()
            db.rawQuery("SELECT way, pts FROM segs WHERE cell = ?", arrayOf(cell.toString())).use { c ->
                while (c.moveToNext()) {
                    val way = wayOf(db, c.getLong(0)) ?: continue
                    val blob = c.getBlob(1)
                    val p = if (legacyPoints) decodePointsV1(blob) else decodePoints(blob)
                    out += Run(way, p.lat, p.lon)
                }
            }
            return out
        }

        private fun wayOf(db: SQLiteDatabase, id: Long): Way? {
            ways[id]?.let { return it }
            db.rawQuery("SELECT name, ref, kind, oneway, fwd, bwd, est FROM ways WHERE id = ?", arrayOf(id.toString())).use { c ->
                if (!c.moveToFirst()) return null
                val est = c.getInt(6)
                return Way(
                    id = id,
                    name = c.getString(0),
                    ref = c.getString(1),
                    kind = c.getInt(2),
                    oneway = c.getInt(3) != 0,
                    fwd = c.getInt(4),
                    bwd = c.getInt(5),
                    fwdEstimated = est and 1 != 0,
                    bwdEstimated = est and 2 != 0,
                ).also { ways[id] = it }
            }
        }
    }

    companion object {
        const val FOLDER = "Rumo"
        const val REGIONS = "regions"
        const val MAP = "map.pmtiles"
        const val ROADS = "roads.db"
        const val CONTOUR = "contour.geojson"
        const val MAPARADAR = "maparadar.csv"
        private const val WAY_SEARCH_M = 45.0

        /** Abre a primeira pasta Rumo encontrada, ou null. Lê o disco: chamar fora da thread principal. */
        fun open(ctx: Context): MapData? {
            for (dir in candidates(ctx)) {
                val folders = File(dir, REGIONS).listFiles().orEmpty()
                    .filter { it.isDirectory && (File(it, ROADS).isFile || File(it, MAP).isFile) }
                    .sortedBy { it.name }
                if (folders.isEmpty()) continue
                return MapData(dir, folders.map { RegionData(it, openDb(File(it, ROADS))) })
            }
            return null
        }

        private fun openDb(file: File): SQLiteDatabase? {
            if (!file.isFile) return null
            return try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            } catch (e: Exception) {
                null
            }
        }

        @Suppress("DEPRECATION") // getExternalStorageDirectory: no Android 9 é o jeito de achar /sdcard
        private fun candidates(ctx: Context): List<File> {
            val roots = mutableListOf(Environment.getExternalStorageDirectory())
            ctx.getExternalFilesDir(null)?.let { roots += it }
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.let { roots += it }
            File("/mnt/media_rw").listFiles()?.let { roots += it }
            return roots.map { File(it, FOLDER) }
        }

        private fun loadRadars(db: SQLiteDatabase): List<Radar> {
            val out = ArrayList<Radar>()
            // roads.db antigo não tem a coluna kind (tipo do ponto)
            val hasKind = db.rawQuery("PRAGMA table_info(radars)", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == "kind" }
            }
            val sql = if (hasKind) "SELECT id, lat, lon, lim, bearing, kind FROM radars" else "SELECT id, lat, lon, lim, bearing FROM radars"
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val kind = if (hasKind) c.getInt(5) else RadarKind.OSM
                    out += Radar(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3), c.getInt(4), kind)
                }
            }
            return out
        }

        private fun loadInfo(db: SQLiteDatabase): Map<String, String> {
            val out = HashMap<String, String>()
            db.rawQuery("SELECT k, v FROM info", null).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            }
            return out
        }
    }
}
