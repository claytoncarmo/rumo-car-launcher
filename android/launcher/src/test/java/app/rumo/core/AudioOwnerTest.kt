package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOwnerTest {
    @Test
    fun valuesSeenOnDevice() {
        assertEquals(AudioOwner.ZLINK, audioOwnerOf("com.zjinnova.zlink"))
        assertEquals(AudioOwner.ZLINK, audioOwnerOf("com.zjinnova.android.zlink.features.main.MainActivity"))
        assertEquals(AudioOwner.RADIO, audioOwnerOf("com.hcn.autoradio"))
        assertEquals(AudioOwner.RADIO, audioOwnerOf(".RadioMain"))
        assertEquals(AudioOwner.OTHER, audioOwnerOf("com.hcn.btmusic"))
    }

    @Test
    fun playStopsOnlyWhatIsKnownToPlay() {
        // Tocando e ligado no serviço do rádio: o play para (mudo)
        assertFalse(radioPlayStarts(muted = false, lastRawOwner = "com.hcn.autoradio", serviceConnected = true))
        assertFalse(radioPlayStarts(muted = false, lastRawOwner = null, serviceConnected = true))
        // Mudo, ou o sourceinfo disse que não toca: o play liga
        assertTrue(radioPlayStarts(muted = true, lastRawOwner = "com.hcn.autoradio", serviceConnected = true))
        assertTrue(radioPlayStarts(muted = false, lastRawOwner = "", serviceConnected = true))
        // Depois de atualizar o app: sem o serviço ligado o rádio está mudo, mesmo com o card dizendo que toca
        assertTrue(radioPlayStarts(muted = false, lastRawOwner = null, serviceConnected = false))
    }

    @Test
    fun resumesTheRadioServiceOnlyWhenItIsTheSourceAndNotStoppedByRumo() {
        assertTrue(shouldResumeRadio(radioIsSource = true, muted = false, serviceConnected = false))
        assertFalse(shouldResumeRadio(radioIsSource = true, muted = false, serviceConnected = true))
        assertFalse(shouldResumeRadio(radioIsSource = true, muted = true, serviceConnected = false))
        assertFalse(shouldResumeRadio(radioIsSource = false, muted = false, serviceConnected = false))
    }

    @Test
    fun emptyKeepsSource() {
        assertNull(audioOwnerOf(""))
        assertNull(audioOwnerOf(null))
    }
}
