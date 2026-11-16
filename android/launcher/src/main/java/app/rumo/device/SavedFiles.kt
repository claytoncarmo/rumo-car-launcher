package app.rumo.device

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * Gravação dos relatórios e registros que o dono leva do carro (pasta Rumo e pendrive).
 *
 * - **Atômica:** grava num `.tmp` e só então troca pelo definitivo. O carro pode desligar no meio da gravação, e um
 *   arquivo pela metade no pendrive não abre (o `alertas.log` ficou corrompido assim).
 * - **Visível:** avisa o índice de mídia do Android, que muitos gerenciadores de arquivos usam para listar; sem isso, um
 *   arquivo novo na memória interna pode não aparecer.
 */
object SavedFiles {

    /** Grava [text] em [target]; devolve se deu certo. */
    fun write(context: Context?, target: File, text: String): Boolean = replace(context, target) { it.writeText(text) }

    /** Copia [source] para [target]. */
    fun copy(context: Context?, source: File, target: File): Boolean = replace(context, target) { source.copyTo(it, overwrite = true) }

    private fun replace(context: Context?, target: File, fill: (File) -> Unit): Boolean = try {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        fill(tmp)
        // Em alguns sistemas de arquivos (FAT do pendrive) o rename não substitui um arquivo que já existe
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("não deu para trocar ${target.name}")
        }
        context?.let { MediaScannerConnection.scanFile(it.applicationContext, arrayOf(target.path), null, null) }
        true
    } catch (e: Exception) {
        false
    }
}
