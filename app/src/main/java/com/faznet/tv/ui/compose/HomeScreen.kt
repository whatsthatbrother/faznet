package com.faznet.tv.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.ui.GroupItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the home screen shows when there are no channels (welcome, error, empty group). */
data class EmptyInfo(
    val title: String,
    val text: String,
    val button: String? = null,
    val action: (() -> Unit)? = null,
    val showDevice: Boolean = false,
    val showDemo: Boolean = false,
)

/** Everything the home screen draws. MainActivity changes these; Compose redraws what changed. */
@Stable
class HomeState {
    var playlistName by mutableStateOf("")
    var xtream by mutableStateOf(false)
    var groups by mutableStateOf<List<GroupItem>>(emptyList())
    var selectedGroup by mutableStateOf("")
    var channels by mutableStateOf<List<Channel>>(emptyList())
    var favs by mutableStateOf<Set<String>>(emptySet())
    var loading by mutableStateOf(false)
    var query by mutableStateOf("")
    var empty by mutableStateOf<EmptyInfo?>(null)
    /** Channel that should get the remote's focus (coming back from the player). */
    var focusUrl by mutableStateOf<String?>(null)
    /** Increase to scroll the channel grid back to the top. */
    var scrollTop by mutableIntStateOf(0)
    /** Increase after a channel check so "not working" labels are redrawn. */
    var checkVersion by mutableIntStateOf(0)
}

interface HomeActions {
    fun openChannel(index: Int)
    fun toggleFavorite(ch: Channel)
    fun selectGroup(key: String, fromClick: Boolean)
    fun checkGroup(key: String)
    fun search()
    fun playlists()
    fun refresh()
    fun debugPlaylist()
    fun device()
    fun settings()
    fun movies()
    fun series()
    fun guide()
}

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, wide: Boolean, tv: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Fz.bgTop, Fz.bg, Color(0xFF070A20))))
            .padding(if (tv) PaddingValues(horizontal = 32.dp, vertical = 16.dp) else PaddingValues(0.dp))
    ) {
        TopBar(state, actions, wide)
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                GroupList(state, actions, vertical = true, modifier = Modifier.width(250.dp).fillMaxHeight())
                Spacer(Modifier.width(8.dp))
                ChannelArea(state, actions, wide, tv, Modifier.weight(1f))
            }
        } else {
            GroupList(state, actions, vertical = false, modifier = Modifier.fillMaxWidth())
            ChannelArea(state, actions, wide, tv, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TopBar(state: HomeState, actions: HomeActions, wide: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(34.dp))
        if (wide) {
            Spacer(Modifier.width(10.dp))
            Text("Faznet", color = Fz.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        // Playlist name (opens the playlists) and search
        FocusBox(
            onClick = { actions.playlists() },
            modifier = Modifier.height(42.dp).widthIn(max = 260.dp),
            shape = RoundedCornerShape(21.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_playlist), null, tint = Fz.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(state.playlistName.ifEmpty { stringResource(R.string.add_playlist) }, color = Fz.text, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        FocusBox(
            onClick = { actions.search() },
            modifier = Modifier.height(42.dp).weight(1f),
            shape = RoundedCornerShape(21.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), null, tint = Fz.dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    state.query.ifEmpty { stringResource(R.string.search_hint) },
                    color = if (state.query.isEmpty()) Fz.dim else Fz.text, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (wide) Clock()
        LazyRow(Modifier.widthIn(max = if (wide) 480.dp else 200.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.xtream) {
                item { IconAction(R.drawable.ic_guide, stringResource(R.string.guide), actions::guide) }
                item { IconAction(R.drawable.ic_movie, stringResource(R.string.movies), actions::movies) }
                item { IconAction(R.drawable.ic_series, stringResource(R.string.series), actions::series) }
            }
            item { IconAction(R.drawable.ic_device, stringResource(R.string.device_title), actions::device) }
            item {
                IconAction(R.drawable.ic_refresh, stringResource(R.string.refresh), actions::refresh,
                    onLongClick = { actions.debugPlaylist() })
            }
            item { IconAction(R.drawable.ic_settings, stringResource(R.string.settings), actions::settings) }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val f = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Text(f.format(now), color = Fz.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 14.dp))
}

@Composable
private fun GroupList(state: HomeState, actions: HomeActions, vertical: Boolean, modifier: Modifier) {
    val groups = state.groups
    if (groups.isEmpty()) return
    if (vertical) {
        val listState = rememberLazyListState()
        LazyColumn(modifier, state = listState, contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(groups, key = { it.key }) { g -> GroupChip(g, state, actions, vertical = true) }
        }
    } else {
        LazyRow(modifier.padding(bottom = 6.dp), contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(groups, key = { it.key }) { g -> GroupChip(g, state, actions, vertical = false) }
        }
    }
}

@Composable
private fun GroupChip(g: GroupItem, state: HomeState, actions: HomeActions, vertical: Boolean) {
    val selected = g.key == state.selectedGroup
    FocusBox(
        onClick = { actions.selectGroup(g.key, true) },
        onLongClick = { actions.checkGroup(g.key) },
        // TV: moving over a category opens it, like a TV guide
        onFocus = { f -> if (f && vertical && g.key != state.selectedGroup) actions.selectGroup(g.key, false) },
        selected = selected,
        modifier = if (vertical) Modifier.fillMaxWidth().height(42.dp) else Modifier.height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (vertical) Color.Transparent else Fz.surface,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.size(width = 3.dp, height = 18.dp).background(Fz.accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                g.title, color = if (selected) Fz.text else Fz.text.copy(alpha = 0.85f), fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (vertical) Modifier.weight(1f) else Modifier,
            )
            if (g.count >= 0) {
                Spacer(Modifier.width(8.dp))
                Text(g.count.toString(), color = Fz.dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChannelArea(state: HomeState, actions: HomeActions, wide: Boolean, tv: Boolean, modifier: Modifier) {
    Box(modifier) {
        val channels = state.channels
        val gridState = rememberLazyGridState()
        val minCell = if (tv) 190.dp else if (wide) 250.dp else 270.dp
        LaunchedEffect(state.scrollTop) { if (state.scrollTop > 0) gridState.scrollToItem(0) }
        val favs = state.favs
        val check = state.checkVersion
        val focusUrl = state.focusUrl
        val target = focusUrl
        LaunchedEffect(target, channels) {
            if (target == null) return@LaunchedEffect
            val i = channels.indexOfFirst { it.url == target }
            if (i < 0) { state.focusUrl = null; return@LaunchedEffect }
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == i }) gridState.scrollToItem(i)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            state = gridState,
            contentPadding = PaddingValues(6.dp, 6.dp, 6.dp, 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(channels, key = { i, ch -> ch.url + "#" + i }, contentType = { _, _ -> "ch" }) { i, ch ->
                // Plain values in, so Compose skips every card whose values did not change.
                ChannelCard(
                    ch, i, fav = ch.url in favs, check = check, focusMe = ch.url == focusUrl,
                    onFocused = { state.focusUrl = null }, actions = actions,
                )
            }
        }
        if (state.loading) CircularProgressIndicator(color = Fz.accent, modifier = Modifier.align(Alignment.Center).size(48.dp))
        state.empty?.let { EmptyBox(it, actions, Modifier.align(Alignment.Center)) }
    }
}

@Composable
private fun ChannelCard(
    ch: Channel, index: Int, fav: Boolean, check: Int, focusMe: Boolean,
    onFocused: () -> Unit, actions: HomeActions,
) {
    val requester = remember { FocusRequester() }
    val dead = remember(ch.url, check) { ChannelCheck.isDead(ch.url) }
    if (focusMe) {
        LaunchedEffect(ch.url) {
            delay(30)
            runCatching { requester.requestFocus() }
            onFocused()
        }
    }
    FocusBox(
        onClick = { actions.openChannel(index) },
        onLongClick = { actions.toggleFavorite(ch) },
        modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(requester),
        shape = RoundedCornerShape(12.dp),
    ) { focused ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ch.number.toString(), color = if (focused) Color.White else Fz.dim, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
            Box(
                Modifier.size(width = 44.dp, height = 30.dp).background(Color(0xFF0B0E13), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = ch.logo?.takeIf { it.isNotBlank() } ?: R.drawable.ic_tv,
                    contentDescription = null,
                    placeholder = painterResource(R.drawable.ic_tv),
                    error = painterResource(R.drawable.ic_tv),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = Fz.text.copy(alpha = if (dead) 0.45f else 1f), fontSize = 13.sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (dead) stringResource(R.string.channel_dead) else ch.group,
                    color = if (dead) Color(0xFFFF6B81) else if (focused) Color.White.copy(alpha = 0.8f) else Fz.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (fav) Icon(painterResource(R.drawable.ic_star), null, tint = Color(0xFFFFC94D), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyBox(e: EmptyInfo, actions: HomeActions, modifier: Modifier) {
    val first = remember { FocusRequester() }
    Column(modifier.padding(24.dp).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (e.title.isNotEmpty()) {
            Text(e.title, color = Fz.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
        }
        Text(e.text, color = Fz.dim, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
        Spacer(Modifier.height(20.dp))
        if (e.button != null && e.action != null) {
            WideButton(e.button, primary = true, onClick = e.action, modifier = Modifier.focusRequester(first))
            LaunchedEffect(e) { delay(50); runCatching { first.requestFocus() } }
        }
        if (e.showDevice) WideButton(stringResource(R.string.upload_from_web), primary = false, onClick = { actions.device() })
        if (e.showDemo) WideButton("Demo playlist (debug)", primary = false, onClick = { actions.debugPlaylist() })
    }
}

@Composable
fun WideButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.padding(top = 8.dp).height(46.dp).widthIn(min = 220.dp),
        shape = RoundedCornerShape(23.dp),
        color = if (primary) Fz.accent else Color(0xFF2A3580),
        // Buttons turn white under the remote, like the other buttons of the app
        focusedColor = Color.White,
        focusedBrush = null,
        focusScale = 1.06f,
    ) { focused ->
        Text(text, color = if (focused) Fz.bg else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
    }
}
