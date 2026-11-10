package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.faznet.tv.player.TrackDialog
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.Repository
import com.faznet.tv.player.PlayerFactory
import com.faznet.tv.player.StreamProbe
import com.faznet.tv.player.StreamType

/**
 * Player for things with a beginning and an end: movies, series episodes and catch-up programmes.
 * Seek bar, ±10 s with Left/Right, and "continue where you left off".
 */
@OptIn(UnstableApi::class)
class VodPlayerActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private lateinit var playerView: PlayerView
    private lateinit var tvError: TextView
    private var player: ExoPlayer? = null
    private var url = ""
    private var title = ""
    private lateinit var titleView: TextView
    /** The episodes after this one, in order: they follow by themselves (series only; empty for a movie). */
    private var nextUrls = ArrayList<String>()
    private var nextTitles = ArrayList<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    /** prepare() after an error goes on from where the player stopped. */
    private val retry = Runnable { player?.let { it.prepare(); it.play() } }
    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTv()) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_vod_player)
        repo = Repository.get(this)
        val from = savedInstanceState ?: intent.extras
        url = from?.getString(EXTRA_URL) ?: run { finish(); return }
        title = from.getString(EXTRA_TITLE).orEmpty()
        nextUrls = from.getStringArrayList(EXTRA_NEXT_URLS) ?: ArrayList()
        nextTitles = from.getStringArrayList(EXTRA_NEXT_TITLES) ?: ArrayList()
        playerView = findViewById(R.id.vodPlayerView)
        tvError = findViewById(R.id.vodError)
        titleView = findViewById(R.id.vodTitle)
        titleView.text = title
        playerView.resizeMode = ASPECT_MODES[repo.vodAspectMode.coerceIn(0, ASPECT_MODES.size - 1)]
        addAspectButton()
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            findViewById<View>(R.id.vodTitle).visibility = v
        })
        hideSystemUi()
    }

    /**
     * Fit / stretch / zoom, as in Live TV: many films have black bars inside the picture itself (a cinema film in a 16:9
     * file), which only zoom takes away. A button among the player's own, beside the settings gear, so the remote
     * reaches it like the others; the choice is kept for every film.
     */
    private fun addAspectButton() {
        addBarButton(R.drawable.ic_aspect, R.string.aspect) { cycleAspect() }
        // Many films (.mkv) carry subtitles in many languages, and some a second sound language: the player's own
        // subtitle button is off by default, so there was no way to see or choose them.
        // The choice is remembered: the next film or episode starts with the same subtitle (and sound) language if it
        // has it.
        addBarButton(R.drawable.ic_tracks, R.string.tracks) {
            val p = player ?: return@addBarButton
            TrackDialog.show(this, p) { c ->
                when (c) {
                    is TrackDialog.Choice.Subtitles -> c.language?.let { repo.vodSubtitles = it }
                    TrackDialog.Choice.NoSubtitles -> repo.vodSubtitles = TrackDialog.OFF
                    is TrackDialog.Choice.Sound -> c.language?.let { repo.vodSound = it }
                }
                p.trackSelectionParameters = TrackDialog.withRemembered(p.trackSelectionParameters, repo.vodSubtitles, repo.vodSound)
            }
        }
    }

    /** A button among the player's own, before the settings gear, looking like them. */
    private fun addBarButton(icon: Int, label: Int, onClick: () -> Unit) {
        val gear = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings) ?: return
        val bar = gear.parent as? android.view.ViewGroup ?: return
        val button = android.widget.ImageButton(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(gear.layoutParams)
            background = gear.background?.constantState?.newDrawable()?.mutate()
            setPadding(gear.paddingLeft, gear.paddingTop, gear.paddingRight, gear.paddingBottom)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageResource(icon)
            contentDescription = getString(label)
            setOnClickListener { onClick() }
        }
        bar.addView(button, bar.indexOfChild(gear))
    }

    private fun cycleAspect() {
        val next = (repo.vodAspectMode + 1) % ASPECT_MODES.size
        repo.vodAspectMode = next
        playerView.resizeMode = ASPECT_MODES[next]
        Toast.makeText(this, getString(R.string.picture, getString(ASPECT_LABELS[next])), Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Several episodes in a row: after the app was restarted it goes on with the one playing, not the first.
        outState.putString(EXTRA_URL, url)
        outState.putString(EXTRA_TITLE, title)
        outState.putStringArrayList(EXTRA_NEXT_URLS, nextUrls)
        outState.putStringArrayList(EXTRA_NEXT_TITLES, nextTitles)
    }

    /** The internet came back after it was lost: go on at once if the player had given up. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // onAvailable also fires right after registering: only react to a network that comes back after a loss.
        @Volatile private var lost = false

        override fun onLost(network: Network) { lost = true }

        override fun onAvailable(network: Network) {
            if (!lost) return
            lost = false
            handler.post { if (player?.playerError != null) retryNow() }
        }
    }

    private fun retryNow() {
        retries = 0
        handler.removeCallbacks(retry)
        tvError.setText(R.string.reconnecting)
        retry.run()
    }

    /** Puts [url] into the player, at the place it was left last time. */
    private fun open(p: ExoPlayer) {
        val ch = Channel(0, "", url, null, "", null, null)
        val type = StreamProbe.guess(url) ?: StreamType.PROGRESSIVE
        p.setMediaSource(PlayerFactory.mediaSource(this, ch, repo.userAgent, type))
        resumePosition(this, url).takeIf { it > 0 }?.let { p.seekTo(it) }
        p.prepare()
    }

    /** An episode ended: the next one of the series starts by itself. @return false when there is none. */
    private fun playNext(): Boolean {
        val p = player ?: return false
        if (nextUrls.isEmpty()) return false
        url = nextUrls.removeAt(0)
        title = nextTitles.removeAtOrNull() ?: ""
        titleView.text = title
        retries = 0
        tvError.visibility = View.GONE
        Toast.makeText(this, getString(R.string.next_episode, title), Toast.LENGTH_LONG).show()
        open(p)
        p.play()
        return true
    }

    private fun ArrayList<String>.removeAtOrNull() = if (isEmpty()) null else removeAt(0)

    override fun onStart() {
        super.onStart()
        val p = PlayerFactory.create(this, repo.useFfmpeg)
        retries = 0
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback
            )
        }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // The network or the server failed for a moment: go on from the same place by itself, as the live
                // player does, instead of leaving the movie on an error until the viewer starts it again.
                if (error.errorCode in 2000..2999 && retries < MAX_RETRIES) {
                    retries++
                    tvError.setText(R.string.reconnecting)
                    tvError.visibility = View.VISIBLE
                    handler.removeCallbacks(retry)
                    handler.postDelayed(retry, 2_000L * retries)
                    return
                }
                tvError.text = getString(R.string.err_stream, error.errorCodeName)
                tvError.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { tvError.visibility = View.GONE; retries = 0 }
                if (state == Player.STATE_ENDED) {
                    clearPosition()
                    if (!playNext()) finish()
                }
            }
        })
        p.trackSelectionParameters = TrackDialog.withRemembered(p.trackSelectionParameters, repo.vodSubtitles, repo.vodSound)
        playerView.player = p
        player = p
        open(p)
        p.playWhenReady = true
        handler.postDelayed(saveTick, 10_000)
    }

    override fun onStop() {
        super.onStop()
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) }
        savePosition()
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        // The buffer memory is shared with the live player: give it back, as the live player does when it closes.
        PlayerFactory.freeBuffers()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun savePosition() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0 || pos < 30_000) return
        if (dur - pos < 60_000) clearPosition()
        else prefs(this).edit().putLong(key(url), pos).apply()
    }

    private fun clearPosition() = prefs(this).edit().remove(key(url)).apply()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        // The player gave up (no internet for a while): OK tries again from the same place.
        if (p != null && p.playerError != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            retryNow()
            return true
        }
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); playerView.showController(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    p.seekTo(p.currentPosition + 10_000); playerView.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        private const val EXTRA_NEXT_URLS = "next_urls"
        private const val EXTRA_NEXT_TITLES = "next_titles"
        private const val MAX_RETRIES = 5
        private val ASPECT_MODES = intArrayOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        )
        private val ASPECT_LABELS = intArrayOf(R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_zoom)

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)
        private fun key(url: String) = url.hashCode().toString(36) + "_" + url.length

        /** Saved position (ms) to continue from, 0 if none. */
        fun resumePosition(c: Context, url: String): Long = prefs(c).getLong(key(url), 0)

        /** [next]: what follows by itself when this ends (the next episodes of a series), as address → title. */
        fun start(c: Context, url: String, title: String, next: List<Pair<String, String>> = emptyList()) {
            c.startActivity(
                Intent(c, VodPlayerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
                    .putStringArrayListExtra(EXTRA_NEXT_URLS, ArrayList(next.map { it.first }))
                    .putStringArrayListExtra(EXTRA_NEXT_TITLES, ArrayList(next.map { it.second }))
            )
        }
    }
}
