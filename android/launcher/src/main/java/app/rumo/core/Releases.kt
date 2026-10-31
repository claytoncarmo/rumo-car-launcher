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

/** Um estado: [group] é a região do país ("Sul", "Sudeste") para agrupar na lista; pode vir vazio. */
data class Region(val id: String, val name: String, val files: List<RemoteFile>, val group: String = "") {
    val totalSize: Long get() = files.sumOf { it.size }
}

/** [common]: arquivos de qualquer estado (as fontes dos nomes de rua). */
data class DataManifest(val builtAt: String, val regions: List<Region>, val common: List<RemoteFile> = emptyList())

/** O id vira nome de pasta (`regioes/<id>`) e vem da rede: só letras minúsculas, números e sublinhado. */
fun isSafeRegionId(id: String): Boolean = Regex("[a-z0-9_]{1,16}").matches(id)

/** O destino vem da rede: nunca pode sair da pasta Rumo. */
fun isSafeDestination(dest: String): Boolean =
    dest.isNotBlank() && !dest.startsWith("/") && '\\' !in dest && dest.none { it < ' ' } &&
        dest.split('/').none { it.isEmpty() || it == "." || it == ".." }

private fun parseFiles(arr: org.json.JSONArray?): List<RemoteFile> =
    if (arr == null) emptyList() else (0 until arr.length()).map { k ->
        val f = arr.getJSONObject(k)
        RemoteFile(f.getString("destino"), f.getLong("tamanho"), f.getString("sha256"), f.getString("url"))
    }

private fun isValidFile(f: RemoteFile) =
    isSafeDestination(f.dest) && f.size > 0 && f.sha256.length == 64 && f.url.startsWith("https://")

fun parseDataManifest(text: String): DataManifest? = try {
    val j = JSONObject(text)
    val regions = j.getJSONArray("regioes").let { arr ->
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Region(r.getString("id"), r.getString("nome"), parseFiles(r.getJSONArray("arquivos")), r.optString("grupo"))
        }
    }
    val common = parseFiles(j.optJSONArray("comum"))
    val valid = regions.isNotEmpty() && regions.map { it.id }.distinct().size == regions.size &&
        common.all(::isValidFile) &&
        regions.all { region ->
            isSafeRegionId(region.id) && region.files.isNotEmpty() && region.files.all(::isValidFile)
        }
    if (valid) DataManifest(j.optString("geradoEm"), regions, common) else null
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
