package app.rumo.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DownloadCancelled : IOException("Download cancelado")

class HttpFailure(val code: Int, url: String) : IOException("HTTP $code em $url")

/** SHA-256 de um arquivo, em minúsculas. */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { feed(it, digest) }
    return hex(digest.digest())
}

private fun feed(input: InputStream, digest: MessageDigest) {
    val buf = ByteArray(BUFFER)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return
        digest.update(buf, 0, n)
    }
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private const val BUFFER = 64 * 1024

/**
 * Cliente HTTP mínimo (só `java.net`): segue redirecionamentos (o GitHub manda os downloads para outro endereço),
 * baixa com retomada e confere o SHA-256. Só aceita https; [allowHttp] existe para os testes locais.
 */
class Http(private val allowHttp: Boolean = false) {

    /** Texto de um endereço (os manifestos). */
    fun getText(url: String): String {
        val c = open(url, 0)
        try {
            if (c.responseCode != 200) throw HttpFailure(c.responseCode, url)
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT) throw IOException("resposta grande demais")
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Baixa [url] para [dest]. O que já veio fica em `dest.part` e é retomado na próxima vez. Só grava [dest] (com
     * rename) depois de conferir o tamanho e o [sha256]. [onProgress] recebe os bytes já gravados; [cancelled] é
     * consultado a cada bloco e, se verdadeiro, para e guarda o `.part`.
     */
    fun download(
        url: String,
        dest: File,
        size: Long,
        sha256: String,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        require(size > 0) { "tamanho esperado inválido" }
        dest.absoluteFile.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        attempt(url, dest, part, size, sha256, onProgress, cancelled, retried = false)
    }

    private fun attempt(
        url: String,
        dest: File,
        part: File,
        size: Long,
        sha256: String,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
        retried: Boolean,
    ) {
        var have = if (part.isFile) part.length() else 0L
        if (have > size) {
            part.delete()
            have = 0
        }
        val digest = MessageDigest.getInstance("SHA-256")

        if (have < size) {
            val c = open(url, have)
            try {
                val code = c.responseCode
                if (code == 416 && !retried) {
                    // O servidor não aceitou o pedaço: recomeça do zero
                    part.delete()
                    attempt(url, dest, part, size, sha256, onProgress, cancelled, retried = true)
                    return
                }
                val resumed = code == 206 && have > 0 && c.getHeaderField("Content-Range")?.startsWith("bytes $have-") == true
                if (code != 200 && !resumed) throw HttpFailure(code, url)
                if (!resumed) {
                    // 200: o servidor mandou o arquivo inteiro (ou não havia o que retomar)
                    if (part.exists()) part.delete()
                    have = 0
                } else {
                    part.inputStream().use { feed(it, digest) }
                }
                onProgress(have)
                FileOutputStream(part, true).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (cancelled()) throw DownloadCancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            have += n
                            onProgress(have)
                            if (have > size) break
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        } else {
            // O .part já está completo (o app parou antes do rename): só confere
            part.inputStream().use { feed(it, digest) }
        }

        when {
            part.length() > size -> {
                part.delete()
                throw IOException("arquivo maior que o esperado")
            }
            part.length() < size -> throw IOException("download incompleto: ${part.length()} de $size bytes")
        }
        if (!hex(digest.digest()).equals(sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("o arquivo baixado não confere com o hash do manifesto")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    /** Abre o endereço seguindo redirecionamentos (e mantendo o Range). Devolve a conexão já com resposta. */
    private fun open(start: String, rangeFrom: Long): HttpURLConnection {
        var url = URL(start)
        for (i in 0..MAX_REDIRECTS) {
            if (url.protocol != "https" && !(allowHttp && url.protocol == "http")) {
                throw IOException("só https é aceito: $url")
            }
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.setRequestProperty("User-Agent", "Rumo")
            if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
            when (c.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val location = c.getHeaderField("Location") ?: throw IOException("redirecionamento sem destino")
                    c.disconnect()
                    url = URL(url, location)
                }
                else -> return c
            }
        }
        throw IOException("redirecionamentos demais")
    }

    private companion object {
        const val CONNECT_MS = 10_000
        const val READ_MS = 20_000
        const val MAX_REDIRECTS = 6
        const val MAX_TEXT = 1 shl 20
    }
}
