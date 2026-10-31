package app.rumo.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Comandos do rádio do aparelho, para a tela de rádio do Rumo. Usa o serviço do próprio app de rádio
 * (com.hcn.autoradio, FMPlugService, IRadioServiceAPI), para o app original continuar em dia com a estação, e lê a
 * frequência no serviço de sistema "fmradio". Números de transação lidos no aparelho (docs/integracao-aparelho.md).
 *
 * Nunca grava nas memórias do aparelho: a procura de estações usa a busca (seek), não o "AS" (auto store), que
 * sobrescreve as 6 memórias.
 */
class RadioControl(context: Context) {
    private val ctx = context.applicationContext
    @Volatile private var api: IBinder? = null
    private var connection: ServiceConnection? = null
    private var fm: IBinder? = null

    /** Divisor da frequência que o gotoFreq entende (1 = kHz, 10, 100), descoberto na primeira sintonia. */
    var unit: Int? = null

    val connected: Boolean get() = api?.isBinderAlive == true

    /** Liga no serviço do app de rádio (cria se preciso: a tela de rádio foi aberta de propósito). Thread principal. */
    suspend fun connect(): String {
        if (connected) return "já ligado"
        // Ligação velha com o serviço morto: solta antes de ligar de novo
        disconnect()
        val b = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<IBinder?> { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        api = service
                        if (cont.isActive) cont.resume(service)
                    }

                    // O serviço do rádio morre quando o Android Auto toma o áudio. Com a ligação aberta, o Android o
                    // recriaria e o rádio voltaria brigando com o Android Auto: solta a ligação; a tela de rádio liga de
                    // novo quando for aberta
                    override fun onServiceDisconnected(name: ComponentName) {
                        api = null
                        disconnect()
                    }
                }
                val ok = try {
                    ctx.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    false
                }
                if (ok) connection = conn else cont.resume(null)
            }
        }
        if (b == null) {
            disconnect()
            return "serviço do rádio não respondeu"
        }
        return "ligado ao serviço do rádio"
    }

    fun disconnect() {
        val c = connection
        connection = null
        c?.let {
            try {
                ctx.unbindService(it)
            } catch (e: Exception) {
            }
        }
        api = null
    }

    // ---- comandos (chamar fora da thread principal) ----

    /** Põe o rádio como fonte de áudio. */
    fun playAudio(): String {
        val b = api ?: return "requestPlayAudio: sem serviço"
        return transact(b, API, TX_REQUEST_PLAY_AUDIO, {}) { readInt() != 0 }
            ?.let { "requestPlayAudio() = $it" } ?: "requestPlayAudio: erro"
    }

    // No aparelho os nomes estão trocados: onSeekUpEvent busca para baixo e onSeekDownEvent, para cima
    fun seek(up: Boolean): String =
        if (up) apiCall(TX_SEEK_DOWN, "onSeekDownEvent") {} else apiCall(TX_SEEK_UP, "onSeekUpEvent") {}

    fun gotoFreq(value: Int): String = apiCall(TX_GOTO_FREQ, "gotoFreq($value)") { writeInt(value) }
    fun gotoFreq2(text: String): String = apiCall(TX_GOTO_FREQ2, "gotoFreq2(\"$text\")") { writeString(text) }

    /**
     * Mudo do rádio (fmradio.setMute). É o "parar" do play do card: a tecla play/pausa não chega ao app de rádio,
     * que não tem sessão de mídia.
     */
    fun mute(on: Boolean): String = fm()?.let { b ->
        if (transact(b, FM, TX_FM_SET_MUTE, { writeInt(if (on) 1 else 0) }) { true } == true) "setMute($on) ok" else "setMute($on): erro"
    } ?: "setMute: sem fmradio"

    /** Frequência atual em kHz (fmradio). */
    fun freq(): Int? = fm()?.let { b -> transact(b, FM, TX_FM_GET_FREQ, {}) { readInt() } }?.takeIf { it > 0 }

    fun isSeeking(): Boolean? = api?.let { b -> transact(b, API, TX_IS_SEEK, {}) { readInt() != 0 } }

    fun uiBand(): Int? = fm()?.let { b -> transact(b, FM, TX_FM_GET_UIBAND, {}) { readInt() } }

    /** Memórias do aparelho para a banda (só leitura). */
    fun presets(band: Int): IntArray? = fm()?.let { b -> transact(b, FM, TX_FM_GET_PRESET, { writeInt(band) }) { createIntArray() } }

    // ---- ações compostas (suspensas, fora da thread principal) ----

    /**
     * Sintoniza [khz]. Na primeira vez descobre a unidade do gotoFreq (kHz, dezenas ou centenas de kHz; por último
     * o gotoFreq2 com texto "95.9"), conferindo a frequência no fmradio.
     */
    suspend fun tune(khz: Int, log: (String) -> Unit): Boolean {
        for (d in unit?.let { listOf(it) } ?: listOf(1, 10, 100)) {
            log(gotoFreq(khz / d))
            if (waitFreq(khz)) {
                if (unit == null) log("gotoFreq usa ${UNIT_NAMES[d] ?: "÷$d"}")
                unit = d
                return true
            }
        }
        val text = String.format(java.util.Locale.US, "%.1f", khz / 1000.0)
        log(gotoFreq2(text))
        if (waitFreq(khz)) return true
        log("não sintonizou ${khz} kHz")
        return false
    }

    /** Busca a próxima estação com sinal e espera ela parar; devolve a frequência. */
    suspend fun seekAndWait(up: Boolean, log: (String) -> Unit): Int? {
        val before = freq()
        log(seek(up))
        return waitStable(before)
    }

    /**
     * Procura as estações da faixa: vai para a ponta de baixo e repete a busca para cima até ela dar a volta (voltar
     * para uma frequência menor ou já vista); no fim volta à estação de antes. [onFound] recebe cada uma, em ordem.
     * Para se [cancelled] ficar verdadeiro.
     */
    suspend fun scan(onFound: (Int) -> Unit, cancelled: () -> Boolean, log: (String) -> Unit): List<Int> {
        val start = freq()
        val found = mutableListOf<Int>()
        tune(FM_BOTTOM_KHZ, log)
        for (i in 0 until MAX_SCAN_STEPS) {
            if (cancelled()) break
            val f = seekAndWait(true, log) ?: break
            // Deu a volta na faixa
            if (f in found || (found.isNotEmpty() && f < found.last())) break
            found += f
            onFound(f)
        }
        start?.let { tune(it, log) }
        return found
    }

    private suspend fun waitFreq(khz: Int): Boolean {
        repeat(12) {
            delay(150)
            if (freq() == khz) return true
        }
        return false
    }

    /** Espera a busca terminar: a frequência mudou e ficou parada por um tempo (ou até 10 s). */
    private suspend fun waitStable(before: Int?): Int? {
        var last = freq()
        var stableSince = System.currentTimeMillis()
        val until = System.currentTimeMillis() + 10_000
        delay(400)
        while (System.currentTimeMillis() < until) {
            delay(250)
            val now = freq()
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
                continue
            }
            val seeking = isSeeking() == true
            if (!seeking && now != before && System.currentTimeMillis() - stableSince > 700) return now
            if (!seeking && now == before && System.currentTimeMillis() - stableSince > 2_500) return now
        }
        return last
    }

    // ---- binder ----

    private fun fm(): IBinder? = fm?.takeIf { it.isBinderAlive } ?: Aidl.service("fmradio").also { fm = it }

    private fun apiCall(code: Int, label: String, write: Parcel.() -> Unit): String {
        val b = api ?: return "$label: sem serviço"
        return if (transact(b, API, code, write) { true } == true) "$label ok" else "$label: erro"
    }

    private fun <T> transact(b: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.write()
            b.transact(code, data, reply, 0)
            reply.readException()
            reply.read()
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val PKG = "com.hcn.autoradio"
        const val ACTION = "com.hcn.autoradio.FM_PLUG_SERVICE"
        const val API = "com.hcn.autoradio.IRadioServiceAPI"
        const val FM = "android.radio.IRadioPlayer"

        // IRadioServiceAPI
        const val TX_SEEK_DOWN = 9
        const val TX_SEEK_UP = 10
        const val TX_GOTO_FREQ = 14
        const val TX_GOTO_FREQ2 = 15
        const val TX_IS_SEEK = 22
        const val TX_REQUEST_PLAY_AUDIO = 25

        // android.radio.IRadioPlayer (fmradio)
        const val TX_FM_GET_FREQ = 9
        const val TX_FM_SET_MUTE = 14
        const val TX_FM_GET_PRESET = 15
        const val TX_FM_GET_UIBAND = 18

        const val MAX_SCAN_STEPS = 60
        const val FM_BOTTOM_KHZ = 87_500
        val UNIT_NAMES = mapOf(1 to "kHz", 10 to "dezenas de kHz", 100 to "centenas de kHz")
    }
}
