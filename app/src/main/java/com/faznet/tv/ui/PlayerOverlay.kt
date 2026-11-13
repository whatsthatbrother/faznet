package com.faznet.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faznet.tv.R
import com.faznet.tv.ui.compose.ChannelRow
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.IconAction
import com.faznet.tv.ui.compose.PagedChannels
import com.faznet.tv.ui.compose.channelLogo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything drawn over the live picture. [PlayerActivity] changes these; Compose redraws what changed. */
@Stable
internal class PlayerOverlayState {
    // Options bar (long OK / Menu / a tap)
    var optionsShown by mutableStateOf(false)
    var title by mutableStateOf("")
    var favorite by mutableStateOf(false)
    /** Phones only: the button that turns the picture sideways. */
    var fullscreenButton by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    // Infobar, shown on every channel change
    var infoShown by mutableStateOf(false)
    var infoNumber by mutableStateOf("")
    var infoName by mutableStateOf("")
    var infoGroup by mutableStateOf("")
    var infoLogo by mutableStateOf<String?>(null)
    var infoEpg by mutableStateOf<String?>(null)
    /** How far the programme on air is, 0..1; null = no bar. */
    var infoProgress by mutableStateOf<Float?>(null)

    var numberEntry by mutableStateOf("")
    var stats by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    // Channel list over the picture (OK)
    var listShown by mutableStateOf(false)
    var listTitle by mutableStateOf("")
    var listCount by mutableStateOf("")
    var listPages by mutableStateOf<PagedChannels?>(null)
    var listPlaying by mutableStateOf<String?>(null)
    var listEpg by mutableStateOf<String?>(null)
    /** The row that should take the remote's focus (and be scrolled to); null once it has. */
    var listFocus by mutableStateOf<Int?>(null)
    /** Increased with every new [listFocus], so the same row can be asked for twice. */
    var listFocusVersion by mutableIntStateOf(0)
    /** The row the remote is on. */
    var listFocused by mutableIntStateOf(-1)
    val listState = LazyListState()
    var listWidth by mutableStateOf(420.dp)

    fun focusListRow(pos: Int) {
        listFocus = pos
        listFocusVersion++
    }
}

internal interface PlayerOverlayActions {
    fun listPick(pos: Int)
    fun listFocusMoved(pos: Int)
    fun prevGroup()
    fun nextGroup()
    fun openList()
    fun favorite()
    fun aspect()
    fun tracks()
    fun sleep()
    fun playlists()
    fun stats()
    fun fullscreen()
}

/** Infobar sizes: phones use the small set, TVs and tablets (900 dp and wider) the larger one. */
private class Sizes(wide: Boolean) {
    val pad: Dp = if (wide) 36.dp else 20.dp
    val name = if (wide) 22.sp else 18.sp
    val number = if (wide) 32.sp else 26.sp
    val logoW: Dp = if (wide) 96.dp else 72.dp
    val logoH: Dp = if (wide) 60.dp else 48.dp
    val listTitle = if (wide) 19.sp else 17.sp
    val row: Dp = if (wide) 54.dp else 48.dp
}

@Composable
internal fun PlayerOverlay(
    state: PlayerOverlayState,
    messages: PlayerMessages,
    actions: PlayerOverlayActions,
    tv: Boolean,
    wide: Boolean,
) {
    val sizes = remember(wide) { Sizes(wide) }
    Box(Modifier.fillMaxSize()) {
        if (messages.spinner) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }
        if (state.optionsShown) OptionsBar(state, actions, sizes)
        if (state.infoShown && !state.optionsShown && !state.listShown) InfoBar(state, sizes, Modifier.align(Alignment.BottomCenter))
        if (state.listShown) ChannelList(state, actions, tv, sizes)
        if (state.numberEntry.isNotEmpty()) {
            Panel(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                Text(state.numberEntry, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
        state.stats?.let {
            Panel(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Text(it, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                    modifier = Modifier.padding(10.dp))
            }
        }
        state.toast?.let {
            Panel(Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) {
                Text(it, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
        if (messages.errorShown && messages.errorText.isNotEmpty()) {
            Panel(Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 520.dp)) {
                Text(messages.errorText, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** A dark rounded panel over the picture. */
@Composable
private fun Panel(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Color(0xEB2C2C2E), RoundedCornerShape(18.dp)), content = content)
}

@Composable
private fun OptionsBar(state: PlayerOverlayState, actions: PlayerOverlayActions, sizes: Sizes) {
    val first = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
            .padding(start = sizes.pad, end = sizes.pad, top = 12.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.title, color = Color.White, fontSize = sizes.name, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // A phone held upright is narrower than the buttons: they scroll sideways, the title gives way.
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (state.fullscreenButton) {
                IconAction(if (state.fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                    stringResource(R.string.fullscreen), actions::fullscreen)
            }
            Box(Modifier.focusRequester(first)) {
                IconAction(R.drawable.ic_list, stringResource(R.string.channel_list), actions::openList)
            }
            IconAction(if (state.favorite) R.drawable.ic_star else R.drawable.ic_star_border, stringResource(R.string.favorite),
                actions::favorite)
            IconAction(R.drawable.ic_aspect, stringResource(R.string.aspect), actions::aspect)
            IconAction(R.drawable.ic_tracks, stringResource(R.string.tracks), actions::tracks)
            IconAction(R.drawable.ic_sleep, stringResource(R.string.sleep_timer), actions::sleep)
            IconAction(R.drawable.ic_playlist, stringResource(R.string.playlists_switch), actions::playlists)
            IconAction(R.drawable.ic_stats, stringResource(R.string.stats), actions::stats)
        }
    }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
}

@Composable
private fun InfoBar(state: PlayerOverlayState, sizes: Sizes, modifier: Modifier) {
    val tvIcon = painterResource(R.drawable.ic_tv)
    Row(
        modifier.padding(sizes.pad).fillMaxWidth()
            .background(Color(0xE61C1C1E), RoundedCornerShape(24.dp))
            .padding(start = 24.dp, end = 28.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.infoNumber, color = Fz.dim, fontSize = sizes.number, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 18.dp))
        Box(Modifier.padding(end = 20.dp).size(sizes.logoW, sizes.logoH).channelLogo(state.infoLogo?.takeIf { it.isNotBlank() }, tvIcon))
        Column(Modifier.weight(1f)) {
            Text(state.infoName, color = Color.White, fontSize = sizes.name, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(state.infoGroup, color = Fz.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.infoEpg?.let {
                Text(it, color = Color(0xE6FFFFFF), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            state.infoProgress?.let { p ->
                // A thin white bar on a faint track (Apple)
                Box(Modifier.padding(top = 8.dp).size(240.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Color.White))
                }
            }
        }
        Clock(sizes)
    }
}

@Composable
private fun Clock(sizes: Sizes) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val f = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Text(f.format(now), color = Color(0xE6FFFFFF), fontSize = sizes.name, fontWeight = FontWeight.Light,
        modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun ChannelList(state: PlayerOverlayState, actions: PlayerOverlayActions, tv: Boolean, sizes: Sizes) {
    val tvIcon = painterResource(R.drawable.ic_tv)
    Column(
        Modifier.padding(20.dp).width(state.listWidth).fillMaxHeight()
            .background(Color(0xEB1C1C1E), RoundedCornerShape(28.dp))
            // Taps on the panel must not reach the picture behind it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = if (tv) 18.dp else 14.dp, end = 14.dp, top = if (tv) 20.dp else 18.dp, bottom = if (tv) 16.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.ic_chevron_left, stringResource(R.string.prev_group), actions::prevGroup, size = 36.dp)
            Text(state.listTitle, color = Color.White, fontSize = sizes.listTitle, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconAction(R.drawable.ic_chevron_right, stringResource(R.string.next_group), actions::nextGroup, size = 36.dp)
        }
        Text(state.listCount, color = Fz.dim, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
        Spacer(Modifier.height(6.dp))
        val pages = state.listPages
        if (pages != null) {
            val listState = state.listState
            val target = state.listFocus
            LaunchedEffect(pages, state.listFocusVersion) {
                if (target == null) return@LaunchedEffect
                // The row a third from the top, as on the screen before.
                listState.scrollToItem(target, -(listState.layoutInfo.viewportSize.height / 3))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pages.size, key = { it }, contentType = { "ch" }) { i ->
                    val ch = pages.peek(i)
                    if (ch == null) pages.Need(i)
                    val requester = remember { FocusRequester() }
                    if (state.listFocus == i) LaunchedEffect(state.listFocusVersion) {
                        delay(30)
                        if (runCatching { requester.requestFocus() }.isSuccess) state.listFocus = null
                    }
                    ChannelRow(
                        ch, i, playing = ch != null && ch.url == state.listPlaying, tvIcon = tvIcon,
                        onClick = { actions.listPick(i) },
                        onFocus = { f -> if (f) actions.listFocusMoved(i) },
                        modifier = Modifier.focusRequester(requester),
                        height = sizes.row,
                    )
                }
            }
        } else Spacer(Modifier.weight(1f))
        state.listEpg?.let {
            Text(it, color = Color(0xE6FFFFFF), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(Color(0xEB2C2C2E), RoundedCornerShape(18.dp))
                    .padding(12.dp))
        }
    }
}
