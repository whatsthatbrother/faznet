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
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.Parental
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XProgramme
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * TV guide of the Xtream account: pick a channel, see its programmes. Past programmes that the provider keeps
 * in its archive can be watched again (catch-up); the one on air opens the live channel.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var account: XtreamAccount
    private var channels: List<Channel> = emptyList()
    private lateinit var progAdapter: ProgrammeAdapter
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var rvProg: RecyclerView
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
        account = XtreamAccount.from(repo.activePlaylist()) ?: run { finish(); return }
        channels = Parental.visible(this, ChannelStore.all).filter { XtreamAccount.liveStreamId(it.url) != null }
        findViewById<View>(R.id.guideBack).setOnClickListener { finish() }
        progress = findViewById(R.id.guideProgress)
        empty = findViewById(R.id.guideEmpty)
        title = findViewById(R.id.guideChannel)

        val chAdapter = OverlayChannelAdapter { pos -> showChannel(pos) }
        findViewById<RecyclerView>(R.id.guideChannels).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            adapter = chAdapter
            itemAnimator = null
        }
        chAdapter.submit(channels, null)
        progAdapter = ProgrammeAdapter()
        rvProg = findViewById(R.id.guideProgrammes)
        rvProg.layoutManager = LinearLayoutManager(this)
        rvProg.adapter = progAdapter
        rvProg.itemAnimator = null

        lifecycleScope.launch {
            serverTz = XtreamApi.serverTimeZone(account)
            archive = XtreamApi.archiveDays(account)
            val start = channels.indexOfFirst { it.url == ChannelStore.lastPlayedUrl }.coerceAtLeast(0)
            if (channels.isNotEmpty()) showChannel(start)
            else { empty.setText(R.string.guide_none); empty.visibility = View.VISIBLE }
        }
    }

    private fun showChannel(pos: Int) {
        val ch = channels.getOrNull(pos) ?: return
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
                ChannelStore.current = channels
                ChannelStore.groupKey = MainActivity.KEY_ALL
                startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, channels.indexOf(ch)))
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
