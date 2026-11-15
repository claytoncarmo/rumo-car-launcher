package app.rumo.shared

/**
 * Conversa entre o Rumo e o app do rádio online (`app.rumo.webradio`), por binder puro: o Rumo liga no serviço e manda
 * comandos; o serviço avisa as mudanças a um ouvinte do Rumo. Os dois lados só aceitam chamadas de um app assinado com
 * a mesma chave. Este arquivo entra nos dois módulos (`android/shared`).
 *
 * O player fica num app à parte porque o firmware encerra o pacote que tocou mídia quando outra fonte (rádio ou
 * Android Auto) pega o som: assim cai o app do rádio online, não a tela inicial (ver docs/device-integration.md).
 */
object WebRadioProtocol {
    const val PACKAGE = "app.rumo.webradio"
    const val SERVICE = "app.rumo.webradio.WebRadioService"

    /** Muda se os comandos mudarem de um jeito que um lado antigo não entende. */
    const val VERSION = 1

    const val DESCRIPTOR = "app.rumo.webradio.Control"
    const val EVENTS_DESCRIPTOR = "app.rumo.webradio.Events"

    // Comandos (ao serviço): todos só de ida (FLAG_ONEWAY)
    /** IBinder do ouvinte, que recebe [TX_EVENT]. */
    const val TX_LISTEN = 1
    /** id, endereço, nome da estação, hls (1/0). */
    const val TX_PLAY = 2
    const val TX_STOP = 3

    // Aviso (ao ouvinte)
    /** id da estação (ou vazio), estado ([STATUS_OFF]…), título que o stream manda (ou vazio). */
    const val TX_EVENT = 1

    const val STATUS_OFF = "OFF"
    const val STATUS_CONNECTING = "CONNECTING"
    const val STATUS_LIVE = "LIVE"
    const val STATUS_ERROR = "ERROR"
}
