package com.faznet.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsExitTest {

    private fun exit(at: Long, reason: Int, foreground: Boolean = true, trace: String = "") =
        Reports.Exit(at, reason, "desc $reason", foreground, pssKb = 90 * 1024, trace = trace)

    @Test
    fun aNativeCrashIsReported() {
        // Mi TV Stick, 06.11 18:33: "Scudo ERROR: corrupted chunk header" while a logo was decoded.
        val text = Reports.exitText(listOf(exit(2_000, Reports.REASON_CRASH_NATIVE)), since = 1_000)!!
        assertTrue(text, text.contains("native crash: desc 5"))
        assertTrue(text, text.contains("pss=90 MB"))
    }

    @Test
    fun onlyWhatCameAfterTheLastLook() {
        assertNull(Reports.exitText(listOf(exit(1_000, Reports.REASON_CRASH_NATIVE)), since = 1_000))
    }

    @Test
    fun ordinaryEndingsAreNotProblems() {
        val user = 10 // REASON_USER_REQUESTED
        val javaCrash = 4 // comes with crash.txt already
        val text = Reports.exitText(
            listOf(exit(2_000, user), exit(3_000, javaCrash), exit(4_000, Reports.REASON_LOW_MEMORY, foreground = false)),
            since = 0,
        )
        assertNull("leaving, a Java crash and memory in the background say nothing new", text)
    }

    @Test
    fun runningOutOfMemoryOnScreenIsReported() {
        val text = Reports.exitText(listOf(exit(2_000, Reports.REASON_LOW_MEMORY, foreground = true)), since = 0)!!
        assertTrue(text.contains("killed for memory while on screen"))
    }

    @Test
    fun anAnrCarriesTheMainThreadOnly() {
        val trace = "----- pid 1 -----\n\n\"main\" prio=5 tid=1 Blocked\n  at com.faznet.tv.A.b(A.kt:1)\n  at x.Y.z(Y.kt:2)\n\n\"other\" prio=5\n  at q.R.s\n"
        val main = Reports.mainThread(trace)
        assertTrue(main.startsWith("\"main\""))
        assertTrue(main.contains("com.faznet.tv.A.b"))
        assertFalse("another thread is not part of it", main.contains("other"))
        val text = Reports.exitText(listOf(exit(2_000, Reports.REASON_ANR, trace = main)), since = 0)!!
        assertTrue(text.contains("not responding (ANR)"))
        assertTrue(text.contains("com.faznet.tv.A.b"))
    }

    @Test
    fun severalComeOldestFirst() {
        val text = Reports.exitText(listOf(exit(9_000, Reports.REASON_ANR), exit(5_000, Reports.REASON_CRASH_NATIVE)), since = 0)!!
        assertEquals(listOf("native crash", "not responding"), Regex("(native crash|not responding)").findAll(text).map { it.value }.toList())
    }
}
