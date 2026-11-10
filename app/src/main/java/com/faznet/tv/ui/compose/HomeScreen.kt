package com.faznet.tv.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.ui.GroupItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HELD_KEY_STEP_MS = 90L

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
    /** The channels that are favourites in the list on screen. */
    var favIds by mutableStateOf<Set<Long>>(emptySet())
    var loading by mutableStateOf(false)
    /** How many channels of a playlist have been read so far while it is being imported. */
    var importing by mutableStateOf<Int?>(null)
    /** Filled in when the account has answered: without any, the button stays away. */
    var hasMovies by mutableStateOf(false)
    var hasSeries by mutableStateOf(false)
    var query by mutableStateOf("")
    var empty by mutableStateOf<EmptyInfo?>(null)
    /** Place in the grid of the channel that should get the remote's focus (coming back from the player). */
    var focusIndex by mutableStateOf<Int?>(null)
    /** The open group takes the remote's focus: its last channel is going away. */
    var focusOpenGroup by mutableStateOf(false)
    /** Increase to scroll the channel grid back to the top. */
    var scrollTop by mutableIntStateOf(0)
    /** Increase after a channel check so "not working" labels are redrawn. */
    var checkVersion by mutableIntStateOf(0)
    /** A newer Faznet is downloaded and ready: the top bar shows an "Update" button. */
    var updateVersion by mutableStateOf<String?>(null)
    /** What the "add playlist" button on the welcome screen does. */
    var addPlaylist: (() -> Unit)? = null
}

interface HomeActions {
    fun openChannel(channel: Channel)
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
    fun update()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(state: HomeState, pages: Flow<PagingData<Channel>>, actions: HomeActions, wide: Boolean, tv: Boolean) {
    if (tv) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides PivotScroll
        ) { HomeContent(state, pages, actions, wide, tv) }
    } else HomeContent(state, pages, actions, wide, tv)
}

@Composable
private fun HomeContent(state: HomeState, pages: Flow<PagingData<Channel>>, actions: HomeActions, wide: Boolean, tv: Boolean) {
    // tvOS: a soft graphite light from the top fading into black.
    val background = remember { Brush.verticalGradient(0f to Color(0xFF2A2A2E), 0.45f to Color(0xFF0E0E10), 1f to Color.Black) }
    val lastRepeat = remember { LongArray(1) }
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .padding(if (tv) PaddingValues(horizontal = 32.dp, vertical = 16.dp) else PaddingValues(0.dp))
            // A direction held down on the remote: one step at a pace the stick can draw. The system repeats the key
            // every 50 ms, and more steps than can be drawn only make the focus run on after the key is released.
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != android.view.KeyEvent.ACTION_DOWN || k.repeatCount == 0) return@onPreviewKeyEvent false
                when (k.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (k.eventTime - lastRepeat[0] < HELD_KEY_STEP_MS) return@onPreviewKeyEvent true
                        lastRepeat[0] = k.eventTime
                    }
                }
                false
            }
    ) {
        TopBar(state, actions, wide)
        // A phone's top bar has no room for it: search and the buttons would be pushed off the screen.
        if (!wide) state.updateVersion?.let { v ->
            UpdateButton(v, actions, Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                GroupList(state, actions, vertical = true, modifier = Modifier.width(250.dp).fillMaxHeight())
                Spacer(Modifier.width(8.dp))
                ChannelArea(state, pages, actions, tv, Modifier.weight(1f))
            }
        } else {
            GroupList(state, actions, vertical = false, modifier = Modifier.fillMaxWidth())
            ChannelArea(state, pages, actions, tv, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TopBar(state: HomeState, actions: HomeActions, wide: Boolean) {
    if (wide) {
        // tvOS: the sections as text tabs in one frosted bar in the middle, the app's name on the left, the time and the
        // round buttons on the right.
        Box(Modifier.fillMaxWidth().height(72.dp).padding(start = 12.dp, end = 4.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Faznet", color = Fz.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
            }
            TabBar(state, actions, Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                state.updateVersion?.let { v ->
                    UpdateButton(v, actions, Modifier)
                    Spacer(Modifier.width(8.dp))
                }
                Clock()
                TopIcons(state, actions, Modifier, sections = false)
            }
        }
    } else {
        // A phone has no room for all of it in one row: search would shrink to an icon and Settings leave the screen.
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(34.dp))
            Spacer(Modifier.weight(1f))
            TopIcons(state, actions, Modifier.horizontalScroll(rememberScrollState()))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistPill(state, actions, Modifier.widthIn(max = 150.dp))
            Spacer(Modifier.width(8.dp))
            SearchPill(state, actions, Modifier.weight(1f))
        }
    }
}

/** The frosted bar of the TV top: text tabs, the focused one turns white (tvOS tab bar). */
@Composable
private fun TabBar(state: HomeState, actions: HomeActions, modifier: Modifier) {
    Row(
        modifier.background(Color(0x1FFFFFFF), RoundedCornerShape(26.dp)).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tab(state.playlistName.ifEmpty { stringResource(R.string.add_playlist) }, R.drawable.ic_playlist, actions::playlists,
            Modifier.widthIn(max = 240.dp))
        Tab(state.query.ifEmpty { stringResource(R.string.tab_search) }, R.drawable.ic_search, actions::search,
            Modifier.widthIn(max = 220.dp))
        // Movies and series have their own cards on the start screen; Live TV keeps only the guide.
        if (state.xtream) Tab(stringResource(R.string.guide), null, actions::guide)
    }
}

@Composable
private fun Tab(text: String, icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(21.dp),
        color = Color.Transparent,
        focusedColor = Fz.focusLight,
        focusedBrush = null,
        focusScale = 1.06f,
        outline = false,
    ) { focused ->
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), null, tint = if (focused) Fz.onLight else Fz.dim, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = if (focused) Fz.onLight else Fz.text.copy(alpha = 0.85f), fontSize = 15.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaylistPill(state: HomeState, actions: HomeActions, modifier: Modifier) {
    LightPill(state.playlistName.ifEmpty { stringResource(R.string.add_playlist) }, R.drawable.ic_playlist, dim = false,
        onClick = actions::playlists, modifier = modifier)
}

@Composable
private fun SearchPill(state: HomeState, actions: HomeActions, modifier: Modifier) {
    LightPill(state.query.ifEmpty { stringResource(R.string.search_hint) }, R.drawable.ic_search, dim = state.query.isEmpty(),
        onClick = actions::search, modifier = modifier)
}

/** Phone top: a grey pill that turns white under the finger or the remote. */
@Composable
private fun LightPill(text: String, icon: Int, dim: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = Fz.surface2,
        focusedColor = Fz.focusLight,
        focusedBrush = null,
        outline = false,
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, tint = if (focused) Fz.onLight else Fz.dim, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = if (focused) Fz.onLight else if (dim) Fz.dim else Fz.text, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TopIcons(state: HomeState, actions: HomeActions, modifier: Modifier, sections: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (sections && state.xtream) {
            IconAction(R.drawable.ic_guide, stringResource(R.string.guide), actions::guide)
            if (state.hasMovies) IconAction(R.drawable.ic_movie, stringResource(R.string.movies), actions::movies)
            if (state.hasSeries) IconAction(R.drawable.ic_series, stringResource(R.string.series), actions::series)
        }
        IconAction(R.drawable.ic_device, stringResource(R.string.device_title), actions::device)
        IconAction(R.drawable.ic_refresh, stringResource(R.string.refresh), actions::refresh, onLongClick = { actions.debugPlaylist() })
        IconAction(R.drawable.ic_settings, stringResource(R.string.settings), actions::settings)
    }
}

@Composable
private fun UpdateButton(version: String, actions: HomeActions, modifier: Modifier) {
    FocusBox(
        onClick = { actions.update() },
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(21.dp),
        color = Color(0xFF30D158), // Apple green
        focusedColor = Fz.focusLight,
        focusedBrush = null,
        outline = false,
        focusScale = 1.08f,
    ) { focused ->
        Text(
            stringResource(R.string.update_banner, version), color = if (focused) Fz.onLight else Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
        )
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
    Text(f.format(now), color = Fz.text.copy(alpha = 0.9f), fontSize = 20.sp, fontWeight = FontWeight.Medium,
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
    // Read through derivedStateOf: moving over the groups then redraws two of them, not all that are on screen.
    val selected by remember(g.key) { derivedStateOf { g.key == state.selectedGroup } }
    val requester = remember { FocusRequester() }
    if (selected && state.focusOpenGroup) {
        LaunchedEffect(Unit) {
            runCatching { requester.requestFocus() }
            state.focusOpenGroup = false
        }
    }
    FocusBox(
        onClick = { actions.selectGroup(g.key, true) },
        onLongClick = { actions.checkGroup(g.key) },
        // TV: moving over a category opens it, like a TV guide
        onFocus = { f -> if (f && vertical && g.key != state.selectedGroup) actions.selectGroup(g.key, false) },
        selected = selected,
        // tvOS sidebar: plain text rows; the open group sits on a faint grey, the focused row turns white with black text.
        modifier = (if (vertical) Modifier.fillMaxWidth().height(44.dp) else Modifier.height(36.dp)).focusRequester(requester),
        shape = RoundedCornerShape(if (vertical) 12.dp else 18.dp),
        color = if (vertical) Color.Transparent else Fz.surface2,
        focusedColor = Fz.focusLight,
        selectedColor = Fz.selected,
        focusedBrush = null,
        outline = false,
        focusScale = 1.03f,
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                g.title,
                color = when { focused -> Fz.onLight; selected -> Fz.text; else -> Fz.text.copy(alpha = 0.7f) },
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (vertical) Modifier.weight(1f) else Modifier,
            )
            if (g.count >= 0) {
                Spacer(Modifier.width(8.dp))
                Text(g.count.toString(), color = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ChannelArea(state: HomeState, pages: Flow<PagingData<Channel>>, actions: HomeActions, tv: Boolean, modifier: Modifier) {
    Box(modifier) {
        val channels = pages.collectAsLazyPagingItems()
        val gridState = rememberLazyGridState()
        // One painter for every tile's "no logo" picture.
        val tvIcon = painterResource(R.drawable.ic_tv)
        LaunchedEffect(state.scrollTop) { if (state.scrollTop > 0) gridState.scrollToItem(0) }
        val favs = state.favIds
        val check = state.checkVersion
        ScrollToFocusedChannel(state, gridState, channels.itemCount)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = gridState,
            // tvOS: generous air between the cards, room for the focused one to grow.
            contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // The place is the key: a tile keeps the focus when its channel arrives from the database, and a new group
            // only updates the tiles on screen instead of building them again.
            items(channels.itemCount, key = { it }, contentType = { "ch" }) { i ->
                val ch = channels[i]
                // Plain values in, so Compose skips every tile whose values did not change.
                TileCard(ch, i, fav = ch != null && ch.id in favs, check = check, tvIcon, state, actions)
            }
        }
        if (state.loading) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Fz.accent, modifier = Modifier.size(48.dp))
                state.importing?.let { read ->
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.import_progress, read), color = Fz.dim, fontSize = 14.sp)
                }
            }
        }
        state.empty?.let { EmptyBox(it, actions, Modifier.align(Alignment.Center)) }
    }
}

/** Its own function, so that a change of the focused channel does not recompose the whole grid. */
@Composable
private fun ScrollToFocusedChannel(state: HomeState, gridState: androidx.compose.foundation.lazy.grid.LazyGridState, count: Int) {
    val target = state.focusIndex
    LaunchedEffect(target, count) {
        if (target == null || target >= count) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) gridState.scrollToItem(target)
    }
}

/** Coming back from the player: the tile of the channel that was playing takes the remote's focus. */
@Composable
private fun rememberFocusMe(index: Int, state: HomeState): FocusRequester {
    val requester = remember { FocusRequester() }
    if (state.focusIndex == index) {
        LaunchedEffect(index) {
            delay(30)
            runCatching { requester.requestFocus() }
            state.focusIndex = null
        }
    }
    return requester
}

private val TileName = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp, textAlign = TextAlign.Center,
    fontWeight = FontWeight.Medium)
private val TileNameFocused = TileName.copy(fontWeight = FontWeight.SemiBold)
private val TileNameColor = Fz.text.copy(alpha = 0.6f)
private val TileNameDead = Color(0xFFFF453A) // Apple red

/** Apple TV card: the logo floats on a quiet grey card, the name small below it; the focused card lifts and grows. */
@Composable
private fun TileCard(
    ch: Channel?, index: Int, fav: Boolean, check: Int,
    tvIcon: androidx.compose.ui.graphics.painter.Painter, state: HomeState, actions: HomeActions,
) {
    val requester = rememberFocusMe(index, state)
    val dead = remember(ch?.url, check) { ch != null && ChannelCheck.isDead(ch.url) }
    FocusBox(
        onClick = { ch?.let(actions::openChannel) },
        onLongClick = { ch?.let(actions::toggleFavorite) },
        modifier = Modifier.fillMaxWidth().height(116.dp).focusRequester(requester),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1C1C1E),
        focusedColor = Color(0xFF3A3A3C),
        focusedBrush = null,
        focusScale = 1.1f,
        outline = false,
    ) { focused ->
        // The fewest layouts that give this picture: every tile on screen is composed when a group opens.
        Box(
            Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 38.dp)
                .channelLogo(ch?.logo?.takeIf { it.isNotBlank() }, tvIcon.takeIf { ch != null })
        )
        if (fav) Icon(painterResource(R.drawable.ic_star), null, tint = Color(0xFFFFD60A),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(13.dp))
        BasicText(
            ch?.name.orEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            style = if (focused) TileNameFocused else TileName,
            color = { if (dead) TileNameDead else if (focused) Color.White else TileNameColor },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        color = if (primary) Fz.accent else Fz.surface2,
        // Buttons turn white under the remote (tvOS)
        focusedColor = Fz.focusLight,
        focusedBrush = null,
        outline = false,
        focusScale = 1.06f,
    ) { focused ->
        Text(text, color = if (focused) Fz.onLight else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
    }
}
