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

    class Problem(val reason: String, val wedged: Boolean, val behindMs: Long) {
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
        return Result(canShow, healthy, reason?.let { Problem(it, wedged, behind) })
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
 * The last resort, for both engines: whatever the reason (error screen, endless buffering, every other fix used up), a
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

/** VLC has no frame callbacks, so its decoding counters are compared on every look. */
class VlcWatch {

    enum class Problem(val text: String) {
        NO_START("no start"),
        /** The TV's decoder gives nothing (black picture, sound plays). */
        NO_PICTURE("no picture"),
        FROZEN_AT_START("picture frozen at start"),
        FROZEN("picture frozen"),
        /** Still behind the loading circle: the provider's first seconds are bad, re-opening quickly fixes it. */
        STUCK_AT_START("stuck at start"),
        STUCK("playback stuck"),
    }

    class Result(val problem: Problem?, val healthy: Boolean)

    private var playingAt = 0L
    private var hasVideo = false
    private var picturesSeen = false
    private var counted = false
    private var lastPictures = 0L
    private var lastAudio = 0L
    private var lastTime = 0L
    private var pictureAt = 0L
    private var audioAt = 0L
    private var timeAt = 0L
    private var openedAt = 0L

    fun onOpen(now: Long) {
        playingAt = 0L
        hasVideo = false
        picturesSeen = false
        counted = false
        pictureAt = now
        audioAt = now
        timeAt = now
        openedAt = now
    }

    fun onPlaying(now: Long) {
        if (playingAt == 0L) playingAt = now
    }

    fun onVideo() {
        hasVideo = true
    }

    /** Never started: the server accepted the connection but sends nothing usable. */
    fun neverStarted(now: Long): Boolean = playingAt == 0L && now - openedAt > OPEN_TIMEOUT_MS

    val hasStarted get() = playingAt != 0L

    fun check(now: Long, pictures: Long, audio: Long, time: Long, shown: Boolean): Result {
        if (!counted) {
            // Counting starts now, not when the channel was requested (opening can take a while).
            counted = true
            remember(pictures, audio, time)
            pictureAt = now
            audioAt = now
            timeAt = now
            return Result(null, false)
        }
        if (pictures != lastPictures) {
            pictureAt = now
            if (pictures > 0) picturesSeen = true
        }
        if (audio != lastAudio) audioAt = now
        if (time != lastTime) timeAt = now
        remember(pictures, audio, time)
        val audioRuns = now - audioAt < AUDIO_RUNS_MS
        val early = now - playingAt < EARLY_MS
        val problem = when {
            hasVideo && !picturesSeen && audioRuns && now - playingAt > NO_PICTURE_MS -> Problem.NO_PICTURE
            picturesSeen && audioRuns && now - pictureAt > (if (early) EARLY_FROZEN_MS else FROZEN_MS) ->
                if (early) Problem.FROZEN_AT_START else Problem.FROZEN
            !shown && now - timeAt > EARLY_STUCK_MS && now - audioAt > EARLY_STUCK_MS -> Problem.STUCK_AT_START
            now - timeAt > STUCK_MS && now - audioAt > STUCK_MS -> Problem.STUCK
            else -> null
        }
        return Result(problem, problem == null && now - openedAt > HEALTHY_MS)
    }

    private fun remember(pictures: Long, audio: Long, time: Long) {
        lastPictures = pictures
        lastAudio = audio
        lastTime = time
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 8_000L
        const val AUDIO_RUNS_MS = 1_500L
        const val EARLY_MS = 8_000L
        const val NO_PICTURE_MS = 6_000L
        const val FROZEN_MS = 4_000L
        const val EARLY_FROZEN_MS = 2_000L
        const val EARLY_STUCK_MS = 3_000L
        const val STUCK_MS = 12_000L
        const val HEALTHY_MS = 30_000L
    }
}
