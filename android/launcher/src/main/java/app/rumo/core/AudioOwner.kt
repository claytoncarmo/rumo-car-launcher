package app.rumo.core

/** Quem toca agora, pelo que o serviço sourceinfo do multimídia informa. */
enum class AudioOwner { ZLINK, RADIO, OTHER }

/**
 * O play do card com o rádio como fonte: liga (true) ou para (false). Só para se o Rumo sabe que o rádio toca: parado,
 * com o sourceinfo dizendo que ele não toca, ou sem o Rumo ligado no serviço do rádio (o serviço existe enquanto o Rumo
 * está ligado nele, então depois de atualizar o app ou do Android encerrar o processo o rádio fica mudo mesmo com o
 * card dizendo que toca), o play liga o rádio em vez de pará-lo.
 */
fun radioPlayStarts(muted: Boolean, lastRawOwner: String?, serviceConnected: Boolean): Boolean =
    muted || lastRawOwner == "" || !serviceConnected

/**
 * Ao abrir o Rumo com o rádio como fonte: religar o serviço do rádio, que morre junto com o processo do Rumo e deixa
 * o rádio mudo. Não religa com o mudo do Rumo (o dono parou o rádio) nem com o serviço já ligado.
 */
fun shouldResumeRadio(radioIsSource: Boolean, muted: Boolean, serviceConnected: Boolean): Boolean =
    radioIsSource && !muted && !serviceConnected

/**
 * Pacote (onSourceChanged) ou classe (MusicInfo.className) → fonte. Vazio quer dizer "sem novidade" (veio vazio
 * ao mexer no sintonizador, com o rádio ainda tocando): null, e a tela mantém a fonte de antes.
 */
fun audioOwnerOf(name: String?): AudioOwner? {
    val n = name?.trim()?.lowercase() ?: return null
    return when {
        n.isEmpty() -> null
        "zlink" in n -> AudioOwner.ZLINK
        "radio" in n -> AudioOwner.RADIO
        else -> AudioOwner.OTHER
    }
}
