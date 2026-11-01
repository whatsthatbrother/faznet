package com.faznet.tv.ui.compose

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A channel logo, fitted and centred in the element, with [placeholder] until it is there or when there is none.
 *
 * It draws straight from the image loader without any composition of its own: on a TV stick a screenful of
 * channels appears much sooner this way than with a composable image per channel, and a logo arriving only
 * repaints its own tile.
 */
fun Modifier.channelLogo(url: String?, placeholder: Painter): Modifier = this then LogoElement(url, placeholder)

/** Logos that just failed: a dead server must not be asked again every time a tile scrolls back into view. */
private object FailedLogos {
    private const val RETRY_AFTER_MS = 120_000L
    private const val MAX_ENTRIES = 5_000
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun add(url: String) {
        if (failedAt.size > MAX_ENTRIES) failedAt.clear()
        failedAt[url] = SystemClock.elapsedRealtime()
    }

    fun has(url: String): Boolean {
        val at = failedAt[url] ?: return false
        if (SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS) return true
        failedAt.remove(url)
        return false
    }
}

private class LogoElement(private val url: String?, private val placeholder: Painter) : ModifierNodeElement<LogoNode>() {
    override fun create() = LogoNode(url, placeholder)

    override fun update(node: LogoNode) = node.update(url, placeholder)

    override fun InspectorInfo.inspectableProperties() {
        name = "channelLogo"
        properties["url"] = url
    }

    override fun equals(other: Any?) = other is LogoElement && other.url == url && other.placeholder === placeholder

    override fun hashCode() = url.hashCode() * 31 + placeholder.hashCode()
}

private class LogoNode(private var url: String?, private var placeholder: Painter) :
    Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var image: ImageBitmap? = null
    private var request: Disposable? = null
    private var measured = IntSize.Zero

    fun update(newUrl: String?, newPlaceholder: Painter) {
        placeholder = newPlaceholder
        if (newUrl == url) return
        url = newUrl
        clear()
        invalidateDraw()
        load()
    }

    override fun onRemeasured(size: IntSize) {
        if (size == measured) return
        measured = size
        load()
    }

    override fun onAttach() = load()

    override fun onDetach() = clear()

    override fun onReset() = clear()

    private fun clear() {
        request?.dispose()
        request = null
        image = null
    }

    private fun load() {
        val u = url ?: return
        if (!isAttached || image != null || measured.width <= 0 || measured.height <= 0 || FailedLogos.has(u)) return
        request?.dispose()
        val context = currentValueOf(LocalContext)
        request = context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(u)
                .size(measured.width, measured.height)
                .scale(Scale.FIT)
                .precision(Precision.INEXACT)
                .target(
                    onSuccess = { drawable -> show(drawable) },
                    onError = { FailedLogos.add(u) },
                )
                .build()
        )
    }

    private fun show(drawable: Drawable) {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        image = bitmap.asImageBitmap()
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val img = image
        if (img != null) {
            val scale = min(size.width / img.width, size.height / img.height)
            val w = (img.width * scale).roundToInt()
            val h = (img.height * scale).roundToInt()
            drawImage(
                img,
                dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                dstSize = IntSize(w, h),
            )
        } else {
            val own = placeholder.intrinsicSize
            val scale = min(size.width / own.width, size.height / own.height)
            val w = own.width * scale
            val h = own.height * scale
            translate((size.width - w) / 2, (size.height - h) / 2) { with(placeholder) { draw(Size(w, h)) } }
        }
        drawContent()
    }
}
