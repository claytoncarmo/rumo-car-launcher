package app.rumo.core

import org.json.JSONObject

/** De onde o app lê as versões e os dados: releases públicas do repositório. */
object Endpoints {
    private const val BASE = "https://github.com/claytoncarmo/rumo-car-launcher/releases"

    /** `rumo.json` da última release estável (o GitHub redireciona `latest` para a mais recente que não é pré-release). */
    const val STABLE = "$BASE/latest/download/rumo.json"

    /** `rumo.json` da pré-release nightly, substituída a cada push na main. */
    const val NIGHTLY = "$BASE/download/nightly/rumo.json"

    /** `dados.json` dos dados offline (mapa, vias, radares). */
    const val DATA = "$BASE/download/rumo-dados/dados.json"
}

enum class Channel(val label: String, val url: String) {
    STABLE("Estável", Endpoints.STABLE),
    NIGHTLY("Nightly", Endpoints.NIGHTLY),
}

/** Uma versão publicada do app (`rumo.json`, gerado pelo CI ao lado do APK). */
data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
    val size: Long,
    val url: String,
    val builtAt: String,
    val commit: String,
)

fun parseAppRelease(text: String): AppRelease? = try {
    val j = JSONObject(text)
    val release = AppRelease(
        versionName = j.getString("versionName"),
        versionCode = j.getLong("versionCode"),
        sha256 = j.getString("sha256"),
        size = j.getLong("size"),
        url = j.getString("url"),
        builtAt = j.optString("builtAt"),
        commit = j.optString("commit"),
    )
    release.takeIf { it.versionName.isNotBlank() && it.size > 0 && it.sha256.length == 64 && it.url.startsWith("https://") }
} catch (e: Exception) {
    null
}

/** A versão publicada em relação à instalada. O Android não deixa instalar uma versão de `versionCode` menor. */
enum class UpdateKind { NEWER, SAME, OLDER }

fun compareWithInstalled(installedCode: Long, release: AppRelease): UpdateKind = when {
    release.versionCode > installedCode -> UpdateKind.NEWER
    release.versionCode == installedCode -> UpdateKind.SAME
    else -> UpdateKind.OLDER
}

/** Canal da versão instalada pelo nome: `0.5.0-nightly.57` é nightly, `0.0.0-dev` é build local, o resto é estável. */
fun installedChannelLabel(versionName: String): String = when {
    "-nightly" in versionName -> "nightly"
    versionName.endsWith("-dev") -> "build local"
    else -> "estável"
}

/** Um arquivo dos dados offline: destino dentro da pasta Rumo, tamanho, SHA-256 e onde baixar. */
data class RemoteFile(val dest: String, val size: Long, val sha256: String, val url: String)

data class Region(val id: String, val name: String, val files: List<RemoteFile>) {
    val totalSize: Long get() = files.sumOf { it.size }
}

data class DataManifest(val builtAt: String, val regions: List<Region>)

/** O destino vem da rede: nunca pode sair da pasta Rumo. */
fun isSafeDestination(dest: String): Boolean =
    dest.isNotBlank() && !dest.startsWith("/") && '\\' !in dest && dest.none { it < ' ' } &&
        dest.split('/').none { it.isEmpty() || it == "." || it == ".." }

fun parseDataManifest(text: String): DataManifest? = try {
    val j = JSONObject(text)
    val regions = j.getJSONArray("regioes").let { arr ->
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val files = r.getJSONArray("arquivos").let { fa ->
                (0 until fa.length()).map { k ->
                    val f = fa.getJSONObject(k)
                    RemoteFile(f.getString("destino"), f.getLong("tamanho"), f.getString("sha256"), f.getString("url"))
                }
            }
            Region(r.getString("id"), r.getString("nome"), files)
        }
    }
    val valid = regions.isNotEmpty() && regions.all { region ->
        region.files.isNotEmpty() && region.files.all {
            isSafeDestination(it.dest) && it.size > 0 && it.sha256.length == 64 && it.url.startsWith("https://")
        }
    }
    if (valid) DataManifest(j.optString("geradoEm"), regions) else null
} catch (e: Exception) {
    null
}

/** O que há no aparelho sobre um arquivo dos dados: existe, tamanho e o SHA-256 gravado quando o app o baixou. */
data class LocalFile(val exists: Boolean, val size: Long, val recordedSha: String?)

enum class DataStatus { NOT_INSTALLED, UPDATE, UP_TO_DATE }

/**
 * O que baixar para deixar a região em dia. [verified] são arquivos que já estavam certos mas sem registro (copiados
 * à mão): o chamador grava o registro para não conferir de novo.
 */
data class DataPlan(val download: List<RemoteFile>, val verified: List<RemoteFile>, val existing: Int) {
    val bytes: Long get() = download.sumOf { it.size }

    val status: DataStatus
        get() = when {
            download.isEmpty() -> DataStatus.UP_TO_DATE
            existing == 0 -> DataStatus.NOT_INSTALLED
            else -> DataStatus.UPDATE
        }
}

/**
 * Arquivo certo = existe, tem o tamanho do manifesto e o hash gravado (ou, sem registro, o hash calculado em
 * [hashOf], que só é chamado quando o tamanho confere) bate com o do manifesto.
 */
fun planData(region: Region, local: (RemoteFile) -> LocalFile, hashOf: (RemoteFile) -> String): DataPlan {
    val download = mutableListOf<RemoteFile>()
    val verified = mutableListOf<RemoteFile>()
    var existing = 0
    for (f in region.files) {
        val l = local(f)
        if (l.exists) existing++
        when {
            !l.exists || l.size != f.size -> download += f
            l.recordedSha.equals(f.sha256, ignoreCase = true) -> {}
            hashOf(f).equals(f.sha256, ignoreCase = true) -> verified += f
            else -> download += f
        }
    }
    return DataPlan(download, verified, existing)
}
