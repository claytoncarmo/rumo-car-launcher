package app.rumo.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Uma viagem do diário: do motor ligado até a última leitura do scanner. O Rumo só lê o carro com ele na tela, então
 * parte da viagem pode passar sem leitura (Android Auto aberto). A distância vem do contador do carro (PID 31), que conta
 * sempre; o consumo, a velocidade e o tempo parado só existem no trecho lido ([seenKm]).
 */
data class Trip(
    /** Hora em que o motor ligou (pelo tempo desde a partida, PID 1F; sem ele, a primeira leitura). */
    val start: Long,
    /** Última leitura com o motor ligado. */
    val end: Long,
    /** Tempo de motor ligado na última leitura. */
    val engineSeconds: Int,
    /** Contador de km do carro na primeira e na última leitura. */
    val startCounter: Int?,
    val endCounter: Int?,
    /** Km que o carro contou sem o Rumo ver (antes da primeira leitura ou depois da última). */
    val gapKm: Int,
    /** Distância e combustível no trecho lido (velocidade e consumo de cada leitura). */
    val seenKm: Double,
    val liters: Double,
    /** Tempo parado com o motor ligado (no trecho lido). */
    val idleSeconds: Int,
    val maxCoolant: Int?,
    val maxSpeed: Int?,
) {
    private val counterKm: Int? get() = if (startCounter != null && endCounter != null && endCounter >= startCounter) endCounter - startCounter else null

    /** Distância da viagem: o contador do carro (mais o que ele contou sem leitura) ou o trecho lido, o que for maior. */
    val km: Double get() = maxOf(seenKm, (counterKm ?: 0).toDouble()) + gapKm

    /** Média do trecho lido; null com pouco combustível somado. */
    val kmPerLiter: Double? get() = if (liters < 0.05) null else seenKm / liters

    /** Parte da viagem sem leitura (mais de 1 km): só a distância vale para ela. */
    val partial: Boolean get() = km - seenKm > 1.0
}

/** Totais de um período do diário. */
data class TripSummary(val trips: Int, val km: Double, val engineSeconds: Long, val seenKm: Double, val liters: Double) {
    val kmPerLiter: Double? get() = if (liters < 0.05) null else seenKm / liters
}

/** Uma leitura do carro, do jeito que o diário precisa. */
data class TripReading(
    val now: Long,
    val rpm: Double?,
    val speedKmh: Double?,
    val litersPerHour: Double?,
    val coolant: Double?,
    /** Tempo desde a partida (PID 1F), em segundos; null se o carro não informa. */
    val runtimeSec: Int?,
    /** Distância desde apagar as falhas (PID 31), em km. */
    val counter: Int?,
)

/**
 * Diário de viagens montado com as leituras do scanner. Uma viagem nova começa quando o motor dá partida de novo: o
 * "tempo desde a partida" aponta outra hora de partida (ou, sem ele, depois de [NEW_TRIP_GAP_MS] sem leitura). Os km
 * que o carro contou entre a última leitura de uma viagem e a primeira da seguinte vão para a anterior se a nova acabou
 * de começar (o Rumo ficou sem ler o fim dela) ou para a nova (sem saber dividir).
 */
class TripDiary(trips: List<Trip> = emptyList()) {
    private val list = ArrayList(trips)

    /** Viagens, da mais antiga à mais nova; a última pode estar em andamento. */
    val trips: List<Trip> get() = list

    private var lastNow = 0L
    private var lastRuntime: Int? = null

    /** Uma leitura com o carro respondendo; devolve se uma viagem nova começou (hora de gravar a anterior). */
    fun onReading(r: TripReading): Boolean {
        val dt = (r.now - lastNow).takeIf { it in 1..MAX_STEP_MS }?.div(1000.0) ?: 0.0
        lastNow = r.now
        // O valor do tempo desde a partida fica guardado entre as leituras: só vale quando acabou de mudar
        val runtime = r.runtimeSec?.takeIf { it != lastRuntime }
        if (r.runtimeSec != null) lastRuntime = r.runtimeSec
        val running = (r.rpm ?: 0.0) > 0.0
        if (!running) return false

        val cur = list.lastOrNull()
        val start = runtime?.let { r.now - it * 1000L }
        val isNew = when {
            cur == null -> true
            start != null -> kotlin.math.abs(start - cur.start) > SAME_START_MS
            else -> r.now - cur.end > NEW_TRIP_GAP_MS
        }
        if (isNew) {
            var gap = 0
            if (cur?.endCounter != null && r.counter != null) gap = (r.counter - cur.endCounter).takeIf { it in 1..MAX_GAP_KM } ?: 0
            val toPrevious = gap > 0 && runtime != null && runtime <= JUST_STARTED_SEC
            if (cur != null && toPrevious) list[list.size - 1] = cur.copy(gapKm = cur.gapKm + gap)
            list += Trip(
                start = start ?: r.now, end = r.now, engineSeconds = runtime ?: 0,
                startCounter = r.counter, endCounter = r.counter, gapKm = if (toPrevious) 0 else gap,
                seenKm = 0.0, liters = 0.0, idleSeconds = 0,
                maxCoolant = r.coolant?.toInt(), maxSpeed = r.speedKmh?.toInt(),
            )
            while (list.size > MAX_TRIPS) list.removeAt(0)
            return cur != null
        }
        val t = cur!!
        val speed = r.speedKmh ?: 0.0
        list[list.size - 1] = t.copy(
            end = r.now,
            engineSeconds = runtime ?: maxOf(t.engineSeconds, ((r.now - t.start) / 1000).toInt()),
            startCounter = t.startCounter ?: r.counter,
            endCounter = r.counter ?: t.endCounter,
            seenKm = t.seenKm + speed * dt / 3600.0,
            liters = t.liters + (r.litersPerHour ?: 0.0) * dt / 3600.0,
            idleSeconds = t.idleSeconds + if (speed < 1.0) Math.round(dt).toInt() else 0,
            maxCoolant = maxOfNullable(t.maxCoolant, r.coolant?.toInt()),
            maxSpeed = maxOfNullable(t.maxSpeed, r.speedKmh?.toInt()),
        )
        return false
    }

    fun clear() {
        list.clear()
    }

    companion object {
        /** Partidas a menos que isto uma da outra são a mesma (o tempo desde a partida chega com atraso de uma leitura). */
        const val SAME_START_MS = 180_000L
        /** Sem o tempo desde a partida: tanto tempo sem leitura com o motor ligado é outra viagem. */
        const val NEW_TRIP_GAP_MS = 10 * 60_000L
        /** A viagem nova acabou de começar: os km sem leitura são do fim da anterior. */
        const val JUST_STARTED_SEC = 180
        /** Entre duas leituras: mais que isto é pausa (o Rumo saiu da tela), não conta consumo nem distância. */
        const val MAX_STEP_MS = 5_000L
        /** Salto do contador maior que isto entre viagens é leitura estranha. */
        const val MAX_GAP_KM = 3_000
        const val MAX_TRIPS = 500

        private fun maxOfNullable(a: Int?, b: Int?): Int? = if (a == null) b else if (b == null) a else maxOf(a, b)

        /**
         * Média de consumo para a autonomia: a viagem atual inteira (a última da lista) se ela já leu [minKm]; senão soma
         * as anteriores, da mais nova para trás, até juntar [minKm] lidos. Assim o começo de uma viagem não faz a conta
         * pular, e uma viagem longa (estrada ou cidade) vale por ela mesma. Null sem combustível lido suficiente.
         */
        fun rangeKmPerLiter(trips: List<Trip>, minKm: Double = RANGE_MIN_KM): Double? {
            var km = 0.0
            var liters = 0.0
            for (t in trips.asReversed()) {
                km += t.seenKm
                liters += t.liters
                if (km >= minKm) break
            }
            return if (liters < 0.05) null else km / liters
        }

        /** Mínimo de km lidos para a média da autonomia. */
        const val RANGE_MIN_KM = 10.0

        /** Totais das viagens que começaram entre [from] (inclusive) e [until] (exclusive). */
        fun summary(trips: List<Trip>, from: Long, until: Long): TripSummary {
            val sel = trips.filter { it.start in from until until }
            return TripSummary(sel.size, sel.sumOf { it.km }, sel.sumOf { it.engineSeconds.toLong() }, sel.sumOf { it.seenKm }, sel.sumOf { it.liters })
        }

        /** Início da semana (segunda-feira, 0h) e do mês de [today], em milissegundos. */
        fun weekStart(today: LocalDate, zone: ZoneId): Long =
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()

        fun monthStart(today: LocalDate, zone: ZoneId): Long = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

        fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        // ---- arquivo (uma viagem por linha) ----

        private const val HEADER = "start,end,engine_s,start_counter,end_counter,gap_km,seen_km,liters,idle_s,max_coolant,max_speed"

        fun encode(trips: List<Trip>): String = buildString {
            append(HEADER).append('\n')
            for (t in trips) {
                append(t.start).append(',').append(t.end).append(',').append(t.engineSeconds).append(',')
                append(t.startCounter ?: "").append(',').append(t.endCounter ?: "").append(',').append(t.gapKm).append(',')
                append("%.3f".format(java.util.Locale.ROOT, t.seenKm)).append(',').append("%.4f".format(java.util.Locale.ROOT, t.liters)).append(',')
                append(t.idleSeconds).append(',').append(t.maxCoolant ?: "").append(',').append(t.maxSpeed ?: "").append('\n')
            }
        }

        fun decode(text: String?): List<Trip> {
            if (text.isNullOrBlank()) return emptyList()
            return text.lineSequence().drop(1).mapNotNull { line ->
                val f = line.split(',')
                if (f.size < 11) return@mapNotNull null
                try {
                    Trip(
                        f[0].toLong(), f[1].toLong(), f[2].toInt(), f[3].toIntOrNull(), f[4].toIntOrNull(), f[5].toInt(),
                        f[6].toDouble(), f[7].toDouble(), f[8].toInt(), f[9].toIntOrNull(), f[10].toIntOrNull(),
                    )
                } catch (e: NumberFormatException) {
                    null
                }
            }.toList()
        }
    }
}
