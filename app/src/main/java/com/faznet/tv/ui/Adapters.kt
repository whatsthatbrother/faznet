package com.faznet.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.faznet.tv.R
import com.faznet.tv.data.Channel

data class GroupItem(val key: String, val title: String, val count: Int)

class GroupAdapter(
    private val horizontal: Boolean,
    private val selectOnFocus: Boolean,
    private val onSelect: (String) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private var items: List<GroupItem> = emptyList()
    var selectedKey: String = ""
        private set

    fun submit(list: List<GroupItem>, selected: String) {
        items = list
        selectedKey = selected
        notifyDataSetChanged()
    }

    fun select(key: String) {
        val old = items.indexOfFirst { it.key == selectedKey }
        selectedKey = key
        val now = items.indexOfFirst { it.key == key }
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvGroupName)
        val count: TextView = v.findViewById(R.id.tvGroupCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        if (horizontal) {
            v.layoutParams = v.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
            val name = v.findViewById<TextView>(R.id.tvGroupName)
            name.layoutParams = (name.layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                weight = 0f
            }
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.name.text = item.title
        h.count.text = item.count.toString()
        h.itemView.isSelected = item.key == selectedKey
        h.itemView.setOnClickListener { onSelect(item.key) }
        // TV: moving the D-pad over a category opens it, like a TV guide
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && selectOnFocus && !v.isInTouchMode && item.key != selectedKey) onSelect(item.key)
        }
    }
}

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var favs: Set<String> = emptySet()

    fun submit(list: List<Channel>, favorites: Set<String>) {
        items = list
        favs = favorites
        notifyDataSetChanged()
    }

    fun updateFavorites(favorites: Set<String>) {
        favs = favorites
        notifyItemRangeChanged(0, items.size, PAYLOAD_FAV)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.tvNumber)
        val logo: ImageView = v.findViewById(R.id.ivLogo)
        val name: TextView = v.findViewById(R.id.tvName)
        val group: TextView = v.findViewById(R.id.tvGroup)
        val fav: ImageView = v.findViewById(R.id.ivFav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_FAV)) {
            h.fav.visibility = if (favs.contains(items[position].url)) View.VISIBLE else View.GONE
        } else {
            onBindViewHolder(h, position)
        }
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.number.text = ch.number.toString()
        h.name.text = ch.name
        h.group.text = ch.group
        h.fav.visibility = if (favs.contains(ch.url)) View.VISIBLE else View.GONE
        loadLogo(h.logo, ch.logo)
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.04f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            v.translationZ = if (hasFocus) 8f else 0f
        }
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        h.itemView.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onLongClick(items[it]) }
            true
        }
    }

    companion object {
        private const val PAYLOAD_FAV = "fav"
    }
}

fun loadLogo(view: ImageView, url: String?) {
    if (url.isNullOrBlank()) {
        view.load(R.drawable.ic_tv)
    } else {
        view.load(url) {
            crossfade(true)
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
        }
    }
}
