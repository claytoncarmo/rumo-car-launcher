package app.rumo.device

import android.os.IBinder
import app.rumo.core.RadioNames

/** Estação do rádio: frequência em kHz, memória (0 a 5; null na sintonia manual) e texto do RDS, se houver. */
data class RadioNow(val khz: Int, val preset: Int?, val text: String?)

/**
 * Estação do rádio pelo serviço de sistema "fmradio" (android.radio.IRadioPlayer).
 *
 * - Frequência e memória: getRadioInfo(), perguntado de tempos em tempos (mFreq em kHz, mIndexof = memória ou -1).
 * - Texto RDS (nomes de cidade e slogans que a emissora manda: "Penha", "R.Cedros"): chega pelo ouvinte
 *   IRadioCallback, onStringEvent(38, texto), 1 a 2 s depois de a estação firmar. Sem ouvinte o texto não aparece
 *   nem no mRTtype, então o ouvinte fica ligado com o Rumo na tela, só para ler.
 *
 * Logo após a troca o mRTtype ainda é o da estação anterior: pelo getRadioInfo o texto só vale quando muda depois da
 * troca. Os textos vão para [names], que guarda por frequência (todas as estações) por algumas horas.
 */
class RadioStation(private val names: RadioNames) {
    private var binder: IBinder? = null
    private var lastKhz: Int? = null
    private var staleText: String? = null
    private var text: String? = null

    /** Algum nome novo chegou desde a última vez que alguém guardou (o HomeActivity grava nas preferências). */
    @Volatile var namesChanged = false

    private val tap = AidlTap(CALLBACK) { method, args ->
        when (method) {
            // Troca de estação avisada na hora: o texto que vier depois já é da nova
            "onEvent" -> args.getOrNull(1)?.let { info ->
                (field(info, "mFreq") as? Int)?.let { onFreq(it, (field(info, "mRTtype") as? String)?.trim().orEmpty()) }
            }
            "onStringEvent" -> if (args.getOrNull(0) == RDS_TEXT) (args.getOrNull(1) as? String)?.let(::onText)
            else -> {}
        }
    }
    private var listening = false

    /** Liga o ouvinte (só leitura). Chamada ao sistema: fora da thread principal. */
    fun start() {
        val b = service() ?: return
        if (!listening) listening = Aidl.callWithBinder(b, IFACE, "setcallback", tap).endsWith("ok")
    }

    fun stop() {
        val b = binder ?: return
        if (listening) Aidl.callWithBinder(b, IFACE, "removecallback", tap)
        listening = false
    }

    /** Estação atual; null sem o serviço. Chamada ao sistema: fora da thread principal. */
    @Synchronized
    fun read(): RadioNow? {
        val b = service() ?: return null
        val info = Aidl.call(b, IFACE, "getRadioInfo") ?: return null
        val khz = (field(info, "mFreq") as? Int)?.takeIf { it > 0 } ?: return null
        val preset = (field(info, "mIndexof") as? Int)?.takeIf { it in 0..5 }
        val rt = (field(info, "mRTtype") as? String)?.trim().orEmpty()
        if (khz != lastKhz) {
            onFreq(khz, rt)
        } else if (rt.isNotEmpty() && rt != staleText) {
            onText(rt)
            staleText = null
        }
        return RadioNow(khz, preset, text)
    }

    /** Estação nova; [staleRt] é o texto que ainda está lá, da estação anterior. */
    @Synchronized
    private fun onFreq(khz: Int, staleRt: String) {
        if (khz == lastKhz) return
        lastKhz = khz
        staleText = staleRt
        text = names.get(khz, System.currentTimeMillis())
    }

    @Synchronized
    private fun onText(raw: String) {
        val t = raw.trim()
        val khz = lastKhz ?: return
        // Vazio só quer dizer "sem texto agora": fica o último
        if (t.isEmpty()) return
        text = t
        if (names.put(khz, t, System.currentTimeMillis())) namesChanged = true
    }

    private fun service(): IBinder? =
        binder?.takeIf { it.isBinderAlive } ?: Aidl.service(SERVICE)?.also { binder = it; listening = false }

    private companion object {
        const val SERVICE = "fmradio"
        const val IFACE = "android.radio.IRadioPlayer"
        const val CALLBACK = "android.radio.IRadioCallback"
        const val RDS_TEXT = 38

        fun field(obj: Any, name: String): Any? = try {
            obj.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(obj) }
        } catch (e: Throwable) {
            null
        }
    }
}
