package com.faznet.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelPages
import com.faznet.tv.data.ChannelRepository
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XProgramme
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
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
    private var channels: ChannelPages? = null
    private lateinit var progAdapter: ProgrammeAdapter
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var rvProg: RecyclerView
    private var playlistId = ""
    private var serverTz: TimeZone = TimeZone.getDefault()
    private var archive: Map<String, Int> = emptyMap()
    private var job: Job? = null
    private val day = SimpleDateFormat("EEE dd.MM", Locale.getDefault())
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        findViewById<View>(android.R.id.content).applyTvSafeArea()
        val repo = Repository.get(this)
        val active = repo.activePlaylist()
        account = XtreamAccount.from(active) ?: run { finish(); return }
        playlistId = active!!.id
        findViewById<View>(R.id.guideBack).setOnClickListener { finish() }
        progress = findViewById(R.id.guideProgress)
        empty = findViewById(R.id.guideEmpty)
        title = findViewById(R.id.guideChannel)

        val chAdapter = OverlayChannelAdapter(lifecycleScope) { pos -> showChannel(pos) }
        val rvChannels = findViewById<RecyclerView>(R.id.guideChannels).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            adapter = chAdapter
            itemAnimator = null
        }
        progAdapter = ProgrammeAdapter()
        rvProg = findViewById(R.id.guideProgrammes)
        rvProg.layoutManager = LinearLayoutManager(this)
        rvProg.adapter = progAdapter
        rvProg.itemAnimator = null

        lifecycleScope.launch {
            val list = channelRepo.activeList(playlistId)?.let { channelRepo.pagesOf(channelRepo.listView(it).all()) }
            channels = list
            if (list == null || list.size == 0) {
                empty.setText(R.string.guide_none)
                empty.visibility = View.VISIBLE
                return@launch
            }
            val last = ChannelStore.lastPlayed
            chAdapter.submit(list, last?.url)
            val start = last?.let { list.indexOf(it) } ?: 0
            (rvChannels.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start, 0)
            list.get(start)
            serverTz = XtreamApi.serverTimeZone(account)
            archive = XtreamApi.archiveDays(account)
            showChannel(start)
        }
    }

    private fun showChannel(pos: Int) {
        val list = channels ?: return
        val ch = list.peek(pos) ?: return
        val id = XtreamAccount.liveStreamId(ch.url) ?: return
        title.text = ch.name + if (archive.containsKey(id)) "  ·  " + getString(R.string.catchup_days, archive[id]) else ""
        job?.cancel()
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        progAdapter.submit(ch, id, emptyList())
        job = lifecycleScope.launch {
            val list = XtreamApi.guide(account, id)
            progress.visibility = View.GONE
            progAdapter.submit(ch, id, list)
            if (list.isEmpty()) { empty.setText(R.string.guide_empty); empty.visibility = View.VISIBLE }
            val now = System.currentTimeMillis()
            val at = list.indexOfFirst { now < it.end }.coerceAtLeast(0)
            (rvProg.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, rvProg.height / 3)
        }
    }

    private fun open(ch: Channel, streamId: String, p: XProgramme) {
        val now = System.currentTimeMillis()
        when {
            p.start <= now && now < p.end -> {
                // On air: the live channel
                val list = channels ?: return
                ChannelStore.start(playlistId, ch, list.scope, null, getString(R.string.group_all))
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            p.end <= now && (p.hasArchive || archive.containsKey(streamId)) ->
                VodPlayerActivity.start(this, XtreamApi.catchupUrl(account, streamId, p, serverTz), "${ch.name} · ${p.title}")
            p.end <= now -> Toast.makeText(this, R.string.catchup_not_available, Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, R.string.programme_later, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ProgrammeAdapter : RecyclerView.Adapter<ProgrammeAdapter.VH>() {
        private var ch: Channel? = null
        private var streamId = ""
        private var list: List<XProgramme> = emptyList()

        fun submit(c: Channel, id: String, l: List<XProgramme>) {
            ch = c; streamId = id; list = l
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val time: TextView = v.findViewById(R.id.progTime)
            val title: TextView = v.findViewById(R.id.progTitle)
            val desc: TextView = v.findViewById(R.id.progDesc)
            val badge: TextView = v.findViewById(R.id.progBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_programme, parent, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = list[position]
            val now = System.currentTimeMillis()
            val prevDay = list.getOrNull(position - 1)?.let { day.format(it.start) }
            val d = day.format(p.start)
            h.time.text = (if (d != prevDay) "$d\n" else "") + time.format(p.start) + "–" + time.format(p.end)
            h.title.text = p.title
            h.desc.text = p.desc
            h.desc.visibility = if (p.desc.isEmpty()) View.GONE else View.VISIBLE
            val live = p.start <= now && now < p.end
            val canReplay = p.end <= now && (p.hasArchive || archive.containsKey(streamId))
            h.badge.text = when {
                live -> getString(R.string.badge_live)
                canReplay -> getString(R.string.badge_catchup)
                else -> ""
            }
            h.badge.visibility = if (h.badge.text.isEmpty()) View.GONE else View.VISIBLE
            h.itemView.alpha = if (p.end <= now && !canReplay) 0.55f else 1f
            h.itemView.setOnClickListener { ch?.let { c -> open(c, streamId, p) } }
        }
    }

    companion object {
        fun start(c: Context) = c.startActivity(Intent(c, GuideActivity::class.java))
    }
}
