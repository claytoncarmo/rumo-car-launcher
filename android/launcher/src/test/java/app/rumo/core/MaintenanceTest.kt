package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MaintenanceTest {
    private val plan = OilPlan(OilType.SEMI_SYNTHETIC, 7_500, 6, LocalDate.of(2026, 6, 1), 40_000)

    @Test
    fun defaultsByOilType() {
        assertEquals(5_000, OilType.MINERAL.intervalKm)
        assertEquals(12, OilType.SYNTHETIC.intervalMonths)
        assertEquals(47_500, plan.nextKm)
        assertEquals(LocalDate.of(2026, 12, 1), plan.nextDate)
    }

    @Test
    fun statusByKmAndTime() {
        val today = LocalDate.of(2026, 9, 1)
        val ok = Maintenance.status(plan, 44_000.0, today)
        assertEquals(3_500, ok.kmLeft)
        assertEquals(91, ok.daysLeft)
        assertFalse(ok.due)
        assertFalse(ok.soon)
        assertEquals("Faltam 3.500 km ou 3 meses", Maintenance.text(ok))

        // Perto pelos km
        val soonKm = Maintenance.status(plan, 47_200.0, today)
        assertTrue(soonKm.soon)
        // Vencida pelos km
        val dueKm = Maintenance.status(plan, 47_620.0, today)
        assertTrue(dueKm.due)
        assertEquals("Vencida há 120 km", Maintenance.text(dueKm))
        // Vencida pelo tempo, mesmo sem rodar
        val dueTime = Maintenance.status(plan, 41_000.0, LocalDate.of(2026, 12, 11))
        assertTrue(dueTime.due)
        assertEquals("Vencida há 10 dias", Maintenance.text(dueTime))
        // Sem o km atual: só pelo tempo
        val noKm = Maintenance.status(plan, null, LocalDate.of(2026, 11, 21))
        assertNull(noKm.kmLeft)
        assertTrue(noKm.soon)
        assertEquals("Faltam 10 dias", Maintenance.text(noKm))
    }

    @Test
    fun parsesTypedDateAndKm() {
        assertEquals(LocalDate.of(2026, 6, 1), Maintenance.parseDate("01/06/2026"))
        assertEquals(LocalDate.of(2026, 6, 1), Maintenance.parseDate(" 1/6/26 "))
        assertNull(Maintenance.parseDate("31/02/2026"))
        assertNull(Maintenance.parseDate("2026-06-01"))
        assertEquals("01/06/2026", Maintenance.formatDate(LocalDate.of(2026, 6, 1)))
        assertEquals(45_300, Maintenance.parseKm("45.300"))
        assertEquals(45_300, Maintenance.parseKm("45 300 km"))
        assertNull(Maintenance.parseKm(""))
        assertNull(Maintenance.parseKm("123456789"))
    }

    @Test
    fun odometerFollowsTheCarCounter() {
        val o = Odometer(45_000.0, null)
        assertTrue(o.onCounter(1_200))        // primeira leitura: só guarda
        assertEquals(45_000.0, o.km, 0.0)
        assertTrue(o.onCounter(1_230))        // rodou 30 km
        assertEquals(45_030.0, o.km, 0.0)
        assertFalse(o.onCounter(1_230))       // nada mudou
        assertTrue(o.onCounter(12))           // o contador zerou (falhas apagadas): anda os 12 desde o zero
        assertEquals(45_042.0, o.km, 0.0)
        assertFalse(o.onCounter(40_000))      // salto absurdo: ignora e recomeça daqui
        assertEquals(45_042.0, o.km, 0.0)
        assertTrue(o.onCounter(40_010))
        assertEquals(45_052.0, o.km, 0.0)
        o.set(46_000.0, 40_015)               // corrigido pelo painel, com o carro lido agora
        assertEquals(46_000.0, o.km, 0.0)
        assertTrue(o.onCounter(40_020))
        assertEquals(46_005.0, o.km, 0.0)
        o.set(47_000.0, null)                 // sem leitura agora: a próxima só marca o ponto de partida
        assertTrue(o.onCounter(41_000))
        assertEquals(47_000.0, o.km, 0.0)
    }

    @Test
    fun viscosity() {
        assertEquals("5W-30", Maintenance.parseViscosity("5w30"))
        assertEquals("0W-20", Maintenance.parseViscosity(" sae 0W-20 "))
        assertEquals("10W-40", Maintenance.parseViscosity("10 w 40"))
        assertEquals("SAE 40", Maintenance.parseViscosity("40"))
        assertNull(Maintenance.parseViscosity("sintético"))
        assertNull(plan.viscosity)
    }
}
