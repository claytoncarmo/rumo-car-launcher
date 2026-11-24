package app.rumo.device

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Registro dos alertas sonoros, para entender depois por que um aviso não tocou: radar que entrou na zona, bipes
 * ligados e desligados, e o que o áudio respondeu a cada um. Só eventos (nunca um por posição do GPS). Vai para
 * Rumo/alertas.log na memória interna; passando de [MAX_BYTES] o arquivo vira alertas-anterior.log.
 */
class AlertLog(private val fileName: String = "alertas.log", private val oldFileName: String = "alertas-anterior.log") {
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "rumo-registro").apply { isDaemon = true } }

    /** Cópia automática para os pendrives (ver [copyEvery]). */
    private class AutoCopy(val context: Context, val everyMs: Long, val dirs: () -> List<File>)

    @Volatile private var autoCopy: AutoCopy? = null
    private var copyPending = false

    /**
     * Depois de cada anotação nova, copia o registro para os pendrives no máximo [everyMs] depois. O multimídia desliga
     * com o Rumo na tela (sem passar pela saída da tela), e a cópia só na saída deixava o pendrive sem a viagem.
     */
    fun copyEvery(context: Context, everyMs: Long, dirs: () -> List<File>) {
        autoCopy = AutoCopy(context.applicationContext, everyMs, dirs)
    }
    private val stamp = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    @Suppress("DEPRECATION")
    private val dir = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun add(text: String) {
        val line = "${LocalDateTime.now().format(stamp)} $text\n"
        io.execute {
            try {
                dir.mkdirs()
                val file = File(dir, fileName)
                if (file.length() > MAX_BYTES) {
                    val old = File(dir, oldFileName)
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line)
            } catch (e: Exception) {
                // sem o registro, o resto segue igual
            }
            scheduleCopy()
        }
    }

    /** Na thread do registro: agenda uma cópia, se ainda não houver uma esperando. */
    private fun scheduleCopy() {
        val c = autoCopy ?: return
        if (copyPending) return
        copyPending = true
        io.schedule(
            Runnable {
                copyPending = false
                copyNow(c.context, c.dirs())
            },
            c.everyMs, TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Copia o registro para a pasta do Rumo em cada pendrive/cartão (o que se leva do carro para o computador), sem
     * deixar cópia pela metade se o carro desligar no meio, e avisa o índice de mídia (o arquivo aparece na memória
     * interna nos gerenciadores de arquivos).
     */
    fun copyTo(context: Context, dirs: List<File>) {
        val app = context.applicationContext
        io.execute { copyNow(app, dirs) }
    }

    private fun copyNow(app: Context, dirs: List<File>) {
        val file = File(dir, fileName)
        if (!file.exists()) return
        MediaScannerConnection.scanFile(app, arrayOf(file.path), null, null)
        for (d in dirs) SavedFiles.copy(app, file, File(d, fileName))
    }

    private companion object {
        const val FOLDER = "Rumo"
        const val MAX_BYTES = 200_000L
    }
}
