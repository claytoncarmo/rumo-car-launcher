package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    @Test
    fun roundTripKeepsEveryType() {
        val prefs = mapOf<String, Any?>(
            "radar_zone_m" to 450,
            "obd_fuel_factor" to 1.12f,
            "alerts_via_rumo_fm" to true,
            "theme" to "NIGHT",
            // Texto com tabulação, quebra de linha, barra e acentos: tudo volta igual
            "radio_names" to "98.5=Rádio\tUm\n99.1=a\\b",
            "web_favorites" to setOf("b", "a", "c\tx"),
            "oil_viscosity" to "",
            "odometer_km" to "45300.0",
        )
        val files = mapOf(
            "my-points.csv" to "lat,lon\n-23.5,-46.6\n",
            "trips.csv" to "start,end\n1,2\n",
        )
        val text = Backup.encode("0.2.0-nightly.99", "2026-11-23T12:00:00", prefs, files)
        val back = Backup.decode(text)!!
        assertEquals("0.2.0-nightly.99", back.appVersion)
        assertEquals("2026-11-23T12:00:00", back.at)
        assertEquals(450, back.prefs["radar_zone_m"])
        assertEquals(1.12f, back.prefs["obd_fuel_factor"])
        assertEquals(true, back.prefs["alerts_via_rumo_fm"])
        assertEquals("NIGHT", back.prefs["theme"])
        assertEquals("98.5=Rádio\tUm\n99.1=a\\b", back.prefs["radio_names"])
        assertEquals(setOf("a", "b", "c\tx"), back.prefs["web_favorites"])
        assertEquals("", back.prefs["oil_viscosity"])
        assertEquals("45300.0", back.prefs["odometer_km"])
        assertEquals(files, back.files)
        assertEquals(prefs.size, back.prefs.size)
    }

    @Test
    fun longAndEmptySet() {
        val text = Backup.encode("v", "t", mapOf("oil_last_km" to 40_000L, "favorites" to emptySet<String>()), emptyMap())
        val back = Backup.decode(text)!!
        assertEquals(40_000L, back.prefs["oil_last_km"])
        assertEquals(emptySet<String>(), back.prefs["favorites"])
    }

    @Test
    fun keysOutsideTheListsNeverGoInOrComeOut() {
        val text = Backup.encode(
            "v", "t",
            mapOf("session_open" to true, "update_found" to "x", "last_lat" to "1.0", "theme" to "DAY"),
            mapOf("evil.txt" to "x", "../trips.csv" to "x", "trips.csv" to "ok"),
        )
        assertFalse(text.contains("session_open"))
        assertFalse(text.contains("evil.txt"))
        assertEquals(setOf("theme"), Backup.decode(text)!!.prefs.keys)
        assertEquals(setOf("trips.csv"), Backup.decode(text)!!.files.keys)

        // Um arquivo adulterado com chave ou arquivo fora das listas: ignorados ao ler
        val forged = "rumo-backup\t1\tv\tt\n" +
            "p\ts\tsession_open\ttrue\n" +
            "p\ts\tupdate_found\thttp://x\n" +
            "f\t../../x\tmal\n" +
            "f\tmy-points.csv\tbom\n" +
            "p\ti\ttheme\tnao-e-numero\n" +
            "p\ti\tradar_zone_m\t300\n"
        val back = Backup.decode(forged)!!
        assertEquals(mapOf<String, Any>("radar_zone_m" to 300), back.prefs)
        assertEquals(mapOf("my-points.csv" to "bom"), back.files)
    }

    @Test
    fun rejectsWhatIsNotABackup() {
        assertNull(Backup.decode(""))
        assertNull(Backup.decode("lixo qualquer"))
        assertNull(Backup.decode("rumo-backup\t1\tv"))
        assertNull(Backup.decode("outra-coisa\t1\tv\tt\n"))
        // Formato mais novo que o app conhece: não arrisca restaurar
        assertNull(Backup.decode("rumo-backup\t2\tv\tt\n"))
        // Arquivo com final de linha do Windows
        assertEquals(5, Backup.decode("rumo-backup\t1\tv\tt\r\np\ti\tradar_zone_m\t5\r\n")!!.prefs["radar_zone_m"])
    }

    @Test
    fun backupNames() {
        assertTrue(Backup.isBackupName("rumo-backup.txt"))
        assertTrue(Backup.isBackupName("rumo-backup-anterior.txt"))
        assertTrue(Backup.isBackupName("rumo-backup-previous.txt"))
        assertTrue(Backup.isBackupName("rumo-backup (1).txt"))
        assertFalse(Backup.isBackupName("rumo-backup.txt.tmp"))
        assertFalse(Backup.isBackupName("alertas.log"))
    }
}
