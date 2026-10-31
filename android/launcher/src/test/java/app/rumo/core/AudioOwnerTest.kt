package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOwnerTest {
    @Test
    fun valoresVistosNoAparelho() {
        assertEquals(AudioOwner.ZLINK, audioOwnerOf("com.zjinnova.zlink"))
        assertEquals(AudioOwner.ZLINK, audioOwnerOf("com.zjinnova.android.zlink.features.main.MainActivity"))
        assertEquals(AudioOwner.RADIO, audioOwnerOf("com.hcn.autoradio"))
        assertEquals(AudioOwner.RADIO, audioOwnerOf(".RadioMain"))
        assertEquals(AudioOwner.OTHER, audioOwnerOf("com.hcn.btmusic"))
    }

    @Test
    fun vazioNaoMudaAFonte() {
        assertNull(audioOwnerOf(""))
        assertNull(audioOwnerOf(null))
    }
}
