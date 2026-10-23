package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
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
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod_player)
        repo = Repository.get(this)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        playerView = findViewById(R.id.vodPlayerView)
        tvError = findViewById(R.id.vodError)
        findViewById<TextView>(R.id.vodTitle).text = intent.getStringExtra(EXTRA_TITLE) ?: ""
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            findViewById<View>(R.id.vodTitle).visibility = v
        })
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        val p = PlayerFactory.create(this, repo.useFfmpeg)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tvError.text = getString(R.string.err_stream, error.errorCodeName)
                tvError.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) tvError.visibility = View.GONE
                if (state == Player.STATE_ENDED) { clearPosition(); finish() }
            }
        })
        val ch = Channel(0, "", url, null, "", null, null)
        val type = StreamProbe.guess(url) ?: StreamType.PROGRESSIVE
        p.setMediaSource(PlayerFactory.mediaSource(this, ch, repo.userAgent, type))
        resumePosition(this, url).takeIf { it > 0 }?.let { p.seekTo(it) }
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
        handler.postDelayed(saveTick, 10_000)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
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

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)
        private fun key(url: String) = url.hashCode().toString(36) + "_" + url.length

        /** Saved position (ms) to continue from, 0 if none. */
        fun resumePosition(c: Context, url: String): Long = prefs(c).getLong(key(url), 0)

        fun start(c: Context, url: String, title: String) {
            c.startActivity(Intent(c, VodPlayerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title))
        }
    }
}
