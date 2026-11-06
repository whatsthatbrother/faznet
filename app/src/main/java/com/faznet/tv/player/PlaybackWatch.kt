package com.faznet.tv.player

/**
 * Decides when a channel has stopped playing. Only the decisions live here, with the time and what the engine reports
 * passed in, so every case can be tested without a player. What to do about it stays with the screen.
 */
class ExoWatch(private val traceWatching: Boolean = false) {

    /** What ExoPlayer reports right now. [framePtsUs] is -1 before the first picture; [frameIntervalUs] 0 before the second. */
    class Sample(
        val now: Long,
        val hasVideo: Boolean,
        val rendered: Int,
        val decoderActivity: Long,
        val positionMs: Long,
        val framePtsUs: Long,
        val frameIntervalUs: Long,
    )

    /** [sinceFrameMs], [sinceClockMs], [sinceDecoderMs]: how long the picture, the clock and the decoder had stood still. */
    class Problem(
        val reason: String,
        val wedged: Boolean,
        val behindMs: Long,
        val sinceFrameMs: Long,
        val sinceClockMs: Long,
        val sinceDecoderMs: Long,
    ) {
        val atStart get() = reason.endsWith("at start")
        val noPicture get() = reason == NO_PICTURE
    }

    /** [canShow]: the picture runs, it may appear. [healthy]: playing fine for a while, earlier fixes are forgiven. */
    class Result(val canShow: Boolean, val healthy: Boolean, val problem: Problem?)

    private var readySince = 0L
    private var firstFrameSeen = false
    private var lastRendered = -1
    private var lastRenderedAt = 0L
    private var lastPos = -1L
    private var lastPosAt = 0L
    private var lastActivity = -1L
    private var lastActiveAt = 0L

    /** The player is in READY; the rest only applies then. */
    val isReady get() = readySince != 0L

    fun onOpen() {
        firstFrameSeen = false
        readySince = 0L
        lastRendered = -1
        lastPos = -1L
        lastActivity = -1L
    }

    fun onReady(now: Long) {
        readySince = now
        lastPosAt = now
        lastRenderedAt = now
        lastActiveAt = now
    }

    fun onNotReady() {
        readySince = 0L
    }

    fun onFirstFrame(now: Long) {
        firstFrameSeen = true
        lastRenderedAt = now
    }

    fun check(s: Sample): Result {
        val now = s.now
        // Anything the decoder does: a frame taken in, a picture shown, a late one dropped.
        if (s.decoderActivity != lastActivity) {
            lastActivity = s.decoderActivity
            lastActiveAt = now
        }
        var healthy = false
        if (s.rendered != lastRendered) {
            lastRendered = s.rendered
            lastRenderedAt = now
            healthy = firstFrameSeen && now - readySince > HEALTHY_MS
        }
        if (s.positionMs != lastPos) {
            lastPos = s.positionMs
            lastPosAt = now
        }
        val canShow = (s.rendered >= STABLE_FRAMES && now - lastRenderedAt < RUNNING_MS) ||
            (!s.hasVideo && now - readySince > NO_VIDEO_SHOW_MS)

        // Right after a channel opens is when the Mi TV Stick's video decoder sometimes stops dead: it shows a few
        // pictures, then takes nothing in and gives nothing out while the sound plays on. A new decoder fixes it, so
        // this reacts within half a second, and only to that: a picture that is merely late is not touched.
        val early = now - readySince < EARLY_WINDOW_MS
        val behind = if (s.framePtsUs >= 0) s.positionMs - s.framePtsUs / 1000 else 0L
        val wedged = !traceWatching && s.hasVideo && firstFrameSeen && now - lastActiveAt > DECODER_IDLE_MS &&
            behind > maxOf(DECODER_BEHIND_MS, 3 * s.frameIntervalUs / 1000) && (early || now - lastRenderedAt > LATE_WEDGED_MS)
        val frozenLimit = if (traceWatching) 60_000L else if (early) EARLY_FROZEN_MS else FROZEN_TIMEOUT_MS
        val reason = when {
            s.hasVideo && !firstFrameSeen && now - readySince > FIRST_FRAME_TIMEOUT_MS -> NO_PICTURE
            wedged || (s.hasVideo && firstFrameSeen && now - lastRenderedAt > frozenLimit && now - lastPosAt < 1_000) ->
                if (early) "picture frozen at start" else "picture frozen"
            now - lastPosAt > STUCK_TIMEOUT_MS -> "playback stuck"
            else -> null
        }
        return Result(canShow, healthy, reason?.let { Problem(it, wedged, behind, now - lastRenderedAt, now - lastPosAt, now - lastActiveAt) })
    }

    private companion object {
        const val NO_PICTURE = "no picture"
        // The Mi TV Stick's decoder often stops at 5 pictures on interlaced channels (rarely up to 9), and a new one plays.
        // With fewer than this the viewer would see the channel start, stop and start again.
        const val STABLE_FRAMES = 10
        const val RUNNING_MS = 400L
        const val NO_VIDEO_SHOW_MS = 500L
        const val HEALTHY_MS = 30_000L
        const val FIRST_FRAME_TIMEOUT_MS = 2_500L
        const val FROZEN_TIMEOUT_MS = 2_500L
        const val EARLY_WINDOW_MS = 6_000L
        // No new picture for this long right after the start, even though the decoder still looks busy.
        const val EARLY_FROZEN_MS = 1_500L
        // The decoder is wedged when it has done nothing at all for this long and the clock has passed the last picture.
        const val DECODER_IDLE_MS = 400L
        const val DECODER_BEHIND_MS = 500L
        const val LATE_WEDGED_MS = 1_500L
        const val STUCK_TIMEOUT_MS = 8_000L
    }
}

/**
 * Whether the video decoder should change for a channel. Some channels stop the Mi TV Stick's hardware decoder at every
 * opening (KLAN PLUS FHD: 83 frames in, 5 pictures out, then nothing), so re-opening never helps. Others freeze only now
 * and then, usually on the first opening, and play on the next one. Only freezes [LIMIT] times in a row, with no healthy
 * playing between them, tell the first kind apart: then the channel changes decoder, and the screen remembers that.
 */
class StuckDecoder {
    private var inRow = 0

    /** A new channel, or it played well for a while. */
    fun reset() {
        inRow = 0
    }

    /** The picture froze with the decoder in use. @return true if the channel should change decoder now. */
    fun froze(): Boolean {
        inRow++
        if (inRow < LIMIT) return false
        inRow = 0
        return true
    }

    private companion object {
        const val LIMIT = 2
    }
}

/**
 * The last resort: whatever the reason (error screen, endless buffering, every other fix used up), a
 * channel whose picture and clock stand still is opened again after 15 s, then 30, 60, 120 s.
 */
class ProgressWatch {
    private var pos = -1L
    private var frames = -1L
    private var since = 0L
    private var delay = FIRST_MS

    fun reset(now: Long) {
        pos = -1L
        frames = -1L
        since = now
        delay = FIRST_MS
    }

    /**
     * Channels with a picture count as moving only while new pictures come (a frozen picture with the sound still
     * playing is caught here too); radio uses the clock.
     * @return how long it stood still if it should be opened again now, otherwise null.
     */
    fun check(now: Long, position: Long, frameCount: Long, shown: Boolean, zapping: Boolean): Long? {
        if (zapping) {
            since = now
            return null
        }
        val moving = if (frameCount > 0 || frames > 0) frameCount != frames else position != pos
        if (moving) {
            if (since != 0L && shown) delay = FIRST_MS // playing again: start counting from 15 s
            pos = position
            frames = frameCount
            since = now
            return null
        }
        pos = position
        if (now - since < delay) return null
        val waited = delay
        since = now
        delay = (delay * 2).coerceAtMost(MAX_MS)
        return waited
    }

    private companion object {
        const val FIRST_MS = 15_000L
        const val MAX_MS = 120_000L
    }
}

/**
 * The server refuses a channel: not in the package or offline, which stays so, or the account's connection limit, which
 * passes once the provider has dropped the previous channel's connection. The viewer is told after about 3 s, and two more
 * quiet tries still catch the limit passing, which has taken up to 15 s. Five requests in all.
 */
class RefusalWatch {
    /** [retryInMs]: when to ask again, null for never. [tell]: say so on the screen now. */
    class Step(val retryInMs: Long?, val tell: Boolean)

    private var refusals = 0

    /** Tries are under way: starting over from elsewhere would only make more connections. */
    val active get() = refusals > 0

    /** The viewer has been told, so the message stays through the quiet tries. */
    val told get() = refusals >= TELL_AT

    fun reset() {
        refusals = 0
    }

    fun onRefused(): Step {
        refusals++
        return Step(DELAYS_MS.getOrNull(refusals - 1), refusals == TELL_AT)
    }

    private companion object {
        val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 7_000).toList()
        const val TELL_AT = 3
    }
}

/**
 * Holds a line for the problem log until it is known how it ended. Whether the channel came back, and how soon, is what
 * tells a blink from a lost minute. [write] gets the channel, the line and the wall clock of the problem.
 */
class ProblemOutcome(private val write: (channel: String, what: String, at: Long) -> Unit) {
    private var pending = false
    private var channel = ""
    private var what = ""
    private var at = 0L
    private var since = 0L

    /** A new problem. One still waiting did not end well: the channel was not back when this one came. */
    fun problem(now: Long, wallClock: Long, channel: String, what: String) {
        finish(now, "not back")
        pending = true
        this.channel = channel
        this.what = what
        at = wallClock
        since = now
    }

    /** The picture runs again. */
    fun back(now: Long) = finish(now, "back")

    /** The viewer went to another channel or left the player. */
    fun left(now: Long) = finish(now, "left")

    private fun finish(now: Long, how: String) {
        if (!pending) return
        pending = false
        val tenths = (now - since) / 100
        write(channel, "$what → $how after ${tenths / 10}.${tenths % 10} s", at)
    }
}
