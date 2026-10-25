package com.faznet.tv.ui.compose

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Faznet colours (same as res/values/colors.xml). */
object Fz {
    val bg = Color(0xFF0B1030)
    val bgTop = Color(0xFF1B2266)
    val surface = Color(0xFF141B45)
    val surface2 = Color(0xFF1E2766)
    val accent = Color(0xFF4F6BFF)
    val accent2 = Color(0xFF8A4DFF)
    val text = Color(0xFFEEF1FF)
    val dim = Color(0xFFA0A8D0)
    val line = Color(0xFF2A3475)
    val selected = Color(0x404F6BFF)

    /** The focused item: blue to violet, like Smart IPTV. */
    val focusBrush = Brush.horizontalGradient(listOf(accent, accent2))
}

@Composable
fun FaznetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fz.accent,
            onPrimary = Color.White,
            secondary = Fz.accent2,
            background = Fz.bg,
            surface = Fz.surface,
            onBackground = Fz.text,
            onSurface = Fz.text,
        ),
        content = content,
    )
}

/**
 * A focusable, clickable box made for the TV remote and for touch:
 * OK = click, OK held = long click; grows and turns blue-violet when focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = Fz.surface,
    focusedColor: Color = Fz.surface2,
    selectedColor: Color = Fz.selected,
    focusedBrush: Brush? = Fz.focusBrush,
    focusScale: Float = 1.04f,
    /** Thin frame while not focused, so clickable things stand out from the background. */
    outline: Boolean = color != Color.Transparent,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "scale")
    val bg by animateColorAsState(
        when {
            focused -> focusedColor
            selected -> selectedColor
            else -> color
        }, label = "bg"
    )
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .then(if (focused && focusedBrush != null) Modifier.background(focusedBrush) else Modifier.background(bg))
            .border(
                if (focused) 2.dp else if (outline) 1.dp else 0.dp,
                if (focused) Color.White else if (outline) Fz.line else Color.Transparent,
                shape,
            )
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocus?.invoke(it.isFocused)
                }
            }
            // Remote: OK pressed = click, OK held (key repeat) = long click.
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER &&
                    k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                ) return@onPreviewKeyEvent false
                when (k.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (k.repeatCount == 0) longFired = false
                        else if (!longFired && onLongClick != null) { longFired = true; onLongClick() }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!longFired) onClick()
                        longFired = false
                    }
                }
                true
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.CenterStart,
    ) { content(focused) }
}

/** Round icon button of the top bar. */
@Composable
fun IconAction(icon: Int, label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, size: Dp = 46.dp) {
    FocusBox(
        onClick = onClick, onLongClick = onLongClick, modifier = Modifier.padding(horizontal = 3.dp).size(size),
        shape = RoundedCornerShape(50), color = Color(0xB3262F75), outline = true, focusScale = 1.1f,
    ) {
        Icon(
            painterResource(icon), contentDescription = label, tint = Fz.text,
            modifier = Modifier.align(Alignment.Center).size(size * 0.5f)
        )
    }
}
