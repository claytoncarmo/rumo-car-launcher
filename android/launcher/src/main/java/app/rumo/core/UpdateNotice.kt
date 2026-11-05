package app.rumo.core

/** De quanto em quanto tempo o Rumo, na tela, consulta se há versão nova. */
const val UPDATE_CHECK_EVERY_MS = 4L * 60 * 60 * 1000

/** Depois de uma consulta que falhou (sem rede, GitHub fora), tenta de novo em meia hora. */
const val UPDATE_RETRY_MS = 30L * 60 * 1000

/**
 * O canal que vale para o aviso: quem usa a estável só é avisado de versão estável nova e quem usa a nightly, de
 * nightly nova. Build local (`0.0.0-dev`) não é avisado.
 */
fun noticeChannel(versionName: String): Channel? = when {
    "-nightly" in versionName -> Channel.NIGHTLY
    versionName.endsWith("-dev") -> null
    else -> Channel.STABLE
}

/**
 * Quanto falta (ms) para a próxima consulta, dada a hora da última que deu certo. Sem consulta anterior, ou com o
 * relógio do aparelho atrás dela (o multimídia pode acordar com a hora errada), é agora.
 */
fun msUntilUpdateCheck(lastCheckedAt: Long, now: Long): Long {
    if (lastCheckedAt <= 0 || lastCheckedAt > now) return 0
    return (lastCheckedAt + UPDATE_CHECK_EVERY_MS - now).coerceAtLeast(0)
}

/** A versão publicada só merece aviso se for mais nova que a instalada. */
fun newerThanInstalled(installedCode: Long, release: AppRelease?): AppRelease? =
    release?.takeIf { compareWithInstalled(installedCode, it) == UpdateKind.NEWER }
