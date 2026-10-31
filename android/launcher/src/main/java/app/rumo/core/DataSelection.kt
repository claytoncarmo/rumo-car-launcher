package app.rumo.core

/** Um estado do manifesto e o que falta baixar dele no aparelho. */
data class RegionInfo(val region: Region, val plan: DataPlan) {
    val installed: Boolean get() = plan.status != DataStatus.NOT_INSTALLED
}

/** O que fazer para o aparelho ficar como a escolha do usuário: arquivos a baixar e estados a remover. */
class DataChange(val download: List<RemoteFile>, val remove: List<Region>) {
    val bytes: Long get() = download.sumOf { it.size }
    val isEmpty: Boolean get() = download.isEmpty() && remove.isEmpty()
}

/**
 * Estados escolhidos ([wanted]): baixa os que faltam ou estão desatualizados (mais os arquivos comuns, as fontes, se
 * faltarem) e remove os instalados que foram desmarcados. Sem nenhum estado escolhido não baixa nem as fontes.
 */
fun planChange(regions: List<RegionInfo>, common: DataPlan?, wanted: Set<String>): DataChange {
    val files = regions.filter { it.region.id in wanted }.flatMap { it.plan.download }
    val withCommon = if (wanted.isNotEmpty() && common != null) common.download + files else files
    val remove = regions.filter { it.installed && it.region.id !in wanted }.map { it.region }
    return DataChange(withCommon.distinctBy { it.dest }, remove)
}

/** Os estados já instalados: a escolha inicial da lista. */
fun installedIds(regions: List<RegionInfo>): Set<String> = regions.filter { it.installed }.map { it.region.id }.toSet()
