package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XEpisode
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.channelLogo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One series (Apple TV style): the poster and the story on the left, the seasons as tabs and their episodes on the
 * right. An episode started before shows where it stopped. Replaces the plain list in a dialog.
 */
class SeriesActivity : AppCompatActivity() {

    private lateinit var account: XtreamAccount
    private var episodes by mutableStateOf<List<XEpisode>?>(null)
    /** Increased when coming back from the player: the "continue at" times are read again. */
    private val resumeVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = XtreamAccount.from(Repository.get(this).activePlaylist()) ?: run { finish(); return }
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val cover = intent.getStringExtra(EXTRA_COVER)
        val plot = intent.getStringExtra(EXTRA_PLOT).orEmpty()
        val rating = intent.getStringExtra(EXTRA_RATING).orEmpty()
        window.setBackgroundDrawable(null)
        setContent {
            FaznetTheme {
                Screen(name, cover, plot, rating, episodes, resumeVersion.intValue, ::play)
            }
        }
        seriesId = id
        load()
    }

    private var seriesId = ""
    /** The server could not be reached: the screen says so, and OK asks again. */
    private var failed by mutableStateOf(false)

    private fun load() {
        failed = false
        episodes = null
        lifecycleScope.launch {
            val found = XtreamApi.episodes(account, seriesId)
            failed = found == null
            episodes = found.orEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeVersion.intValue++
    }

    private fun url(e: XEpisode) = XtreamApi.episodeUrl(account, e)

    private fun play(e: XEpisode) {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        fun title(x: XEpisode) = "$name · S${x.season} E${x.number}"
        // The episodes after it follow by themselves, across seasons, in the order the screen shows them.
        val all = episodes.orEmpty()
        // At most a few hundred: they travel inside the Intent, which has a size limit.
        val after = all.drop(all.indexOf(e) + 1).take(MAX_QUEUE).map { url(it) to title(it) }
        VodPlayerActivity.start(this, url(e), title(e), after)
    }

    @Composable
    private fun Screen(
        name: String, cover: String?, plot: String, rating: String,
        eps: List<XEpisode>?, resume: Int, onPlay: (XEpisode) -> Unit,
    ) {
        val tvIcon = painterResource(R.drawable.ic_series)
        val seasons = remember(eps) { eps?.map { it.season }?.distinct()?.sorted().orEmpty() }
        var season by remember(seasons) { mutableStateOf(seasons.firstOrNull() ?: 0) }
        val shown = remember(eps, season) { eps?.filter { it.season == season }.orEmpty() }
        val firstEpisode = remember { FocusRequester() }
        val episodesPart: @Composable ColumnScope.() -> Unit = {
            Episodes(eps, seasons, season, { season = it }, shown, resume, firstEpisode, onPlay)
        }

        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(0f to Color(0xFF2A2A2E), 0.5f to Color(0xFF0E0E10), 1f to Color.Black))
        ) {
            // A phone held upright: 300 dp of poster and story beside the episodes left them no room at all (360 dp wide
            // screen, 96 dp of padding), so the episodes could not be seen. There the story goes above them.
            if (maxWidth < 600.dp) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row {
                        Box(
                            Modifier.size(width = 96.dp, height = 140.dp).clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1C1C1E)).channelLogo(cover?.takeIf { it.isNotBlank() }, tvIcon)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Fz.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
                            if (rating.isNotEmpty() && rating != "0") {
                                Spacer(Modifier.height(4.dp))
                                Text("★ $rating", color = Color(0xFFFFD60A), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            if (plot.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(plot, color = Fz.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.weight(1f).fillMaxWidth(), content = episodesPart)
                }
            } else {
                Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
                    // Left: poster, title, rating, story
                    Column(Modifier.width(300.dp).fillMaxHeight()) {
                        Box(
                            Modifier.size(width = 220.dp, height = 320.dp).clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1C1C1E)).channelLogo(cover?.takeIf { it.isNotBlank() }, tvIcon)
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(name, color = Fz.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, lineHeight = 30.sp)
                        if (rating.isNotEmpty() && rating != "0") {
                            Spacer(Modifier.height(6.dp))
                            Text("★ $rating", color = Color(0xFFFFD60A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        if (plot.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(plot, color = Fz.dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(36.dp))
                    // Right: seasons and episodes
                    Column(Modifier.weight(1f).fillMaxHeight(), content = episodesPart)
                }
            }
        }
    }

    @Composable
    private fun Episodes(
        eps: List<XEpisode>?, seasons: List<Int>, season: Int, onSeason: (Int) -> Unit, shown: List<XEpisode>,
        resume: Int, firstEpisode: FocusRequester, onPlay: (XEpisode) -> Unit,
    ) {
        when {
            eps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(44.dp))
            }
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FocusBox(
                    onClick = ::load, modifier = Modifier.focusRequester(firstEpisode), shape = RoundedCornerShape(14.dp),
                    color = Fz.surface, focusedColor = Fz.focusLight, focusedBrush = null, outline = false, focusScale = 1.04f,
                ) { focused ->
                    Text(stringResource(R.string.err_library), color = if (focused) Fz.onLight else Fz.text, fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
                }
                LaunchedEffect(Unit) { delay(80); runCatching { firstEpisode.requestFocus() } }
            }
            eps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_items), color = Fz.dim, fontSize = 16.sp)
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(seasons) { s ->
                            SeasonTab(stringResource(R.string.season_n, s), selected = s == season,
                                onFocus = { onSeason(s) }, onClick = { onSeason(s); runCatching { firstEpisode.requestFocus() } })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    Text(stringResource(R.string.season_n, season), color = Fz.text, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                    // No key from the server's episode ids: a panel that repeats one, or sends none, would close the app
                    // ("key was already used").
                    items(shown) { e ->
                        val at = remember(e.id, resume) { VodPlayerActivity.resumePosition(this@SeriesActivity, url(e)) }
                        EpisodeRow(e, at, if (e === shown.firstOrNull()) firstEpisode else null) { onPlay(e) }
                    }
                }
                LaunchedEffect(eps) { delay(80); runCatching { firstEpisode.requestFocus() } }
            }
        }
    }

    @Composable
    private fun SeasonTab(text: String, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
        FocusBox(
            onClick = onClick, onFocus = { if (it) onFocus() }, selected = selected,
            modifier = Modifier.height(42.dp), shape = RoundedCornerShape(21.dp),
            color = Color.Transparent, focusedColor = Fz.focusLight, selectedColor = Color(0x2EFFFFFF),
            focusedBrush = null, outline = false, focusScale = 1.06f,
        ) { focused ->
            Text(text, color = if (focused) Fz.onLight else if (selected) Fz.text else Fz.text.copy(alpha = 0.7f),
                fontSize = 15.sp, fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
    }

    @Composable
    private fun EpisodeRow(e: XEpisode, resumeMs: Long, requester: FocusRequester?, onClick: () -> Unit) {
        FocusBox(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
            shape = RoundedCornerShape(14.dp), color = Fz.surface, focusedColor = Fz.focusLight,
            focusedBrush = null, outline = false, focusScale = 1.02f,
        ) { focused ->
            val main = if (focused) Fz.onLight else Fz.text
            val dim = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${e.number}", color = dim, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifEmpty { stringResource(R.string.episode_n, e.number) }, color = main, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.plot.isNotEmpty()) Text(e.plot, color = dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (resumeMs > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.resume_at, fmt(resumeMs)), color = if (focused) Fz.accent else Color(0xFF409CFF),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(10.dp))
                Icon(painterResource(R.drawable.ic_play_small), null, tint = dim, modifier = Modifier.size(16.dp))
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_COVER = "cover"
        private const val EXTRA_PLOT = "plot"
        private const val EXTRA_RATING = "rating"
        private const val MAX_QUEUE = 300

        fun start(c: Context, id: String, name: String, cover: String?, plot: String, rating: String) =
            c.startActivity(
                Intent(c, SeriesActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_COVER, cover).putExtra(EXTRA_PLOT, plot).putExtra(EXTRA_RATING, rating)
            )
    }
}
