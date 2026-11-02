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
import app.rumo.core.Region
import app.rumo.core.RegionInfo
import app.rumo.core.RemoteFile
import app.rumo.core.parseAppRelease
import app.rumo.core.parseDataManifest
import app.rumo.core.parseMapaRadarManifest
import app.rumo.core.dataRecord
import app.rumo.core.planData
import app.rumo.core.recordedSha
import app.rumo.core.sha256Of
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * O que se sabe de um canal (estável ou nightly): a versão publicada ou o motivo de não haver. [offline]: a consulta
 * falhou por falta de conexão (e não porque o GitHub respondeu erro).
 */
data class ChannelInfo(val release: AppRelease?, val problem: String?, val offline: Boolean = false)

/** Sem conexão a consulta não apaga o que já se sabia: fica a última que deu certo. */
fun ChannelInfo.keepingLast(previous: ChannelInfo?): ChannelInfo =
    if (offline && previous?.release != null) previous else this

/**
 * Dados offline: o manifesto, cada estado com o que falta baixar, o que falta dos arquivos comuns (fontes) e, se deu
 * errado, o motivo.
 */
data class DataInfo(
    val manifest: DataManifest?,
    val regions: List<RegionInfo>,
    val common: DataPlan?,
    val problem: String?,
    val offline: Boolean = false,
)

/** Sem conexão a consulta não apaga o que já se sabia: fica a última que deu certo. */
fun DataInfo.keepingLast(previous: DataInfo?): DataInfo =
    if (offline && previous?.manifest != null) previous else this

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
            ChannelInfo(null, "Sem conexão com o GitHub", offline = true)
        }
    }

    /** Manifesto dos dados e o que falta baixar. Pode demorar na primeira vez (confere o hash de dados copiados à mão). */
    suspend fun fetchData(): DataInfo = withContext(Dispatchers.IO) {
        try {
            val manifest = parseDataManifest(http.getText(Endpoints.DATA))
                ?: return@withContext DataInfo(null, emptyList(), null, "Manifesto dos dados inválido")
            val regions = manifest.regions.map { RegionInfo(it, planOf(it)) }
            // O CSV original do MapaRadar vem de outra release; sem ela (ou sem rede para ela) o resto segue
            val radarFile = try {
                parseMapaRadarManifest(http.getText(Endpoints.MAPARADAR))
            } catch (e: IOException) {
                null
            }
            val common = planOf(Region("common", "Fontes e radares", manifest.common + listOfNotNull(radarFile)))
            DataInfo(manifest, regions, common, null)
        } catch (e: HttpFailure) {
            DataInfo(null, emptyList(), null, if (e.code == 404) "Dados ainda não publicados" else "Erro ${e.code} no servidor")
        } catch (e: IOException) {
            DataInfo(null, emptyList(), null, "Sem conexão com o GitHub", offline = true)
        }
    }

    private fun planOf(region: Region): DataPlan {
        val plan = planData(region, { localFile(it) }, { sha256Of(fileOf(it)) })
        // Estavam certos, só não tinham registro: grava para não conferir de novo
        plan.verified.forEach { record(it) }
        return plan
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

    /** Apaga os arquivos de um estado (`regions/<id>`). O id já foi validado ao ler o manifesto. */
    suspend fun removeRegion(region: Region) = withContext(Dispatchers.IO) {
        File(rumoDir, "$REGIONS/${region.id}").deleteRecursively()
        region.files.forEach { prefs.edit().remove(KEY + it.dest).apply() }
    }

    /**
     * Os nomes das pastas e dos arquivos de dados passaram para o inglês (regioes/mapa.pmtiles/vias.db/fontes viraram
     * regions/map.pmtiles/roads.db/fonts). Depois que um estado novo está inteiro no aparelho, a pasta antiga, que só
     * ocupa espaço, sai junto com o registro de hashes dela.
     */
    suspend fun removeOldLayout() = withContext(Dispatchers.IO) {
        val regions = File(rumoDir, REGIONS).listFiles().orEmpty()
        val complete = regions.any { File(it, "map.pmtiles").isFile && File(it, "roads.db").isFile }
        if (complete) {
            listOf("regioes", "fontes").forEach { File(rumoDir, it).deleteRecursively() }
            listOf("mapa.pmtiles", "vias.db", "mapa.pmtiles.part", "vias.db.part").forEach { File(rumoDir, it).delete() }
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(OLD_KEY) }.forEach { editor.remove(it) }
            editor.apply()
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
        if (!file.isFile) return LocalFile(false, 0L, null)
        val record = prefs.getString(KEY + f.dest, null)
        val sha = recordedSha(record, file.lastModified())
        // Registro antigo (sem a data): passa a valer só para este arquivo
        if (sha != null && sha == record) record(f, sha)
        return LocalFile(true, file.length(), sha)
    }

    private fun record(f: RemoteFile, sha256: String = f.sha256) =
        prefs.edit().putString(KEY + f.dest, dataRecord(sha256, fileOf(f).lastModified())).apply()

    private companion object {
        const val KEY = "data_"
        const val OLD_KEY = "dados_"
        const val REGIONS = "regions"
    }
}
