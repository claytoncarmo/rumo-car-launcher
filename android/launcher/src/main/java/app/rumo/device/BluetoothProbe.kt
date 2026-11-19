package app.rumo.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import app.rumo.core.Elm327
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Teste do Bluetooth: o aparelho esconde o menu de Bluetooth, então vê se o Android ainda deixa um app comum usar o
 * adaptador: lista o que há, procura dispositivos, e com um scanner OBD2 (ELM327) à vista tenta parear, conectar na
 * porta serial (SPP) e fazer perguntas só de leitura (versão, tensão, rotação, temperatura…). Não grava nem apaga nada
 * no carro. Chamar fora da thread principal; leva até uns 2 minutos.
 */
object BluetoothProbe {
    class Report(val summary: List<String>, val full: List<String>)

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val OBD_NAME = Regex("(?i)(obd|elm|v-?link|vgate|icar|konnwei|veepeak|scan|can ?bus)")
    private val PINS = listOf("1234", "0000")
    private const val SEARCH_MS = 20_000L
    private const val BOND_WAIT_MS = 25_000L
    private const val CONNECT_MS = 15_000L

    private class Seen(val name: String?, val address: String, val bonded: Boolean, val rssi: Int?, val cls: Int?)

    fun run(ctx: Context, progress: (String) -> Unit): Report {
        val full = ArrayList<String>()
        val summary = ArrayList<String>()
        fun log(line: String) {
            full += line
        }

        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        val pm = ctx.packageManager
        log("FEATURE_BLUETOOTH: ${pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)}")
        log("FEATURE_BLUETOOTH_LE: ${pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
        for (p in listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)) {
            log("permissão ${p.substringAfterLast('.')}: ${ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED}")
        }

        val adapter: BluetoothAdapter? = try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            log("erro ao pegar o adaptador: ${e.javaClass.simpleName} ${e.message}")
            null
        }
        if (adapter == null) {
            summary += "Sem adaptador Bluetooth para o Rumo (o Android não oferece a apps comuns)."
            return Report(summary, full)
        }
        log("adaptador: estado=${adapter.state} ligado=${adapter.isEnabled} nome=${safe { adapter.name }}")
        if (!adapter.isEnabled) {
            progress("Teste do Bluetooth: ligando o Bluetooth…")
            val ok = try {
                @Suppress("DEPRECATION")
                adapter.enable()
            } catch (e: Exception) {
                log("enable() falhou: ${e.javaClass.simpleName} ${e.message}")
                false
            }
            log("enable() = $ok")
            val until = System.currentTimeMillis() + 8_000
            while (!adapter.isEnabled && System.currentTimeMillis() < until) Thread.sleep(200)
            log("ligado depois de esperar: ${adapter.isEnabled}")
        }
        if (!adapter.isEnabled) {
            summary += "Bluetooth desligado e o Rumo não conseguiu ligar."
            return Report(summary, full)
        }
        summary += "Adaptador Bluetooth disponível e ligado."

        // Pareados
        val seen = LinkedHashMap<String, Seen>()
        try {
            for (d in adapter.bondedDevices.orEmpty()) {
                seen[d.address] = Seen(safe { d.name }, d.address, true, null, safe { d.bluetoothClass?.deviceClass })
                log("pareado: ${safe { d.name }} · ${d.address} · tipo=${d.type} · classe=${safe { d.bluetoothClass?.deviceClass }}")
            }
        } catch (e: Exception) {
            log("lista de pareados falhou: ${e.javaClass.simpleName} ${e.message}")
        }
        log("pareados: ${seen.size}")

        // Procura
        progress("Teste do Bluetooth: procurando dispositivos (20 s)…")
        val found = discover(ctx, adapter, ::log)
        for (f in found) if (f.address !in seen) seen[f.address] = f
        log("achados na procura: ${found.size}")
        for (f in found) log("achado: ${f.name} · ${f.address} · sinal=${f.rssi} · classe=${f.cls}")
        summary += "Procura: ${found.size} dispositivo(s) à vista, ${seen.count { it.value.bonded }} pareado(s)."
        if (found.isNotEmpty()) summary += "À vista: " + found.joinToString(", ") { it.name ?: "(sem nome)" }.take(120)

        // Scanner OBD2
        val target = seen.values.firstOrNull { it.name != null && OBD_NAME.containsMatchIn(it.name) }
        if (target == null) {
            summary += "Nenhum dispositivo com nome de scanner OBD2 (OBD, ELM, V-LINK…). Ligue o scanner e repita."
            return Report(summary, full)
        }
        summary += "Scanner à vista: ${target.name}."
        progress("Teste do Bluetooth: conectando em ${target.name}…")
        val device = adapter.getRemoteDevice(target.address)
        val socket = connect(ctx, adapter, device, ::log)
        if (socket == null) {
            summary += "Não conseguiu conectar em ${target.name} (veja o relatório)."
            return Report(summary, full)
        }
        summary += "Conectado em ${target.name} pela porta serial."
        try {
            talk(socket, ::log, summary)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
        return Report(summary, full)
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private fun discover(ctx: Context, adapter: BluetoothAdapter, log: (String) -> Unit): List<Seen> {
        // O aviso chega na thread principal; esta função espera em outra
        val found = java.util.Collections.synchronizedMap(LinkedHashMap<String, Seen>())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                        found[d.address] = Seen(safe { d.name }, d.address, d.bondState == BluetoothDevice.BOND_BONDED, rssi, safe { d.bluetoothClass?.deviceClass })
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> done.set(true)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ctx.registerReceiver(receiver, filter)
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            val started = adapter.startDiscovery()
            log("startDiscovery() = $started")
            val until = System.currentTimeMillis() + SEARCH_MS
            while (!done.get() && System.currentTimeMillis() < until) Thread.sleep(250)
            log("procura terminou sozinha: ${done.get()}")
        } catch (e: Exception) {
            log("procura falhou: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try {
                adapter.cancelDiscovery()
            } catch (e: Exception) {
            }
            try {
                ctx.unregisterReceiver(receiver)
            } catch (e: Exception) {
            }
        }
        return synchronized(found) { found.values.toList() }
    }

    /** Pareia (se preciso, testando os PINs comuns) e abre a porta serial; null se nada funcionar. */
    private fun connect(ctx: Context, adapter: BluetoothAdapter, device: BluetoothDevice, log: (String) -> Unit): BluetoothSocket? {
        log("pareamento atual: ${device.bondState} (12 = pareado)")
        // 1) Direto, sem parear (muitos scanners aceitam)
        openSocket(adapter, device, log)?.let { return it }
        // 2) Pareando
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            for (pin in PINS) {
                if (bond(ctx, device, pin, log)) {
                    openSocket(adapter, device, log)?.let { return it }
                    break
                }
            }
        }
        return null
    }

    private fun openSocket(adapter: BluetoothAdapter, device: BluetoothDevice, log: (String) -> Unit): BluetoothSocket? {
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "seguro (SPP)" to { device.createRfcommSocketToServiceRecord(SPP) },
            "inseguro (SPP)" to { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            // Truque conhecido dos clones ELM327: canal 1 direto, por reflexão
            "canal 1 (reflexão)" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
            },
        )
        for ((label, make) in attempts) {
            try {
                adapter.cancelDiscovery()
                val socket = make()
                // connect() bloqueia até uns 12 s: um vigia fecha o socket se passar do prazo
                val watchdog = Thread {
                    try {
                        Thread.sleep(CONNECT_MS)
                        socket.close()
                    } catch (e: InterruptedException) {
                    } catch (e: Exception) {
                    }
                }.apply { isDaemon = true; start() }
                try {
                    socket.connect()
                } finally {
                    watchdog.interrupt()
                }
                log("conexão $label: ok")
                return socket
            } catch (e: Exception) {
                log("conexão $label: ${e.javaClass.simpleName} ${e.cause?.message ?: e.message ?: ""}")
            }
        }
        return null
    }

    private fun bond(ctx: Context, device: BluetoothDevice, pin: String, log: (String) -> Unit): Boolean {
        val state = java.util.concurrent.atomic.AtomicInteger(device.bondState)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.address != device.address) return
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        state.set(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, state.get()))
                        log("pareamento mudou para ${state.get()}")
                    }
                    "android.bluetooth.device.action.PAIRING_REQUEST" -> {
                        val variant = intent.getIntExtra("android.bluetooth.device.extra.PAIRING_VARIANT", -1)
                        log("pedido de pareamento (variante $variant): tentando o PIN $pin")
                        try {
                            log("setPin = ${d.setPin(pin.toByteArray())}")
                        } catch (e: Exception) {
                            log("setPin falhou: ${e.javaClass.simpleName} ${e.message}")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction("android.bluetooth.device.action.PAIRING_REQUEST")
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        ctx.registerReceiver(receiver, filter)
        try {
            val started = device.createBond()
            log("createBond() com o PIN $pin = $started")
            val until = System.currentTimeMillis() + BOND_WAIT_MS
            while (state.get() == BluetoothDevice.BOND_NONE || state.get() == BluetoothDevice.BOND_BONDING) {
                if (System.currentTimeMillis() > until || (!started && state.get() == BluetoothDevice.BOND_NONE)) break
                Thread.sleep(250)
            }
            return state.get() == BluetoothDevice.BOND_BONDED
        } catch (e: Exception) {
            log("pareamento falhou: ${e.javaClass.simpleName} ${e.message}")
            return false
        } finally {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (e: Exception) {
            }
        }
    }

    /** Pergunta ao ELM327 só coisas de leitura e anota as respostas. */
    private fun talk(socket: BluetoothSocket, log: (String) -> Unit, summary: MutableList<String>) {
        val input = socket.inputStream
        val output = socket.outputStream
        fun ask(cmd: String, timeoutMs: Long = 4_000): String {
            val reply = send(input, output, cmd, timeoutMs)
            log("» $cmd\n« ${reply.replace('\r', ' ').trim()}")
            return reply
        }
        val reset = ask("ATZ", 5_000)
        if (reset.isBlank()) {
            summary += "O scanner conectou mas não respondeu a ATZ (talvez não seja ELM327)."
            return
        }
        summary += "Scanner: ${reset.replace(Regex("[\\r>]"), " ").trim().takeLast(40)}"
        ask("ATE0")
        ask("ATL0")
        ask("ATS0")
        val volt = Elm327.voltage(ask("ATRV"))
        summary += "Tensão lida pelo scanner: ${volt ?: "?"} V"
        ask("ATDPN")
        val support = ask("0100", 10_000)
        if (Elm327.carSilent(support) || Elm327.payload(support, 1, 0x00) == null) {
            summary += "O carro não respondeu (ignição desligada, ou protocolo não achado): ${support.replace(Regex("[\\r>]"), " ").trim().take(60)}"
            return
        }
        val pids = ArrayList<Int>()
        Elm327.payload(support, 1, 0x00)?.let { pids += Elm327.supportedPids(it, 0x00) }
        for (base in listOf(0x20, 0x40)) {
            if (base !in pids) break
            val r = ask("01%02X".format(base))
            Elm327.payload(r, 1, base)?.let { pids += Elm327.supportedPids(it, base) }
        }
        log("PIDs do modo 01 que o carro informa: ${pids.joinToString(" ") { "%02X".format(it) }}")
        summary += "O carro informa ${pids.size} PIDs de dados ao vivo."
        fun read(pid: Int): List<Int>? = if (pid in pids) Elm327.payload(ask("01%02X".format(pid)), 1, pid) else null
        read(0x0C)?.let { Elm327.rpm(it)?.let { v -> summary += "Rotação: $v rpm" } }
        read(0x0D)?.let { Elm327.speedKmh(it)?.let { v -> summary += "Velocidade: $v km/h" } }
        read(0x05)?.let { Elm327.coolantC(it)?.let { v -> summary += "Temperatura do motor: $v °C" } }
        read(0x2F)?.let { Elm327.fuelPercent(it)?.let { v -> summary += "Tanque: $v %" } }
        read(0x04)?.let { Elm327.loadPercent(it)?.let { v -> summary += "Carga do motor: $v %" } }
        // O que o carro tem de PIDs para consumo (ajuda a decidir como estimar)
        log("consumo: MAF(10)=${0x10 in pids} MAP(0B)=${0x0B in pids} taxa de combustível(5E)=${0x5E in pids} temp. admissão(0F)=${0x0F in pids}")
        // Códigos de falha guardados (só leitura)
        val dtc = ask("03", 6_000)
        log("códigos de falha (modo 03): ${dtc.replace(Regex("[\\r>]"), " ").trim()}")
    }

    /** Manda [cmd] e lê até o ">" do ELM327 (ou o prazo). */
    private fun send(input: InputStream, output: OutputStream, cmd: String, timeoutMs: Long): String {
        try {
            while (input.available() > 0) input.read()
            output.write((cmd + "\r").toByteArray())
            output.flush()
        } catch (e: Exception) {
            return ""
        }
        val sb = StringBuilder()
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            try {
                val n = input.available()
                if (n > 0) {
                    val buf = ByteArray(n)
                    val read = input.read(buf)
                    if (read > 0) sb.append(String(buf, 0, read))
                    if (sb.contains('>')) break
                } else {
                    Thread.sleep(40)
                }
            } catch (e: Exception) {
                break
            }
        }
        return sb.toString()
    }
}
