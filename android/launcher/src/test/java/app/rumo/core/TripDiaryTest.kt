package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TripDiaryTest {
    private val t0 = 1_700_000_000_000L

    private fun reading(sec: Int, speed: Double = 36.0, runtime: Int? = null, counter: Int? = null, rpm: Double = 1500.0, lph: Double = 3.6, coolant: Double = 90.0) =
        TripReading(t0 + sec * 1000L, rpm, speed, lph, coolant, runtime, counter)

    @Test
    fun startsAtEnginePartidaAndSumsWhatItReads() {
        val d = TripDiary()
        assertFalse(d.onReading(reading(0, runtime = 60, counter = 1_000)))
        // 100 leituras de 1 s a 36 km/h e 3,6 L/h: 1 km e 0,1 L
        for (s in 1..100) d.onReading(reading(s, runtime = if (s % 30 == 0) 60 + s else null))
        val t = d.trips.single()
        assertEquals(t0 - 60_000L, t.start)
        assertEquals(1.0, t.seenKm, 1e-6)
        assertEquals(0.1, t.liters, 1e-6)
        assertEquals(10.0, t.kmPerLiter!!, 1e-6)
        assertEquals(160, t.engineSeconds)
        assertFalse(t.partial)
    }

    @Test
    fun idleAndEngineOff() {
        val d = TripDiary()
        d.onReading(reading(0, runtime = 5))
        for (s in 1..30) d.onReading(reading(s, speed = 0.0))
        // Ignição ligada com o motor desligado: não conta
        d.onReading(reading(31, speed = 0.0, rpm = 0.0))
        assertEquals(30, d.trips.single().idleSeconds)
    }

    @Test
    fun newPartidaClosesTheTripAndTheUnreadKmGoToTheRightTrip() {
        val d = TripDiary()
        d.onReading(reading(0, runtime = 10, counter = 1_000))
        d.onReading(reading(1, counter = 1_002))
        // O Android Auto ficou aberto até desligar; a próxima partida foi 2 h depois e o Rumo leu logo (60 s de motor)
        assertTrue(d.onReading(reading(7_200, runtime = 60, counter = 1_015)))
        assertEquals(2, d.trips.size)
        assertEquals(13, d.trips[0].gapKm)
        assertEquals(15.0, d.trips[0].km, 1e-6)
        assertTrue(d.trips[0].partial)
        assertEquals(0, d.trips[1].gapKm)

        // Agora o Rumo só leu 20 min depois da partida: os km sem leitura ficam na nova
        assertTrue(d.onReading(reading(20_000, runtime = 1_200, counter = 1_030)))
        assertEquals(0, d.trips[1].gapKm)
        assertEquals(15, d.trips[2].gapKm)
    }

    @Test
    fun sameTripWhenTheRumoComesBack() {
        val d = TripDiary()
        d.onReading(reading(0, runtime = 100, counter = 500))
        // Saiu da tela por 10 min (Android Auto) e voltou: mesma partida, mesma viagem; o intervalo não conta consumo
        assertFalse(d.onReading(reading(600, runtime = 700, counter = 507)))
        val t = d.trips.single()
        assertEquals(7.0, t.km, 1e-6)
        assertEquals(0.0, t.seenKm, 1e-6)
        assertEquals(700, t.engineSeconds)
        assertTrue(t.partial)
    }

    @Test
    fun withoutRuntimeALongPauseIsANewTrip() {
        val d = TripDiary()
        d.onReading(reading(0))
        d.onReading(reading(300))
        assertEquals(1, d.trips.size)
        assertTrue(d.onReading(reading(300 + 11 * 60)))
        assertEquals(2, d.trips.size)
    }

    @Test
    fun rangeAverageUsesAtLeastTwentyKmRead() {
        fun trip(km: Double, l: Double) = Trip(0, 0, 0, null, null, 0, km, l, 0, null, null)
        val older = trip(30.0, 3.0)      // 10 km/L
        val previous = trip(17.0, 1.0)   // 17 km/L
        assertEquals(20.0, TripDiary.RANGE_MIN_KM, 0.0)
        // Viagem atual com 1 km lido: soma a anterior (17) e a de antes (30) até passar de 20 km
        assertEquals((1.0 + 17 + 30) / (0.2 + 1.0 + 3), TripDiary.rangeKmPerLiter(listOf(older, previous, trip(1.0, 0.2)))!!, 1e-9)
        // Atual com 3 km: 3 + 17 = 20, já basta
        assertEquals(20.0 / 1.3, TripDiary.rangeKmPerLiter(listOf(older, previous, trip(3.0, 0.3)))!!, 1e-9)
        // Atual com 25 km: só ela, inteira
        assertEquals(25.0 / 2.0, TripDiary.rangeKmPerLiter(listOf(older, previous, trip(25.0, 2.0)))!!, 1e-9)
        // Diário curto: usa o que tem; sem combustível lido, nada
        assertEquals(2.0 / 0.2, TripDiary.rangeKmPerLiter(listOf(trip(2.0, 0.2)))!!, 1e-9)
        assertNull(TripDiary.rangeKmPerLiter(listOf(trip(0.1, 0.01))))
        assertNull(TripDiary.rangeKmPerLiter(emptyList()))
    }

    @Test
    fun summaryAndFile() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.of(2026, 11, 25) // quarta-feira
        val monday = TripDiary.weekStart(today, zone)
        assertEquals(LocalDate.of(2026, 11, 23), TripDiary.dayOf(monday, zone))
        assertEquals(LocalDate.of(2026, 11, 1), TripDiary.dayOf(TripDiary.monthStart(today, zone), zone))

        val a = Trip(monday + 3_600_000, monday + 5_400_000, 1_800, 10, 30, 0, 19.5, 1.5, 120, 95, 80)
        val b = Trip(monday - 3_600_000, monday, 600, 30, 35, 2, 5.0, 0.5, 0, null, null)
        val s = TripDiary.summary(listOf(a, b), monday, Long.MAX_VALUE)
        assertEquals(1, s.trips)
        assertEquals(20.0, s.km, 1e-6)
        assertEquals(13.0, s.kmPerLiter!!, 1e-6)

        val back = TripDiary.decode(TripDiary.encode(listOf(a, b)))
        assertEquals(listOf(a, b), back)
        assertNull(back[1].maxCoolant)
        assertTrue(TripDiary.decode("lixo").isEmpty())
    }
}
