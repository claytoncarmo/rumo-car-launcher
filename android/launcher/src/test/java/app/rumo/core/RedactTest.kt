package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    @Test
    fun hidesMacAddresses() {
        assertEquals(
            "com.zjinnova.zlink {phoneBTMac=(oculto)}",
            redact("com.zjinnova.zlink {phoneBTMac=A4:C3:F0:12:34:56}"),
        )
        assertEquals("wifi (MAC oculto) ok", redact("wifi 02-00-00-aa-bb-cc ok"))
    }

    @Test
    fun hidesSerialsAndIds() {
        assertFalse("XYZ123" in redact("prop:ro.serialno: XYZ123"))
        assertFalse("XYZ123" in redact("[ro.boot.serialno]: [XYZ123]"))
        assertFalse("abcdef0123" in redact("secure:android_id=abcdef0123"))
        assertEquals("prop:ro.serialno: (oculto)", redact("prop:ro.serialno: XYZ123"))
    }

    @Test
    fun keepsOrdinaryLines() {
        val line = "prop:persist.vendor.cem.light0: 1 → 0"
        assertEquals(line, redact(line))
        assertEquals("Avisos recebidos (3):", redact("Avisos recebidos (3):"))
    }
}
