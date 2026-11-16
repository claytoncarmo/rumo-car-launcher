package app.rumo.device

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Registro dos alertas sonoros, para entender depois por que um aviso não tocou: radar que entrou na zona, bipes
 * ligados e desligados, e o que o áudio respondeu a cada um. Só eventos (nunca um por posição do GPS). Vai para
 * Rumo/alertas.log na memória interna; passando de [MAX_BYTES] o arquivo vira alertas-anterior.log.
 */
class AlertLog {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "rumo-registro").apply { isDaemon = true } }
    private val stamp = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    @Suppress("DEPRECATION")
    private val dir = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun add(text: String) {
        val line = "${LocalDateTime.now().format(stamp)} $text\n"
        io.execute {
            try {
                dir.mkdirs()
                val file = File(dir, FILE)
                if (file.length() > MAX_BYTES) {
                    val old = File(dir, FILE_OLD)
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line)
            } catch (e: Exception) {
                // sem o registro, o resto segue igual
            }
        }
    }

    /**
     * Copia o registro para a pasta do Rumo em cada pendrive/cartão (o que se leva do carro para o computador), sem
     * deixar cópia pela metade se o carro desligar no meio, e avisa o índice de mídia (o arquivo aparece na memória
     * interna nos gerenciadores de arquivos).
     */
    fun copyTo(context: Context, dirs: List<File>) {
        val app = context.applicationContext
        io.execute {
            val file = File(dir, FILE)
            if (!file.exists()) return@execute
            MediaScannerConnection.scanFile(app, arrayOf(file.path), null, null)
            for (d in dirs) SavedFiles.copy(app, file, File(d, FILE))
        }
    }

    private companion object {
        const val FOLDER = "Rumo"
        const val FILE = "alertas.log"
        const val FILE_OLD = "alertas-anterior.log"
        const val MAX_BYTES = 200_000L
    }
}
