package app.rumo.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerMenuTest {
    @Test
    fun reconheceOBotaoDeReiniciar() {
        assertTrue(isRestartLabel("Reiniciar"))
        assertTrue(isRestartLabel("  REINICIAR "))
        assertTrue(isRestartLabel("Restart"))
        assertTrue(isRestartLabel("Reboot"))
    }

    @Test
    fun naoConfundeComOutrosBotoes() {
        assertFalse(isRestartLabel(null))
        assertFalse(isRestartLabel(""))
        assertFalse(isRestartLabel("Desligar"))
        assertFalse(isRestartLabel("Reiniciar o Wi-Fi"))
        assertFalse(isRestartLabel("Cancelar"))
    }

    @Test
    fun reconheceGanchosDeReinicio() {
        assertTrue(looksLikeRebootHook("com.hcn.settings.RebootReceiver"))
        assertTrue(looksLikeRebootHook("android.intent.action.ACTION_SHUTDOWN"))
        assertTrue(looksLikeRebootHook("setPowerOff"))
        assertFalse(looksLikeRebootHook("com.hcn.autoradio.RadioService"))
    }
}
