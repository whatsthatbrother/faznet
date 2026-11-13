package com.faznet.tv.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faznet.tv.R
import kotlinx.coroutines.delay

/** tvOS: a soft graphite light from the top fading into black (the background of every screen). */
val ScreenBrush: Brush = Brush.verticalGradient(0f to Color(0xFF2A2A2E), 0.45f to Color(0xFF0E0E10), 1f to Color.Black)

/** The round back button at the top left of a screen. */
@Composable
fun BackButton(onClick: () -> Unit) = IconAction(R.drawable.ic_back, stringResource(R.string.back), onClick)

/** A screen's title next to its back button. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Fz.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier.padding(start = 12.dp))
}

/** A spinning circle in the middle of the space it is given. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(44.dp))
    }
}

/** "Could not reach the server": a button that asks again, with the remote's focus on it. */
@Composable
fun RetryBox(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), takeFocus: Boolean = true) {
    val requester = remember { FocusRequester() }
    Box(modifier, contentAlignment = Alignment.Center) {
        FocusBox(
            onClick = onRetry, modifier = Modifier.focusRequester(requester), shape = RoundedCornerShape(14.dp),
            color = Fz.surface, focusedColor = Fz.focusLight, focusedBrush = null, outline = false, focusScale = 1.04f,
        ) { focused ->
            Text(text, color = if (focused) Fz.onLight else Fz.text, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
        }
    }
    if (takeFocus) LaunchedEffect(Unit) { delay(80); runCatching { requester.requestFocus() } }
}

/** A quiet message in the middle (an empty list). */
@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, color = Fz.dim, fontSize = 16.sp) }
}

/**
 * A search field that suits the remote: moving over it does not open the keyboard (in a plain text field every pass
 * of the focus brought it up); OK does. Typing is shown at once, [onQuery] gets every change.
 */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    FocusBox(
        onClick = { editing = true },
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(23.dp),
        color = Fz.surface2,
        focusedColor = if (editing) Fz.surface2 else Fz.focusLight,
        focusedBrush = null,
        outline = false,
        focusScale = if (editing) 1f else 1.04f,
    ) { focused ->
        val light = focused && !editing
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), null, tint = if (light) Fz.onLight else Fz.dim, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            if (editing) {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Fz.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Fz.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); editing = false }),
                    modifier = Modifier.weight(1f).focusRequester(field)
                        .onFocusChanged { if (!it.hasFocus && editing) editing = false },
                )
                LaunchedEffect(Unit) {
                    runCatching { field.requestFocus() }
                    keyboard?.show()
                }
            } else {
                Text(query.ifEmpty { hint }, color = if (light) Fz.onLight else if (query.isEmpty()) Fz.dim else Fz.text,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * One row of a side list (categories, groups): plain text, the open one on a faint grey, the focused one white with
 * black text (tvOS sidebar). [vertical] false = a chip of a horizontal row (phones held upright).
 */
@Composable
fun SideItem(
    title: String,
    selected: Boolean,
    vertical: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = -1,
    onFocus: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    FocusBox(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        selected = selected,
        modifier = (if (vertical) modifier.fillMaxWidth().height(44.dp) else modifier.height(36.dp)),
        shape = RoundedCornerShape(if (vertical) 12.dp else 18.dp),
        color = if (vertical) Color.Transparent else Fz.surface2,
        focusedColor = Fz.focusLight,
        selectedColor = Fz.selected,
        focusedBrush = null,
        outline = false,
        focusScale = 1.03f,
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = when { focused -> Fz.onLight; selected -> Fz.text; else -> Fz.text.copy(alpha = 0.7f) },
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (vertical) Modifier.weight(1f) else Modifier,
            )
            if (count >= 0) {
                Spacer(Modifier.width(8.dp))
                Text(count.toString(), color = if (focused) Fz.onLight.copy(alpha = 0.6f) else Fz.dim, fontSize = 13.sp)
            }
        }
    }
}

/** The whole screen's background, with the TV's safe margins (overscan) when [tv]. */
fun Modifier.screen(tv: Boolean): Modifier =
    background(ScreenBrush).then(if (tv) Modifier.padding(horizontal = 32.dp, vertical = 18.dp) else Modifier)
