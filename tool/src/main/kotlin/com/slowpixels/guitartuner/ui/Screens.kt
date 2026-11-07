package com.slowpixels.guitartuner.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.slowpixels.guitartuner.Hub
import com.slowpixels.guitartuner.model.Tuning
import com.slowpixels.guitartuner.model.TuningGroup
import com.slowpixels.guitartuner.model.Tunings
import com.slowpixels.guitartuner.model.Settings
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** A tuning to choose. Choosing one takes every list down, so the tuner is back in front. */
@Composable
private fun TuningRow(t: Tuning, chosen: Boolean, screen: ListScreen) {
    val line = if (t.needsLightStrings) "${t.letters} · Light strings" else t.letters
    Choice(t.name, chosen, line) { Hub.chooseTuning(t); screen.goBack() }
}

/** The groups of tunings, with the last few used on top. */
class TuningsScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val s by Hub.settings.collectAsState()
        // One tuning isn't a choice worth repeating.
        val recent = s.recentTunings.takeIf { it.size > 1 } ?: emptyList()
        ListPage("Tunings", back = { goBack() }) {
            if (recent.isNotEmpty()) {
                Heading("Recent")
                for (t in recent) TuningRow(t, t.id == s.tuning, this@TuningsScreen)
                Heading("All tunings")
            }
            for (g in Tunings.groups) Item(g.name, "${g.tunings.size}") { navigateTo({ GroupScreen(it, g) }) }
        }
    }
}

/** One group's tunings. */
class GroupScreen(sealed: SealedLightActivity, private val group: TuningGroup) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val s by Hub.settings.collectAsState()
        ListPage(group.name, back = { goBack() }) {
            for (t in group.tunings) TuningRow(t, t.id == s.tuning, this@GroupScreen)
        }
    }
}

class MenuScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val s by Hub.settings.collectAsState()
        ListPage("Menu", back = { goBack() }) {
            Item("Guitar", if (s.electric) "Electric" else "Acoustic") { Hub.setGuitar(!s.electric) }
            Item("Reference pitch", "A = ${s.a4} Hz") { navigateTo(::PitchScreen) }
            Item("Chime", if (s.chime) "On" else "Off") { Hub.setChime(!s.chime) }
            Item("Help") { navigateTo(::HelpScreen) }
            Item("About") { navigateTo(::AboutScreen) }
        }
    }
}

class PitchScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        val s by Hub.settings.collectAsState()
        ListPage("Reference pitch", back = { goBack() }) {
            for (hz in Settings.PITCHES) Choice(if (hz == 440) "440 Hz (standard)" else "$hz Hz", s.a4 == hz) { Hub.choosePitch(hz) }
        }
    }
}

class HelpScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        ListPage("Help", back = { goBack() }) {
            Item("Play a string", "It's found by itself")
            Item("Wavy string", "Not tuned yet")
            Item("Straight string", "In tune")
            Item("Tighten", "Wind the peg up")
            Item("Loosen", "Wind it down")
            Item("Tap a string", "Listens for it alone")
            Item("Tap it again", "Listens for them all")
            Item("Hold a string", "Plays its note")
            Item("Electric", "Hold the phone close")
        }
    }
}

class AboutScreen(sealed: SealedLightActivity) : ListScreen(sealed) {
    @Composable
    override fun Body() {
        ListPage("About", back = { goBack() }) {
            val gap = Modifier.padding(bottom = 1f.gridUnitsAsDp())
            LightText("A guitar tuner. Play a string and the needle finds it; the string on screen straightens as it comes into tune, and a bell rings when it's there.", LightTextVariant.Paragraph, gap)
            LightText("57 tunings: standard and lower, drop, open, open minor, folk and modal, slack key, and the odd and famous ones.", LightTextVariant.Paragraph, gap)
            LightText("It asks for the microphone and nothing else. What the mic hears is never kept or sent, and the tool can't reach the internet.", LightTextVariant.Paragraph, gap)
            LightText("Version ${Hub.VERSION}", LightTextVariant.Paragraph, lighten = true)
            LightText("Made by Slow Pixels", LightTextVariant.Paragraph, lighten = true)
        }
    }
}
