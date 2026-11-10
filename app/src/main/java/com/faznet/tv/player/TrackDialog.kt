package com.faznet.tv.player

import android.app.Activity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import com.faznet.tv.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** "Sound & subtitles": the sound languages and subtitles the stream has, for live channels, movies and episodes. */
object TrackDialog {

    /** What the viewer chose, for remembering it: a language (null when the stream does not name one). */
    sealed interface Choice {
        data class Sound(val language: String?) : Choice
        data class Subtitles(val language: String?) : Choice
        data object NoSubtitles : Choice
    }

    fun show(a: Activity, p: Player, chosen: (Choice) -> Unit = {}) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        var checked = -1
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            n++
            labels.add(a.getString(R.string.track_audio, name(g.getTrackFormat(0), n, codec = true)))
            if (g.isSelected) checked = labels.size - 1
            val group = g.mediaTrackGroup
            val lang = language(g.getTrackFormat(0))
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group, 0)).build()
                chosen(Choice.Sound(lang))
            }
        }
        labels.add(a.getString(R.string.track_subs_off))
        val offIndex = labels.size - 1
        actions.add {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            chosen(Choice.NoSubtitles)
        }
        var anyText = false
        n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            n++
            anyText = true
            labels.add(a.getString(R.string.track_subs, name(g.getTrackFormat(0), n, codec = false)))
            if (g.isSelected) checked = labels.size - 1
            val group = g.mediaTrackGroup
            val lang = language(g.getTrackFormat(0))
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group, 0)).build()
                chosen(Choice.Subtitles(lang))
            }
        }
        if (anyText && checked < 0) checked = offIndex
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.tracks)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> actions[which](); d.dismiss() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun language(f: Format): String? = f.language?.takeIf { it.isNotBlank() && it != "und" }

    /**
     * The remembered choice on top of [params]: "off" turns subtitles off, a language is preferred for subtitles or
     * sound when the film has it (else the player picks as before). Empty = nothing remembered.
     */
    fun withRemembered(params: TrackSelectionParameters, subtitles: String, sound: String): TrackSelectionParameters {
        val b = params.buildUpon()
        when (subtitles) {
            "" -> Unit
            OFF -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(subtitles)
        }
        if (sound.isNotEmpty()) b.setPreferredAudioLanguage(sound)
        return b.build()
    }

    const val OFF = "off"

    /** "English", "Deutsch (eac3)": the label the stream gives, else the language in the viewer's own words. */
    private fun name(f: Format, n: Int, codec: Boolean): String {
        val lang = f.language?.takeIf { it != "und" }?.let { tag ->
            Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotEmpty() && it != tag }
                ?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: tag
        }
        val base = listOfNotNull(f.label, lang).firstOrNull() ?: "#$n"
        val c = f.sampleMimeType?.substringAfter('/')
        return if (codec && c != null) "$base ($c)" else base
    }
}
