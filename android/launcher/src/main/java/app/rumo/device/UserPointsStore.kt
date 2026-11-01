package app.rumo.device

import app.rumo.core.UserPoint
import app.rumo.core.UserPoints
import java.io.File
import java.io.IOException

/**
 * Guarda os pontos do usuário em mais de um arquivo ao mesmo tempo (a pasta Rumo da memória interna, que também
 * sobrevive a desinstalar o app, e a pasta privada do app, que não depende da permissão de arquivos). Ao ler vale o
 * arquivo mais recente. Atualizar o app não mexe em nenhum dos dois.
 */
class UserPointsStore(private val files: List<File>) {

    /** Os pontos do arquivo mais recente que se consegue ler (vazio se não há nenhum). */
    fun load(): List<UserPoint> {
        for (f in files.filter { it.isFile }.sortedByDescending { it.lastModified() }) {
            try {
                return UserPoints.decode(f.readText())
            } catch (e: IOException) {
                // tenta o próximo
            }
        }
        return emptyList()
    }

    /** Grava em todos os arquivos (sem deixar um arquivo pela metade) e devolve em quantos conseguiu. */
    @Synchronized
    fun save(points: List<UserPoint>): Int {
        val text = UserPoints.encode(points)
        var saved = 0
        for (f in files) {
            try {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.writeText(text)
                    tmp.delete()
                }
                saved++
            } catch (e: IOException) {
                // sem permissão ou sem espaço neste: o outro arquivo guarda
            }
        }
        return saved
    }
}
