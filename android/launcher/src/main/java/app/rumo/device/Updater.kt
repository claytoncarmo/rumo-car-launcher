package app.rumo.device

import android.content.SharedPreferences
import android.os.Environment
import app.rumo.core.AppRelease
import app.rumo.core.Channel
import app.rumo.core.DataManifest
import app.rumo.core.DataPlan
import app.rumo.core.Endpoints
import app.rumo.core.Http
import app.rumo.core.HttpFailure
import app.rumo.core.LocalFile
import app.rumo.core.RemoteFile
import app.rumo.core.parseAppRelease
import app.rumo.core.parseDataManifest
import app.rumo.core.planData
import app.rumo.core.sha256Of
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** O que se sabe de um canal (estável ou nightly): a versão publicada ou o motivo de não haver. */
data class ChannelInfo(val release: AppRelease?, val problem: String?)

/** Dados offline: o manifesto, o que falta baixar no aparelho e, se deu errado, o motivo. */
data class DataInfo(val manifest: DataManifest?, val plan: DataPlan?, val problem: String?)

/**
 * Consulta as releases do repositório e baixa o app e os dados offline (mapa, vias, radares) para o aparelho.
 * Só faz entrada e saída: quem chama decide quando e mostra o progresso. Tudo roda fora da thread principal.
 */
class Updater(private val prefs: SharedPreferences, private val http: Http = Http()) {

    /** Pasta Rumo na memória interna do aparelho (`/sdcard/Rumo`): é onde o app procura os dados. */
    @Suppress("DEPRECATION") // getExternalStorageDirectory: no Android 9 é o jeito de achar /sdcard
    private val rumoDir: File get() = File(Environment.getExternalStorageDirectory(), "Rumo")

    suspend fun fetchChannel(channel: Channel): ChannelInfo = withContext(Dispatchers.IO) {
        try {
            val release = parseAppRelease(http.getText(channel.url))
            if (release == null) ChannelInfo(null, "Manifesto da versão inválido") else ChannelInfo(release, null)
        } catch (e: HttpFailure) {
            ChannelInfo(null, if (e.code == 404) "Nenhuma versão publicada" else "Erro ${e.code} no servidor")
        } catch (e: IOException) {
            ChannelInfo(null, "Sem conexão com o GitHub")
        }
    }

    /** Manifesto dos dados e o que falta baixar. Pode demorar na primeira vez (confere o hash de dados copiados à mão). */
    suspend fun fetchData(): DataInfo = withContext(Dispatchers.IO) {
        try {
            val manifest = parseDataManifest(http.getText(Endpoints.DATA))
                ?: return@withContext DataInfo(null, null, "Manifesto dos dados inválido")
            val region = manifest.regions.first()
            val plan = planData(region, { localFile(it) }, { sha256Of(fileOf(it)) })
            // Estavam certos, só não tinham registro: grava para não conferir de novo
            plan.verified.forEach { record(it) }
            DataInfo(manifest, plan, null)
        } catch (e: HttpFailure) {
            DataInfo(null, null, if (e.code == 404) "Dados ainda não publicados" else "Erro ${e.code} no servidor")
        } catch (e: IOException) {
            DataInfo(null, null, "Sem conexão com o GitHub")
        }
    }

    /**
     * Baixa os arquivos do [plan] direto para a pasta Rumo, um por vez, com retomada. [onProgress] recebe os bytes já
     * baixados do plano todo.
     */
    suspend fun downloadData(plan: DataPlan, onProgress: (done: Long) -> Unit, cancelled: () -> Boolean) =
        withContext(Dispatchers.IO) {
            var before = 0L
            for (file in plan.download) {
                val base = before
                http.download(file.url, fileOf(file), file.size, file.sha256, { onProgress(base + it) }, cancelled)
                record(file)
                before += file.size
            }
        }

    /** Baixa o APK para [folder] e o devolve pronto para instalar (já conferido pelo tamanho e pelo hash). */
    suspend fun downloadApp(
        release: AppRelease,
        folder: File,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): File = withContext(Dispatchers.IO) {
        // O nome da versão vem da rede: só letras, números, ponto, hífen e sublinhado viram nome de arquivo
        val name = "rumo-${release.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
        val apk = File(folder, name)
        // APKs de versões antigas não servem mais (o .part desta versão fica, para retomar)
        folder.listFiles()?.filter { it.name != name && it.name != "$name.part" }?.forEach { it.delete() }
        if (apk.isFile && apk.length() == release.size && sha256Of(apk).equals(release.sha256, ignoreCase = true)) {
            return@withContext apk
        }
        http.download(release.url, apk, release.size, release.sha256, onProgress, cancelled)
        apk
    }

    private fun fileOf(f: RemoteFile) = File(rumoDir, f.dest)

    private fun localFile(f: RemoteFile): LocalFile {
        val file = fileOf(f)
        return LocalFile(file.isFile, if (file.isFile) file.length() else 0L, prefs.getString(KEY + f.dest, null))
    }

    private fun record(f: RemoteFile) = prefs.edit().putString(KEY + f.dest, f.sha256).apply()

    private companion object {
        const val KEY = "dados_"
    }
}
