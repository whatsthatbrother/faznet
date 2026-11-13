package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.R
import com.faznet.tv.data.LibraryCache
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XSeries
import com.faznet.tv.data.XVod
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import com.faznet.tv.ui.compose.BackButton
import com.faznet.tv.ui.compose.EmptyText
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.FocusBox
import com.faznet.tv.ui.compose.Fz
import com.faznet.tv.ui.compose.LoadingBox
import com.faznet.tv.ui.compose.PivotScroll
import com.faznet.tv.ui.compose.RetryBox
import com.faznet.tv.ui.compose.ScreenTitle
import com.faznet.tv.ui.compose.SearchField
import com.faznet.tv.ui.compose.SideItem
import com.faznet.tv.ui.compose.posterImage
import com.faznet.tv.ui.compose.screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Movies or series of the active Xtream account: categories on the left, posters on the right. */
class LibraryActivity : AppCompatActivity() {

    /** One poster in the grid (a movie or a series). */
    private class Item(val title: String, val image: String?, val sub: String, val play: () -> Unit) {
        /** For the search: lowered once, not at every letter for every title. */
        val key = title.lowercase()
    }

    private lateinit var account: XtreamAccount
    private var series = false

    private var categories by mutableStateOf<List<GroupItem>>(emptyList())
    private var selected by mutableStateOf(ALL)
    /** Every title of the open category; [shown] is what matches the search. */
    private var items: List<Item> = emptyList()
    private var shown by mutableStateOf<List<Item>>(emptyList())
    private var loading by mutableStateOf(true)
    /** The last load could not reach the server: the screen says so, and OK asks again. */
    private var failed by mutableStateOf(false)
    private var query by mutableStateOf("")
    /** Increased when a new category is shown: the grid goes back to the top. */
    private var shownVersion by mutableStateOf(0)
    private var loadJob: Job? = null
    private var filterJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = XtreamAccount.from(Repository.get(this).activePlaylist()) ?: run { finish(); return }
        series = intent.getBooleanExtra(EXTRA_SERIES, false)
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
        lifecycleScope.launch {
            val cats = if (series) XtreamApi.seriesCategories(account) else XtreamApi.vodCategories(account)
            categories = listOf(GroupItem(ALL, getString(R.string.library_all), -1)) + cats.map { GroupItem(it.id, it.name, -1) }
            loadItems(if (cats.isNotEmpty()) cats.first().id else ALL)
        }
    }

    /** [now] false = the remote is only passing over the category: load it once the remote stops there. */
    private fun loadItems(key: String, now: Boolean = true) {
        selected = key
        loadJob?.cancel()
        filterJob?.cancel()
        loading = true
        failed = false
        loadJob = lifecycleScope.launch {
            if (!now) delay(CATEGORY_DELAY_MS)
            val cat = key.takeUnless { it == ALL }
            // A category seen before in this session comes from memory, not from the server again.
            val cacheKey = LibraryCache.key(account, series, cat)
            var gotNothing = false
            // "All" can be tens of thousands of titles: the tiles are made off the main thread too.
            items = if (series) {
                val found = LibraryCache.get<XSeries>(cacheKey)
                    ?: XtreamApi.series(account, cat)?.also { LibraryCache.put(cacheKey, it) }
                    ?: emptyList<XSeries>().also { gotNothing = true }
                withContext(Dispatchers.Default) {
                    found.map { s ->
                        Item(s.name, s.cover, s.rating.takeIf { it.isNotEmpty() && it != "0" }?.let { "★ $it" } ?: "") {
                            SeriesActivity.start(this@LibraryActivity, s.id, s.name, s.cover, s.plot, s.rating)
                        }
                    }
                }
            } else {
                val found = LibraryCache.get<XVod>(cacheKey)
                    ?: XtreamApi.vodStreams(account, cat)?.also { LibraryCache.put(cacheKey, it) }
                    ?: emptyList<XVod>().also { gotNothing = true }
                withContext(Dispatchers.Default) {
                    found.map { v ->
                        val url = XtreamApi.vodUrl(account, v)
                        Item(v.name, v.icon, listOf(v.year, v.rating.takeIf { it.isNotEmpty() && it != "0" }?.let { "★ $it" })
                            .filter { !it.isNullOrEmpty() }.joinToString(" · ")) { VodPlayerActivity.start(this@LibraryActivity, url, v.name) }
                    }
                }
            }
            failed = gotNothing
            loading = false
            shownVersion++
            applyFilter()
        }
    }

    /**
     * Shows the titles that match the search. With tens of thousands of titles, filtering at every letter on the main
     * thread made typing stick on the Mi Stick: it waits until the typing pauses and runs in the background.
     */
    private fun applyFilter(typing: Boolean = false) {
        filterJob?.cancel()
        val q = query.trim().lowercase()
        val all = items
        if (q.isEmpty()) { shown = all; return }
        filterJob = lifecycleScope.launch {
            if (typing) delay(SEARCH_DELAY_MS)
            shown = withContext(Dispatchers.Default) { all.filter { it.key.contains(q) } }
        }
    }

    @Composable
    private fun Screen(tv: Boolean) {
        BoxWithConstraints(Modifier.fillMaxSize().screen(tv)) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(if (wide) 76.dp else 64.dp).padding(start = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(::finish)
                    ScreenTitle(stringResource(if (series) R.string.series else R.string.movies), Modifier.weight(1f))
                    SearchField(
                        query, { query = it; applyFilter(typing = true) }, stringResource(R.string.library_search),
                        Modifier.width(if (wide) 300.dp else 170.dp),
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Categories(vertical = true, tv = tv, modifier = Modifier.width(250.dp).fillMaxHeight())
                        Spacer(Modifier.width(8.dp))
                        Posters(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Categories(vertical = false, tv = tv, modifier = Modifier.fillMaxWidth())
                    Posters(Modifier.fillMaxSize())
                }
            }
        }
    }

    @Composable
    private fun Categories(vertical: Boolean, tv: Boolean, modifier: Modifier) {
        val cats = categories
        if (cats.isEmpty()) return
        // The remote starts on the category being shown, not on the back button (one OK there closed the screen).
        val start = remember { FocusRequester() }
        val first = remember(cats) { cats.indexOfFirst { it.key == selected }.coerceAtLeast(0) }
        val item: @Composable (Int, GroupItem) -> Unit = { i, g ->
            SideItem(
                g.title, selected = g.key == selected, vertical = vertical,
                onClick = { loadItems(g.key) },
                // TV: moving over a category opens it, like a TV guide.
                onFocus = { f -> if (f && vertical && tv && g.key != selected) loadItems(g.key, now = false) },
                modifier = if (i == first) Modifier.focusRequester(start) else Modifier,
            )
        }
        if (vertical) {
            LazyColumn(modifier, contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(cats) { i, g -> item(i, g) }
            }
        } else {
            LazyRow(modifier.padding(bottom = 6.dp), contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(cats) { i, g -> item(i, g) }
            }
        }
        if (tv) LaunchedEffect(cats) { delay(80); runCatching { start.requestFocus() } }
    }

    @Composable
    private fun Posters(modifier: Modifier) {
        val placeholder = painterResource(if (series) R.drawable.ic_series else R.drawable.ic_movie)
        Box(modifier) {
            val list = shown
            val gridState = rememberLazyGridState()
            LaunchedEffect(shownVersion) { if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                // Fewer, bigger posters with air between them (Apple TV), room for the focused one to grow.
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // The place is the key: server ids repeat on some panels, and a repeated key closes the app.
                items(list.size, key = { it }, contentType = { "poster" }) { i -> Poster(list[i], placeholder) }
            }
            when {
                loading -> LoadingBox()
                failed && list.isEmpty() -> RetryBox(stringResource(R.string.err_library), { loadItems(selected) })
                list.isEmpty() -> EmptyText(stringResource(R.string.no_items))
            }
        }
    }

    @Composable
    private fun Poster(item: Item, placeholder: Painter) {
        var focused by remember { mutableStateOf(false) }
        Column {
            // Apple TV: the focused poster lifts above its neighbours and grows, with a white ring.
            FocusBox(
                onClick = item.play,
                onFocus = { focused = it },
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                shape = RoundedCornerShape(12.dp),
                color = Fz.surface,
                focusedColor = Fz.surface,
                focusedBrush = null,
                outline = false,
                focusScale = 1.1f,
            ) {
                Box(Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(10.dp)).posterImage(item.image?.takeIf { it.isNotBlank() }, placeholder))
            }
            Spacer(Modifier.height(8.dp))
            Text(item.title, color = if (focused) Color.White else Fz.text.copy(alpha = 0.75f), fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            if (item.sub.isNotEmpty()) Text(item.sub, color = Fz.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    companion object {
        private const val ALL = "\u0000all"
        private const val CATEGORY_DELAY_MS = 350L
        private const val SEARCH_DELAY_MS = 250L
        const val EXTRA_SERIES = "series"

        fun start(c: Context, series: Boolean) =
            c.startActivity(Intent(c, LibraryActivity::class.java).putExtra(EXTRA_SERIES, series))
    }
}
