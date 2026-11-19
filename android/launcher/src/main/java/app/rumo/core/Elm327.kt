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
