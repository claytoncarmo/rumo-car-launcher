package app.rumo.device

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import app.rumo.core.Geo
import app.rumo.core.Radar
import app.rumo.core.Run
import app.rumo.core.Way
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pasta "Rumo" copiada para o aparelho (gerada pelo GitHub Actions, ver dados/): mapa.pmtiles, vias.db e fontes/.
 * Procura na memória interna e, se não achar, nos cartões e pendrives.
 */
class MapData private constructor(val dir: File, private val db: SQLiteDatabase?) {

    val mapFile: File? = File(dir, MAP).takeIf { it.isFile }
    val radars: List<Radar> = db?.let(::loadRadars) ?: emptyList()
    val info: Map<String, String> = db?.let(::loadInfo) ?: emptyMap()

    private val ways = HashMap<Long, Way>()
    // Poucas células por vez: o carro anda ~1 célula por minuto a 60 km/h
    private val cells = object : LinkedHashMap<Long, List<Run>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Run>>) = size > 48
    }

    /** Trechos de via a até [radiusM] do ponto. Chamar fora da thread principal. */
    fun runsNear(lat: Double, lon: Double, radiusM: Double): List<Run> {
        if (db == null) return emptyList()
        val out = ArrayList<Run>()
        for (c in Geo.cellsAround(lat, lon, radiusM)) out += cells.getOrPut(c) { loadCell(c) }
        return out
    }

    fun close() = db?.close()

    private fun loadCell(cell: Long): List<Run> {
        val db = db ?: return emptyList()
        val out = ArrayList<Run>()
        db.rawQuery("SELECT way, pts FROM segs WHERE cell = ?", arrayOf(cell.toString())).use { c ->
            while (c.moveToNext()) {
                val way = wayOf(c.getLong(0)) ?: continue
                val buf = ByteBuffer.wrap(c.getBlob(1)).order(ByteOrder.LITTLE_ENDIAN)
                val n = buf.remaining() / 8
                val lat = IntArray(n)
                val lon = IntArray(n)
                for (i in 0 until n) {
                    lat[i] = buf.int
                    lon[i] = buf.int
                }
                out += Run(way, lat, lon)
            }
        }
        return out
    }

    private fun wayOf(id: Long): Way? {
        ways[id]?.let { return it }
        val db = db ?: return null
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

    companion object {
        const val FOLDER = "Rumo"
        const val MAP = "mapa.pmtiles"
        const val ROADS = "vias.db"

        /** Abre a primeira pasta Rumo encontrada, ou null. Lê o disco: chamar fora da thread principal. */
        fun open(ctx: Context): MapData? {
            val dir = candidates(ctx).firstOrNull { File(it, ROADS).isFile || File(it, MAP).isFile } ?: return null
            val db = File(dir, ROADS).takeIf { it.isFile }?.let {
                try {
                    SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                } catch (e: Exception) {
                    null
                }
            }
            return MapData(dir, db)
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
