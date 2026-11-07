package com.slowpixels.guitartuner.model

import kotlin.math.pow

/** A note as a guitarist says it ("E♭"), and which octave it sounds in. */
class Note(val name: String, val octave: Int, val midi: Int) {
    /** Its pitch in Hz, with A above middle C at [a4]. */
    fun hz(a4: Double = 440.0) = a4 * 2.0.pow((midi - 69) / 12.0)

    /** The letter alone, with the ♯ or ♭ apart from it, for drawing them at their own size. */
    val letter get() = name.substring(0, 1)
    val accidental get() = name.substring(1)

    companion object {
        private val STEPS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        /** Reads "Eb2" or "F#3". */
        fun parse(text: String): Note {
            val letter = text[0]
            val mark = text.getOrNull(1)?.takeIf { it == '#' || it == 'b' }
            val octave = text.substring(if (mark == null) 1 else 2).toInt()
            val shift = when (mark) { '#' -> 1; 'b' -> -1; else -> 0 }
            val name = letter + when (mark) { '#' -> "♯"; 'b' -> "♭"; else -> "" }
            return Note(name, octave, 12 * (octave + 1) + STEPS.getValue(letter) + shift)
        }
    }
}

/** Six notes, from the thickest string to the thinnest. */
class Tuning(val name: String, notes: String) {
    val notes: List<Note> = notes.split(' ').map(Note::parse)

    /** Saved in the settings, so a name can't change without bringing the old one along. */
    val id get() = name

    /** How it reads in a list: "D A D G A D". */
    val letters get() = notes.joinToString(" ") { it.name }

    /**
     * True if a string has to go more than a whole step above where it sits in standard. An ordinary set can snap
     * doing that, so the list says so.
     */
    val needsLightStrings get() = notes.indices.any { notes[it].midi - STANDARD_MIDI[it] > 2 }

    fun hz(a4: Double) = DoubleArray(notes.size) { notes[it].hz(a4) }
}

class TuningGroup(val name: String, val tunings: List<Tuning>)

private val STANDARD_MIDI = intArrayOf(40, 45, 50, 55, 59, 64)

/**
 * Every tuning on offer. The notes come from Wikipedia's lists of guitar tunings, its pages on new standard, Nashville
 * and slack-key tuning, and Ragajunglism's string-by-string tables. Those pages mostly give letters, so where a
 * tuning's octaves weren't printed they follow from which way each string moves from standard.
 */
object Tunings {
    val standard = Tuning("Standard", "E2 A2 D3 G3 B3 E4")

    val groups = listOf(
        TuningGroup("Standard and lower", listOf(
            standard,
            Tuning("Half step down", "Eb2 Ab2 Db3 Gb3 Bb3 Eb4"),
            Tuning("Whole step down", "D2 G2 C3 F3 A3 D4"),
            Tuning("1½ steps down", "C#2 F#2 B2 E3 G#3 C#4"),
            Tuning("2 steps down", "C2 F2 Bb2 Eb3 G3 C4"),
            Tuning("B standard", "B1 E2 A2 D3 F#3 B3"),
            Tuning("A standard", "A1 D2 G2 C3 E3 A3"),
            Tuning("Half step up", "F2 Bb2 Eb3 Ab3 C4 F4"),
        )),
        TuningGroup("Drop", listOf(
            Tuning("Drop D", "D2 A2 D3 G3 B3 E4"),
            Tuning("Double drop D", "D2 A2 D3 G3 B3 D4"),
            Tuning("Drop D♭", "Db2 Ab2 Db3 Gb3 Bb3 Eb4"),
            Tuning("Drop C", "C2 G2 C3 F3 A3 D4"),
            Tuning("Drop B", "B1 F#2 B2 E3 G#3 C#4"),
            Tuning("Drop B♭", "Bb1 F2 Bb2 Eb3 G3 C4"),
            Tuning("Drop A", "A1 E2 A2 D3 F#3 B3"),
        )),
        TuningGroup("Open", listOf(
            Tuning("Open G", "D2 G2 D3 G3 B3 D4"),
            Tuning("Open D", "D2 A2 D3 F#3 A3 D4"),
            Tuning("Open E", "E2 B2 E3 G#3 B3 E4"),
            Tuning("Open A", "E2 A2 E3 A3 C#4 E4"),
            Tuning("Open C", "C2 G2 C3 G3 C4 E4"),
            Tuning("Open F", "C2 F2 C3 F3 A3 C4"),
            Tuning("Open B", "B1 F#2 B2 F#3 B3 D#4"),
            Tuning("Dobro G", "G2 B2 D3 G3 B3 D4"),
        )),
        TuningGroup("Open minor", listOf(
            Tuning("Open D minor", "D2 A2 D3 F3 A3 D4"),
            Tuning("Open E minor", "E2 B2 E3 G3 B3 E4"),
            Tuning("Open G minor", "D2 G2 D3 G3 Bb3 D4"),
            Tuning("Open A minor", "E2 A2 E3 A3 C4 E4"),
            Tuning("Open C minor", "C2 G2 C3 G3 C4 Eb4"),
        )),
        TuningGroup("Folk and modal", listOf(
            Tuning("DADGAD", "D2 A2 D3 G3 A3 D4"),
            Tuning("DADDAD", "D2 A2 D3 D3 A3 D4"),
            Tuning("DADEAD", "D2 A2 D3 E3 A3 D4"),
            Tuning("Gsus4", "D2 G2 D3 G3 C4 D4"),
            Tuning("Csus2", "C2 G2 C3 G3 C4 D4"),
            Tuning("Orkney", "C2 G2 D3 G3 C4 D4"),
            Tuning("Open Page", "D2 G2 C3 G3 C4 D4"),
            Tuning("Lute", "E2 A2 D3 F#3 B3 E4"),
        )),
        TuningGroup("Slack key", listOf(
            Tuning("Taro Patch", "D2 G2 D3 G3 B3 D4"),
            Tuning("G Wahine", "D2 G2 D3 F#3 B3 D4"),
            Tuning("D Wahine", "D2 A2 D3 F#3 A3 C#4"),
            Tuning("C Wahine", "C2 G2 D3 G3 B3 D4"),
            Tuning("F Wahine", "C2 F2 C3 G3 C4 E4"),
            Tuning("C Mauna Loa", "C2 G2 E3 G3 A3 E4"),
            Tuning("Old Mauna Loa", "C2 G2 C3 G3 A3 D4"),
            Tuning("Atta's C", "C2 G2 E3 G3 C4 E4"),
            Tuning("C6", "C2 G2 C3 G3 A3 E4"),
            Tuning("Double slack F", "C2 F2 C3 E3 A3 C4"),
        )),
        TuningGroup("Odd and famous", listOf(
            Tuning("Nashville", "E3 A3 D4 G4 B3 E4"),
            Tuning("Iris", "B1 D2 D3 D3 D4 D4"),
            Tuning("Pink Moon", "C2 G2 C3 F3 C4 E4"),
            Tuning("Bron-Yr-Aur", "C2 A2 C3 G3 C4 E4"),
            Tuning("FACGCE", "F2 A2 C3 G3 C4 E4"),
            Tuning("Drake's drone", "B1 E2 B2 E3 B3 E4"),
            Tuning("Ostrich E", "E2 E2 E3 E3 E4 E4"),
            Tuning("Ostrich D", "D2 D2 D3 D3 D4 D4"),
            Tuning("All fourths", "E2 A2 D3 G3 C4 F4"),
            Tuning("New standard", "C2 G2 D3 A3 E4 G4"),
            Tuning("All fifths", "C2 G2 D3 A3 E4 B4"),
            Tuning("Major thirds", "E2 G#2 C3 E3 G#3 C4"),
        )),
    )

    val all = groups.flatMap { it.tunings }

    fun named(id: String?) = all.firstOrNull { it.id == id } ?: standard
}
