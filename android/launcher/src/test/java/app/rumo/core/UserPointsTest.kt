package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPointsTest {
    private fun point(kind: Int = RadarKind.FIXED, limit: Int = 60, bearing: Int = 90, lat: Double = -27.6, lon: Double = -48.5) =
        UserPoint(lat, lon, kind, limit, bearing, "2026-11-01")

    @Test
    fun guardaELeDeVoltaTodosOsTipos() {
        val pontos = UserPoints.KINDS.mapIndexed { i, kind ->
            point(kind, if (kind in RadarKind.ENFORCING) 40 + i * 10 else 0, if (i % 2 == 0) i * 40 else -1, lat = (-2760 - i) / 100.0)
        }
        assertEquals(pontos, UserPoints.decode(UserPoints.encode(pontos)))
    }

    @Test
    fun asTresPrimeirasColunasSaoAsDoCsvDoMapaRadar() {
        for (kind in UserPoints.KINDS) {
            val limit = if (kind in RadarKind.ENFORCING) 60 else 0
            val linha = UserPoints.encode(listOf(point(kind, limit))).lines().first { !it.startsWith("#") && it.isNotBlank() }
            val csv = linha.split(',').take(3).joinToString(",")
            val p = MapaRadar.parseLine(csv, 1)!!
            assertEquals(kind, p.kind)
            assertEquals(limit, p.limit)
            assertEquals(-27.6, p.lat, 1e-6)
            assertEquals(-48.5, p.lon, 1e-6)
        }
    }

    @Test
    fun ignoraComentarioLinhaEmBrancoELixo() {
        val texto = """
            # comentário
            -48.500000,-27.600000,Radar Fixo - 60 kmh@60,90,2026-11-01

            lixo
            -48.5,-27.6,Tipo Novo - 60 kmh@60,,
            abc,-27.6,Radar Fixo - 60 kmh@60
            -48.5,-27.6,Pedagio - 0 kmh@0
        """.trimIndent()
        val pontos = UserPoints.decode(texto)
        assertEquals(listOf(RadarKind.FIXED, RadarKind.TOLL), pontos.map { it.kind })
        assertEquals(90, pontos[0].bearing)
        assertEquals("2026-11-01", pontos[0].date)
        assertEquals(-1, pontos[1].bearing)
        assertEquals("", pontos[1].date)
    }

    @Test
    fun sentidoForaDeFaixaViraOsDois() {
        assertEquals(-1, UserPoints.decode("-48.5,-27.6,Radar Fixo - 60 kmh@60,400,").single().bearing)
        assertEquals(-1, UserPoints.decode("-48.5,-27.6,Radar Fixo - 60 kmh@60,norte,").single().bearing)
    }

    @Test
    fun quemNaoFiscalizaNaoGuardaLimite() {
        val linha = UserPoints.encode(listOf(point(RadarKind.TOLL, limit = 80)))
        assertTrue(linha.contains("Pedagio - 0 kmh@0"))
        assertEquals(0, UserPoints.decode(linha).single().limit)
    }

    @Test
    fun lombadaEletronicaVoltaComoEletronica() {
        val back = UserPoints.decode(UserPoints.encode(listOf(point(RadarKind.ELECTRONIC_BUMP, 30), point(RadarKind.BUMP, 0, lat = -27.7)))).map { it.kind to it.limit }
        assertEquals(listOf(RadarKind.ELECTRONIC_BUMP to 30, RadarKind.BUMP to 0), back)
    }

    @Test
    fun tipoSemNomeNoCsvNaoEhGravado() {
        assertNull(MapaRadar.describe(RadarKind.OSM, 60))
        assertTrue(UserPoints.decode(UserPoints.encode(listOf(point(RadarKind.OSM)))).isEmpty())
    }

    @Test
    fun mesmoTipoPertoTrocaOPonto() {
        val a = point(limit = 60)
        // ~5 m ao norte, mesmo tipo: troca (e muda o limite)
        val b = point(limit = 80, lat = -27.6 + 5 / Geo.M_PER_DEG_LAT)
        assertEquals(listOf(b), UserPoints.add(listOf(a), b))
        // outro tipo no mesmo lugar, ou o mesmo tipo longe: acrescenta
        assertEquals(2, UserPoints.add(listOf(a), point(RadarKind.TOLL, 0)).size)
        assertEquals(2, UserPoints.add(listOf(a), point(lat = -27.6 + 100 / Geo.M_PER_DEG_LAT)).size)
    }

    @Test
    fun apagaPeloIndiceEIgnoraIndiceRuim() {
        val l = listOf(point(lat = -27.1), point(lat = -27.2), point(lat = -27.3))
        assertEquals(listOf(l[0], l[2]), UserPoints.remove(l, 1))
        assertEquals(l, UserPoints.remove(l, 3))
        assertEquals(l, UserPoints.remove(l, -1))
    }

    @Test
    fun viramRadaresComIdProprio() {
        val radares = UserPoints.toRadars(listOf(point(RadarKind.MOBILE, 50, 180), point(RadarKind.POLICE, 0, -1, lat = -27.7)))
        assertEquals(2, radares.map { it.id }.toSet().size)
        // fora dos ids do OSM (positivos) e do MapaRadar (a linha, negativa e pequena)
        assertTrue(radares.all { it.id < -1_000_000_000L })
        assertEquals(RadarKind.MOBILE, radares[0].kind)
        assertEquals(50, radares[0].limit)
        assertEquals(180, radares[0].bearing)
        assertEquals(-1, radares[1].bearing)
        assertTrue(radares[1].chimes)
    }

    @Test
    fun rotuloComELimite() {
        assertEquals("Radar · 60 km/h", UserPoints.label(point()))
        assertEquals("Lombada", UserPoints.label(point(RadarKind.BUMP, 0)))
        assertEquals("Semáforo com radar · 40 km/h", UserPoints.label(point(RadarKind.TRAFFIC_LIGHT_RADAR, 40)))
    }

    // ---- onde se tocou no "+"

    @Test
    fun paradoSemSentido() {
        assertNull(MarkSpot.of(-27.6, -48.5, 120f, 0, 60).bearing)
        assertNull(MarkSpot.of(-27.6, -48.5, null, 40, 60).bearing)
        assertEquals(120, MarkSpot.of(-27.6, -48.5, 120.2f, 40, 60).bearing)
        assertEquals(0, MarkSpot.of(-27.6, -48.5, 359.6f, 40, 60).bearing)
    }

    @Test
    fun limiteSugeridoVemDaViaArredondadoEDentroDaFaixa() {
        assertEquals(60, MarkSpot.of(0.0, 0.0, null, null, 57).limit)
        assertEquals(60, MarkSpot.of(0.0, 0.0, null, null, null).limit)
        assertEquals(60, MarkSpot.of(0.0, 0.0, null, null, 0).limit)
        assertEquals(UserPoints.MAX_LIMIT, MarkSpot.of(0.0, 0.0, null, null, 200).limit)
        assertEquals(UserPoints.MIN_LIMIT, MarkSpot.of(0.0, 0.0, null, null, 5).limit)
        assertNotNull(MarkSpot.of(0.0, 0.0, null, null, 30))
    }
}
