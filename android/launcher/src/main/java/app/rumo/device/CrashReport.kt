package app.rumo.device

import android.content.Context
import android.os.Environment
import android.os.Process
import android.util.Log
import app.rumo.core.CrashLog
import java.io.File
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * Quando o Rumo fecha com erro, grava o erro em `crash-report.txt`: na pasta Rumo, no pendrive (pastas do app nas
 * memórias externas) e nos arquivos do app. Na abertura seguinte, [collect] completa com o log do Android (que pega
 * também os fechamentos que não passam por aqui, como erro nativo) e avisa.
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

    /**
     * Na abertura: junta o que houver do fechamento anterior (o relatório do erro Java, se gravou; o trecho do logcat
     * com o fechamento, que pega também erro nativo; e os comandos do rádio da sessão anterior) e grava o
     * `crash-report.txt`. [endedOutside]: a sessão anterior acabou sem o Rumo sair da tela (o processo foi encerrado).
     * Verdadeiro se havia fechamento novo. Fora da thread principal.
     */
    fun collect(context: Context, previousLog: List<String>, endedOutside: Boolean): Boolean {
        val prefs = context.getSharedPreferences("rumo", Context.MODE_PRIVATE)
        val own = File(context.filesDir, FILE)
        val recorded = own.takeIf { it.isFile && it.lastModified() > prefs.getLong(PREF_SEEN, 0L) }?.readText()
        val block = CrashLog.lastCrash(logcat(LOGCAT_LINES), Process.myPid())
        val newBlock = block != null && block.firstOrNull() != prefs.getString(PREF_LAST_BLOCK, null)
        if (recorded == null && !newBlock && !endedOutside) return false
        val text = buildString {
            append(
                recorded ?: if (newBlock) {
                    "Rumo ${versionOf(context)} · fechou (achado no log do Android) · ${LocalDateTime.now()}\n"
                } else {
                    // Sem erro do Rumo: o processo foi encerrado de fora (pelo sistema ou pelo firmware) com a tela aberta
                    "Rumo ${versionOf(context)} · foi encerrado sem erro, de fora (sistema ou firmware) · ${LocalDateTime.now()}\n"
                },
            )
            if (newBlock && block != null) {
                append("\nLog do Android no fechamento:\n")
                block.forEach { append(it).append('\n') }
            }
            if (previousLog.isNotEmpty()) {
                append("\nComandos do rádio antes de fechar:\n")
                previousLog.takeLast(PREVIOUS_LINES).forEach { append(it).append('\n') }
            }
        }
        writeAll(context, text)
        prefs.edit()
            .putLong(PREF_SEEN, own.lastModified())
            .putString(PREF_LAST_BLOCK, block?.firstOrNull())
            .apply()
        return true
    }

    /** Últimas linhas do logcat que o app enxerga (só as dele). */
    fun logcat(lines: Int): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", lines.toString()).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readLines() }.also { p.waitFor() }
    } catch (e: Exception) {
        listOf("logcat indisponível: ${e.message}")
    }

    private fun versionOf(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        "?"
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
        val version = versionOf(ctx)
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
        writeAll(ctx, text)
    }

    @Suppress("DEPRECATION") // getExternalStorageDirectory: no Android 9 é o jeito de achar /sdcard
    private fun writeAll(ctx: Context, text: String) {
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
    private const val PREF_LAST_BLOCK = "crash_last_logcat"
    private const val LOGCAT_LINES = 5_000
    private const val PREVIOUS_LINES = 80
}
