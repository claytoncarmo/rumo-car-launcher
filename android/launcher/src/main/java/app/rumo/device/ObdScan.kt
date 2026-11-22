package app.rumo.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * Procura de scanners OBD2 por perto, para escolher um (só com o carro parado: a procura pesa no Bluetooth do aparelho,
 * que também faz as chamadas). Tudo na thread principal.
 */
object ObdScan {
    private val OBD_NAME = Regex("(?i)(obd|elm|v-?link|vgate|icar|konnwei|veepeak|scan|can ?bus)")

    /** O nome parece de um scanner OBD2 (a lista mostra esses primeiro). */
    fun looksLikeScanner(name: String?): Boolean = name != null && OBD_NAME.containsMatchIn(name)

    /** Já pareados (o celular do dono, por exemplo, e scanners pareados antes). */
    fun bonded(): List<ObdDevice> = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().map { ObdDevice(it.name, it.address) }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Procura por até [ms]: [onFound] a cada dispositivo novo e [onDone] ao fim (também ao cancelar). Devolve a função
     * que cancela. Sem adaptador ou desligado, termina logo.
     */
    fun start(ctx: Context, ms: Long, onFound: (ObdDevice) -> Unit, onDone: () -> Unit): () -> Unit {
        val app = ctx.applicationContext
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val handler = Handler(Looper.getMainLooper())
        if (adapter == null || !adapter.isEnabled) {
            handler.post(onDone)
            return {}
        }
        val seen = HashSet<String>()
        var finished = false
        lateinit var receiver: BroadcastReceiver
        fun finish() {
            if (finished) return
            finished = true
            try {
                adapter.cancelDiscovery()
            } catch (e: Exception) {
            }
            try {
                app.unregisterReceiver(receiver)
            } catch (e: Exception) {
            }
            onDone()
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (seen.add(d.address)) onFound(ObdDevice(try { d.name } catch (e: Exception) { null }, d.address))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finish()
                }
            }
        }
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (!adapter.startDiscovery()) {
                handler.post { finish() }
                return { finish() }
            }
        } catch (e: Exception) {
            handler.post { finish() }
            return { finish() }
        }
        handler.postDelayed({ finish() }, ms)
        return { finish() }
    }
}
