package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun emptyKeepsSource() {
        assertNull(audioOwnerOf(""))
        assertNull(audioOwnerOf(null))
    }
}
