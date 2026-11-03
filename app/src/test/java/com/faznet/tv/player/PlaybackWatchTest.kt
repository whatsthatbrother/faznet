package com.faznet.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoWatchTest {

    private val watch = ExoWatch()

    private fun sample(now: Long, rendered: Int, active: Long, pos: Long, ptsMs: Long = -1, hasVideo: Boolean = true) =
        ExoWatch.Sample(now, hasVideo, rendered, active, pos, if (ptsMs < 0) -1 else ptsMs * 1000, 40_000)

    /** Opens a channel at 1000 ms and plays [frames] pictures, one every 40 ms. Returns the time it ends at. */
    private fun playFrames(frames: Int): Long {
        watch.onOpen()
        watch.onReady(1_000)
        watch.onFirstFrame(1_000)
        var now = 1_000L
        for (i in 1..frames) {
            now += 40
            assertNull(watch.check(sample(now, i, i * 2L, now - 1_000, now - 1_000)).problem)
        }
        return now
    }

    @Test
    fun aChannelThatPlaysHasNoProblem() {
        val now = playFrames(200)
        assertTrue(watch.check(sample(now, 200, 400, now - 1_000, now - 1_000)).canShow)
    }

    @Test
    fun thePictureAppearsOnlyOnceItRuns() {
        watch.onOpen()
        watch.onReady(1_000)
        watch.onFirstFrame(1_000)
        assertFalse(watch.check(sample(1_100, 9, 18, 100, 100)).canShow)
        assertTrue(watch.check(sample(1_200, 10, 20, 200, 200)).canShow)
        assertFalse("ten pictures and then nothing is not running", watch.check(sample(1_700, 10, 20, 700, 200)).canShow)
    }

    @Test
    fun aRadioChannelAppearsWithoutPictures() {
        watch.onOpen()
        watch.onReady(1_000)
        assertFalse(watch.check(sample(1_400, 0, 0, 400, hasVideo = false)).canShow)
        assertTrue(watch.check(sample(1_600, 0, 0, 600, hasVideo = false)).canShow)
    }

    /** What the reports show: in=91 out=5, the clock about 900 ms past the last picture, about half a second after it stopped. */
    @Test
    fun aDecoderStoppedAfterFivePicturesIsCaughtWithinASecond() {
        val stopped = playFrames(5)
        val pts = stopped - 1_000
        assertNull("not yet", watch.check(sample(stopped + 300, 5, 10, pts + 300, pts)).problem)
        assertNull("idle long enough, clock not far enough", watch.check(sample(stopped + 450, 5, 10, pts + 450, pts)).problem)
        val p = watch.check(sample(stopped + 600, 5, 10, pts + 600, pts)).problem!!
        assertEquals("picture frozen at start", p.reason)
        assertTrue(p.wedged)
        assertTrue(p.atStart)
        assertEquals(600, p.behindMs)
    }

    @Test
    fun aPictureThatIsMerelyLateIsLeftAlone() {
        val stopped = playFrames(5)
        val pts = stopped - 1_000
        // The decoder still takes frames in: it is busy, not stuck.
        var active = 10L
        for (t in 100L..1_400L step 100) {
            active += 3
            assertNull(watch.check(sample(stopped + t, 5, active, pts + t, pts)).problem)
        }
        assertEquals("picture frozen at start", watch.check(sample(stopped + 1_600, 5, active + 3, pts + 1_600, pts)).problem?.reason)
    }

    @Test
    fun noPictureAtAll() {
        watch.onOpen()
        watch.onReady(1_000)
        assertNull(watch.check(sample(3_400, 0, 5, 2_400)).problem)
        val p = watch.check(sample(3_600, 0, 5, 2_600)).problem!!
        assertTrue(p.noPicture)
        assertFalse(p.wedged)
    }

    @Test
    fun aFreezeLaterUsesTheLongerLimits() {
        val stopped = playFrames(200) // 8 s, the window of the start is over
        val pts = stopped - 1_000
        assertNull(watch.check(sample(stopped + 1_000, 200, 400, pts + 1_000, pts)).problem)
        val p = watch.check(sample(stopped + 1_600, 200, 400, pts + 1_600, pts)).problem!!
        assertEquals("picture frozen", p.reason)
        assertFalse(p.atStart)
    }

    @Test
    fun nothingMovingAtAll() {
        val stopped = playFrames(200)
        val pos = stopped - 1_000
        // No picture information: only the clock is there to look at.
        watch.check(ExoWatch.Sample(stopped + 100, false, 200, 400, pos, -1, 0))
        assertNull(watch.check(ExoWatch.Sample(stopped + 8_000, false, 200, 400, pos, -1, 0)).problem)
        assertEquals("playback stuck", watch.check(ExoWatch.Sample(stopped + 8_200, false, 200, 400, pos, -1, 0)).problem?.reason)
    }

    @Test
    fun playingForAWhileForgivesEarlierFixes() {
        watch.onOpen()
        watch.onReady(1_000)
        watch.onFirstFrame(1_000)
        assertFalse(watch.check(sample(2_000, 25, 50, 1_000, 1_000)).healthy)
        assertTrue(watch.check(sample(31_100, 750, 1_500, 30_100, 30_100)).healthy)
    }

    @Test
    fun aNewOpeningStartsClean() {
        val stopped = playFrames(5)
        watch.onNotReady()
        assertFalse(watch.isReady)
        watch.onOpen()
        watch.onReady(stopped + 5_000)
        assertTrue(watch.isReady)
        assertNull(watch.check(sample(stopped + 5_100, 0, 0, 0)).problem)
    }

    @Test
    fun theTraceBuildLeavesAStuckDecoderAlone() {
        val tracing = ExoWatch(traceWatching = true)
        tracing.onOpen()
        tracing.onReady(1_000)
        tracing.onFirstFrame(1_000)
        tracing.check(sample(1_200, 5, 10, 200, 200))
        assertNull(tracing.check(sample(5_000, 5, 10, 4_000, 200)).problem)
    }
}

class ProgressWatchTest {

    private val watch = ProgressWatch().apply { reset(0) }

    @Test
    fun picturesThatKeepComingAreProgress() {
        for (t in 1..100L) assertNull(watch.check(t * 1_000, 0, t, shown = true, zapping = false))
    }

    @Test
    fun aFrozenPictureCountsAsStuckEvenIfTheClockRuns() {
        assertNull(watch.check(1_000, 1_000, 50, shown = true, zapping = false))
        assertNull(watch.check(15_900, 15_900, 50, shown = true, zapping = false))
        assertEquals(15_000L, watch.check(16_000, 16_000, 50, shown = true, zapping = false))
    }

    @Test
    fun radioUsesTheClock() {
        for (t in 1..30L) assertNull(watch.check(t * 1_000, t * 1_000, 0, shown = true, zapping = false))
        assertNull(watch.check(44_900, 30_000, 0, shown = true, zapping = false))
        assertEquals(15_000L, watch.check(45_000, 30_000, 0, shown = true, zapping = false))
    }

    @Test
    fun theWaitGrowsToTwoMinutesAndStartsOverOnceItPlays() {
        watch.check(0, 0, 1, shown = false, zapping = false)
        var now = 0L
        for (expected in listOf(15_000L, 30_000L, 60_000L, 120_000L, 120_000L)) {
            now += expected
            assertEquals(expected, watch.check(now, 0, 1, shown = false, zapping = false))
        }
        now += 1_000
        assertNull(watch.check(now, 0, 2, shown = true, zapping = false))
        now += 15_000
        assertEquals(15_000L, watch.check(now, 0, 2, shown = true, zapping = false))
    }

    @Test
    fun movingWhileStillHiddenKeepsTheLongerWait() {
        watch.check(0, 0, 1, shown = false, zapping = false)
        assertEquals(15_000L, watch.check(15_000, 0, 1, shown = false, zapping = false))
        assertNull(watch.check(16_000, 0, 2, shown = false, zapping = false))
        assertNull("still 30 s", watch.check(31_000, 0, 2, shown = false, zapping = false))
        assertEquals(30_000L, watch.check(46_000, 0, 2, shown = false, zapping = false))
    }

    @Test
    fun zappingHoldsTheCount() {
        watch.check(0, 0, 1, shown = true, zapping = false)
        assertNull(watch.check(20_000, 0, 1, shown = true, zapping = true))
        assertNull(watch.check(30_000, 0, 1, shown = true, zapping = false))
    }
}

class RefusalWatchTest {

    private val watch = RefusalWatch()

    @Test
    fun aRefusedChannelIsToldAtTheThirdAnswerAndLeftAfterTheFifth() {
        val steps = List(5) { watch.onRefused() }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 7_000L, null), steps.map { it.retryInMs })
        assertEquals("told once, at about 3 s", listOf(false, false, true, false, false), steps.map { it.tell })
        assertTrue(watch.told)
    }

    @Test
    fun theQuietTriesReachAsFarAsTheOldRetriesDid() {
        val last = List(4) { watch.onRefused().retryInMs!! }.sum()
        assertEquals("the last request goes out 15 s after the first", 15_000L, last)
    }

    @Test
    fun aChannelThatPlaysStartsCountingAgain() {
        repeat(3) { watch.onRefused() }
        watch.reset()
        assertFalse(watch.active)
        assertFalse(watch.told)
        assertEquals(1_000L, watch.onRefused().retryInMs)
        assertTrue(watch.active)
    }
}

class ProblemOutcomeTest {

    private val lines = ArrayList<Triple<String, String, Long>>()
    private val outcome = ProblemOutcome { channel, what, at -> lines += Triple(channel, what, at) }

    @Test
    fun theLineWaitsForTheChannelToComeBack() {
        outcome.problem(1_000, 500_000, "One", "picture frozen [x]")
        assertTrue(lines.isEmpty())
        outcome.back(2_250)
        assertEquals(listOf(Triple("One", "picture frozen [x] → back after 1.2 s", 500_000L)), lines)
    }

    @Test
    fun onlyTheFirstPictureAfterAProblemCounts() {
        outcome.back(1_000)
        outcome.problem(1_000, 0, "One", "a")
        outcome.back(2_000)
        outcome.back(9_000)
        assertEquals(1, lines.size)
    }

    @Test
    fun aNewProblemSaysTheOldOneDidNotEndWell() {
        outcome.problem(1_000, 10, "One", "a")
        outcome.problem(16_000, 20, "One", "b")
        assertEquals("a → not back after 15.0 s", lines.single().second)
        outcome.left(16_500)
        assertEquals("b → left after 0.5 s", lines.last().second)
        assertEquals(20L, lines.last().third)
    }

    @Test
    fun leavingWithNothingWaitingWritesNothing() {
        outcome.left(1_000)
        assertTrue(lines.isEmpty())
    }
}
