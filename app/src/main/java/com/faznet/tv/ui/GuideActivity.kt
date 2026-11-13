package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelRepository
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.DbErrors
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XProgramme
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import com.faznet.tv.ui.compose.BackButton
import com.faznet.tv.ui.compose.ChannelRow
import com.faznet.tv.ui.compose.EmptyText
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.LoadingBox
import com.faznet.tv.ui.compose.PagedChannels
import com.faznet.tv.ui.compose.PivotScroll
import com.faznet.tv.ui.compose.ScreenTitle
import com.faznet.tv.ui.compose.screen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TV guide of the Xtream account: pick a channel, see its programmes. Past programmes that the provider keeps
 * in its archive can be watched again (catch-up); the one on air opens the live channel.
 */
@AndroidEntryPoint
class GuideActivity : AppCompatActivity() {

    @Inject lateinit var channelRepo: ChannelRepository

    private lateinit var account: XtreamAccount
    private var channels by mutableStateOf<PagedChannels?>(null)
    /** True once it is known whether there are channels at all. */
    private var channelsRead by mutableStateOf(false)
    /** Where the list starts: the channel played last. */
    private var startAt = 0
    private var playlistId = ""
    private var serverTz: TimeZone = TimeZone.getDefault()
    private var archive by mutableStateOf<Map<String, Int>>(emptyMap())
    private var job: Job? = null
    private val day = SimpleDateFormat("EEE dd.MM", Locale.getDefault())
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    // The programme on the right
    private var shownChannel by mutableStateOf<Channel?>(null)
    private var shownIndex by mutableIntStateOf(-1)
    /** The channel's guide id; null = the account has no guide for it. */
    private var streamId by mutableStateOf<String?>(null)
    private var programmes by mutableStateOf<List<XProgramme>>(emptyList())
    private var loadingProgrammes by mutableStateOf(false)
    /** Increased when a new programme list is shown: it scrolls to what is on now. */
    private var programmesVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repository.get(this)
        val active = repo.activePlaylist()
        account = XtreamAccount.from(active) ?: run { finish(); return }
        playlistId = active!!.id
        val tv = isTv()
        window.setBackgroundDrawable(null)
        setContent {
            FaznetTheme {
                if (tv) {
                    @OptIn(ExperimentalFoundationApi::class)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotScroll) { Screen(tv) }
                } else Screen(tv)
            }
        }

        lifecycleScope.launch(DbErrors) {
            val list = channelRepo.activeList(playlistId)?.let { channelRepo.pagesOf(channelRepo.listView(it).all()) }
            if (list == null || list.size == 0) {
                channelsRead = true
                return@launch
            }
            val last = ChannelStore.lastPlayed
            startAt = last?.let { list.indexOf(it) } ?: 0
            list.get(startAt)
            channels = PagedChannels(list)
            channelsRead = true
            // The programme comes first. Which channels keep an archive is the account's whole channel list, which
            // can take seconds on a big account: it fills in the title when it arrives.
            showChannel(startAt)
            launch { serverTz = XtreamApi.serverTimeZone(account) }
            archive = XtreamApi.archiveDays(account)
        }
    }

    private fun showChannel(pos: Int) {
        val list = channels ?: return
        val ch = list.pages.peek(pos) ?: return
        if (pos == shownIndex && shownChannel?.url == ch.url) return
        job?.cancel()
        shownChannel = ch
        shownIndex = pos
        val id = XtreamAccount.liveStreamId(ch.url)
        streamId = id
        programmes = emptyList()
        if (id == null) {
            // A channel the account has no guide for: say so, instead of leaving the previous channel's programme
            // on screen as if it were this one's.
            loadingProgrammes = false
            return
        }
        loadingProgrammes = true
        job = lifecycleScope.launch {
            // Some providers send a guide that ended months ago. Nothing older than the channel's catch-up archive
            // (at least a day) is shown: a stale guide on screen reads as if it were today's.
            val keepDays = maxOf(archive[id] ?: 0, 1)
            val since = System.currentTimeMillis() - keepDays * 24L * 3600_000L
            programmes = XtreamApi.guide(account, id).filter { it.end > since }
            loadingProgrammes = false
            programmesVersion++
        }
    }

    private fun open(ch: Channel, streamId: String, p: XProgramme) {
        val now = System.currentTimeMillis()
        when {
            p.start <= now && now < p.end -> {
                // On air: the live channel
                val list = channels ?: return
                ChannelStore.start(playlistId, ch, list.pages.scope, null, getString(R.string.group_all))
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            p.end <= now && (p.hasArchive || archive.containsKey(streamId)) -> lifecycleScope.launch {
                // Start times are in the server's time zone: asked once, at once if it is not here yet.
                val tz = XtreamApi.serverTimeZone(account).also { serverTz = it }
                VodPlayerActivity.start(this@GuideActivity, XtreamApi.catchupUrl(account, streamId, p, tz), "${ch.name} · ${p.title}")
            }
            p.end <= now -> Toast.makeText(this, R.string.catchup_not_available, Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, R.string.programme_later, Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun Screen(tv: Boolean) {
        BoxWithConstraints(Modifier.fillMaxSize().screen(tv)) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(if (wide) 76.dp else 64.dp).padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BackButton(::finish)
                    ScreenTitle(stringResource(R.string.guide))
                    Spacer(Modifier.weight(1f))
                    shownChannel?.let { ch ->
                        val days = streamId?.let { archive[it] }
                        Text(ch.name + if (days != null) "  ·  " + stringResource(R.string.catchup_days, days) else "",
                            color = Fz.dim, fontSize = if (wide) 17.sp else 15.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                when {
                    !channelsRead -> LoadingBox()
                    channels == null -> EmptyText(stringResource(R.string.guide_none))
                    wide -> Row(Modifier.fillMaxSize()) {
                        Channels(tv, Modifier.width(320.dp).fillMaxHeight())
                        Spacer(Modifier.width(12.dp))
                        Programmes(Modifier.weight(1f).fillMaxHeight())
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        Channels(tv, Modifier.fillMaxWidth().weight(1f))
                        Programmes(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun Channels(tv: Boolean, modifier: Modifier) {
        val list = channels ?: return
        val state = rememberLazyListState(initialFirstVisibleItemIndex = startAt)
        val start = remember { FocusRequester() }
        val tvIcon = painterResource(R.drawable.ic_tv)
        val focusJob = remember { arrayOfNulls<Job>(1) }
        LazyColumn(modifier, state = state, contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list.size, key = { it }, contentType = { "ch" }) { i ->
                val ch = list.peek(i)
                if (ch == null) list.Need(i)
                ChannelRow(
                    ch, i, playing = i == shownIndex, tvIcon = tvIcon, onClick = { showChannel(i) },
                    // TV: resting on a channel shows its programme, like a TV guide (not every channel passed over).
                    onFocus = { f ->
                        focusJob[0]?.cancel()
                        if (f && tv) focusJob[0] = lifecycleScope.launch { delay(FOCUS_DELAY_MS); showChannel(i) }
                    },
                    modifier = if (i == startAt) Modifier.focusRequester(start) else Modifier,
                )
            }
        }
        // The remote starts on the channel whose programme is shown, not on the back button.
        LaunchedEffect(list) { delay(80); runCatching { start.requestFocus() } }
    }

    @Composable
    private fun Programmes(modifier: Modifier) {
        val id = streamId
        val list = programmes
        Box(modifier) {
            when {
                loadingProgrammes -> LoadingBox()
                shownChannel == null -> Unit
                id == null || list.isEmpty() -> EmptyText(stringResource(R.string.guide_empty))
                else -> {
                    val state = rememberLazyListState()
                    LaunchedEffect(programmesVersion) {
                        val now = System.currentTimeMillis()
                        state.scrollToItem(list.indexOfFirst { now < it.end }.coerceAtLeast(0))
                    }
                    val ch = shownChannel!!
                    LazyColumn(Modifier.fillMaxSize(), state = state,
                        contentPadding = PaddingValues(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(list) { i, p ->
                            val prevDay = list.getOrNull(i - 1)?.let { day.format(it.start) }
                            ProgrammeRow(p, prevDay, archive.containsKey(id)) { open(ch, id, p) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgrammeRow(p: XProgramme, prevDay: String?, channelArchive: Boolean, onClick: () -> Unit) {
        val now = System.currentTimeMillis()
        val live = p.start <= now && now < p.end
        val canReplay = p.end <= now && (p.hasArchive || channelArchive)
        // Past programmes that can't be watched again are dimmed, but not while focused: the white row stays white.
        val dimmed = p.end <= now && !canReplay
        val d = day.format(p.start)
        val times = (if (d != prevDay) "$d\n" else "") + time.format(p.start) + "–" + time.format(p.end)
        var focused by remember { mutableStateOf(false) }
        FocusBox(
            onClick = onClick, onFocus = { focused = it }, selected = live,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (dimmed && !focused) 0.5f else 1f),
            shape = RoundedCornerShape(14.dp), color = Fz.surface, focusedColor = Fz.focusLight,
            selectedColor = Fz.surface2, focusedBrush = null, outline = false, focusScale = 1.02f,
        ) { f ->
            val main = if (f) Fz.onLight else Fz.text
            val dim = if (f) Fz.onLight.copy(alpha = 0.6f) else Fz.dim
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(times, color = dim, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp,
                    modifier = Modifier.width(120.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, color = main, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (p.desc.isNotEmpty()) Text(p.desc, color = dim, fontSize = 13.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                val badge = when {
                    live -> stringResource(R.string.badge_live)
                    canReplay -> stringResource(R.string.badge_catchup)
                    else -> null
                }
                if (badge != null) {
                    Spacer(Modifier.width(12.dp))
                    // LIVE red, catch-up grey
                    Text(badge.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.background(if (live) Color(0xFFFF453A) else Color(0xFF48484A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
        }
    }

    companion object {
        private const val FOCUS_DELAY_MS = 400L

        fun start(c: Context) = c.startActivity(Intent(c, GuideActivity::class.java))
    }
}
