package app.rumo.device

import app.rumo.core.Http
import app.rumo.core.WebRadio
import app.rumo.core.WebStation
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.InetAddress

/**
 * Lista de estações do rádio online, do catálogo Radio Browser, com cópia em [dir] (a última resposta de cada
 * consulta): a lista aparece na hora, mesmo sem Wi-Fi, e só é consultada de novo depois de [MAX_AGE_MS].
 * Chamar fora da thread principal.
 */
class WebRadioCatalog(private val dir: File) {

    fun cached(key: String): List<WebStation>? = try {
        file(key).takeIf { it.isFile }?.readText()?.let(WebRadio::parse)
    } catch (e: Exception) {
        null
    }

    fun fresh(key: String, now: Long = System.currentTimeMillis()): Boolean =
        file(key).let { it.isFile && now - it.lastModified() < MAX_AGE_MS }

    /** Consulta [path] no primeiro servidor do catálogo que responder e guarda a resposta. */
    fun fetch(path: String, key: String): List<WebStation> {
        var last: Exception? = null
        for (server in servers()) {
            try {
                val text = Http().getText(server + path)
                val list = WebRadio.parse(text)
                dir.mkdirs()
                val tmp = File(dir, "$key.tmp")
                tmp.writeText(text)
                tmp.renameTo(file(key))
                return list
            } catch (e: IOException) {
                last = e
            } catch (e: JSONException) {
                last = e
            }
        }
        throw IOException("catálogo de rádios fora do ar", last)
    }

    private fun file(key: String) = File(dir, "$key.json")

    /** Os servidores de agora vêm pelo DNS ("all" aponta para todos); os conhecidos ficam de reserva. */
    private fun servers(): List<String> {
        val live = try {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { it.canonicalHostName.takeIf { h -> h.endsWith(".radio-browser.info") } }
                .map { "https://$it" }
                .shuffled()
        } catch (e: Exception) {
            emptyList()
        }
        return (live + WebRadio.SERVERS).distinct()
    }

    private companion object {
        const val MAX_AGE_MS = 24 * 3_600_000L
    }
}
