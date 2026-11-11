package com.faznet.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.faznet.tv.R
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.data.ChannelPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Compact channel rows for the list over the picture; the channels come a page at a time as the list reaches them. */
internal class OverlayChannelAdapter(
    private val scope: CoroutineScope,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<OverlayChannelAdapter.VH>() {

    private var pages: ChannelPages? = null
    private var playingUrl: String? = null
    private val reading = HashSet<Int>()

    fun submit(list: ChannelPages, playing: String?) {
        pages = list
        playingUrl = playing
        reading.clear()
        notifyDataSetChanged()
    }

    fun setPlaying(url: String) {
        playingUrl = url
        notifyDataSetChanged()
    }

    private fun read(list: ChannelPages, position: Int) {
        // Its page came without this row (the list is shorter than it said): nothing more will arrive for it.
        if (list.isRead(position)) return
        val page = position / ChannelPages.PAGE
        if (!reading.add(page)) return
        // Not Main.immediate: this is called while the list is laid out, and a page that is there at once (search
        // results, a very quick read) would then change the list in the middle of that, which RecyclerView refuses.
        scope.launch(Dispatchers.Main) {
            list.get(position)
            if (pages !== list) return@launch
            reading.remove(page)
            if (list.isRead(position)) notifyItemRangeChanged(page * ChannelPages.PAGE, ChannelPages.PAGE)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.ovNumber)
        val logo: ImageView = v.findViewById(R.id.ovLogo)
        val name: TextView = v.findViewById(R.id.ovName)
        val playing: ImageView = v.findViewById(R.id.ovPlaying)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_channel, parent, false))

    override fun getItemCount() = pages?.size ?: 0

    override fun onBindViewHolder(h: VH, position: Int) {
        val list = pages ?: return
        val ch = list.peek(position)
        if (ch == null) {
            read(list, position)
            h.number.text = (position + 1).toString()
            h.name.text = ""
            h.logo.setImageDrawable(null)
            h.playing.visibility = View.GONE
            h.itemView.isSelected = false
            h.itemView.setOnClickListener(null)
            return
        }
        val isPlaying = ch.url == playingUrl
        h.number.text = ch.number.toString()
        h.name.text = ch.name
        h.itemView.isSelected = isPlaying
        h.itemView.alpha = if (ChannelCheck.isDead(ch.url)) 0.45f else 1f
        h.playing.visibility = if (isPlaying) View.VISIBLE else View.GONE
        loadLogo(h.logo, ch.logo)
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onPick) }
    }
}
