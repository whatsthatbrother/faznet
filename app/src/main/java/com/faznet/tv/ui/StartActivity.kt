package com.faznet.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.R
import com.faznet.tv.data.RemoteSync
import com.faznet.tv.data.Repository
import com.faznet.tv.data.Reports
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.IconAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The first screen (Apple TV style): Live TV, Movies and Series as three big cards. Nothing of a list is read here;
 * the channels load only when Live TV is chosen, movies and series only when theirs is. Most viewers only watch live
 * channels, and a stick then never spends memory on the other two.
 */
class StartActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private val state = StartState()

    class StartState {
        var playlistName by mutableStateOf("")
        var hasPlaylist by mutableStateOf(false)
        var xtream by mutableStateOf(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository.get(this)
        window.setBackgroundDrawable(null)

        // "Open the last channel on start" or "open Live TV straight away": into Live TV at once. The start screen
        // stays below it, so Back still leads to Movies and Series.
        if (savedInstanceState == null && repo.activePlaylist() != null && (repo.autoPlayLast || repo.startWithLive)) {
            openLive(autoPlay = repo.autoPlayLast)
        }
        setContent { FaznetTheme { StartScreen(state, ::openLive, ::openLibrary, ::openDevice, ::openSettings) } }

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                Reports.sendPendingCrash(this@StartActivity, repo)
                Reports.sendExits(this@StartActivity, repo)
                Reports.sendNewProblems(this@StartActivity, repo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Playlists uploaded on the website (MAC + PIN) appear here too, at most every 30 s.
        if (repo.serverUrl.isNotEmpty() && System.currentTimeMillis() - repo.lastSync > 30_000) {
            lifecycleScope.launch {
                val r = RemoteSync.sync(repo)
                if (r is RemoteSync.Result.Ok && r.changed) refresh()
            }
        }
    }

    private fun refresh() {
        val p = repo.activePlaylist()
        state.hasPlaylist = p != null
        state.playlistName = p?.name.orEmpty()
        state.xtream = XtreamAccount.from(p) != null
    }

    private fun openLive(autoPlay: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOPLAY, autoPlay))
    }

    private fun openLibrary(series: Boolean) {
        if (!state.hasPlaylist) return openPlaylists()
        if (!state.xtream) {
            Toast.makeText(this, R.string.start_xtream_only, Toast.LENGTH_LONG).show()
            return
        }
        LibraryActivity.start(this, series = series)
    }

    private fun openPlaylists() = startActivity(Intent(this, PlaylistsActivity::class.java))
    private fun openDevice() = startActivity(Intent(this, DeviceActivity::class.java))
    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))
}

@Composable
private fun StartScreen(
    state: StartActivity.StartState,
    live: () -> Unit,
    library: (Boolean) -> Unit,
    device: () -> Unit,
    settings: () -> Unit,
) {
    val background = remember { Brush.verticalGradient(0f to Color(0xFF2A2A2E), 0.5f to Color(0xFF0E0E10), 1f to Color.Black) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val narrow = maxWidth < 700.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 56.dp, vertical = if (narrow) 12.dp else 28.dp)) {
            // Top: name on the left, time and the round buttons on the right.
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Faznet", color = Fz.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (!narrow) Clock()
                IconAction(R.drawable.ic_device, stringResource(R.string.device_title), device)
                IconAction(R.drawable.ic_settings, stringResource(R.string.settings), settings)
            }

            // Only the three cards, in the middle (no title above them, no playlist pill below: Diar, 2026-11-09).
            Spacer(Modifier.weight(1f))

            val cards: @Composable (Modifier) -> Unit = { m ->
                HubCard(R.drawable.ic_tv, stringResource(R.string.start_live), stringResource(R.string.start_live_sub),
                    enabled = true, onClick = live, modifier = m.focusRequester(first))
                HubCard(R.drawable.ic_movie, stringResource(R.string.movies), stringResource(R.string.start_movies_sub),
                    enabled = state.xtream, onClick = { library(false) }, modifier = m)
                HubCard(R.drawable.ic_series, stringResource(R.string.series), stringResource(R.string.start_series_sub),
                    enabled = state.xtream, onClick = { library(true) }, modifier = m)
            }
            if (narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { cards(Modifier.fillMaxWidth().height(116.dp)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) { cards(Modifier.weight(1f).height(250.dp)) }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "made by d1@rf", color = Fz.dim.copy(alpha = 0.7f), fontSize = 13.sp, letterSpacing = 0.5.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
            )
        }
    }
}

/** One big section card: white with black text when focused (tvOS), quiet grey otherwise. */
@Composable
private fun HubCard(icon: Int, title: String, sub: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF1C1C1E),
        focusedColor = Fz.focusLight,
        focusedBrush = null,
        outline = false,
        focusScale = 1.07f,
    ) { focused ->
        val main = when { focused -> Fz.onLight; enabled -> Fz.text; else -> Fz.text.copy(alpha = 0.35f) }
        val dim = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim.copy(alpha = if (enabled) 1f else 0.5f)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(painterResource(icon), null, tint = if (focused) Fz.accent else main, modifier = Modifier.size(44.dp))
            Column {
                Text(title, color = main, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(sub, color = dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    val f = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Text(f.format(now), color = Fz.text.copy(alpha = 0.9f), fontSize = 20.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 14.dp))
}
