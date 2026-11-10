package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.faznet.tv.R
import com.faznet.tv.data.LibraryCache
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XSeries
import com.faznet.tv.data.XVod
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Movies or series of the active Xtream account: categories on the left, posters on the right. */
class LibraryActivity : AppCompatActivity() {

    /** One poster in the grid (a movie or a series). */
    private class Item(val id: String, val title: String, val image: String?, val sub: String, val play: () -> Unit) {
        /** For the search: lowered once, not at every letter for every title. */
        val key = title.lowercase()
    }

    private lateinit var account: XtreamAccount
    private var series = false
    private lateinit var categories: GroupAdapter
    private lateinit var grid: PosterAdapter
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private var items: List<Item> = emptyList()
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var selected = ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        findViewById<View>(android.R.id.content).applyTvSafeArea()
        val repo = Repository.get(this)
        account = XtreamAccount.from(repo.activePlaylist()) ?: run { finish(); return }
        series = intent.getBooleanExtra(EXTRA_SERIES, false)
        findViewById<TextView>(R.id.libTitle).setText(if (series) R.string.series else R.string.movies)
        findViewById<View>(R.id.libBack).setOnClickListener { finish() }
        progress = findViewById(R.id.libProgress)
        empty = findViewById<TextView>(R.id.libEmpty).apply {
            setBackgroundResource(R.drawable.bg_choice)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            gravity = android.view.Gravity.CENTER
        }
        search = findViewById(R.id.libSearch)
        search.doAfterTextChanged { applyFilter(typing = true) }

        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        categories = GroupAdapter(horizontal = portrait, selectOnFocus = !portrait, onSelect = { key, fromClick -> loadItems(key, fromClick) })
        findViewById<RecyclerView>(R.id.libCategories).apply {
            layoutManager = LinearLayoutManager(this@LibraryActivity, if (portrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
            adapter = categories
            itemAnimator = null
        }
        grid = PosterAdapter()
        val widthDp = resources.configuration.screenWidthDp - if (portrait) 20 else 260
        findViewById<RecyclerView>(R.id.libItems).apply {
            // Fewer, bigger posters with air between them (Apple TV)
            layoutManager = GridLayoutManager(this@LibraryActivity, (widthDp / 140).coerceIn(2, 7))
            adapter = grid
            itemAnimator = null
        }

        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val cats = if (series) XtreamApi.seriesCategories(account) else XtreamApi.vodCategories(account)
            val list = ArrayList<GroupItem>()
            list.add(GroupItem(ALL, getString(R.string.library_all), -1))
            cats.forEach { list.add(GroupItem(it.id, it.name, -1)) }
            categories.submit(list, ALL)
            val first = if (cats.isNotEmpty()) cats.first().id else ALL
            loadItems(first)
            // The remote starts on the category being shown, not on the Back button (one OK there closed the screen).
            if (!portrait) findViewById<RecyclerView>(R.id.libCategories).let { rv ->
                val at = list.indexOfFirst { it.key == first }.coerceAtLeast(0)
                rv.post { rv.findViewHolderForAdapterPosition(at)?.itemView?.requestFocus() }
            }
        }
    }

    /** [now] false = the remote is only passing over the category: load it once the remote stops there. */
    private fun loadItems(key: String, now: Boolean = true) {
        selected = key
        categories.select(key)
        loadJob?.cancel()
        filterJob?.cancel()
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        loadJob = lifecycleScope.launch {
            if (!now) delay(CATEGORY_DELAY_MS)
            val cat = key.takeUnless { it == ALL }
            // "All" can be tens of thousands of titles: the tiles are made off the main thread too.
            failed = false
            // A category seen before in this session comes from memory, not from the server again.
            val cacheKey = LibraryCache.key(account, series, cat)
            items = if (series) {
                val found = LibraryCache.get<XSeries>(cacheKey)
                    ?: XtreamApi.series(account, cat)?.also { LibraryCache.put(cacheKey, it) }
                    ?: emptyList<XSeries>().also { failed = true }
                withContext(Dispatchers.Default) {
                    found.map { s ->
                        Item(s.id, s.name, s.cover, s.rating.takeIf { it.isNotEmpty() && it != "0" }?.let { "★ $it" } ?: "") {
                            SeriesActivity.start(this@LibraryActivity, s.id, s.name, s.cover, s.plot, s.rating)
                        }
                    }
                }
            } else {
                val found = LibraryCache.get<XVod>(cacheKey)
                    ?: XtreamApi.vodStreams(account, cat)?.also { LibraryCache.put(cacheKey, it) }
                    ?: emptyList<XVod>().also { failed = true }
                withContext(Dispatchers.Default) {
                    found.map { v ->
                        val url = XtreamApi.vodUrl(account, v)
                        Item(v.id, v.name, v.icon, listOf(v.year, v.rating.takeIf { it.isNotEmpty() && it != "0" }?.let { "★ $it" })
                            .filter { !it.isNullOrEmpty() }.joinToString(" · ")) { VodPlayerActivity.start(this@LibraryActivity, url, v.name) }
                    }
                }
            }
            progress.visibility = View.GONE
            applyFilter()
        }
    }

    /**
     * Shows the titles that match the search. With tens of thousands of titles, filtering at every letter on the main
     * thread made typing stick on the Mi Stick: it waits until the typing pauses and runs in the background.
     */
    private fun applyFilter(typing: Boolean = false) {
        filterJob?.cancel()
        val q = search.text.toString().trim().lowercase()
        val all = items
        if (q.isEmpty()) { show(all); return }
        filterJob = lifecycleScope.launch {
            if (typing) delay(SEARCH_DELAY_MS)
            show(withContext(Dispatchers.Default) { all.filter { it.key.contains(q) } })
        }
    }

    private fun show(shown: List<Item>) {
        grid.submit(shown)
        val show = shown.isEmpty() && progress.visibility != View.VISIBLE
        // The server did not answer: say so, and OK on the message asks again (before it read "nothing here", as if the
        // account had no movies, with no way to try again but choosing another category).
        empty.text = getString(if (failed) R.string.err_library else R.string.no_items)
        empty.isFocusable = failed
        empty.isClickable = failed
        empty.setOnClickListener(if (failed) View.OnClickListener { loadItems(selected) } else null)
        empty.visibility = if (show) View.VISIBLE else View.GONE
        if (show && failed) empty.requestFocus()
    }

    /** The last load could not reach the server. */
    private var failed = false

    private inner class PosterAdapter : RecyclerView.Adapter<PosterAdapter.VH>() {
        private var list: List<Item> = emptyList()

        fun submit(l: List<Item>) {
            list = l
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById<ImageView>(R.id.posterImage).apply { clipToOutline = true } // rounded poster
            val title: TextView = v.findViewById(R.id.posterTitle)
            val sub: TextView = v.findViewById(R.id.posterSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = list[position]
            h.title.text = item.title
            h.sub.text = item.sub
            h.image.load(item.image) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
            h.itemView.setOnClickListener { item.play() }
            // Apple TV: the focused poster lifts above its neighbours and grows, with a white ring (fg_poster_focus). On a
            // TV the others step back a little while the remote is in the grid, so the one OK will play stands out.
            val tv = !h.itemView.isInTouchMode
            h.itemView.alpha = if (h.itemView.isFocused || !tv) 1f else UNFOCUSED_ALPHA
            // A recycled tile may still be grown from the poster it showed before.
            (if (h.itemView.isFocused) 1.14f else 1f).let { h.itemView.scaleX = it; h.itemView.scaleY = it }
            h.itemView.translationZ = if (h.itemView.isFocused) 16f * h.itemView.resources.displayMetrics.density else 0f
            h.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.14f else 1f
                v.animate().scaleX(sc).scaleY(sc).alpha(if (f || v.isInTouchMode) 1f else UNFOCUSED_ALPHA)
                    .translationZ(if (f) 16f * v.resources.displayMetrics.density else 0f)
                    .setDuration(160).start()
            }
        }
    }

    companion object {
        private const val ALL = "\u0000all"
        private const val CATEGORY_DELAY_MS = 350L
        private const val SEARCH_DELAY_MS = 250L
        private const val UNFOCUSED_ALPHA = 0.7f
        const val EXTRA_SERIES = "series"

        fun start(c: Context, series: Boolean) =
            c.startActivity(Intent(c, LibraryActivity::class.java).putExtra(EXTRA_SERIES, series))
    }
}
