package app.rumo.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.rumo.core.Elm327
import app.rumo.core.FuelEstimate
import app.rumo.core.FuelTrip
import app.rumo.core.ObdPids
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * PIDs lidos em toda rodada: rotação, velocidade, fluxo de ar e taxa de combustível (consumo), pedal do acelerador e
 * carga do motor (jeito de dirigir). Sem o pedal, a borboleta (PID 11) no lugar.
 */
private val EVERY_CYCLE = listOf(0x0C, 0x0D, 0x10, 0x5E, 0x49, 0x04)

/** Scanner OBD2 Bluetooth: nome (como o scanner se anuncia) e endereço. */
data class ObdDevice(val name: String?, val address: String)

enum class ObdStatus {
    /** Sem scanner escolhido, ou parado (Rumo fora da tela). */
    OFF,
    /** Procurando e conectando no scanner. */
    CONNECTING,
    /** Conectado e o carro responde. */
    LIVE,
    /** Conectado no scanner, mas o carro não responde (ignição desligada). */
    CAR_OFF,
    /** Não deu para conectar (scanner desligado ou fora de alcance, Bluetooth desligado). */
    ERROR,
}

/** Tudo o que a tela mostra do carro, de uma vez (imutável: vem da thread do scanner para a principal). */
class ObdSnapshot(
    val status: ObdStatus = ObdStatus.OFF,
    val message: String? = null,
    val voltage: Double? = null,
    /** Bytes de dados da última resposta de cada PID do modo 01. */
    val values: Map<Int, List<Int>> = emptyMap(),
    /** PIDs do modo 01 que o carro diz ter. */
    val supported: List<Int> = emptyList(),
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    /** Códigos de falha guardados; null enquanto não foram lidos. */
    val dtcs: List<String>? = null,
    val litersPerHour: Double? = null,
    val kmPerLiter: Double? = null,
    val tripKmPerLiter: Double? = null,
    val tripLiters: Double = 0.0,
    val tripKm: Double = 0.0,
) {
    fun number(pid: Int): Double? = values[pid]?.let { ObdPids.value(pid, it) }
}

/**
 * Ligação com o scanner OBD2 (ELM327) pelo Bluetooth: com o Rumo na tela, liga no scanner escolhido (sem procurar, só
 * conectando no endereço guardado), pergunta ao carro só coisas de leitura e entrega um [ObdSnapshot] a cada rodada.
 * Cai e liga de novo sozinha. Uma thread só; [onSnapshot] chega na thread principal. [detail] verdadeiro (a folha do
 * carro aberta): pergunta tudo o que o carro tem; falso: só o que o cartão mostra.
 */
class ObdLink(
    context: Context,
    private val detail: () -> Boolean,
    /** Aba Motor na tela: rodadas seguidas (até ~5 por segundo), para os mostradores andarem junto com o carro. */
    private val fast: () -> Boolean = { false },
    private val onSnapshot: (ObdSnapshot) -> Unit,
    /** Eventos da ligação (conectou, caiu, carro parou de responder…) para o registro; chega em qualquer thread. */
    private val onEvent: (String) -> Unit = {},
) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null

    /** Ajuste do consumo (1,0 = estimativa pura); muda com a calibração. */
    @Volatile var fuelFactor = 1.0

    private val trip = FuelTrip()
    private var snapshot = ObdSnapshot()

    val running: Boolean get() = thread != null

    fun start(address: String) {
        stop()
        val t = Thread(
            {
                val me = Thread.currentThread()
                try {
                    run(address, me)
                } catch (e: Exception) {
                    // Sem permissão do Bluetooth (Android 12+) ou outro erro: o cartão mostra, o resto do Rumo segue
                    if (thread === me) post(ObdSnapshot(status = ObdStatus.ERROR, message = "Bluetooth indisponível: ${e.javaClass.simpleName}"))
                }
            },
            "rumo-obd",
        )
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        val t = thread
        thread = null
        t?.interrupt()
        closeSocket()
        if (t != null) post(ObdSnapshot(status = ObdStatus.OFF, tripKmPerLiter = trip.kmPerLiter(), tripLiters = trip.liters, tripKm = trip.km))
    }

    fun resetTrip() {
        synchronized(trip) { trip.reset() }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    private fun post(s: ObdSnapshot) {
        snapshot = s
        main.post { onSnapshot(s) }
    }

    private fun alive(me: Thread) = thread === me && !me.isInterrupted

    private fun nap(ms: Long, me: Thread): Boolean {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            return false
        }
        return alive(me)
    }

    private fun run(address: String, me: Thread) {
        var backoff = 3_000L
        var failures = 0
        while (alive(me)) {
            post(snapshot.copyWith(ObdStatus.CONNECTING, "Conectando no scanner…"))
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                post(snapshot.copyWith(ObdStatus.ERROR, "Este aparelho não tem Bluetooth para o Rumo"))
                return
            }
            if (!adapter.isEnabled) {
                try {
                    @Suppress("DEPRECATION")
                    adapter.enable()
                } catch (e: Exception) {
                }
                if (!nap(4_000, me)) return
            }
            val sock = if (adapter.isEnabled) open(adapter, adapter.getRemoteDevice(address), me) else null
            if (sock == null) {
                if (!alive(me)) return
                // Só o começo de cada série de falhas (e a cada 10ª): o scanner desligado tentaria sem parar
                if (failures++ % 10 == 0) onEvent("scanner: não conectou (${if (adapter.isEnabled) "não encontrado" else "Bluetooth desligado"}), tentativa ${failures}")
                post(snapshot.copyWith(ObdStatus.ERROR, if (adapter.isEnabled) "Scanner não encontrado: ligado na tomada do carro?" else "Bluetooth desligado"))
                if (!nap(backoff, me)) return
                backoff = minOf(backoff * 2, 20_000L)
                continue
            }
            backoff = 3_000L
            failures = 0
            socket = sock
            onEvent("scanner: conectado")
            try {
                session(sock, me)
                if (alive(me)) onEvent("scanner: sem resposta, enlace encerrado")
            } catch (e: Exception) {
                // O enlace caiu: liga de novo
                if (alive(me)) onEvent("scanner: enlace caiu (${e.javaClass.simpleName})")
            } finally {
                closeSocket()
            }
            if (!alive(me)) return
            post(snapshot.copyWith(ObdStatus.CONNECTING, "Reconectando no scanner…"))
            if (!nap(2_000, me)) return
        }
    }

    private fun ObdSnapshot.copyWith(status: ObdStatus, message: String?) = ObdSnapshot(
        status = status, message = message, voltage = voltage, values = if (status == ObdStatus.LIVE) values else emptyMap(),
        supported = supported, milOn = milOn, dtcCount = dtcCount, dtcs = dtcs,
        tripKmPerLiter = trip.kmPerLiter(), tripLiters = trip.liters, tripKm = trip.km,
    )

    /** O que fazer com cada rodada de perguntas e quando repetir (em rodadas). */
    private fun session(sock: BluetoothSocket, me: Thread) {
        val input = sock.inputStream
        val output = sock.outputStream
        if (send(input, output, "ATZ", 5_000).isBlank()) return
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0")) send(input, output, cmd, 2_000)

        var supported = emptyList<Int>()
        var voltage = Elm327.voltage(send(input, output, "ATRV", 2_000))
        val values = HashMap<Int, List<Int>>()
        var milOn: Boolean? = null
        var dtcCount: Int? = null
        var dtcs: List<String>? = null
        var lastVoltageAt = System.currentTimeMillis()
        var lastDtcAt = 0L
        var lastTick = System.currentTimeMillis()
        var rotate = 0
        var dead = 0
        var carSilent = false
        // Truques do ELM327, testados uma vez por conexão: "1" no fim do pedido (responde na primeira resposta, sem
        // esperar o prazo) e vários PIDs num pedido só (CAN; em grupos cuja resposta cabe num quadro). Falhando seguido,
        // desliga e segue do jeito simples.
        var probed = false
        var oneReply = false
        var grouped = false
        var oneReplyMiss = 0
        var groupedMiss = 0
        var lastCoolantAt = 0L
        var lastTankAt = 0L
        var lastSlowAt = 0L

        fun publish(status: ObdStatus, message: String?) {
            val mafPid = values[0x10]?.let { ObdPids.value(0x10, it) }
            val fuelRate = values[0x5E]?.let { ObdPids.value(0x5E, it) }
            val speed = values[0x0D]?.let { ObdPids.value(0x0D, it) }
            val lph = fuelRate ?: mafPid?.let { FuelEstimate.litersPerHour(it, fuelFactor) }
            val kmPerL = if (lph != null && speed != null) FuelEstimate.kmPerLiter(speed, lph) else null
            post(
                ObdSnapshot(
                    status = status, message = message, voltage = voltage, values = HashMap(values), supported = supported,
                    milOn = milOn, dtcCount = dtcCount, dtcs = dtcs, litersPerHour = lph, kmPerLiter = kmPerL,
                    tripKmPerLiter = trip.kmPerLiter(), tripLiters = trip.liters, tripKm = trip.km,
                ),
            )
        }

        while (alive(me)) {
            val now = System.currentTimeMillis()
            // Tensão da bateria: a cada 5 s (e serve de prova de vida do scanner)
            if (now - lastVoltageAt > 5_000) {
                lastVoltageAt = now
                val r = send(input, output, "ATRV", 2_000)
                if (r.isBlank()) {
                    onEvent("scanner: sem resposta à tensão ($dead de 3 já perdidas)")
                    if (++dead >= 3) return
                } else {
                    dead = 0
                    Elm327.voltage(r)?.let { voltage = it }
                }
            }

            // Ainda não sei o que o carro tem: pergunta (e espera a ignição, se o carro não responder)
            if (supported.isEmpty()) {
                val r = send(input, output, "0100", 10_000)
                val p = Elm327.payload(r, 1, 0x00)
                if (p == null) {
                    if (r.isBlank() && ++dead >= 3) return
                    if (!carSilent) {
                        carSilent = true
                        onEvent("carro: não responde (ignição desligada?), tensão ${voltage?.let { String.format("%.1f", it) } ?: "--"} V")
                    }
                    values.clear()
                    publish(ObdStatus.CAR_OFF, "Scanner conectado. Ligue a ignição para ler o carro.")
                    if (!nap(3_000, me)) return
                    continue
                }
                dead = 0
                if (carSilent) {
                    carSilent = false
                    onEvent("carro: voltou a responder, tensão ${voltage?.let { String.format("%.1f", it) } ?: "--"} V")
                }
                val pids = ArrayList<Int>(Elm327.supportedPids(p, 0x00))
                for (base in listOf(0x20, 0x40, 0x60)) {
                    if (base !in pids) break
                    Elm327.payload(send(input, output, "01%02X".format(base), 4_000), 1, base)?.let { pids += Elm327.supportedPids(it, base) }
                }
                supported = pids.filter { it % 0x20 != 0 }
                if (0x01 in supported) {
                    Elm327.payload(send(input, output, "0101", 3_000), 1, 0x01)?.let { ObdPids.monitor(it)?.let { m -> milOn = m.milOn; dtcCount = m.dtcCount } }
                }
            }

            if (!probed) {
                probed = true
                oneReply = Elm327.payload(send(input, output, Elm327.request(listOf(0x0D), true), 2_000), 1, 0x0D) != null
                val pair = listOf(0x0C, 0x0D).filter { it in supported }
                grouped = pair.size == 2 &&
                    Elm327.multiPayload(send(input, output, Elm327.request(pair, oneReply), 2_000)).keys.containsAll(pair)
                onEvent("scanner: resposta na primeira ${if (oneReply) "sim" else "não"}, vários PIDs num pedido ${if (grouped) "sim" else "não"}")
            }
            val roundAt = System.currentTimeMillis()

            // Uma rodada: o essencial toda vez; o resto pelo tempo, ou em rodízio com a folha do carro aberta
            val wanted = LinkedHashSet<Int>()
            for (pid in EVERY_CYCLE) if (pid in supported) wanted += pid
            if (0x49 !in supported && 0x11 in supported) wanted += 0x11
            if (roundAt - lastCoolantAt > 3_000 && 0x05 in supported) {
                wanted += 0x05
                lastCoolantAt = roundAt
            }
            if (roundAt - lastTankAt > 10_000 && 0x2F in supported) {
                wanted += 0x2F
                lastTankAt = roundAt
            }
            // A cada ~30 s (e na primeira rodada): a distância que o carro conta (PID 31, o km do painel para a
            // manutenção) e o tempo desde a partida (PID 1F, diz ao diário quando o motor ligou)
            if (roundAt - lastSlowAt > 30_000) {
                if (0x31 in supported) wanted += 0x31
                if (0x1F in supported) wanted += 0x1F
                lastSlowAt = roundAt
            }
            val inDetail = detail()
            if (inDetail) {
                val rest = ObdPids.DEFS.map { it.pid }.filter { it in supported && it !in wanted && it !in EVERY_CYCLE }
                if (rest.isNotEmpty()) repeat(minOf(3, rest.size)) { wanted += rest[(rotate++) % rest.size] }
            }
            var silent = false
            val alone = ArrayList<Int>()
            if (grouped) {
                for (chunk in Elm327.singleFrameGroups(wanted.toList())) {
                    if (!alive(me)) return
                    val r = send(input, output, Elm327.request(chunk, oneReply), 3_000)
                    val got = Elm327.multiPayload(r)
                    if (got.isEmpty()) {
                        if (Elm327.carSilent(r)) {
                            silent = true
                            break
                        }
                        if (r.isBlank() && ++dead >= 3) return
                        if (++groupedMiss >= 3) {
                            grouped = false
                            onEvent("scanner: vários PIDs num pedido falhou 3 vezes seguidas, desligado")
                        }
                    } else {
                        dead = 0
                        groupedMiss = 0
                        values.putAll(got)
                    }
                    // O que não veio no grupo vai sozinho
                    alone += chunk.filter { it !in got }
                }
                alone += wanted.filter { it !in Elm327.DATA_BYTES }
            } else {
                alone += wanted
            }
            if (!silent) {
                for (pid in alone) {
                    if (!alive(me)) return
                    val r = send(input, output, Elm327.request(listOf(pid), oneReply), 3_000)
                    val p = Elm327.payload(r, 1, pid)
                    if (p != null) {
                        values[pid] = p
                        dead = 0
                        oneReplyMiss = 0
                    } else if (Elm327.carSilent(r)) {
                        silent = true
                        break
                    } else if (r.isBlank() && ++dead >= 3) {
                        return
                    } else if (oneReply && ++oneReplyMiss >= 5) {
                        oneReply = false
                        onEvent("scanner: resposta na primeira falhou 5 vezes seguidas, desligada")
                    }
                }
            }
            if (silent) {
                // Ignição desligada (ou o motor parou): zera as leituras e pergunta de novo o que o carro tem
                values.clear()
                supported = emptyList()
                if (!carSilent) {
                    carSilent = true
                    onEvent("carro: parou de responder (ignição desligada?), tensão ${voltage?.let { String.format("%.1f", it) } ?: "--"} V")
                }
                publish(ObdStatus.CAR_OFF, "Scanner conectado. Ligue a ignição para ler o carro.")
                if (!nap(3_000, me)) return
                continue
            }

            // Falhas guardadas e luz de injeção: a cada 30 s (e na primeira vez), só com a folha aberta
            if (inDetail && now - lastDtcAt > 30_000) {
                lastDtcAt = now
                dtcs = ObdPids.dtcs(send(input, output, "03", 6_000))
                if (0x01 in supported) {
                    Elm327.payload(send(input, output, "0101", 3_000), 1, 0x01)?.let { ObdPids.monitor(it)?.let { m -> milOn = m.milOn; dtcCount = m.dtcCount } }
                }
            }

            // Consumo da viagem: soma só com o carro andando ou o motor ligado
            val dt = (System.currentTimeMillis() - lastTick) / 1000.0
            lastTick = System.currentTimeMillis()
            val speed = values[0x0D]?.let { ObdPids.value(0x0D, it) }
            val lph = values[0x5E]?.let { ObdPids.value(0x5E, it) }
                ?: values[0x10]?.let { ObdPids.value(0x10, it) }?.let { FuelEstimate.litersPerHour(it, fuelFactor) }
            if (lph != null && speed != null) synchronized(trip) { trip.add(dt, lph, speed) }

            publish(ObdStatus.LIVE, null)
            // Aba Motor na tela: rodadas seguidas, no máximo uma a cada 200 ms (os mostradores e o jeito de dirigir
            // andam junto com o carro). Com a folha aberta, seguidas também. No resto, uma rodada por segundo basta
            val pause = when {
                inDetail -> 0L
                fast() -> FAST_ROUND_MS - (System.currentTimeMillis() - roundAt)
                else -> 700L
            }
            if (pause > 0 && !nap(pause, me)) return
        }
    }

    // ---- Bluetooth ----

    private fun open(adapter: BluetoothAdapter, device: BluetoothDevice, me: Thread): BluetoothSocket? {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            { device.createRfcommSocketToServiceRecord(SPP) },
            // Truque conhecido dos clones ELM327: canal 1 direto, por reflexão
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        )
        for (make in attempts) {
            if (!alive(me)) return null
            try {
                adapter.cancelDiscovery()
                val s = make()
                // connect() bloqueia até uns 12 s: um vigia fecha o socket se passar do prazo
                val watchdog = Thread {
                    try {
                        Thread.sleep(CONNECT_MS)
                        s.close()
                    } catch (e: Exception) {
                    }
                }.apply { isDaemon = true; start() }
                try {
                    s.connect()
                } finally {
                    watchdog.interrupt()
                }
                return s
            } catch (e: Exception) {
                // tenta o próximo jeito
            }
        }
        return null
    }

    /** Manda [cmd] e lê até o ">" do ELM327 (ou o prazo); vazio se o enlace falhou. */
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
                    Thread.sleep(25)
                }
            } catch (e: Exception) {
                break
            }
        }
        return sb.toString()
    }

    private companion object {
        /** Rodada mais curta no modo rápido (aba Motor). */
        const val FAST_ROUND_MS = 200L
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val CONNECT_MS = 12_000L
    }
}
