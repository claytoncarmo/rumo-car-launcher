package app.rumo.core

/**
 * Leitura das respostas de um scanner OBD2 com chip ELM327 (texto em hexadecimal: "41 0C 1A F8"). Só lógica de texto,
 * sem Bluetooth, para ser testada.
 */
object Elm327 {

    /**
     * Bytes de dados da resposta ao pedido [mode] [pid] (modo 01 = dados ao vivo): a linha que começa com 0x40+modo e o
     * PID, sem esses dois bytes. Null se não houver (sem dados, carro desligado, PID que o carro não tem).
     */
    fun payload(response: String, mode: Int, pid: Int): List<Int>? {
        val head = "%02X%02X".format(0x40 + mode, pid)
        for (line in response.lines()) {
            val hex = line.filter { it.isLetterOrDigit() }.uppercase()
            val i = hex.indexOf(head)
            if (i < 0) continue
            val data = hex.substring(i + head.length)
            if (data.isEmpty() || data.length % 2 != 0 || !data.all { it in "0123456789ABCDEF" }) continue
            return data.chunked(2).map { it.toInt(16) }
        }
        return null
    }

    /**
     * Bytes de dados de cada PID do modo 01 que o Rumo pergunta em grupo. Para separar a resposta de um pedido com vários
     * PIDs é preciso saber quantos bytes cada um traz; um PID fora desta lista vai sempre sozinho.
     */
    val DATA_BYTES: Map<Int, Int> = mapOf(
        0x04 to 1, 0x05 to 1, 0x0B to 1, 0x0C to 2, 0x0D to 1, 0x0E to 1, 0x0F to 1, 0x10 to 2, 0x11 to 1,
        0x1F to 2, 0x2F to 1, 0x31 to 2, 0x33 to 1, 0x45 to 1, 0x47 to 1, 0x49 to 1, 0x4A to 1, 0x4C to 1, 0x5E to 2,
    )

    /** Pedido do modo 01 para [pids] (até 6, em CAN); [oneReply]: termina com "1", e o scanner responde logo na primeira resposta. */
    fun request(pids: List<Int>, oneReply: Boolean): String =
        "01" + pids.joinToString("") { "%02X".format(it) } + if (oneReply) "1" else ""

    /**
     * Separa [pids] (os de tamanho conhecido) em grupos cuja resposta cabe num quadro CAN só (até 7 bytes: o 41 e, para
     * cada PID, o número e os dados). Resposta em vários quadros é onde os clones mais erram, e com o "1" no fim do
     * pedido o scanner poderia devolver só o primeiro quadro.
     */
    fun singleFrameGroups(pids: List<Int>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var group = ArrayList<Int>()
        var size = 1
        for (pid in pids) {
            val n = 1 + (DATA_BYTES[pid] ?: continue)
            if (size + n > SINGLE_FRAME_BYTES && group.isNotEmpty()) {
                out += group
                group = ArrayList()
                size = 1
            }
            group += pid
            size += n
        }
        if (group.isNotEmpty()) out += group
        return out
    }

    /** Bytes de dados que cabem num quadro CAN único. */
    const val SINGLE_FRAME_BYTES = 7

    /**
     * Dados de cada PID na resposta a um pedido com vários PIDs. Em CAN, uma resposta com mais de 7 bytes vem em quadros:
     * uma linha com o tamanho ("00E") e linhas "0:", "1:"… Mais de uma central pode responder (cada uma com a sua
     * mensagem): vale a primeira que trouxer cada PID. Para no primeiro PID de tamanho desconhecido.
     */
    fun multiPayload(response: String): Map<Int, List<Int>> {
        val out = LinkedHashMap<Int, List<Int>>()
        fun parse(msg: List<Int>) {
            if (msg.firstOrNull() != 0x41) return
            var i = 1
            while (i < msg.size) {
                val pid = msg[i]
                val n = DATA_BYTES[pid] ?: return
                if (i + 1 + n > msg.size) return
                if (pid !in out) out[pid] = msg.subList(i + 1, i + 1 + n).toList()
                i += 1 + n
            }
        }
        var frames: MutableList<Int>? = null
        var size = 0
        for (raw in response.split('\r', '\n')) {
            val t = raw.uppercase().filter { it.isLetterOrDigit() || it == ':' }
            if (t.isEmpty()) continue
            if (t.length == 3 && t.all { it in HEX }) {
                frames?.let { parse(it.take(size)) }
                frames = ArrayList()
                size = t.toInt(16)
                continue
            }
            val colon = t.indexOf(':')
            val current = frames
            if (colon == 1 && current != null) {
                bytes(t.substring(2))?.let { current.addAll(it) }
                continue
            }
            if (colon < 0) bytes(t)?.let { parse(it) }
        }
        frames?.let { parse(it.take(size)) }
        return out
    }

    private const val HEX = "0123456789ABCDEF"

    private fun bytes(hex: String): List<Int>? =
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in HEX }) null else hex.chunked(2).map { it.toInt(16) }

    /** O scanner respondeu, mas o carro não: nada de dados (ignição desligada, protocolo não achado). */
    fun carSilent(response: String): Boolean {
        val r = response.uppercase()
        return listOf("NO DATA", "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR", "BUS BUSY", "STOPPED").any { it in r }
    }

    /** Rotação do motor (PID 0C), em rpm. */
    fun rpm(p: List<Int>): Int? = if (p.size >= 2) (p[0] * 256 + p[1]) / 4 else null

    /** Temperatura do líquido de arrefecimento (PID 05), em °C. */
    fun coolantC(p: List<Int>): Int? = p.firstOrNull()?.minus(40)

    /** Velocidade do carro (PID 0D), em km/h. */
    fun speedKmh(p: List<Int>): Int? = p.firstOrNull()

    /** Nível do tanque (PID 2F), em %. */
    fun fuelPercent(p: List<Int>): Int? = p.firstOrNull()?.let { it * 100 / 255 }

    /** Carga do motor (PID 04), em %. */
    fun loadPercent(p: List<Int>): Int? = p.firstOrNull()?.let { it * 100 / 255 }

    /** PIDs que o carro diz ter, a partir da resposta a 0100, 0120, 0140…: [base] é o primeiro PID do bloco (0x00, 0x20…). */
    fun supportedPids(p: List<Int>, base: Int): List<Int> {
        if (p.size < 4) return emptyList()
        val out = ArrayList<Int>()
        for (i in 0 until 32) {
            val byte = p[i / 8]
            if (byte and (0x80 shr (i % 8)) != 0) out += base + i + 1
        }
        return out
    }

    /** Resposta à tensão da bateria (ATRV): "12.6V" → 12,6. */
    fun voltage(response: String): Double? =
        Regex("""(\d{1,2}(?:\.\d)?)\s*V""", RegexOption.IGNORE_CASE).find(response)?.groupValues?.get(1)?.toDoubleOrNull()
}
