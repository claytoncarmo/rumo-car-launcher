package app.rumo.core

/** Quem toca agora, pelo que o serviço sourceinfo do multimídia informa. */
enum class AudioOwner { ZLINK, RADIO, OTHER }

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
