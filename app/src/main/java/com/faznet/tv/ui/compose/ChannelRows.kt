package com.faznet.tv.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.data.ChannelPages

/**
 * A channel list read a page at a time as the screen reaches it. A row whose page is not there yet asks for it
 * ([Need]); when it arrives [version] goes up and the rows on screen read their channel again.
 */
@Stable
class PagedChannels(val pages: ChannelPages) {
    val version = mutableIntStateOf(0)
    private val reading = HashSet<Int>()

    val size get() = pages.size

    /** The channel at [i] if its page is read; reading [version] here redraws the row when the page arrives. */
    fun peek(i: Int): Channel? {
        version.intValue
        return pages.peek(i)
    }

    suspend fun read(i: Int) {
        // Its page came without this row (the list is shorter than it said): nothing more will arrive for it.
        if (pages.isRead(i)) return
        val page = i / ChannelPages.PAGE
        if (!reading.add(page)) return
        try {
            pages.get(i)
        } finally {
            reading.remove(page)
        }
        version.intValue++
    }

    /** Placed in a row whose channel is missing: reads its page. */
    @Composable
    fun Need(i: Int) {
        LaunchedEffect(this, i / ChannelPages.PAGE) { read(i) }
    }
}

/**
 * A compact channel row (the list over the picture, the guide): number, logo, name, a play mark on the one playing.
 * Channels found not working are faded.
 */
@Composable
fun ChannelRow(
    ch: Channel?,
    index: Int,
    playing: Boolean,
    tvIcon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    height: androidx.compose.ui.unit.Dp = 52.dp,
) {
    val dead = ch != null && ChannelCheck.isDead(ch.url)
    FocusBox(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        selected = playing,
        modifier = modifier.fillMaxWidth().height(height).alpha(if (dead) 0.45f else 1f),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        focusedColor = Fz.focusLight,
        selectedColor = Color(0x2EFFFFFF),
        focusedBrush = null,
        outline = false,
        focusScale = 1.02f,
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((ch?.number ?: (index + 1)).toString(), color = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim,
                fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp), maxLines = 1)
            Box(Modifier.size(width = 52.dp, height = 30.dp).channelLogo(ch?.logo?.takeIf { it.isNotBlank() }, tvIcon.takeIf { ch != null }))
            Spacer(Modifier.width(10.dp))
            Text(ch?.name.orEmpty(), color = if (focused) Fz.onLight else Fz.text, fontSize = 15.sp,
                fontWeight = if (focused || playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (playing) {
                Spacer(Modifier.width(6.dp))
                Icon(painterResource(R.drawable.ic_play_small), null, tint = if (focused) Fz.onLight else Fz.accent2,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}
