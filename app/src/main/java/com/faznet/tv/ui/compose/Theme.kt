package com.faznet.tv.ui.compose

import android.view.KeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.faznet.tv.ui.isTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Faznet colours (same as res/values/colors.xml). */
object Fz {
    val bg = Color(0xFF000000)
    val bgTop = Color(0xFF1C1C1E)
    val surface = Color(0xFF1C1C1E)
    val surface2 = Color(0xFF2C2C2E)
    val accent = Color(0xFF0A84FF)
    val accent2 = Color(0xFF409CFF)
    val text = Color(0xFFFFFFFF)
    val dim = Color(0xFF8E8E93)
    val line = Color(0xFF38383A)
    val selected = Color(0x1FFFFFFF)
    /** Raised grey of a focused item (tvOS dark), under a thin white frame. */
    val focus = Color(0xFF48484A)
    /** tvOS: a focused button or row turns white, its text black. */
    val focusLight = Color(0xFFF2F2F7)
    val onLight = Color(0xFF1C1C1E)

    /** The focused item: one calm grey, no colour gradient (Apple style). */
    val focusBrush: Brush = androidx.compose.ui.graphics.SolidColor(focus)
}

@Composable
fun FaznetTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tv = remember(context) { context.isTv() }
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
    ) {
        CompositionLocalProvider(LocalTvMode provides tv, content = content)
    }
}

/** True on a TV: nothing needs a touch ripple there, and every saved object counts on a stick. */
val LocalTvMode = staticCompositionLocalOf { false }

/**
 * A focusable, clickable box made for the TV remote and for touch:
 * OK = click, OK held = long click; grows and lights up when focused.
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
    val longFired = remember { BooleanArray(1) }
    val tv = LocalTvMode.current
    Box(
        modifier
            .then(
                FocusLookElement(shape, color, focusedColor, selectedColor, selected, focusedBrush, focusScale, outline) { f ->
                    focused = f
                    onFocus?.invoke(f)
                }
            )
            .then(if (tv) Modifier else Modifier.clip(shape)) // keeps the touch ripple inside the shape
            // Remote: OK pressed = click, OK held (key repeat) = long click.
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER &&
                    k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                ) return@onPreviewKeyEvent false
                when (k.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (k.repeatCount == 0) longFired[0] = false
                        else if (!longFired[0] && onLongClick != null) { longFired[0] = true; onLongClick() }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!longFired[0]) onClick()
                        longFired[0] = false
                    }
                }
                true
            }
            .combinedClickable(
                interactionSource = null,
                indication = if (tv) null else LocalIndication.current,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) { content(focused) }
}

private val FocusSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 900f) // light, like iOS / tvOS

/**
 * Background, frame and focus growth of a [FocusBox] in one modifier node: no animation, clip or extra modifiers per
 * box, nothing to recompose while it grows, and a screenful of channels draws in a few calls. No shadow under the
 * focused item: on a TV stick's GPU it cost more than the rest of the card.
 */
private class FocusLookElement(
    private val shape: Shape,
    private val color: Color,
    private val focusedColor: Color,
    private val selectedColor: Color,
    private val selected: Boolean,
    private val focusedBrush: Brush?,
    private val focusScale: Float,
    private val outline: Boolean,
    private val onFocusChanged: (Boolean) -> Unit,
) : ModifierNodeElement<FocusLookNode>() {
    override fun create() =
        FocusLookNode(shape, color, focusedColor, selectedColor, selected, focusedBrush, focusScale, outline, onFocusChanged)

    override fun update(node: FocusLookNode) =
        node.update(shape, color, focusedColor, selectedColor, selected, focusedBrush, focusScale, outline, onFocusChanged)

    override fun InspectorInfo.inspectableProperties() {
        name = "focusLook"
    }

    // The focus callback is a new lambda on every recomposition, so elements are never equal and update() always runs.
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

private class FocusLookNode(
    private var shape: Shape,
    private var color: Color,
    private var focusedColor: Color,
    private var selectedColor: Color,
    private var selected: Boolean,
    private var focusedBrush: Brush?,
    private var focusScale: Float,
    private var outline: Boolean,
    private var onFocusChanged: (Boolean) -> Unit,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, FocusEventModifierNode {

    private var focused = false
    private val scale = mutableFloatStateOf(1f)
    private var growing: Job? = null
    private val layer: GraphicsLayerScope.() -> Unit = {
        scaleX = scale.floatValue
        scaleY = scale.floatValue
    }

    // draw() is recorded inside the layer placed below, which invalidateDraw() doesn't reach: it goes to the coordinator
    // around that layer. A state read while drawing redraws whichever layer read it.
    private val look = mutableIntStateOf(0)
    private var outlineFor = Size.Unspecified
    private var cachedOutline: Outline? = null

    fun update(
        newShape: Shape, newColor: Color, newFocusedColor: Color, newSelectedColor: Color, newSelected: Boolean,
        newBrush: Brush?, newScale: Float, newOutline: Boolean, newOnFocusChanged: (Boolean) -> Unit,
    ) {
        onFocusChanged = newOnFocusChanged
        focusScale = newScale
        if (newShape != shape) {
            shape = newShape
            cachedOutline = null
        } else if (newColor == color && newFocusedColor == focusedColor && newSelectedColor == selectedColor &&
            newSelected == selected && newBrush == focusedBrush && newOutline == outline
        ) return
        color = newColor
        focusedColor = newFocusedColor
        selectedColor = newSelectedColor
        selected = newSelected
        focusedBrush = newBrush
        outline = newOutline
        look.intValue++
    }

    /** A box taken back from the lazy list's pool starts over, even if it left the screen focused or still growing. */
    override fun onReset() {
        growing?.cancel()
        focused = false
        scale.floatValue = 1f
        look.intValue++
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        onFocusChanged(focused)
        look.intValue++
        invalidatePlacement() // the focused box is drawn above its neighbours
        growing?.cancel()
        val target = if (focused) focusScale else 1f
        if (scale.floatValue != target) {
            growing = coroutineScope.launch {
                animate(scale.floatValue, target, animationSpec = FocusSpring) { value, _ -> scale.floatValue = value }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // A layer per box also means scrolling only moves it, without drawing it again.
            placeable.placeWithLayer(0, 0, if (focused) 1f else 0f, layer)
        }
    }

    override fun ContentDrawScope.draw() {
        look.intValue
        val shapeOutline = cachedOutline?.takeIf { outlineFor == size }
            ?: shape.createOutline(size, layoutDirection, this).also { cachedOutline = it; outlineFor = size }
        val rounded = (shapeOutline as? Outline.Rounded)?.roundRect
        val radius = rounded?.topLeftCornerRadius
        val uniform = rounded != null && rounded.topRightCornerRadius == radius &&
            rounded.bottomRightCornerRadius == radius && rounded.bottomLeftCornerRadius == radius

        val brush = if (focused) focusedBrush else null
        val fill = if (focused) focusedColor else if (selected) selectedColor else color
        when {
            brush != null -> if (uniform) drawRoundRect(brush, cornerRadius = radius!!) else drawOutline(shapeOutline, brush)
            fill == Color.Transparent -> Unit
            uniform -> drawRoundRect(fill, cornerRadius = radius!!)
            else -> drawOutline(shapeOutline, fill)
        }
        drawContent()

        val frame = if (focused) 2.dp.toPx() else if (outline) 1.dp.toPx() else 0f
        if (frame > 0f) {
            val frameColor = if (focused) Color.White else Fz.line
            if (uniform) {
                val half = frame / 2
                drawRoundRect(
                    frameColor,
                    topLeft = Offset(half, half),
                    size = Size(size.width - frame, size.height - frame),
                    cornerRadius = CornerRadius((radius!!.x - half).coerceAtLeast(0f), (radius.y - half).coerceAtLeast(0f)),
                    style = Stroke(frame),
                )
            } else drawOutline(shapeOutline, frameColor, style = Stroke(frame))
        }
    }
}

/** Round icon button of the top bar. */
@Composable
fun IconAction(icon: Int, label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, size: Dp = 46.dp) {
    FocusBox(
        onClick = onClick, onLongClick = onLongClick, modifier = Modifier.padding(horizontal = 3.dp).size(size),
        shape = RoundedCornerShape(50), color = Fz.surface2, focusedColor = Fz.focusLight, focusedBrush = null,
        outline = false, focusScale = 1.12f,
    ) { focused ->
        Icon(
            painterResource(icon), contentDescription = label, tint = if (focused) Fz.onLight else Fz.text,
            modifier = Modifier.align(Alignment.Center).size(size * 0.5f)
        )
    }
}

/**
 * TV "pivot" scrolling (as on tvOS / Google TV): the focused row stays about a third from the top and the
 * list glides there smoothly, instead of jumping when the focus reaches the edge.
 */
@OptIn(ExperimentalFoundationApi::class)
val PivotScroll: BringIntoViewSpec = object : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(240, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size >= containerSize) return offset
        return offset - (containerSize * 0.3f - size / 2f).coerceAtLeast(0f)
    }
}
