package com.faznet.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
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
import com.faznet.tv.data.ChannelCheck

data class GroupItem(val key: String, val title: String, val count: Int)

class GroupAdapter(
    private val horizontal: Boolean,
    private val selectOnFocus: Boolean,
    /** key, fromClick (false when the D-pad merely moved over the group) */
    private val onSelect: (String, Boolean) -> Unit,
    private val onLongSelect: (String) -> Unit = {},
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
        h.count.text = if (item.count >= 0) item.count.toString() else ""
        h.itemView.isSelected = item.key == selectedKey
        h.itemView.setOnClickListener { onSelect(item.key, true) }
        h.itemView.setOnLongClickListener { onLongSelect(item.key); true }
        // TV: moving the D-pad over a category opens it, like a TV guide
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && selectOnFocus && !v.isInTouchMode && item.key != selectedKey) onSelect(item.key, false)
        }
    }
}

fun loadLogo(view: ImageView, url: String?) {
    if (url.isNullOrBlank()) {
        view.load(R.drawable.ic_tv)
    } else {
        view.load(url) {
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
        }
    }
}

fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")

/** TVs may crop the picture edges (overscan): keep content inside the title-safe area. */
fun View.applyTvSafeArea() {
    if (!context.isTv()) return
    val d = resources.displayMetrics.density
    setPadding(paddingLeft + (32 * d).toInt(), paddingTop + (18 * d).toInt(), paddingRight + (32 * d).toInt(), paddingBottom + (18 * d).toInt())
}
