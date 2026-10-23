package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
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
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Movies or series of the active Xtream account: categories on the left, posters on the right. */
class LibraryActivity : AppCompatActivity() {

    /** One poster in the grid (a movie or a series). */
    private class Item(val id: String, val title: String, val image: String?, val sub: String, val play: () -> Unit)

    private lateinit var account: XtreamAccount
    private var series = false
    private lateinit var categories: GroupAdapter
    private lateinit var grid: PosterAdapter
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private var items: List<Item> = emptyList()
    private var loadJob: Job? = null
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
        empty = findViewById(R.id.libEmpty)
        search = findViewById(R.id.libSearch)
        search.doAfterTextChanged { applyFilter() }

        categories = GroupAdapter(horizontal = false, selectOnFocus = true, onSelect = { key, _ -> loadItems(key) })
        findViewById<RecyclerView>(R.id.libCategories).apply {
            layoutManager = LinearLayoutManager(this@LibraryActivity)
            adapter = categories
            itemAnimator = null
        }
        grid = PosterAdapter()
        val widthDp = resources.configuration.screenWidthDp - 260
        findViewById<RecyclerView>(R.id.libItems).apply {
            layoutManager = GridLayoutManager(this@LibraryActivity, (widthDp / 150).coerceIn(2, 8))
            adapter = grid
            itemAnimator = null
        }

        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val cats = if (series) XtreamApi.seriesCategories(account) else XtreamApi.vodCategories(account)
            val list = ArrayList<GroupItem>()
            list.add(GroupItem(ALL, getString(R.string.group_all), -1))
            cats.forEach { list.add(GroupItem(it.id, it.name, -1)) }
            categories.submit(list, ALL)
            loadItems(if (cats.isNotEmpty()) cats.first().id else ALL)
        }
    }

    override fun onResume() {
        super.onResume()
        grid.notifyDataSetChanged() // "continue watching" times may have changed
    }

    private fun loadItems(key: String) {
        selected = key
        categories.select(key)
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        loadJob = lifecycleScope.launch {
            val cat = key.takeUnless { it == ALL }
            items = if (series) {
                XtreamApi.series(account, cat).map { s ->
                    Item(s.id, s.name, s.cover, s.rating.takeIf { it.isNotEmpty() && it != "0" }?.let { "★ $it" } ?: "") { openSeries(s.id, s.name) }
                }
            } else {
                XtreamApi.vodStreams(account, cat).map { v ->
                    val url = XtreamApi.vodUrl(account, v)
                    Item(v.id, v.name, v.icon, listOf(v.year, v.rating.takeIf { it.isNotEmpty() && it != "0" }?.let { "★ $it" })
                        .filter { !it.isNullOrEmpty() }.joinToString(" · ")) { VodPlayerActivity.start(this@LibraryActivity, url, v.name) }
                }
            }
            progress.visibility = View.GONE
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val shown = if (q.isEmpty()) items else items.filter { it.title.lowercase().contains(q) }
        grid.submit(shown)
        empty.text = getString(R.string.no_items)
        empty.visibility = if (shown.isEmpty() && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun openSeries(id: String, name: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val eps = XtreamApi.episodes(account, id)
            progress.visibility = View.GONE
            if (eps.isEmpty()) {
                MaterialAlertDialogBuilder(this@LibraryActivity).setTitle(name).setMessage(R.string.no_items)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val labels = eps.map { e ->
                val url = XtreamApi.episodeUrl(account, e)
                val resume = VodPlayerActivity.resumePosition(this@LibraryActivity, url)
                "S${e.season} E${e.number}  ${e.title.ifEmpty { name }}" + if (resume > 0) "  ⏵ ${fmt(resume)}" else ""
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@LibraryActivity)
                .setTitle(name)
                .setItems(labels) { _, which ->
                    val e = eps[which]
                    VodPlayerActivity.start(this@LibraryActivity, XtreamApi.episodeUrl(account, e), "$name · S${e.season} E${e.number}")
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private inner class PosterAdapter : RecyclerView.Adapter<PosterAdapter.VH>() {
        private var list: List<Item> = emptyList()

        fun submit(l: List<Item>) {
            list = l
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.posterImage)
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
            h.itemView.setOnFocusChangeListener { v, f ->
                val sc = if (f) 1.05f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            }
        }
    }

    companion object {
        private const val ALL = "\u0000all"
        const val EXTRA_SERIES = "series"

        fun start(c: Context, series: Boolean) =
            c.startActivity(Intent(c, LibraryActivity::class.java).putExtra(EXTRA_SERIES, series))
    }
}
