package app.rumo.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastHuntTest {
    @Test
    fun reconheceNomesDeAvisoDoFarol() {
        assertTrue(BroadcastHunt.looksLikeAction("com.android.intent.action.ILL_ON"))
        assertTrue(BroadcastHunt.looksLikeAction("com.zjinnova.zlink.action.NIGHT_MODE"))
        assertTrue(BroadcastHunt.looksLikeAction("com.mcu.car.headlight_state"))
    }

    @Test
    fun nomeDaClasseDoCarsource() {
        assertEquals("android.carsource.McuService", BroadcastHunt.carsourceClass("android.carsource.McuService.BACKLIGHT"))
        assertEquals("android.carsource.McuService", BroadcastHunt.carsourceClass("Landroid/carsource/McuService;"))
        assertEquals("android.carsource.McuService\$Listener", BroadcastHunt.carsourceClass("Landroid/carsource/McuService\$Listener;"))
        assertNull(BroadcastHunt.carsourceClass("com.hcn.carsource.ui"))
    }

    @Test
    fun ignoraClassesETextosComuns() {
        assertFalse(BroadcastHunt.looksLikeAction("android.graphics.drawable.LightingColorFilter"))
        assertFalse(BroadcastHunt.looksLikeAction("androidx.core.app.NightModeHelper.night"))
        assertFalse(BroadcastHunt.looksLikeAction("Night mode is on"))
        assertFalse(BroadcastHunt.looksLikeAction("com.empresa.app.ACTION_PLAY"))
    }
}
