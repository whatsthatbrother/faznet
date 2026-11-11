package com.faznet.tv.ui

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.R
import com.faznet.tv.ui.compose.Fz
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The colours are written twice: colors.xml for the screens made of views (player, movies, guide, playlists, device)
 * and [Fz] for the Compose ones. A change in one place only would give the app two looks, so this keeps them the same.
 */
@RunWith(RobolectricTestRunner::class)
class ColoursTest {
    private val c: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `views and Compose screens use the same colours`() {
        val pairs = mapOf(
            R.color.bg to Fz.bg, R.color.surface to Fz.surface, R.color.surface2 to Fz.surface2,
            R.color.accent to Fz.accent, R.color.accent2 to Fz.accent2, R.color.text to Fz.text,
            R.color.text_dim to Fz.dim, R.color.line to Fz.line,
        )
        for ((res, compose) in pairs) {
            assertEquals(c.resources.getResourceEntryName(res), Integer.toHexString(ContextCompat.getColor(c, res)), Integer.toHexString(compose.toArgb()))
        }
    }
}
