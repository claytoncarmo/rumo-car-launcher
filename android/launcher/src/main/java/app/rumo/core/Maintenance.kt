package app.rumo.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Tipos de óleo do motor e o intervalo de troca mais usado para cada um (o que vier primeiro, km ou tempo). O manual do
 * carro manda: os intervalos são só o ponto de partida e o dono ajusta.
 */
enum class OilType(val label: String, val intervalKm: Int, val intervalMonths: Int) {
    MINERAL("Mineral", 5_000, 6),
    SEMI_SYNTHETIC("Semissintético", 7_500, 6),
    SYNTHETIC("Sintético", 10_000, 12),
}

/** A troca de óleo: tipo, intervalo e a última troca (data e km do painel). */
data class OilPlan(
    val type: OilType,
    val intervalKm: Int,
    val intervalMonths: Int,
    val lastDate: LocalDate,
    val lastKm: Int,
) {
    val nextKm: Int get() = lastKm + intervalKm
    val nextDate: LocalDate get() = lastDate.plusMonths(intervalMonths.toLong())
}

/**
 * Situação da troca de óleo hoje: quanto falta em km (null sem o km atual) e em dias, e se já venceu ou está perto
 * (até [SOON_KM] km ou [SOON_DAYS] dias).
 */
data class OilStatus(val kmLeft: Int?, val daysLeft: Long, val due: Boolean, val soon: Boolean)

object Maintenance {
    const val SOON_KM = 500
    const val SOON_DAYS = 15L

    fun status(plan: OilPlan, odometerKm: Double?, today: LocalDate): OilStatus {
        val kmLeft = odometerKm?.let { plan.nextKm - Math.round(it).toInt() }
        val daysLeft = ChronoUnit.DAYS.between(today, plan.nextDate)
        val due = (kmLeft != null && kmLeft <= 0) || daysLeft <= 0
        val soon = !due && ((kmLeft != null && kmLeft <= SOON_KM) || daysLeft <= SOON_DAYS)
        return OilStatus(kmLeft, daysLeft, due, soon)
    }

    /** "faltam 2.340 km ou 3 meses", "vencida há 120 km", "vence em 10 dias". */
    fun text(s: OilStatus): String {
        val km = s.kmLeft
        val time = when {
            s.daysLeft < 0 -> "há ${-s.daysLeft} dias"
            s.daysLeft < 45 -> "${s.daysLeft} dias"
            else -> "${s.daysLeft / 30} meses"
        }
        return when {
            s.due && km != null && km <= 0 -> "Vencida há ${String.format(PT_BR, "%,d", -km)} km"
            s.due -> "Vencida $time"
            km == null -> "Faltam $time"
            else -> "Faltam ${String.format(PT_BR, "%,d", km)} km ou $time"
        }
    }

    // ---- data "dd/mm/aaaa" e km digitados ----

    fun parseDate(text: String): LocalDate? {
        val m = Regex("""^\s*(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})\s*$""").find(text) ?: return null
        val (d, mo, y) = m.destructured
        val year = y.toInt().let { if (it < 100) 2000 + it else it }
        return try {
            LocalDate.of(year, mo.toInt(), d.toInt())
        } catch (e: Exception) {
            null
        }
    }

    fun formatDate(d: LocalDate): String = "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)

    /** "45.300", "45300", "45 300 km" → 45300; null se não for um número de km razoável. */
    fun parseKm(text: String): Int? = text.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 7 }?.toInt()
}

/**
 * Km do painel estimado pelo contador do próprio carro: a "distância desde que as falhas foram apagadas" (PID 31), que a
 * central mantém mesmo com o Rumo desligado. O Rumo soma a diferença entre as leituras ao km informado pelo dono. O
 * contador zera se as falhas forem apagadas ou a bateria desligada: aí a contagem continua do zero (perde só o trecho
 * entre a última leitura e o zero).
 */
class Odometer(km: Double, lastCounter: Int?) {
    var km: Double = km
        private set
    var lastCounter: Int? = lastCounter
        private set

    /** Nova leitura do contador; devolve se o km mudou (para gravar). */
    fun onCounter(counter: Int): Boolean {
        val last = lastCounter
        lastCounter = counter
        if (last == null) return true
        val d = counter - last
        val add = when {
            d in 0..MAX_JUMP_KM -> d
            d < 0 -> counter // o contador zerou: anda o que ele contou desde então
            else -> 0 // salto grande demais: leitura estranha, recomeça daqui
        }
        km += add
        return add != 0
    }

    /** O dono informou o km do painel: passa a valer este. */
    fun set(km: Double) {
        this.km = km
    }

    companion object {
        /** Mais que isto entre duas leituras não é viagem (o Rumo lê o carro a cada uso). */
        const val MAX_JUMP_KM = 5_000
    }
}
