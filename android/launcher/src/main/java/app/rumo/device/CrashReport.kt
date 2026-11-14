package app.rumo.device

import android.content.Context
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * Quando o Rumo fecha com erro, grava o erro em `crash-report.txt`: na pasta Rumo, no pendrive (pastas do app nas
 * memórias externas) e nos arquivos do app. Na abertura seguinte, [takeNew] avisa que há um relatório novo.
 * [extra] acrescenta o contexto do momento (ex.: os últimos comandos do rádio).
 */
object CrashReport {
    const val FILE = "crash-report.txt"

    @Volatile var extra: () -> List<String> = { emptyList() }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(context.applicationContext, previous))
    }

    /** Relatório gravado depois da última abertura (e marca como visto). */
    fun takeNew(context: Context): Boolean {
        val prefs = context.getSharedPreferences("rumo", Context.MODE_PRIVATE)
        val file = File(context.filesDir, FILE)
        val at = if (file.isFile) file.lastModified() else return false
        if (at <= prefs.getLong(PREF_SEEN, 0L)) return false
        prefs.edit().putLong(PREF_SEEN, at).apply()
        return true
    }

    private class Recorder(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                write(ctx, thread, error)
            } catch (e: Throwable) {
                // Sem relatório; o Android encerra o app do mesmo jeito
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    @Suppress("DEPRECATION") // getExternalStorageDirectory: no Android 9 é o jeito de achar /sdcard
    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val context = try {
            extra()
        } catch (e: Throwable) {
            emptyList()
        }
        val text = buildString {
            append("Rumo $version · fechou com erro · ${LocalDateTime.now()}\n")
            append("Thread: ${thread.name}\n\n")
            append(Log.getStackTraceString(error))
            if (context.isNotEmpty()) {
                append("\nÚltimos comandos do rádio:\n")
                context.forEach { append(it).append('\n') }
            }
        }
        val dirs = listOf(File(Environment.getExternalStorageDirectory(), "Rumo")) +
            ctx.getExternalFilesDirs(null).drop(1).filterNotNull() + ctx.filesDir
        for (dir in dirs) {
            try {
                dir.mkdirs()
                File(dir, FILE).writeText(text)
            } catch (e: Exception) {
                // Pasta sem permissão ou pendrive fora: tenta as outras
            }
        }
    }

    private const val PREF_SEEN = "crash_seen_at"
}
