package app.rumo.core

/**
 * Dados ao vivo do carro (modo 01 do OBD2, padronizados pela SAE J1979): quais existem, como se decodificam e como
 * aparecem na tela. Só texto e conta, sem Bluetooth, para ser testado. Cada carro informa só uma parte deles.
 */
object ObdPids {
    enum class Group(val label: String) {
        ENGINE("Motor"),
        FUEL("Combustível"),
        THROTTLE("Acelerador"),
        SENSORS("Sensores e elétrica"),
        SERVICE("Manutenção"),
    }

    /** [decode] recebe os bytes de dados (sem o "41" e o PID) e devolve o valor; [decimals] casas na tela. */
    class Def(
        val pid: Int,
        val label: String,
        val unit: String,
        val group: Group,
        val decimals: Int = 0,
        val decode: (List<Int>) -> Double?,
    )

    private fun u8(p: List<Int>) = p.getOrNull(0)?.toDouble()
    private fun u16(p: List<Int>) = if (p.size >= 2) (p[0] * 256 + p[1]).toDouble() else null
    private fun pct(p: List<Int>) = u8(p)?.let { it * 100 / 255 }

    val DEFS: List<Def> = listOf(
        Def(0x0C, "Rotação", "rpm", Group.ENGINE) { u16(it)?.div(4) },
        Def(0x0D, "Velocidade do carro", "km/h", Group.ENGINE) { u8(it) },
        Def(0x04, "Carga do motor", "%", Group.ENGINE) { pct(it) },
        Def(0x43, "Carga absoluta", "%", Group.ENGINE, 1) { u16(it)?.times(100)?.div(255) },
        Def(0x05, "Temperatura do motor", "°C", Group.ENGINE) { u8(it)?.minus(40) },
        Def(0x5C, "Temperatura do óleo", "°C", Group.ENGINE) { u8(it)?.minus(40) },
        Def(0x0E, "Avanço da ignição", "°", Group.ENGINE, 1) { u8(it)?.div(2)?.minus(64) },
        Def(0x0F, "Temperatura do ar de admissão", "°C", Group.ENGINE) { u8(it)?.minus(40) },
        Def(0x0B, "Pressão do coletor (MAP)", "kPa", Group.ENGINE) { u8(it) },
        Def(0x10, "Fluxo de ar (MAF)", "g/s", Group.ENGINE, 1) { u16(it)?.div(100) },
        Def(0x33, "Pressão atmosférica", "kPa", Group.ENGINE) { u8(it) },
        Def(0x46, "Temperatura ambiente", "°C", Group.ENGINE) { u8(it)?.minus(40) },

        Def(0x2F, "Nível do tanque", "%", Group.FUEL) { pct(it) },
        Def(0x5E, "Taxa de combustível", "L/h", Group.FUEL, 1) { u16(it)?.div(20) },
        Def(0x06, "Correção de combustível curta", "%", Group.FUEL, 1) { u8(it)?.times(100)?.div(128)?.minus(100) },
        Def(0x07, "Correção de combustível longa", "%", Group.FUEL, 1) { u8(it)?.times(100)?.div(128)?.minus(100) },
        Def(0x44, "Mistura comandada (lambda)", "", Group.FUEL, 3) { u16(it)?.div(32768) },
        Def(0x34, "Sensor de oxigênio 1 (lambda)", "", Group.FUEL, 3) { u16(it)?.div(32768) },
        Def(0x2E, "Purga do canister", "%", Group.FUEL) { pct(it) },
        Def(0x0A, "Pressão do combustível", "kPa", Group.FUEL) { u8(it)?.times(3) },

        Def(0x11, "Posição do acelerador", "%", Group.THROTTLE) { pct(it) },
        Def(0x45, "Acelerador relativo", "%", Group.THROTTLE) { pct(it) },
        Def(0x47, "Acelerador B absoluto", "%", Group.THROTTLE) { pct(it) },
        Def(0x49, "Pedal do acelerador D", "%", Group.THROTTLE) { pct(it) },
        Def(0x4A, "Pedal do acelerador E", "%", Group.THROTTLE) { pct(it) },
        Def(0x4C, "Atuador do acelerador", "%", Group.THROTTLE) { pct(it) },

        Def(0x42, "Tensão da central (ECU)", "V", Group.SENSORS, 2) { u16(it)?.div(1000) },

        Def(0x1F, "Tempo desde a partida", "s", Group.SERVICE) { u16(it) },
        Def(0x21, "Distância com a luz de injeção acesa", "km", Group.SERVICE) { u16(it) },
        Def(0x30, "Partidas desde apagar as falhas", "", Group.SERVICE) { u8(it) },
        Def(0x31, "Distância desde apagar as falhas", "km", Group.SERVICE) { u16(it) },
    )

    private val BY_PID = DEFS.associateBy { it.pid }

    fun def(pid: Int): Def? = BY_PID[pid]

    /** Valor numérico de [pid] nos bytes [payload], ou null (PID desconhecido ou bytes de menos). */
    fun value(pid: Int, payload: List<Int>): Double? = BY_PID[pid]?.decode?.invoke(payload)

    /** Texto do valor, no formato brasileiro ("1.726", "0,978"), sem a unidade. */
    fun text(def: Def, value: Double): String =
        if (def.decimals == 0) String.format(PT_BR, "%,d", Math.round(value)) else String.format(PT_BR, "%,.${def.decimals}f", value)

    /** Texto do PID já com a unidade ("81 °C"), ou null se não decodificar. */
    fun display(pid: Int, payload: List<Int>): String? {
        val def = BY_PID[pid] ?: return null
        val v = def.decode(payload) ?: return null
        return text(def, v) + if (def.unit.isEmpty()) "" else " ${def.unit}"
    }

    /** Estado do monitor (PID 01): se a luz de injeção está acesa e quantos códigos de falha o carro tem. */
    class Monitor(val milOn: Boolean, val dtcCount: Int)

    fun monitor(payload: List<Int>): Monitor? =
        payload.firstOrNull()?.let { Monitor(milOn = it and 0x80 != 0, dtcCount = it and 0x7F) }

    /**
     * Códigos de falha guardados (resposta ao modo 03, "43 01 33 00 00…" ou, em CAN, "43 01 01 33"): "P0133".
     * Vazio se não houver nenhum ou a resposta não for do modo 03.
     */
    fun dtcs(response: String): List<String> {
        val hex = response.lines().joinToString("") { l -> l.filter { it in "0123456789ABCDEFabcdef" }.takeIf { it.length >= 2 } ?: "" }
            .uppercase()
        val i = hex.indexOf("43")
        if (i < 0) return emptyList()
        var rest = hex.substring(i + 2)
        // Em CAN, o primeiro byte depois do 43 é a quantidade de códigos (sobra 1 byte além dos pares)
        if (rest.length % 4 == 2) rest = rest.substring(2)
        val out = ArrayList<String>()
        for (k in 0 until rest.length / 4) {
            val code = rest.substring(k * 4, k * 4 + 4).toInt(16)
            if (code == 0) continue
            val letter = "PCBU"[(code shr 14) and 0x3]
            out += "$letter${(code shr 12) and 0x3}${"%03X".format(code and 0xFFF)}"
        }
        return out
    }
}

/**
 * Consumo estimado (o carro não informa a taxa de combustível, só o ar que entra): litros por hora a partir do fluxo de
 * ar (MAF), da mistura ar/combustível ideal da gasolina brasileira (com etanol, cerca de 13,2:1) e da densidade dela
 * (~750 g/L). Erra uns 10 %: o [factor] de calibração corrige com um tanque abastecido e a distância rodada.
 */
object FuelEstimate {
    const val AFR = 13.2
    const val DENSITY_G_PER_L = 750.0

    /** L/h pelo fluxo de ar em g/s; [factor] 1,0 = sem ajuste. */
    fun litersPerHour(mafGs: Double, factor: Double = 1.0): Double = mafGs * 3600.0 / (AFR * DENSITY_G_PER_L) * factor

    /** km/L agora; null parado (abaixo de 3 km/h o número não quer dizer nada). */
    fun kmPerLiter(speedKmh: Double, lph: Double): Double? = if (speedKmh < 3.0 || lph <= 0.05) null else speedKmh / lph

    /** Novo fator de calibração: o consumo real (litros do abastecimento) contra o estimado no mesmo trecho. */
    fun calibrate(currentFactor: Double, estimatedLiters: Double, realLiters: Double): Double? =
        if (estimatedLiters <= 0.0 || realLiters <= 0.0) null else (currentFactor * realLiters / estimatedLiters).coerceIn(0.5, 2.0)
}

/** Soma do consumo e da distância da viagem, para a média (km/L) desde que a conta foi zerada. */
class FuelTrip {
    var liters = 0.0
        private set
    var km = 0.0
        private set

    /** [dtSeconds] desde a leitura anterior (limitado: uma pausa longa não vira consumo). */
    fun add(dtSeconds: Double, lph: Double, speedKmh: Double) {
        val dt = dtSeconds.coerceIn(0.0, 5.0)
        liters += lph * dt / 3600.0
        km += speedKmh * dt / 3600.0
    }

    /** Média em km/L; null com pouco consumo ainda (menos de 50 mL). */
    fun kmPerLiter(): Double? = if (liters < 0.05) null else km / liters

    fun reset() {
        liters = 0.0
        km = 0.0
    }
}
