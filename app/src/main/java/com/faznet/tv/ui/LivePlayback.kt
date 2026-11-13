package com.faznet.tv.ui

import android.os.Handler
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import com.faznet.tv.Perf
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelMemory
import com.faznet.tv.data.ProblemLog
import com.faznet.tv.data.Repository
import com.faznet.tv.data.safeMessage
import com.faznet.tv.player.ExoWatch
import com.faznet.tv.player.OpeningErrorPolicy
import com.faznet.tv.player.PlayerFactory
import com.faznet.tv.player.ProblemOutcome
import com.faznet.tv.player.ProgressWatch
import com.faznet.tv.player.RefusalWatch
import com.faznet.tv.player.StreamProbe
import com.faznet.tv.player.StreamType
import com.faznet.tv.player.StuckDecoder
import com.faznet.tv.player.Throughput
import com.faznet.tv.player.loadErrorKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Plays one live channel and keeps it playing: a new player for every opening, the clean start (picture and sound stay
 * hidden until the picture runs), and every way back from a freeze, a stall, an error or a refusal. The decisions
 * themselves are in player/PlaybackWatch.kt; this acts on them. Which channel plays, the lists and everything on top of
 * the picture stay with [PlayerActivity].
 *
 * [handler] is the screen's own: leaving the screen clears it, and with it everything waiting here.
 * [zapping]: the viewer is still choosing a channel, so nothing may be re-opened. [onBadPeriod]: several channels
 * froze at their start, the device's decoder is in a bad period.
 */
/** The two things [LivePlayback] puts over the picture: the loading circle and a message. Compose draws them. */
@androidx.compose.runtime.Stable
internal class PlayerMessages {
    var spinner by androidx.compose.runtime.mutableStateOf(false)
    var errorText by androidx.compose.runtime.mutableStateOf("")
    var errorShown by androidx.compose.runtime.mutableStateOf(false)
}

@OptIn(UnstableApi::class)
internal class LivePlayback(
    private val activity: AppCompatActivity,
    private val repo: Repository,
    private val handler: Handler,
    private val playerView: PlayerView,
    private val ui: PlayerMessages,
    private val zapping: () -> Boolean,
    private val onBadPeriod: () -> Unit,
) {
    /** The channel being played. */
    private var channel: Channel? = null

    private var stallCount = 0
    private var underruns = 0
    private var bufferingSince = 0L
    private var bufferingTotalMs = 0L
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var lastWarning = ""

    /** The player of the channel on the screen; a new one for every opening. */
    var player: ExoPlayer? = null
        private set

    private var forceHls = false
    private var retries = 0

    private val exoWatch = ExoWatch(com.faznet.tv.BuildConfig.PLAYER_TRACE_WATCH)
    /** Written on the player's thread: the time of the last picture and the distance between the last two. */
    @Volatile private var lastFramePtsUs = -1L
    @Volatile private var frameIntervalUs = 0L
    private var recoveries = 0
    private val refusals = RefusalWatch()
    private val stuckDecoder = StuckDecoder()
    /** Read on the loading thread: no picture has come yet from this opening, see [OpeningErrorPolicy]. */
    @Volatile private var opening = true
    private val openingErrors = OpeningErrorPolicy { opening }
    private val problems = ProblemOutcome { channel, what, at -> ProblemLog.add(activity, channel, what, at) }
    private var lastLoadError = ""
    private var readyAt = 0L

    private var totalRecoveries = 0

    private val retry = Runnable { startCurrent() }

    /** If a live stream stays stuck in buffering, re-open it at the live edge instead of freezing. */
    private val stallWatchdog = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            if (retries < MAX_RETRIES) {
                retries++
                problem("buffering for ${STALL_TIMEOUT_MS / 1000} s")
                showReconnecting()
                startCurrent()
            } else {
                ui.errorText = activity.getString(R.string.err_stream, "TIMEOUT")
                ui.spinner = false
                ui.errorShown = true
            }
        }
    }

    /**
     * Some TV decoders (Amlogic on the Mi TV Stick) start without a clean key frame, or wedge after a while:
     * the sound keeps playing but the picture never appears or stops. Re-opening the channel fixes it, so do
     * that automatically: no first frame shortly after READY, or no new frame while the clock still moves,
     * or nothing moving at all while READY.
     */
    private val frameWatch = object : Runnable {
        override fun run() {
            checkFrames()
            // Often while the channel is still behind the loading circle: it appears the moment it runs.
            handler.postDelayed(this, if (revealed) 500 else 100)
        }
    }

    private var playToken = 0
    private var probeJob: Job? = null

    /**
     * Software video decoder for channels that kept stopping the hardware one (any size, see [StuckDecoder]), and for SD
     * channels that froze before (or always for SD, if chosen in Settings).
     */
    private fun wantsSoftwareVideo(ch: Channel?): Boolean {
        ch ?: return false
        if (ch.url in softwareFailed) return false
        val info = ChannelMemory.get(activity, ch.url) ?: return false
        // Only SD: the Mi TV Stick's processor shows about 5 pictures a second of a 1080 channel in software and falls
        // into buffering every half minute (4.3.1 chose it for 1080 channels too; those go back to the hardware one).
        val sd = info.height in 1..SOFTWARE_MAX_HEIGHT
        return sd && (info.software || repo.softwareSd || info.fixes > 0)
    }

    private var softwareVideo = false
    private val softwareFailed get() = PlayerFactory.softwareFailed

    // ---- clean start: picture and sound stay hidden until the picture runs smoothly ----
    private var revealed = true
    private var hiddenSince = 0L

    private fun hideUntilStable() {
        if (revealed) hiddenSince = SystemClock.elapsedRealtime()
        revealed = false
        playerView.alpha = 0f
        player?.volume = 0f
        ui.spinner = true
    }

    // ---- how long a channel took to appear: logged when it does, tools/zap-test.ps1 reads it ----
    private var openPressAt = 0L
    private var openAt = 0L
    private var openPreparedAt = 0L
    private var openReadyAt = 0L
    private var openFrameAt = 0L
    private var openCount = 0

    private fun logOpen() {
        val ch = channel ?: return
        val now = Perf.now()
        val press = if (openPressAt != 0L) openPressAt else openAt
        fun after(t: Long) = if (t == 0L) -1 else t - openAt
        Perf.log(
            "open total=${now - press} wait=${openAt - press} prepare=${after(openPreparedAt)} ready=${after(openReadyAt)} " +
                "frame=${after(openFrameAt)} show=${now - openAt} reopens=${(openCount - 1).coerceAtLeast(0)} " +
                "forced=${now - hiddenSince > MAX_HIDDEN_MS} ch=${ch.number} ${ch.name}"
        )
        openPressAt = 0L
        openAt = 0L
    }

    private fun reveal() {
        if (revealed) return
        logOpen()
        revealed = true
        playerView.alpha = 1f
        player?.volume = 1f
        ui.spinner = false
        // Shown because it waited too long: an error on the screen has to stay readable.
        if (player?.playerError == null && !refusals.told) ui.errorShown = false
    }

    private var trace: com.faznet.tv.player.PlayerTrace? = null

    private fun initPlayer() {
        softwareVideo = wantsSoftwareVideo(channel)
        val p = PlayerFactory.create(activity, repo.useFfmpeg, softwareVideo = softwareVideo)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        if (openReadyAt == 0L) openReadyAt = Perf.now()
                        ui.errorShown = false
                        retries = 0
                        handler.removeCallbacks(stallWatchdog)
                        refusals.reset()
                        opening = false
                        readyAt = SystemClock.elapsedRealtime()
                        exoWatch.onReady(readyAt)
                    }
                    Player.STATE_BUFFERING -> {
                        stallCount++
                        exoWatch.onNotReady()
                        handler.removeCallbacks(stallWatchdog)
                        handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
                    }
                    Player.STATE_ENDED -> {
                        // A live stream should never "end": the server dropped us, reconnect. With a growing pause
                        // and a limit: a channel that ends at once used to open a new connection every second
                        // forever, which is what trips the provider's connection limit (HTTP 403). After the
                        // limit hardWatch keeps re-opening it, 15 → 30 → 60 → 120 s apart.
                        handler.removeCallbacks(retry)
                        if (retries < MAX_RETRIES) {
                            retries++
                            problem("stream ended after ${(SystemClock.elapsedRealtime() - readyAt) / 1000} s")
                            handler.postDelayed(retry, (1000L * retries).coerceAtMost(5000L))
                        } else {
                            ui.spinner = false
                            ui.errorText = activity.getString(R.string.err_stream, "ENDED")
                            ui.errorShown = true
                            channel?.let { ProblemLog.add(activity, it.name, "stream ended $MAX_RETRIES times") }
                        }
                    }
                    else -> exoWatch.onNotReady()
                }
            }

            override fun onRenderedFirstFrame() {
                if (openFrameAt == 0L) openFrameAt = Perf.now()
                exoWatch.onFirstFrame(SystemClock.elapsedRealtime())
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(t: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                underruns++
            }
            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
                Perf.log("video decoder $decoderName software=$softwareVideo ch=${channel?.number}")
            }
            override fun onVideoInputFormatChanged(t: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                channel?.let { ChannelMemory.rememberHeight(activity, it.url, format.height) }
            }
            override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }
            override fun onLoadError(t: AnalyticsListener.EventTime, l: LoadEventInfo, m: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                lastWarning = "load: ${error.javaClass.simpleName} ${error.safeMessage()}".take(60)
                lastLoadError = loadErrorKind(error)
            }
            override fun onAudioSinkError(t: AnalyticsListener.EventTime, audioSinkError: Exception) {
                lastWarning = "audio: ${audioSinkError.javaClass.simpleName}".take(60)
            }
            override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
                val now = SystemClock.elapsedRealtime()
                if (state == Player.STATE_BUFFERING) bufferingSince = now
                else if (bufferingSince != 0L) { bufferingTotalMs += now - bufferingSince; bufferingSince = 0L }
            }
        })
        if (com.faznet.tv.BuildConfig.DEBUG) p.addAnalyticsListener(EventLogger("FaznetPlayer"))
        if (com.faznet.tv.BuildConfig.PLAYER_TRACE) {
            trace?.stop()
            trace = com.faznet.tv.player.PlayerTrace(p, handler, channel?.let { "${it.number} ${it.name}" } ?: "")
        }
        p.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (lastFramePtsUs >= 0) frameIntervalUs = presentationTimeUs - lastFramePtsUs
            lastFramePtsUs = presentationTimeUs
            if (com.faznet.tv.BuildConfig.PLAYER_TRACE) trace?.frame(presentationTimeUs)
        }
        // Live TV has no pause button, so nothing may pause it. Android sends "audio becoming noisy" whenever the
        // HDMI signal drops for a moment (TV switched input or off and on, CEC standby, a loose cable); ExoPlayer
        // then paused, and since the watchdogs only look at a player that is meant to play, the channel stayed
        // frozen until the user left it (Mi TV Stick, 31.10: HDMI gone for 0.6 s → paused for good).
        p.setHandleAudioBecomingNoisy(false)
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady || player !== p) return
                // Paused by the system anyway (audio focus, a remote, …): open the channel again at the live edge,
                // since the picture in the buffer is old by now.
                channel?.let { ProblemLog.add(activity, it.name, "paused by the system ($reason) → re-open") }
                handler.post { if (player === p) { freshPlayer(); startCurrent() } }
            }
        })
        p.playWhenReady = true
        playerView.player = p
        player = p
    }

    private fun releasePlayer() {
        probeJob?.cancel()
        player?.let {
            playerView.player = null
            it.release()
        }
        player = null
    }

    private fun freshPlayer() {
        releasePlayer()
        videoDecoder = ""
        audioDecoder = ""
        initPlayer()
    }

    private fun startCurrent() {
        refused = false
        opening = true
        // A re-open long after the channel appeared is timed by itself.
        if (openAt == 0L) openAt = Perf.now()
        openCount++
        openPreparedAt = 0L
        openReadyAt = 0L
        openFrameAt = 0L
        val p = player ?: return
        val ch = channel ?: return
        val token = ++playToken
        ui.errorShown = false
        exoWatch.onOpen()
        lastFramePtsUs = -1L
        frameIntervalUs = 0L
        hideUntilStable()
        // A quiet try after the viewer was told: the message stays until the channel really plays.
        if (refusals.told) {
            ui.errorShown = true
            ui.spinner = false
        }
        val ua = ch.userAgent ?: repo.userAgent
        probeJob?.cancel()
        // stop() disables the renderers, so the video decoder is released and re-created for the new stream:
        // a decoder that wedged on the previous channel cannot carry over.
        p.stop()
        p.clearMediaItems()
        probeJob = activity.lifecycleScope.launch {
            val type = if (forceHls) StreamType.HLS else StreamProbe.detect(ch.url, ua, ch.referrer)
            if (token != playToken || player !== p) return@launch
            p.setMediaSource(PlayerFactory.mediaSource(activity, ch, ua, type, openingErrors))
            openPreparedAt = Perf.now()
            p.prepare()
            p.play()
        }
    }

    private val progressWatch = ProgressWatch()
    /** The server said no, for good as far as this opening goes: nothing re-opens it until the viewer does something. */
    private var refused = false

    /** @return true if it re-opened the channel. */
    private fun hardWatch(pos: Long, frames: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        // Retries after a refusal are under way: starting over here would only make more connections.
        val waited = progressWatch.check(now, pos, frames, revealed, zapping() || refusals.active) ?: return false
        channel ?: return false
        problem("no progress for ${waited / 1000} s")
        lastWarning = "watchdog re-open"
        retries = 0
        recoveries = 0
        refusals.reset()
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallWatchdog)
        showReconnecting()
        freshPlayer()
        startCurrent()
        return true
    }

    /** A line for the problem log with what the player knows right now, written once it is known how it ended. */
    private fun problem(what: String, extra: String = "") {
        val ch = channel ?: return
        val facts = listOf(extra, player?.let { facts(it) }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
        problems.problem(SystemClock.elapsedRealtime(), System.currentTimeMillis(), ch.name, "$what [$facts]")
    }

    /** No addresses, playlists or error texts in here: this goes to the developer with the problem log. */
    private fun facts(p: ExoPlayer): String {
        val v = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val a = p.audioDecoderCounters?.also { it.ensureUpdated() }
        val state = when (p.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            else -> "ended"
        }
        return buildString {
            append("state=$state load=${p.isLoading} buf=${p.totalBufferedDuration} net=${Throughput.msSinceLastBytes()}")
            if (v != null) append(" in=${v.queuedInputBufferCount} out=${v.renderedOutputBufferCount} drop=${v.droppedBufferCount} skip=${v.skippedOutputBufferCount}")
            if (a != null) append(" audio=${a.queuedInputBufferCount}/${a.renderedOutputBufferCount}")
            append(" under=$underruns stalls=$stallCount")
            p.videoFormat?.let { append(" ${it.width}x${it.height}@${it.frameRate.toInt()} ${it.sampleMimeType?.substringAfter('/')}") }
            if (videoDecoder.isNotEmpty()) append(" ${videoDecoder.take(30)}")
            if (lastLoadError.isNotEmpty()) append(" err=$lastLoadError")
        }
    }

    private fun checkFrames() {
        if (refused) return
        val p = player ?: return
        if (p.playWhenReady) {
            val frames = p.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount.toLong() } ?: 0L
            if (hardWatch(p.currentPosition, frames)) return
        }
        // Never keep a channel hidden for long: if it can't settle, show it anyway.
        val now = SystemClock.elapsedRealtime()
        if (!revealed && now - hiddenSince > MAX_HIDDEN_MS) reveal()
        if (p.playbackState != Player.STATE_READY || !p.playWhenReady ||
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || !exoWatch.isReady
        ) return
        val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val decoderActivity = if (counters == null) 0L else counters.queuedInputBufferCount.toLong() + rendered +
            counters.skippedOutputBufferCount + counters.droppedBufferCount + counters.droppedInputBufferCount
        if (softwareTooSlow(counters)) return
        val result = exoWatch.check(
            ExoWatch.Sample(
                now, p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO), rendered, decoderActivity, p.currentPosition,
                lastFramePtsUs, frameIntervalUs,
            )
        )
        if (result.healthy) {
            recoveries = 0
            stuckDecoder.reset()
        }
        if (result.canShow) {
            problems.back(now)
            if (!revealed) reveal()
        }
        val problem = result.problem ?: return
        val reason = problem.reason
        // The numbers say what really happened; they reach the developer with the problem log.
        val detail = "wedged=${problem.wedged} behind=${problem.behindMs} frame=${problem.sinceFrameMs} clock=${problem.sinceClockMs} decoder=${problem.sinceDecoderMs}"
        Perf.log("freeze $reason $detail in=${counters?.queuedInputBufferCount} out=$rendered buf=${p.totalBufferedDuration} ch=${channel?.number}")
        // Enough quick fixes on this channel: from here the last-resort watch opens it again, less often.
        if (recoveries >= MAX_RECOVERIES) return
        // No picture at all in strict key-frame mode: this stream probably never sends IDR frames.
        if (problem.noPicture) channel?.let { PlayerFactory.looseKeyframes.add(it.url) }
        recoveries++
        totalRecoveries++
        lastWarning = "auto-fix: $reason"
        channel?.let { ch ->
            // One freeze right at the start says nothing about the channel (the next opening usually plays), so it does
            // not count as a fix; the same decoder freezing again at once does, see StuckDecoder.
            if (!problem.atStart) ChannelMemory.addFix(activity, ch.url)
            p.videoFormat?.let { ChannelMemory.rememberHeight(activity, ch.url, it.height) }
            val decoderFroze = problem.wedged || reason.startsWith("picture frozen")
            // The other decoder from the next opening on, which is right below, and on every later one. Which one froze
            // is the one that really ran, not the one asked for.
            val software = PlayerFactory.isSoftwareDecoder(videoDecoder)
            // Software only where it can keep up: a bigger picture stays with the hardware decoder and its re-opens.
            val small = (p.videoFormat?.height ?: 0) in 1..SOFTWARE_MAX_HEIGHT
            val change = decoderFroze && (software || small) && stuckDecoder.froze()
            if (change) ChannelMemory.useSoftware(activity, ch.url, !software)
            val switched = when {
                !change -> ""
                software -> " → hardware decoder"
                else -> " → software decoder"
            }
            problem(reason + (if (software) " (software)" else "") + switched, detail)
            if (decoderFroze && problem.atStart && !software && PlayerFactory.badPeriod.froze(ch.url, now)) onBadPeriod()
        }
        showReconnecting()
        freshPlayer()
        startCurrent()
    }

    /**
     * The software decoder drops more pictures than it shows: it can't keep up with this channel on this device. Back to
     * the hardware one, for good, and at once. @return true if it re-opened the channel.
     */
    private fun softwareTooSlow(counters: androidx.media3.exoplayer.DecoderCounters?): Boolean {
        counters ?: return false
        if (!softwareVideo || !PlayerFactory.isSoftwareDecoder(videoDecoder)) return false
        val shown = counters.renderedOutputBufferCount
        val dropped = counters.droppedBufferCount + counters.droppedInputBufferCount
        if (shown + dropped < SLOW_CHECK_FRAMES || dropped <= shown) return false
        val ch = channel ?: return false
        softwareFailed.add(ch.url)
        ChannelMemory.useSoftware(activity, ch.url, false)
        problem("software decoder too slow (shown $shown, dropped $dropped) → hardware decoder")
        freshPlayer()
        startCurrent()
        return true
    }

    private fun showReconnecting() {
        // While a channel is still opening behind the loading circle, a quiet retry needs no message.
        if (!revealed) return
        ui.errorText = activity.getString(R.string.reconnecting)
        ui.errorShown = true
    }

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        handler.removeCallbacks(stallWatchdog)
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> if (!forceHls) {
                // Extension-less URLs are often HLS: retry as HLS once and remember it
                forceHls = true
                channel?.let { StreamProbe.remember(it.url, StreamType.HLS) }
                startCurrent()
                return
            }
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
        }
        // The server refused the channel (not in the package, offline, or the account's connection limit while
        // zapping fast): say so soon, and try a little longer for the old connection to close.
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (http != null && http in 400..499) {
            val step = refusals.onRefused()
            if (step.tell) {
                reveal()
                ui.spinner = false
                ui.errorText = activity.getString(R.string.err_refused, http)
                ui.errorShown = true
                channel?.let { problems.problem(SystemClock.elapsedRealtime(), System.currentTimeMillis(), it.name, "refused by server (HTTP $http)") }
            } else if (!refusals.told) {
                showReconnecting()
            }
            handler.removeCallbacks(retry)
            if (step.retryInMs != null) {
                handler.postDelayed(retry, step.retryInMs)
            } else {
                // Leave it: every further try is one more connection on an account that has a limit.
                refused = true
                reveal()
            }
            return
        }
        // The software decoder chosen for this channel can't play it (too big for it, or it does not start): back to the
        // hardware one, for good, and at once.
        if (softwareVideo && error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            channel?.let { ch ->
                softwareFailed.add(ch.url)
                ChannelMemory.useSoftware(activity, ch.url, false)
                problem("software decoder failed (${error.errorCodeName}) → hardware decoder")
            }
            freshPlayer()
            startCurrent()
            return
        }
        val transient = error.errorCode in 1000..2999 || error.errorCode in 4000..4999
        if (transient && retries < MAX_RETRIES) {
            retries++
            lastLoadError = loadErrorKind(error)
            problem("network error")
            showReconnecting()
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, (1500L * retries).coerceAtMost(6000L))
            return
        }
        ui.errorText = activity.getString(R.string.err_stream, error.errorCodeName)
        ui.spinner = false
        channel?.let { ProblemLog.add(activity, it.name, "error ${error.errorCodeName}") }
        ui.errorShown = true
    }

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; else -> "ENDED"
    }

    // ---------- what the screen asks for ----------

    /** Opens [ch] from nothing: every count and every fix of the channel before is forgotten. */
    fun open(ch: Channel) {
        problems.left(SystemClock.elapsedRealtime())
        openAt = Perf.now()
        openCount = 0
        channel = ch
        forceHls = false
        retries = 0
        refusals.reset()
        lastLoadError = ""
        recoveries = 0
        stuckDecoder.reset()
        progressWatch.reset(SystemClock.elapsedRealtime())
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallWatchdog)
        stallCount = 0
        underruns = 0
        bufferingTotalMs = 0
        bufferingSince = 0
        lastWarning = ""
        // A brand-new player (and video decoder) for every channel: re-using the decoder across channels is what
        // left the Mi TV Stick with sound but a frozen picture; re-opening always fixed it, so always do that.
        freshPlayer()
        startCurrent()
    }

    /** A key press that will open a channel: the time to the picture is counted from here. */
    fun pressed() {
        openPressAt = Perf.now()
    }

    /** The viewer is zapping on: the old stream stops at once, so the server sees a single connection. */
    fun stopForZap() {
        handler.removeCallbacks(stallWatchdog)
        handler.removeCallbacks(retry)
        probeJob?.cancel()
        player?.stop()
    }

    /** The screen is shown: look at the picture from now on. */
    fun watch() {
        handler.removeCallbacks(frameWatch)
        handler.postDelayed(frameWatch, 1000)
    }

    /** The screen is left. */
    fun close() {
        problems.left(SystemClock.elapsedRealtime())
        releasePlayer()
    }

    /** Wi-Fi/network came back: re-open a channel that was stuck or failed instead of waiting for the watchdog. */
    fun networkBack() {
        val p = player ?: return
        if (!zapping() && (p.playerError != null || p.playbackState == Player.STATE_BUFFERING ||
                p.playbackState == Player.STATE_IDLE || ui.errorShown)
        ) {
            retries = 0
            refusals.reset()
            freshPlayer()
            startCurrent()
        }
    }

    /** The numbers of the stats overlay, or null while there is no player. */
    fun statsText(): String? {
        val p = player ?: return null
        val v = p.videoFormat
        val a = p.audioFormat
        val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val dropped = counters?.droppedBufferCount ?: 0
        val frames = counters?.renderedOutputBufferCount ?: 0
        return buildString {
            append("Buffer  %.1f s\n".format(p.totalBufferedDuration / 1000.0))
            append("Net     %.2f Mbps\n".format(Throughput.sampleMbps()))
            if (v != null) append("Video   ${v.width}x${v.height} ${v.sampleMimeType?.substringAfter('/') ?: ""}\n")
            if (a != null) append("Audio   ${a.sampleMimeType?.substringAfter('/') ?: ""} ${a.channelCount}ch ${a.sampleRate}Hz\n")
            val audioTracks = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audioTracks.isNotEmpty()) {
                append("ATracks ")
                append(audioTracks.joinToString(" ") { g ->
                    val f = g.getTrackFormat(0)
                    val mark = if (g.isSelected) "*" else ""
                    "$mark${f.sampleMimeType?.substringAfter('/') ?: "?"}${f.language?.let { "/$it" } ?: ""}"
                })
                append("\n")
            }
            val keyMode = if (channel?.url?.let { it in PlayerFactory.looseKeyframes } == true) "loose" else "IDR"
            append("Frames  $frames   Drops $dropped   Fixes $totalRecoveries   Start $keyMode\n")
            if (counters != null) append("Late    skip ${counters.skippedOutputBufferCount}  toKey ${counters.droppedToKeyframeCount}  inDrop ${counters.droppedInputBufferCount}  maxRun ${counters.maxConsecutiveDroppedBufferCount}\n")
            append("Stalls  ${(stallCount - 1).coerceAtLeast(0)}   Underruns $underruns\n")
            val buffering = bufferingTotalMs + if (bufferingSince != 0L) SystemClock.elapsedRealtime() - bufferingSince else 0L
            append("Waiting %.1f s   ${stateName(p.playbackState)}\n".format(buffering / 1000.0))
            if (videoDecoder.isNotEmpty()) append("VDec    ${videoDecoder.take(34)}${if (softwareVideo) " (SW)" else ""}\n")
            if (audioDecoder.isNotEmpty()) append("ADec    ${audioDecoder.take(34)}\n")
            val rt = Runtime.getRuntime()
            append("Memory  ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB")
            if (PlayerFactory.isLowMemory(activity)) append(" (low)")
            if (lastWarning.isNotEmpty()) append("\nWarn    $lastWarning")
        }
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 8_000L
        const val MAX_RETRIES = 5
        const val MAX_HIDDEN_MS = 9_000L
        const val MAX_RECOVERIES = 4
        /** The tallest picture the software video decoder gets (SD). */
        const val SOFTWARE_MAX_HEIGHT = 576
        /** Pictures (shown and dropped) before the software decoder's speed is judged: a few seconds. */
        const val SLOW_CHECK_FRAMES = 100
    }
}
