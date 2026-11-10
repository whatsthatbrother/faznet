package com.faznet.tv.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The subtitle and sound language chosen for one film carry over to the next. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TrackDialogTest {

    private val defaults get() = TrackSelectionParameters.getDefaults(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun nothingRememberedChangesNothing() {
        assertEquals(defaults, TrackDialog.withRemembered(defaults, "", ""))
    }

    @Test
    fun aRememberedLanguageIsPreferred() {
        val p = TrackDialog.withRemembered(defaults, "en", "de")
        assertEquals(listOf("en"), p.preferredTextLanguages)
        assertEquals(listOf("de"), p.preferredAudioLanguages)
        assertFalse(C.TRACK_TYPE_TEXT in p.disabledTrackTypes)
    }

    @Test
    fun offTurnsSubtitlesOffAndALanguageOnAgain() {
        val off = TrackDialog.withRemembered(defaults, TrackDialog.OFF, "")
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        val on = TrackDialog.withRemembered(off, "sq", "")
        assertFalse(C.TRACK_TYPE_TEXT in on.disabledTrackTypes)
        assertEquals(listOf("sq"), on.preferredTextLanguages)
    }
}
