package app.rumo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashLogTest {
    private val log = listOf(
        "11-14 17:10:10.000   900   900 I Rumo: abriu",
        "11-14 17:10:11.000   900   912 D Radio: tocar",
        "11-14 17:10:12.000   900   900 E AndroidRuntime: FATAL EXCEPTION: main",
        "11-14 17:10:12.001   900   900 E AndroidRuntime: java.lang.IllegalStateException: x",
        "11-14 17:10:12.002   905   905 I Outro: linha de outro processo",
        "11-14 17:10:20.000  1500  1500 I Rumo: abriu de novo",
    )

    @Test
    fun findsLastCrashOfPreviousProcess() {
        val block = CrashLog.lastCrash(log, myPid = 1500)!!
        assertEquals(4, block.size)
        assertEquals(listOf(900), block.map { CrashLog.pidOf(it) }.distinct())
        assertEquals(true, block.any { "IllegalStateException" in it })
    }

    @Test
    fun ignoresCurrentProcessAndCleanLogs() {
        assertNull(CrashLog.lastCrash(log, myPid = 900))
        assertNull(CrashLog.lastCrash(log.filterNot { "FATAL" in it }, myPid = 1500))
    }

    @Test
    fun nativeSignal() {
        val native = listOf("11-14 17:10:12.000   900   930 F libc    : Fatal signal 11 (SIGSEGV), code 1")
        assertEquals(1, CrashLog.lastCrash(native, myPid = 1)?.size)
        assertEquals(900, CrashLog.pidOf(native[0]))
        assertNull(CrashLog.pidOf("--------- beginning of crash"))
    }
}
