package app.rumo.device

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import app.rumo.core.Geo
import app.rumo.core.Radar
import app.rumo.core.Run
import app.rumo.core.Way
import app.rumo.core.decodePoints
import app.rumo.core.decodePointsV1
import java.io.File

/**
 * Pasta "Rumo" copiada para o aparelho (gerada pelo GitHub Actions, ver dados/): `fontes/` e, em `regioes/<estado>/`,
 * o mapa.pmtiles e o vias.db de cada estado instalado. Procura na memória interna e, se não achar, nos cartões e
 * pendrives. Vários estados valem ao mesmo tempo: o mapa desenha todos e as vias de todos entram na busca da rua atual,
 * então atravessar a divisa não muda nada. Formato antigo (mapa.pmtiles e vias.db direto na pasta Rumo, só SC) ainda é
 * lido enquanto não houver estado novo.
 */
class MapData private constructor(val dir: File, private val regions: List<RegionData>) {

    /** Um mapa.pmtiles por estado instalado. */
    val mapFiles: List<File> = regions.mapNotNull { it.mapFile }

    /** Fontes dos nomes de rua (comuns a todos os estados). */
    val fontsDir = File(dir, "fontes")

    // Na divisa, os dois estados têm o mesmo radar (a folga de ~8 km se sobrepõe)
    val radars: List<Radar> = regions.flatMap { it.radars }.distinctBy { it.id }

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

    private fun loadCell(cell: Long): List<Run> {
        if (regions.size == 1) return regions[0].loadCell(cell)
        // A mesma via pode estar nos dois estados vizinhos, na faixa de folga da divisa
        val seen = HashSet<Triple<Long, Int, Int>>()
        return regions.flatMap { it.loadCell(cell) }.filter { seen.add(Triple(it.way.id, it.lat.firstOrNull() ?: 0, it.lon.firstOrNull() ?: 0)) }
    }

    /** vias.db e mapa.pmtiles de um estado. */
    private class RegionData(dir: File, private val db: SQLiteDatabase?) {
        val mapFile: File? = File(dir, MAP).takeIf { it.isFile }
        val radars: List<Radar> = db?.let(::loadRadars) ?: emptyList()
        private val info: Map<String, String> = db?.let(::loadInfo) ?: emptyMap()

        // vias.db versão 1 guardava os pontos em pares int32; a 2 (dados/vias.py) em diferenças com varint
        private val legacyPoints = (info["versao"] ?: "1") == "1"
        private val ways = HashMap<Long, Way>()

        fun close() = db?.close()

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
        const val REGIONS = "regioes"
        const val MAP = "mapa.pmtiles"
        const val ROADS = "vias.db"

        /** Abre a primeira pasta Rumo encontrada, ou null. Lê o disco: chamar fora da thread principal. */
        fun open(ctx: Context): MapData? {
            for (dir in candidates(ctx)) {
                val folders = File(dir, REGIONS).listFiles().orEmpty()
                    .filter { it.isDirectory && (File(it, ROADS).isFile || File(it, MAP).isFile) }
                    .sortedBy { it.name }
                    .ifEmpty { listOf(dir).filter { File(it, ROADS).isFile || File(it, MAP).isFile } } // formato antigo
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
            db.rawQuery("SELECT id, lat, lon, lim, bearing FROM radars", null).use { c ->
                while (c.moveToNext()) out += Radar(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3), c.getInt(4))
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
